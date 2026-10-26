/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zzh
 */
package com.lealone.plugins.postgresql.server.handler;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.util.CaseInsensitiveMap;
import com.lealone.common.util.DataUtils;
import com.lealone.common.util.ScriptReader;
import com.lealone.common.util.StringUtils;
import com.lealone.db.Database;
import com.lealone.db.async.AsyncCallback;
import com.lealone.db.async.AsyncResult;
import com.lealone.db.result.Result;
import com.lealone.db.schema.Schema;
import com.lealone.db.session.ServerSession;
import com.lealone.db.table.Column;
import com.lealone.db.table.Table;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueBoolean;
import com.lealone.db.value.ValueBytes;
import com.lealone.db.value.ValueDate;
import com.lealone.db.value.ValueDecimal;
import com.lealone.db.value.ValueDouble;
import com.lealone.db.value.ValueFloat;
import com.lealone.db.value.ValueInt;
import com.lealone.db.value.ValueLong;
import com.lealone.db.value.ValueNull;
import com.lealone.db.value.ValueShort;
import com.lealone.db.value.ValueString;
import com.lealone.db.value.ValueTimestamp;
import com.lealone.plugins.postgresql.server.PgServer;
import com.lealone.plugins.postgresql.server.PgServerConnection;
import com.lealone.plugins.postgresql.server.PgType;
import com.lealone.sql.PreparedSQLStatement;
import com.lealone.sql.SQLStatement;
import com.lealone.db.command.CommandParameter;

/**
 * PostgreSQL 前端/后端协议(v3)的命令消息处理器。
 * <p>
 * 关键点：**一条消息处理完必须立刻按顺序回包**。之前把 Bind/Execute 攒到 Sync 才处理，
 * 导致：事务里第一条语句必崩(客户端已经收到后面语句的回包)、executeBatch 只有最后一条生效、
 * Describe 不回 RowDescription、绑定 NULL 参数崩。现在每个 Execute 就地执行并同步等待结果，
 * 处理完当前消息才读下一条消息，协议顺序自然成立。
 */
public class CommandPacketHandler extends PacketHandler {

    private final CaseInsensitiveMap<Prepared> prepared = new CaseInsensitiveMap<>();
    private final CaseInsensitiveMap<Portal> portals = new CaseInsensitiveMap<>();

    public CommandPacketHandler(PgServer server, PgServerConnection conn) {
        super(server, conn);
    }

    @Override
    public boolean handle(int x) throws IOException {
        switch (x) {
        case 'P':
            parse();
            return false;
        case 'B':
            bind();
            return false;
        case 'C':
            close();
            return false;
        case 'D':
            describe();
            return false;
        case 'E':
            return execute();
        case 'S':
            server.trace("Sync");
            sendReadyForQuery();
            return false;
        case 'H':
            // Flush: 客户端要求把缓冲区里的响应发出去(不等 Sync)。我们每条消息都立即 flush，这里直接返回即可
            server.trace("Flush");
            out.flush();
            return false;
        case 'Q':
            return query();
        case 'X':
            server.trace("Terminate");
            conn.close();
            return false;
        default:
            server.trace("Unsupported: " + x + " (" + (char) x + ")");
            return false;
        }
    }

    // ---------------------------------------------------------------- Parse / Bind

    private void parse() throws IOException {
        server.trace("Parse");
        Prepared p = new Prepared();
        p.name = readString();
        p.sql = getSQL(readString());
        int paramTypesCount = readShort();
        int[] paramTypes = null;
        if (paramTypesCount > 0) {
            paramTypes = new int[paramTypesCount];
            for (int i = 0; i < paramTypesCount; i++) {
                int type = readInt();
                server.checkType(type);
                paramTypes[i] = type;
            }
        }
        try {
            p.prep = session.prepareStatementLocal(p.sql);
            List<? extends CommandParameter> parameters = p.prep.getParameters();
            int count = parameters.size();
            p.paramTypes = new int[count];
            for (int i = 0; i < count; i++) {
                int type;
                if (i < paramTypesCount && paramTypes[i] != 0) {
                    type = paramTypes[i];
                    server.checkType(type);
                } else {
                    type = PgType.convertType(parameters.get(i).getType());
                }
                p.paramTypes[i] = type;
            }
            prepared.put(p.name, p);
            sendParseComplete();
        } catch (Exception e) {
            sendErrorResponse(e);
        }
    }

    private void bind() throws IOException {
        server.trace("Bind");
        String portalName = readString();
        String prepName = readString();
        Prepared prep = prepared.get(prepName);
        if (prep == null) {
            sendErrorResponse("Prepared not found");
            return;
        }
        int formatCodeCount = readShort();
        int[] formatCodes = new int[formatCodeCount];
        for (int i = 0; i < formatCodeCount; i++) {
            formatCodes[i] = readShort();
        }
        int paramCount = readShort();
        Value[] values = new Value[paramCount];
        for (int i = 0; i < paramCount; i++) {
            int paramLen = readInt();
            if (paramLen == -1) { // NULL 参数
                values[i] = ValueNull.INSTANCE;
                continue;
            }
            // 必须先跟"本包剩余字节数"比对：一个 20 字节的包可以声称 paramLen=2G，
            // 照这个值 new byte[] 直接 OOM(OOM 是 Error，PacketHandler.handle 只 catch
            // Exception，会绕过错误响应)，所以这里按协议违规处理: 回 08P01 并断开。
            if (paramLen < 0 || paramLen > in.remaining()) {
                conn.sendProtocolErrorAndClose("invalid parameter length: " + paramLen
                        + ", remaining packet bytes: " + in.remaining());
                return;
            }
            byte[] data = DataUtils.newBytes(paramLen);
            readFully(data);
            boolean text = isText(formatCodes, i);
            try {
                values[i] = decodeParameter(prep.paramTypes[i], data, text);
            } catch (Exception e) {
                sendErrorResponse(e);
                return;
            }
        }
        int resultCodeCount = readShort();
        int[] resultColumnFormat = new int[resultCodeCount];
        for (int i = 0; i < resultCodeCount; i++) {
            resultColumnFormat[i] = readShort();
        }
        // 每个 Bind 生成一个新的 Portal 快照(参数值独立保存)，这样 batch 里的多条 Bind/Execute 互不影响
        Portal portal = new Portal();
        portal.name = portalName;
        portal.prep = prep;
        portal.values = values;
        portal.resultColumnFormat = resultColumnFormat;
        portals.put(portalName, portal);
        sendBindComplete();
    }

    /**
     * PG 协议: format code 个数为 0 时全部是 text，为 1 时套用到所有参数；个数少于参数
     * 个数时(协议规定本不该出现，但客户端可能这么发)沿用最后一个 format code，不能默认
     * 按 binary 解，否则最后一个参数会被当成定长二进制、直接报长度错误。
     */
    static boolean isText(int[] formatCodes, int i) {
        if (formatCodes.length == 0)
            return true;
        if (formatCodes.length == 1)
            return formatCodes[0] == 0;
        int index = Math.min(i, formatCodes.length - 1);
        return formatCodes[index] == 0;
    }

    // ---------------------------------------------------------------- Describe / Execute

    private void describe() throws IOException {
        server.trace("Describe");
        char type = (char) readByte();
        String name = readString();
        if (type == 'S') {
            Prepared p = prepared.get(name);
            if (p == null) {
                sendErrorResponse("Prepared not found: " + name);
                return;
            }
            sendParameterDescription(p);
            sendRowDescriptionOrNoData(p.prep);
            p.columnTypes = resultColumnTypes;
        } else if (type == 'P') {
            Portal p = portals.get(name);
            if (p == null) {
                sendErrorResponse("Portal not found: " + name);
                return;
            }
            sendRowDescriptionOrNoData(p.prep.prep, p.resultColumnFormat);
            p.formats = resultFormats;
        } else {
            sendErrorResponse("expected S or P, got " + type);
        }
    }

    private boolean execute() throws IOException {
        server.trace("Execute");
        String name = readString();
        int maxRows = readInt(); // 暂不支持游标/PortalSuspended，maxRows 忽略
        Portal p = portals.get(name);
        if (p == null) {
            sendErrorResponse("Portal not found: " + name);
            return false;
        }
        Prepared prep = p.prep;
        resultColumnTypes = prep.columnTypes;
        resultFormats = formatsFor(prep.columnTypes, p.resultColumnFormat);
        applyParameters(prep, p.values);
        if (prep.prep.isQuery()) {
            PreparedSQLStatement.Yieldable<?> yieldable = prep.prep.createYieldableQuery(maxRows,
                    false, ar -> {
                        try {
                            if (ar.isFailed()) {
                                sendErrorResponse(ar.getCause());
                            } else {
                                Result result = ar.getResult();
                                try {
                                    applyResultFormats(result, p.resultColumnFormat);
                                    int rowCount = 0;
                                    while (result.next()) {
                                        sendDataRow(result);
                                        rowCount++;
                                    }
                                    sendCommandComplete(prep.prep, rowCount);
                                } finally {
                                    result.close();
                                }
                            }
                        } catch (Throwable e) {
                            sendErrorQuietly(e);
                        } finally {
                            conn.packetFinished();
                        }
                    });
            si.submitYieldableCommand(0, yieldable);
        } else {
            PreparedSQLStatement.Yieldable<?> yieldable = prep.prep.createYieldableUpdate(ar -> {
                try {
                    if (ar.isFailed())
                        sendErrorResponse(ar.getCause());
                    else
                        sendCommandComplete(prep.prep, ar.getResult());
                } catch (Throwable e) {
                    sendErrorQuietly(e);
                } finally {
                    conn.packetFinished();
                }
            });
            si.submitYieldableCommand(0, yieldable);
        }
        return true;
    }

    private void applyParameters(Prepared prep, Value[] values) {
        List<? extends CommandParameter> parameters = prep.prep.getParameters();
        int n = Math.min(parameters.size(), values.length);
        for (int i = 0; i < n; i++) {
            parameters.get(i).setValue(values[i]);
        }
    }

    /**
     * 同步执行查询并等待结果。当前线程如果是调度线程，等待期间由调度器继续推进其他语句
     * (SingleThreadAsyncCallback 的等待语义)，不会把调度线程卡死。
     */
    private Result executeQuery(PreparedSQLStatement stmt, int maxRows) {
        AsyncCallback<Result> callback = AsyncCallback.createSingleThreadCallback();
        PreparedSQLStatement.Yieldable<?> yieldable = stmt.createYieldableQuery(maxRows, false,
                callback::setAsyncResult);
        si.submitYieldableCommand(0, yieldable);
        Result result = callback.get(EXECUTE_TIMEOUT_MILLIS);
        if (result == null)
            throw DbException.getInternalError("execute query timeout");
        return result;
    }

    private int executeUpdate(PreparedSQLStatement stmt) {
        AsyncCallback<Integer> callback = AsyncCallback.createSingleThreadCallback();
        PreparedSQLStatement.Yieldable<?> yieldable = stmt.createYieldableUpdate(
                callback::setAsyncResult);
        si.submitYieldableCommand(0, yieldable);
        Integer count = callback.get(EXECUTE_TIMEOUT_MILLIS);
        return count == null ? 0 : count;
    }

    private static final long EXECUTE_TIMEOUT_MILLIS = 300_000;

    // ---------------------------------------------------------------- Simple query

    private static final class QueryQueue {
        List<String> statements;
        int index;
    }

    private QueryQueue queryQueue;

    /** 当前结果集每列的输出格式: 0=text, 1=binary(客户端在 Bind 里要求的) */
    private int[] resultFormats;

    /** 当前结果集每列的 PG 类型，DataRow 输出时要按类型做文本编码(bool 用 t/f，bytea 用 \\x 十六进制) */
    private int[] resultColumnTypes;

    private boolean query() throws IOException {
        server.trace("Query");
        String query = readString();
        QueryQueue q = new QueryQueue();
        q.statements = new ArrayList<>();
        ScriptReader reader = new ScriptReader(new StringReader(query));
        try {
            while (true) {
                String stmt = reader.readStatement();
                if (stmt == null)
                    break;
                stmt = getSQL(stmt);
                if (!stmt.trim().isEmpty())
                    q.statements.add(stmt);
            }
        } catch (Exception e) {
            sendErrorResponse(e);
            sendReadyForQuery();
            return false;
        }
        queryQueue = q;
        if (q.statements.isEmpty()) {
            sendReadyForQuery();
            queryQueue = null;
            return false;
        }
        runNextQueryStatement();
        return true;
    }

    /** 依次异步执行 Query 里的多条语句；全部完成后发 ReadyForQuery 并继续处理下一个包 */
    private void runNextQueryStatement() {
        QueryQueue q = queryQueue;
        if (q == null)
            return;
        if (q.index >= q.statements.size()) {
            queryQueue = null;
            try {
                sendReadyForQuery();
            } catch (IOException e) {
                sendErrorQuietly(e);
            } finally {
                conn.packetFinished();
            }
            return;
        }
        String sql = q.statements.get(q.index++);
        PreparedSQLStatement prep;
        try {
            prep = session.prepareStatement(sql);
        } catch (Exception e) {
            finishQueryWithError(e);
            return;
        }
        if (prep.isQuery()) {
            PreparedSQLStatement.Yieldable<?> yieldable = prep.createYieldableQuery(-1, false, ar -> {
                try {
                    if (ar.isFailed()) {
                        finishQueryWithError(ar.getCause());
                        return;
                    }
                    Result result = ar.getResult();
                    try {
                        sendRowDescription(prep.getMetaData().get());
                        int rowCount = 0;
                        while (result.next()) {
                            sendDataRow(result);
                            rowCount++;
                        }
                        sendCommandComplete(prep, rowCount);
                    } finally {
                        result.close();
                    }
                } catch (Throwable e) {
                    finishQueryWithError(e);
                    return;
                }
                runNextQueryStatement();
            });
            si.submitYieldableCommand(0, yieldable);
        } else {
            PreparedSQLStatement.Yieldable<?> yieldable = prep.createYieldableUpdate(ar -> {
                try {
                    if (ar.isFailed()) {
                        finishQueryWithError(ar.getCause());
                        return;
                    }
                    sendCommandComplete(prep, ar.getResult());
                } catch (Throwable e) {
                    finishQueryWithError(e);
                    return;
                }
                runNextQueryStatement();
            });
            si.submitYieldableCommand(0, yieldable);
        }
    }

    private void finishQueryWithError(Throwable e) {
        queryQueue = null;
        sendErrorQuietly(e);
        try {
            sendReadyForQuery();
        } catch (IOException e1) {
            sendErrorQuietly(e1);
        } finally {
            conn.packetFinished();
        }
    }

    private void sendErrorQuietly(Throwable e) {
        sendErrorResponse(e);
    }

    // ---------------------------------------------------------------- Close

    private void close() throws IOException {
        server.trace("Close");
        char type = (char) readByte();
        String name = readString();
        if (type == 'S') {
            Prepared p = prepared.remove(name);
            if (p != null && p.prep != null) {
                p.prep.close();
            }
        } else if (type == 'P') {
            portals.remove(name);
        } else {
            sendErrorResponse("expected S or P, got " + type);
            return;
        }
        sendCloseComplete();
    }

    // ---------------------------------------------------------------- 参数解码

    private Value decodeParameter(int pgType, byte[] data, boolean text) throws IOException {
        if (text) {
            String s = new String(data, getEncoding());
            // PG 客户端会带时区后缀(比如 '2024-01-02 +00'、'2024-01-02 03:04:05+00')，Lealone 的类型解析器不认
            switch (pgType) {
            case PgType.PG_TYPE_DATE:
                return ValueDate.parse(stripTimeZone(s));
            case PgType.PG_TYPE_TIMESTAMP_NO_TMZONE:
                return ValueTimestamp.parse(stripTimeZone(s));
            case PgType.PG_TYPE_BOOL: {
                String v = s.trim().toLowerCase();
                return ValueBoolean.get("t".equals(v) || "true".equals(v) || "1".equals(v) || "y".equals(v));
            }
            case PgType.PG_TYPE_BYTEA:
                return ValueBytes.getNoCopy(decodeByteaText(s));
            case PgType.PG_TYPE_UNKNOWN: {
                // pgjdbc 把 setDate/setTimestamp 的值当 UNKNOWN 类型发文本, 这里按形状识别
                String t = stripTimeZone(s);
                if (t.matches("\\d{4}-\\d{2}-\\d{2}"))
                    return ValueDate.parse(t);
                if (t.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?"))
                    return ValueTimestamp.parse(t);
                return ValueString.get(s);
            }
            default:
                return ValueString.get(s);
            }
        }
        ByteBuffer buff = ByteBuffer.wrap(data);
        switch (pgType) {
        case PgType.PG_TYPE_BOOL:
            checkParamLength(1, data.length);
            return ValueBoolean.get(buff.get() != 0);
        case PgType.PG_TYPE_INT2:
            checkParamLength(2, data.length);
            return ValueShort.get(buff.getShort());
        case PgType.PG_TYPE_INT4:
            checkParamLength(4, data.length);
            return ValueInt.get(buff.getInt());
        case PgType.PG_TYPE_INT8:
            checkParamLength(8, data.length);
            return ValueLong.get(buff.getLong());
        case PgType.PG_TYPE_FLOAT4:
            checkParamLength(4, data.length);
            return ValueFloat.get(buff.getFloat());
        case PgType.PG_TYPE_FLOAT8:
            checkParamLength(8, data.length);
            return ValueDouble.get(buff.getDouble());
        case PgType.PG_TYPE_BYTEA:
            return ValueBytes.getNoCopy(data);
        case PgType.PG_TYPE_NUMERIC:
            return decodeNumeric(data);
        case PgType.PG_TYPE_DATE: {
            checkParamLength(4, data.length);
            long days = buff.getInt();
            return ValueDate.get(new java.sql.Date((days * 86400L + 10957L * 86400L) * 1000L));
        }
        case PgType.PG_TYPE_TIMESTAMP_NO_TMZONE: {
            checkParamLength(8, data.length);
            long micros = buff.getLong();
            long millis = 946684800000L + micros / 1000L; // 2000-01-01 00:00:00 UTC
            return ValueTimestamp.get(new Timestamp(millis));
        }
        case PgType.PG_TYPE_VARCHAR:
        case PgType.PG_TYPE_TEXT:
        case PgType.PG_TYPE_BPCHAR:
        case PgType.PG_TYPE_UNKNOWN:
            return ValueString.get(new String(data, getEncoding()));
        default:
            throw DbException.getUnsupportedException(
                    "binary parameter format for pg type " + pgType);
        }
    }

    /** PostgreSQL NUMERIC 二进制格式: int16 ndigits, int16 weight, uint16 sign, int16 dscale, int16 digits[] */
    private static Value decodeNumeric(byte[] data) {
        ByteBuffer buff = ByteBuffer.wrap(data);
        int ndigits = buff.getShort() & 0xFFFF;
        int weight = buff.getShort();
        int sign = buff.getShort() & 0xFFFF;
        int dscale = buff.getShort() & 0xFFFF;
        if (sign == 0xC000) // NaN
            return ValueDecimal.get(BigDecimal.valueOf(Double.NaN));
        // 按"未缩放整数 + 10^4 的权重"拼, 不能用 BigDecimal.pow 负指数(会抛 Invalid operation)
        java.math.BigInteger unscaled = java.math.BigInteger.ZERO;
        for (int i = 0; i < ndigits; i++) {
            int digit = buff.getShort() & 0xFFFF;
            unscaled = unscaled.multiply(java.math.BigInteger.valueOf(10000))
                    .add(java.math.BigInteger.valueOf(digit));
        }
        BigDecimal value = new BigDecimal(unscaled).scaleByPowerOfTen(4 * (weight - ndigits + 1));
        if (dscale > 0)
            value = value.setScale(dscale, RoundingMode.HALF_UP);
        if (sign == 0x4000)
            value = value.negate();
        return ValueDecimal.get(value);
    }

    /** 去掉 PG 文本形式里的时区后缀: 2024-01-02 +00 / 2024-01-02 03:04:05.123+08 / ... UTC */
    static String stripTimeZone(String s) {
        String v = s.trim();
        int i = v.indexOf('+');
        if (i < 0)
            i = v.indexOf('-', 10); // 跳过日期里的 '-'
        if (i > 0)
            v = v.substring(0, i).trim();
        if (v.endsWith("Z") || v.endsWith("UTC"))
            v = v.substring(0, v.length() - ("UTC".length() < v.length() && v.endsWith("UTC") ? 3 : 1)).trim();
        return v;
    }

    /** bytea 的文本形式: \x0102ff */
    static byte[] decodeByteaText(String s) {
        String v = s.trim();
        if (v.startsWith("\\x")) {
            String hex = v.substring(2);
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return b;
        }
        return v.getBytes();
    }

    private static void checkParamLength(int expected, int got) {
        if (expected != got)
            throw DbException.getInvalidValueException("paramLen", got);
    }

    private String getSQL(String s) {
        String lower = StringUtils.toLowerEnglish(s);
        if (lower.startsWith("show max_identifier_length")) {
            s = "CALL 63";
        } else if (lower.startsWith("set client_encoding to")) {
            s = "set DATESTYLE ISO";
        }
        if (server.getTrace()) {
            server.trace(s + ";");
        }
        return s;
    }

    // ---------------------------------------------------------------- 回包

    private void sendCommandComplete(PreparedSQLStatement stat, int updateCount) throws IOException {
        startMessage('C');
        writeString(getCommandTag(stat.getType(), updateCount));
        sendMessage();
    }

    private static String getCommandTag(int type, int count) {
        switch (type) {
        case SQLStatement.INSERT:
            return "INSERT 0 " + count;
        case SQLStatement.UPDATE:
            return "UPDATE " + count;
        case SQLStatement.DELETE:
            return "DELETE " + count;
        case SQLStatement.SELECT:
        case SQLStatement.CALL:
            return "SELECT " + count;
        case SQLStatement.BEGIN:
            return "BEGIN";
        case SQLStatement.COMMIT:
            return "COMMIT";
        case SQLStatement.ROLLBACK:
            return "ROLLBACK";
        case SQLStatement.SET:
            return "SET";
        case SQLStatement.CREATE_TABLE:
            return "CREATE TABLE";
        case SQLStatement.DROP_TABLE:
            return "DROP TABLE";
        default:
            return "OK";
        }
    }

    private void sendDataRow(Result rs) throws Exception {
        int columns = rs.getVisibleColumnCount();
        startMessage('D');
        writeShort(columns);
        Value[] row = rs.currentRow();
        for (int i = 0; i < columns; i++) {
            Value v = row[i];
            if (resultFormats != null && i < resultFormats.length && resultFormats[i] == 1) {
                byte[] bin = toBinary(v, i);
                if (bin == null)
                    writeInt(-1);
                else {
                    writeInt(bin.length);
                    write(bin);
                }
                continue;
            }
            String s = toText(v, i);
            if (s == null) {
                writeInt(-1);
            } else {
                byte[] d2 = s.getBytes(getEncoding());
                writeInt(d2.length);
                write(d2);
            }
        }
        sendMessage();
    }

    /** Execute 时按真实结果集的列类型决定每列输出格式(客户端在 Bind 里请求 binary 时要照着编) */
    private void applyResultFormats(Result result, int[] requestedFormats) {
        int columns = result.getVisibleColumnCount();
        int[] types = new int[columns];
        for (int i = 0; i < columns; i++) {
            types[i] = PgType.convertValueType(result.getColumnType(i));
        }
        resultColumnTypes = types;
        resultFormats = formatsFor(types, requestedFormats);
    }

    private static int[] formatsFor(int[] columnTypes, int[] requestedFormats) {
        if (columnTypes == null || requestedFormats == null || requestedFormats.length == 0)
            return null;
        int[] formats = new int[columnTypes.length];
        for (int i = 0; i < columnTypes.length; i++) {
            formats[i] = wantsBinary(requestedFormats, i) && canEncodeBinary(columnTypes[i]) ? 1 : 0;
        }
        return formats;
    }

    /** 客户端在 Bind 里给的 result format codes(规则同 PG: 0 个=全 text, 1 个=套用到所有列) */
    private static boolean wantsBinary(int[] formats, int column) {
        if (formats == null || formats.length == 0)
            return false;
        if (formats.length == 1)
            return formats[0] == 1;
        return column < formats.length && formats[column] == 1;
    }

    /** 我们能输出二进制格式的类型(其余列退化成 text, 客户端按 RowDescription 里的格式码解析) */
    private static boolean canEncodeBinary(int pgType) {
        switch (pgType) {
        case PgType.PG_TYPE_BOOL:
        case PgType.PG_TYPE_INT2:
        case PgType.PG_TYPE_INT4:
        case PgType.PG_TYPE_INT8:
        case PgType.PG_TYPE_FLOAT4:
        case PgType.PG_TYPE_FLOAT8:
        case PgType.PG_TYPE_BYTEA:
        case PgType.PG_TYPE_VARCHAR:
        case PgType.PG_TYPE_TEXT:
        case PgType.PG_TYPE_BPCHAR:
        case PgType.PG_TYPE_DATE:
        case PgType.PG_TYPE_TIMESTAMP_NO_TMZONE:
            return true;
        default:
            return false;
        }
    }

    /** 按列类型输出 PG 二进制格式(客户端在 Bind 里要求 binary 时) */
    private byte[] toBinary(Value v, int column) throws java.io.UnsupportedEncodingException {
        if (v == null || v == ValueNull.INSTANCE)
            return null;
        int type = resultColumnTypes != null && column < resultColumnTypes.length
                ? resultColumnTypes[column]
                : PgType.PG_TYPE_UNKNOWN;
        switch (type) {
        case PgType.PG_TYPE_BOOL:
            return new byte[] { (byte) (v.getBoolean() ? 1 : 0) };
        case PgType.PG_TYPE_INT2:
            return java.nio.ByteBuffer.allocate(2).putShort((short) v.getInt()).array();
        case PgType.PG_TYPE_INT4:
            return java.nio.ByteBuffer.allocate(4).putInt(v.getInt()).array();
        case PgType.PG_TYPE_INT8:
            return java.nio.ByteBuffer.allocate(8).putLong(v.getLong()).array();
        case PgType.PG_TYPE_FLOAT4:
            return java.nio.ByteBuffer.allocate(4).putFloat((float) v.getDouble()).array();
        case PgType.PG_TYPE_FLOAT8:
            return java.nio.ByteBuffer.allocate(8).putDouble(v.getDouble()).array();
        case PgType.PG_TYPE_BYTEA:
            return v.getBytesNoCopy();
        case PgType.PG_TYPE_VARCHAR:
        case PgType.PG_TYPE_TEXT:
        case PgType.PG_TYPE_BPCHAR:
            return v.getString().getBytes(getEncoding());
        case PgType.PG_TYPE_DATE: {
            // PG: 从 2000-01-01 起的天数
            java.time.LocalDate d = java.time.LocalDate.parse(v.getString().substring(0, 10));
            long days = d.toEpochDay() - java.time.LocalDate.of(2000, 1, 1).toEpochDay();
            return java.nio.ByteBuffer.allocate(4).putInt((int) days).array();
        }
        case PgType.PG_TYPE_TIMESTAMP_NO_TMZONE: {
            // PG: 从 2000-01-01 00:00:00 起的微秒数
            String s = v.getString();
            if (s.length() > 19)
                s = s.substring(0, 19);
            java.time.LocalDateTime ts = java.time.LocalDateTime
                    .parse(s.replace(' ', 'T'));
            long micros = java.time.Duration
                    .between(java.time.LocalDateTime.of(2000, 1, 1, 0, 0), ts).toNanos() / 1000;
            return java.nio.ByteBuffer.allocate(8).putLong(micros).array();
        }
        default:
            return v.getString().getBytes(getEncoding());
        }
    }

    /** 按列类型做 PG 文本格式编码：boolean 必须是 t/f，bytea 必须是 \\x 十六进制 */
    private String toText(Value v, int column) {
        if (v == null || v == ValueNull.INSTANCE)
            return null;
        int type = resultColumnTypes != null && column < resultColumnTypes.length
                ? resultColumnTypes[column]
                : PgType.PG_TYPE_UNKNOWN;
        switch (type) {
        case PgType.PG_TYPE_BOOL:
            return v.getBoolean() ? "t" : "f";
        case PgType.PG_TYPE_BYTEA: {
            byte[] b = v.getBytesNoCopy();
            StringBuilder sb = new StringBuilder(2 + b.length * 2);
            sb.append("\\x");
            for (byte x : b) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        }
        case PgType.PG_TYPE_TEXTARRAY:
            return toPgArray(v);
        default:
            return v.getString();
        }
    }

    /**
     * 数组的 PG 文本格式是 {a,b}(ValueArray.getString() 是 (a, b), 客户端解析不了)。
     * 元素里有逗号/花括号/引号/反斜杠时按 PG 规则加双引号并转义。
     */
    private static String toPgArray(Value v) {
        if (!(v instanceof ValueArray)) {
            return v.getString();
        }
        Value[] list = ((ValueArray) v).getList();
        StringBuilder sb = new StringBuilder(2 + list.length * 8);
        sb.append('{');
        for (int i = 0; i < list.length; i++) {
            if (i > 0)
                sb.append(',');
            if (list[i] == null || list[i] == ValueNull.INSTANCE) {
                sb.append("NULL");
                continue;
            }
            String s = list[i].getString();
            if (s == null) {
                sb.append("NULL");
                continue;
            }
            boolean quote = s.isEmpty();
            for (int j = 0; !quote && j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == ',' || c == '{' || c == '}' || c == '"' || c == '\\'
                        || Character.isWhitespace(c)) {
                    quote = true;
                }
            }
            if (quote) {
                sb.append('"');
                for (int j = 0; j < s.length(); j++) {
                    char c = s.charAt(j);
                    if (c == '"' || c == '\\')
                        sb.append('\\');
                    sb.append(c);
                }
                sb.append('"');
            } else {
                sb.append(s);
            }
        }
        return sb.append('}').toString();
    }

    private void sendParameterDescription(Prepared p) throws IOException {
        List<? extends CommandParameter> parameters = p.prep.getParameters();
        int count = parameters.size();
        startMessage('t');
        writeShort(count);
        for (int i = 0; i < count; i++) {
            int type = (p.paramTypes != null && p.paramTypes[i] != 0) ? p.paramTypes[i]
                    : PgType.PG_TYPE_VARCHAR;
            server.checkType(type);
            writeInt(type);
        }
        sendMessage();
    }

    private void sendRowDescriptionOrNoData(PreparedSQLStatement stmt) throws IOException {
        sendRowDescriptionOrNoData(stmt, null);
    }

    private void sendRowDescriptionOrNoData(PreparedSQLStatement stmt, int[] requestedFormats)
            throws IOException {
        if (!stmt.isQuery()) {
            sendNoData();
            return;
        }
        try {
            sendRowDescription(stmt.getMetaData().get(), requestedFormats);
        } catch (Exception e) {
            sendErrorResponse(e);
        }
    }

    private void sendNoData() throws IOException {
        startMessage('n');
        sendMessage();
    }

    /**
     * 一律声明为 text 格式：原来会按客户端请求回 format=1(二进制)，但 DataRow 实际发的是文本，
     * 结果 boolean 经 JDBC 读出来恒为 false、命名预备语句的 int 列让驱动抛 AIOOBE。
     * 要么真写二进制编码，要么老实声明 text；这里选后者。
     */
    private void sendRowDescription(Result result) throws IOException {
        sendRowDescription(result, null);
    }

    private void sendRowDescription(Result result, int[] requestedFormats) throws IOException {
        if (result == null) {
            sendNoData();
            return;
        }
        int columns = result.getVisibleColumnCount();
        int[] oids = new int[columns];
        int[] attnums = new int[columns];
        int[] types = new int[columns];
        int[] precision = new int[columns];
        String[] names = new String[columns];
        Database database = session.getDatabase();
        for (int i = 0; i < columns; i++) {
            String name = result.getColumnName(i);
            Schema schema = database.findSchema(session, result.getSchemaName(i));
            if (schema != null) {
                Table table = schema.findTableOrView(session, result.getTableName(i));
                if (table != null) {
                    oids[i] = table.getId();
                    Column column = table.getColumn(name);
                    if (column != null)
                        attnums[i] = column.getColumnId() + 1;
                }
            }
            names[i] = name;
            int type = result.getColumnType(i);
            int pgType = PgType.convertValueType(type);
            precision[i] = result.getDisplaySize(i);
            if (type != Value.NULL)
                server.checkType(pgType);
            types[i] = pgType;
        }
        resultColumnTypes = types;
        int[] formats = new int[columns];
        for (int i = 0; i < columns; i++) {
            formats[i] = wantsBinary(requestedFormats, i) && canEncodeBinary(types[i]) ? 1 : 0;
        }
        resultFormats = formats;
        startMessage('T');
        writeShort(columns);
        for (int i = 0; i < columns; i++) {
            writeString(StringUtils.toLowerEnglish(names[i]));
            writeInt(oids[i]);
            writeShort(attnums[i]);
            writeInt(types[i]);
            writeShort(getTypeSize(types[i], precision[i]));
            writeInt(-1);
            writeShort(formats[i]);
        }
        sendMessage();
    }

    private static int getTypeSize(int pgType, int precision) {
        switch (pgType) {
        case PgType.PG_TYPE_VARCHAR:
            return Math.max(255, precision + 10);
        default:
            return precision + 4;
        }
    }

    private void sendParseComplete() throws IOException {
        startMessage('1');
        sendMessage();
    }

    private void sendBindComplete() throws IOException {
        startMessage('2');
        sendMessage();
    }

    private void sendCloseComplete() throws IOException {
        startMessage('3');
        sendMessage();
    }

    static class Prepared {
        /** Describe('S') 时得到的列类型, Bind 请求二进制输出时用来编码 */
        int[] columnTypes;
        String name;
        String sql;
        PreparedSQLStatement prep;
        int[] paramTypes;
    }

    static class Portal {
        String name;
        Prepared prep;
        Value[] values;
        int[] resultColumnFormat;
        /** Describe 时算好的每列输出格式, Execute 时复用 */
        int[] formats;
    }
}
