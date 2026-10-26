/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;

import com.lealone.common.exceptions.DbException;
import com.lealone.common.logging.Logger;
import com.lealone.common.logging.LoggerFactory;
import com.lealone.common.util.Utils;
import com.lealone.db.session.ServerSession;
import com.lealone.net.NetBuffer;
import com.lealone.plugins.postgresql.server.PgServer;
import com.lealone.plugins.postgresql.server.PgServerConnection;
import com.lealone.plugins.postgresql.server.io.NetBufferInput;
import com.lealone.plugins.postgresql.server.io.NetBufferOutput;
import com.lealone.server.scheduler.ServerSessionInfo;

public abstract class PacketHandler {

    private static final Logger logger = LoggerFactory.getLogger(PacketHandler.class);

    protected final PgServer server;
    protected final PgServerConnection conn;

    protected ServerSession session;
    protected ServerSessionInfo si;

    protected NetBufferInput in;
    protected NetBufferOutput out;

    protected String clientEncoding = Utils.getProperty("pgClientEncoding", "UTF-8");
    protected int startPos;

    protected PacketHandler(PgServer server, PgServerConnection conn) {
        this.server = server;
        this.conn = conn;
        out = conn.getOut();
        in = new NetBufferInput();
    }

    /** 处理一个消息；返回 true 表示已交给异步任务，完成后由连接继续处理下一个包。 */
    public abstract boolean handle(int x) throws IOException;

    public boolean handle(NetBuffer buffer, int x) {
        in.reset(buffer);
        try {
            return handle(x);
        } catch (Exception e) {
            logger.error("handle packet exception", e);
            try {
                sendErrorResponse(e);
            } catch (Exception e1) {
                logger.error("sendErrorResponse exception", e1);
            }
            return false;
        }
    }

    public void setSession(ServerSession session, ServerSessionInfo si) {
        this.session = session;
        this.si = si;
    }

    protected String readString() throws IOException {
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        while (true) {
            int x = in.read();
            if (x <= 0) {
                break;
            }
            buff.write(x);
        }
        return new String(buff.toByteArray(), getEncoding());
    }

    protected int readInt() {
        return in.readInt();
    }

    protected int readShort() {
        return in.readShort();
    }

    protected byte readByte() {
        return in.readByte();
    }

    protected void readFully(byte[] buff) {
        in.readFully(buff);
    }

    protected String getEncoding() {
        if ("UNICODE".equals(clientEncoding)) {
            return "UTF-8";
        }
        return clientEncoding;
    }

    protected void sendErrorResponse(String message) throws IOException {
        server.trace("Exception: " + message);
        startMessage('E');
        write('S');
        writeString("ERROR");
        write('C');
        // PROTOCOL VIOLATION
        writeString("08P01");
        write('M');
        writeString(message);
        sendMessage();
    }

    /** 协议违规时给客户端回 08P01(调用方随后必须断开连接) */
    public void sendProtocolError(String message) {
        try {
            sendErrorResponse(message);
        } catch (Throwable t) {
            logger.error("Failed to send protocol error: " + message, t);
        }
    }

    protected void sendErrorResponse(Throwable re) {
        SQLException e = DbException.toSQLException(re);
        server.traceError(e);
        startMessage('E');
        write('S');
        writeString("ERROR");
        write('C');
        writeString(e.getSQLState());
        write('M');
        writeString(e.getMessage());
        write('D');
        writeString(e.toString());
        write(0);
        sendMessage();
    }

    protected void sendReadyForQuery() throws IOException {
        startMessage('Z');
        char c;
        if (session.isAutoCommit()) {
            // idle
            c = 'I';
        } else {
            // in a transaction block
            c = 'T';
        }
        write((byte) c);
        sendMessage();
    }

    protected void writeString(String s) {
        writeStringPart(s);
        write(0);
    }

    protected void writeStringPart(String s) {
        try {
            write(s.getBytes(getEncoding()));
        } catch (UnsupportedEncodingException e) {
            throw DbException.convert(e);
        }
    }

    protected void writeInt(int i) {
        out.writeInt(i);
    }

    protected void writeShort(int i) {
        out.writeShort(i);
    }

    protected void write(byte[] data) {
        out.write(data);
    }

    protected void write(int b) {
        out.write(b);
    }

    protected void startMessage(int newMessageType) {
        out.startMessage();
        out.write(newMessageType);
        startPos = out.length();
        out.writeInt(0); // 占位
    }

    protected void sendMessage() {
        out.setInt(startPos, out.length() - startPos); // 回填
        out.flush();
    }
}
