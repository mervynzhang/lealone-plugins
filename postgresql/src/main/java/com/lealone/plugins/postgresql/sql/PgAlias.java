/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

import com.lealone.db.Constants;
import com.lealone.plugins.postgresql.server.PgServer;
import com.lealone.plugins.postgresql.server.PgType;

/**
 * This class implements a subset of the PostgreSQL protocol as described here:
 * http://developer.postgresql.org/pgdocs/postgres/protocol.html
 * The PostgreSQL catalog is described here:
 * http://www.postgresql.org/docs/7.4/static/catalogs.html
 *
 * @author Thomas Mueller
 * @author Sergi Vladykin 2009-07-03 (convertType)
 * @author zhh
 */
// 以下方法在pg_catalog.sql中用到
public class PgAlias {

    /**
     * The Java implementation of the PostgreSQL function pg_get_indexdef. The
     * method is used to get CREATE INDEX command for an index, or the column
     * definition of one column in the index.
     *
     * @param conn the connection
     * @param indexId the index id
     * @param ordinalPosition the ordinal position (null if the SQL statement
     *            should be returned)
     * @param pretty this flag is ignored
     * @return the SQL statement or the column name
     */
    public static String getIndexColumn(Connection conn, int indexId, Integer ordinalPosition,
            Boolean pretty) {
        if (ordinalPosition == null || ordinalPosition.intValue() == 0) {
            String sql = INDEX_SQL.get(indexId);
            return sql == null ? "" : sql;
        }
        String column = INDEX_COLUMNS.get(indexId + ":" + ordinalPosition.intValue());
        return column == null ? "" : column;
    }

    public static String getCurrentSchema(Connection conn) throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("call schema()");
        rs.next();
        return rs.getString(1);
    }

    /**
     * current_schemas(boolean): pgjdbc 的 TypeInfoCache 用它判断类型/表所在的 schema 是否可见。
     * <p>
     * 这里不能像 getCurrentSchema 那样查库: 别名函数拿到的是同一 session 的嵌套连接,
     * 遍历外层结果集时再查库会把外层游标冲掉(外层静默变成 0 行)。PgSQLParser 在会话建立时
     * 固定把 search_path 初始化为 public, pg_catalog, 所以直接按 PG 的语义返回即可。
     * <p>
     * 返回真正的字符串数组(text[]): pgjdbc 的 SQL 里会写
     * {@code (current_schemas(false))[s.r]} 和 {@code array_upper(current_schemas(false), 1)},
     * 返回逗号分隔的 varchar 时下标运算(ARRAY_GET)取不到元素, 只能恒为 null。
     *
     * @param conn the connection (unused, 保留给别名函数签名)
     * @param includeImplicit 是否包含隐式 schema(pg_catalog); false 时只返回 search_path 里
     *            显式列出的 public(与真 PG 的 current_schemas(false) = {public} 一致)
     * @return schema 名数组
     */
    public static String[] getCurrentSchemas(Connection conn, Boolean includeImplicit) {
        if (includeImplicit != null && includeImplicit.booleanValue()) {
            return new String[] { "pg_catalog", "public" };
        }
        return new String[] { "public" };
    }

    /**
     * array_upper(anyarray, int): PG 数组的上界。当前只用于 current_schemas 返回的 text[],
     * 一维数组的下标从 1 开始, 所以上界就是元素个数; 其他维度跟 PG 一样返回 null。
     */
    public static Integer arrayUpper(String[] array, int dimension) {
        if (array == null || dimension != 1) {
            return null;
        }
        return array.length;
    }

    /**
     * Get the OID of an object.
     * This method is called by the database.
     *
     * @param conn the connection
     * @param tableName the table name
     * @return the oid
     */
    public static int getOid(Connection conn, String tableName) throws SQLException {
        if (tableName.startsWith("\"") && tableName.endsWith("\"")) {
            tableName = tableName.substring(1, tableName.length() - 1);
        }
        PreparedStatement prep = conn.prepareStatement("select oid from pg_class where relName = ?");
        prep.setString(1, tableName);
        ResultSet rs = prep.executeQuery();
        if (!rs.next()) {
            return 0;
        }
        return rs.getInt(1);
    }

    /**
     * Get the name of this encoding code.
     * This method is called by the database.
     *
     * @param code the encoding code
     * @return the encoding name
     */
    public static String getEncodingName(int code) {
        switch (code) {
        case 0:
            return "SQL_ASCII";
        case 6:
            return "UTF8";
        case 8:
            return "LATIN1";
        default:
            return code < 40 ? "UTF8" : "";
        }
    }

    public static int getEncodingCode(String name) {
        switch (name) {
        case "SQL_ASCII":
            return 0;
        case "UTF8":
            return 6;
        case "LATIN1":
            return 8;
        default:
            return 1;
        }
    }

    /**
     * Get the version. This method must return PostgreSQL to keep some clients
     * happy. This method is called by the database.
     *
     * @return the server name and version
     */
    public static String getVersion() {
        return "PostgreSQL " + PgServer.PG_VERSION + ", compiled by Lealone "
                + Constants.getFullVersion() + ", 64-bit";
    }

    /** format_type: psql 显示列类型用。注意 atttypid 存的是 PG 的 OID(不是 java.sql.Types) */
    public static String formatType(final int type, final int typmod) {
        switch (type) {
        case 16:
            return "boolean";
        case 21:
            return "smallint";
        case 23:
            return "integer";
        case 20:
            return "bigint";
        case 700:
            return "real";
        case 701:
            return "double precision";
        case 1700:
            return "numeric";
        case 1043:
            return typmod > 4 ? "character varying(" + (typmod - 4) + ")" : "character varying";
        case 1042:
            return typmod > 4 ? "character(" + (typmod - 4) + ")" : "character";
        case 25:
            return "text";
        case 1082:
            return "date";
        case 1083:
            return "time without time zone";
        case 1114:
            return "timestamp without time zone";
        case 1184:
            return "timestamp with time zone";
        case 17:
            return "bytea";
        case 26:
            return "oid";
        case 1009:
            return "text[]";
        case 114:
            return "json";
        // 兼容以 java.sql.Types 常量传进来的调用方(如 PgSyntaxTest)
        case java.sql.Types.INTEGER:
            return "integer";
        case java.sql.Types.SMALLINT:
            return "smallint";
        case java.sql.Types.BIGINT:
            return "bigint";
        case java.sql.Types.REAL:
            return "real";
        case java.sql.Types.FLOAT:
        case java.sql.Types.DOUBLE:
            return "double precision";
        case java.sql.Types.NUMERIC:
        case java.sql.Types.DECIMAL:
            return "numeric";
        case java.sql.Types.CHAR:
            return "character";
        case java.sql.Types.VARCHAR:
        case java.sql.Types.LONGVARCHAR:
            return typmod > 4 ? "character varying(" + (typmod - 4) + ")" : "character varying";
        case java.sql.Types.DATE:
            return "date";
        case java.sql.Types.TIME:
            return "time without time zone";
        case java.sql.Types.TIMESTAMP:
            return "timestamp without time zone";
        case java.sql.Types.BINARY:
        case java.sql.Types.VARBINARY:
            return "bytea";
        case java.sql.Types.CLOB:
            return "text";
        case java.sql.Types.ARRAY:
            return "text[]";
        default:
            return "unknown";
        }
    }

    /**
     * Get the current system time.
     * This method is called by the database.
     *
     * @return the current system time
     */
    public static Timestamp getStartTime() {
        return new Timestamp(System.currentTimeMillis());
    }

    /**
     * Get the user name for this id.
     * This method is called by the database.
     *
     * @param conn the connection
     * @param id the user id
     * @return the user name
     */
    // 别名函数拿到的是同一 session 的嵌套连接，遍历外层结果集时再查库会把外层游标冲掉
    // (外层会静默变成 0 行)，所以用户表和索引元数据在会话建立时快照到缓存里。
    private static volatile java.util.Map<Integer, String> USER_NAMES = java.util.Collections.emptyMap();
    private static volatile java.util.Map<Integer, String> INDEX_SQL = java.util.Collections.emptyMap();
    private static volatile java.util.Map<String, String> INDEX_COLUMNS = java.util.Collections.emptyMap();

    public static void setUserNames(java.util.Map<Integer, String> names) {
        USER_NAMES = names;
    }

    public static void setIndexCache(java.util.Map<Integer, String> sql,
            java.util.Map<String, String> columns) {
        INDEX_SQL = sql;
        INDEX_COLUMNS = columns;
    }

    public static String getUserById(Connection conn, int id) {
        String name = USER_NAMES.get(id);
        return name == null ? null : name.toLowerCase();
    }

    public static boolean hasDatabasePrivilege(int id, String privilege) {
        return true;
    }

    /**
     * Check if the current session has access to this table.
     * This method is called by the database.
     *
     * @param table the table name
     * @param privilege the privilege to check
     * @return true
     */
    public static boolean hasTablePrivilege(String table, String privilege) {
        return true;
    }

    /**
     * Get the current transaction id.
     * This method is called by the database.
     *
     * @param table the table name
     * @param id the id
     * @return 1
     */
    public static int getCurrentTid(String table, String id) {
        return 1;
    }

    /**
     * Convert the SQL type to a PostgreSQL type
     *
     * @param type the SQL type
     * @return the PostgreSQL type
     */
    public static int convertType(int type) {
        return PgType.convertType(type);
    }

    // ---------------------------------------------------------------- psql / pgjdbc 需要的 catalog 辅助函数

    public static boolean pgTableIsVisible(Connection conn, int oid) {
        return true;
    }

    public static boolean pgFunctionIsVisible(Connection conn, int oid) {
        return true;
    }

    public static boolean pgTypeIsVisible(Connection conn, int oid) {
        return true;
    }

    public static boolean pgCollationIsVisible(Connection conn, int oid) {
        return true;
    }

    public static boolean hasSchemaPrivilege(Connection conn, String schema, String privilege) {
        return true;
    }

    public static boolean hasColumnPrivilege(Connection conn, String table, String column,
            String privilege) {
        return true;
    }

    public static boolean hasFunctionPrivilege(Connection conn, String function, String privilege) {
        return true;
    }

    public static String arrayToString(Object array, String separator) {
        if (array == null)
            return null;
        String sep = separator == null ? "" : separator;
        StringBuilder sb = new StringBuilder();
        if (array instanceof Object[]) {
            Object[] a = (Object[]) array;
            for (int i = 0; i < a.length; i++) {
                if (i > 0)
                    sb.append(sep);
                sb.append(a[i]);
            }
            return sb.toString();
        }
        return array.toString();
    }

    public static String getFunctionResult(Connection conn, int oid) {
        return "";
    }

    public static String getFunctionArguments(Connection conn, int oid) {
        return "";
    }

    public static String getFunctionIdentityArguments(Connection conn, int oid) {
        return "";
    }

    public static String getFunctionDef(Connection conn, int oid) {
        return "";
    }

    public static String getExpr(Connection conn, String expr, int relid) {
        return expr == null ? "" : expr;
    }

    public static String getExpr(Connection conn, String expr, int relid, Boolean pretty) {
        return expr == null ? "" : expr;
    }

    public static String getConstraintDef(Connection conn, int oid) {
        return "";
    }

    public static String getConstraintDef(Connection conn, int oid, Boolean pretty) {
        return "";
    }

    public static String getViewDef(Connection conn, int oid) {
        return "";
    }

    public static String getTriggerDef(Connection conn, int oid) {
        return "";
    }

    public static String getRuleDef(Connection conn, int oid) {
        return "";
    }

    public static String getPartKeyDef(Connection conn, int oid) {
        return null;
    }

    public static String getSerialSequence(Connection conn, String table, String column) {
        return null;
    }

    public static String getCurrentDatabase(Connection conn) throws SQLException {
        String catalog = conn.getCatalog();
        return catalog == null ? "postgres" : catalog;
    }

    public static String getCurrentSetting(Connection conn, String name) {
        if (name == null)
            return null;
        switch (name.toLowerCase()) {
        case "server_version":
            return PgServer.PG_VERSION;
        case "server_encoding":
        case "client_encoding":
            return "UTF8";
        case "transaction_isolation":
        case "default_transaction_isolation":
            return "read committed";
        case "datestyle":
            return "ISO, MDY";
        case "search_path":
            return "public, pg_catalog";
        default:
            return "";
        }
    }

    public static int getBackendPid() {
        return (int) ProcessHandle.current().pid();
    }

    public static String getSizePretty(long size) {
        if (size < 0)
            return null;
        if (size < 10240)
            return size + " bytes";
        final String[] units = { "kB", "MB", "GB", "TB" };
        double v = size / 1024.0;
        int i = 0;
        while (v >= 10240 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format(java.util.Locale.ROOT, "%.0f %s", v, units[i]);
    }

    public static String objDescription(Connection conn, int oid) {
        return null;
    }

    public static String objDescription(Connection conn, int oid, String catalogName) {
        return null;
    }

    public static String colDescription(Connection conn, int relid, int columnNumber) {
        return null;
    }

    public static String shobjDescription(Connection conn, int oid, String catalogName) {
        return null;
    }
}
