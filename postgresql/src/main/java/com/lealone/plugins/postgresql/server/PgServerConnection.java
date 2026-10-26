/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.server;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Properties;

import com.lealone.common.exceptions.DbException;

import com.lealone.db.ConnectionInfo;
import com.lealone.db.Constants;
import com.lealone.db.plugin.PluginManager;
import com.lealone.db.scheduler.Scheduler;
import com.lealone.db.session.ServerSession;
import com.lealone.net.NetBuffer;
import com.lealone.net.WritableChannel;
import com.lealone.plugins.postgresql.PgPlugin;
import com.lealone.plugins.postgresql.server.handler.AuthPacketHandler;
import com.lealone.plugins.postgresql.server.handler.CommandPacketHandler;
import com.lealone.plugins.postgresql.server.handler.PacketHandler;
import com.lealone.plugins.postgresql.server.io.NetBufferOutput;
import com.lealone.plugins.postgresql.sql.PgAlias;
import com.lealone.server.AsyncServerConnection;
import com.lealone.server.scheduler.ServerSessionInfo;
import com.lealone.sql.SQLEngine;

public class PgServerConnection extends AsyncServerConnection {

    private final PgServer server;
    private ServerSession session;
    private boolean stop;
    private boolean initDone;
    private int processId;
    private PacketHandler packetHandler;

    private final NetBufferOutput out;

    protected PgServerConnection(PgServer server, WritableChannel writableChannel, Scheduler scheduler) {
        super(writableChannel, scheduler);
        this.server = server;
        out = new NetBufferOutput(getWritableChannel(), scheduler.getOutputBuffer());
        // 需要先认证，然后再切换到CommandPacketHandler
        packetHandler = new AuthPacketHandler(server, this);
    }

    public NetBufferOutput getOut() {
        return out;
    }

    public void setProcessId(int id) {
        this.processId = id;
    }

    public int getProcessId() {
        return processId;
    }

    @Override
    public void closeSession(ServerSessionInfo si) {
    }

    @Override
    public int getSessionCount() {
        return 1;
    }

    public void initDone() {
        initDone = true;
    }

    public void stop() {
        stop = true;
    }

    public void createSession(String databaseName, String userName, String password)
            throws SQLException {
        Properties info = new Properties();
        info.put("MODE", PgPlugin.NAME);
        info.put("USER", userName);
        info.put("PASSWORD", password);
        info.put("DEFAULT_SQL_ENGINE", PgPlugin.NAME);
        String url = Constants.URL_PREFIX + Constants.URL_TCP + server.getHost() + ":" + server.getPort()
                + "/" + databaseName;
        ConnectionInfo ci = new ConnectionInfo(url, info);
        ci.setRemote(false);
        session = (ServerSession) ci.createSession();
        ServerSessionInfo si = new ServerSessionInfo(scheduler, this, session, -1, -1);
        scheduler.addSessionInfo(si);
        session.setSQLEngine(PluginManager.getPlugin(SQLEngine.class, PgPlugin.NAME));
        session.setVersion(PgAlias.getVersion());

        packetHandler.setSession(session, si); // 旧的设置一次
        packetHandler = new CommandPacketHandler(server, this);
        packetHandler.setSession(session, si); // 新的再设置一次

        server.createBuiltInSchemas(session);
    }

    @Override
    public void close() {
        if (session == null)
            return;
        try {
            stop = true;
            session.close();
            server.trace("Close");
            super.close();
        } catch (Exception e) {
            server.traceError(e);
        }
        session = null;
        server.removeConnection(this);
    }

    @Override
    public int getPacketLengthByteCount() {
        if (initDone)
            return 5;
        else
            return 4;
    }

    private int x;

    @Override
    public int getPacketLength(ByteBuffer buffer) {
        if (initDone) {
            x = (buffer.get() & 0xff);
        }
        int len = buffer.getInt();
        return len - 4;
    }

    // 一条消息处理完(含异步执行完)才处理下一条：Execute/Query 是异步的，执行期间收到的
    // 后续包先复制排队，回调里调用 packetFinished() 再按顺序处理，保证协议顺序且不阻塞调度线程。
    private boolean busy;
    private final java.util.ArrayDeque<Object[]> pendingPackets = new java.util.ArrayDeque<>();

    @Override
    public void handle(NetBuffer buffer, boolean autoRecycle) {
        if (stop)
            return;
        try {
            if (!initDone) {
                // 启动阶段(StartupMessage/SSLRequest)没有类型字节, 直接处理, 不会重入
                packetHandler.handle(buffer, 0);
                return;
            }
            if (x < 0) {
                close();
                return;
            }
            int type = x;
            byte[] data = copyPacket(buffer);
            if (busy) {
                pendingPackets.add(new Object[] { type, data });
                return;
            }
            busy = true;
            dispatchPacket(type, data);
        } finally {
            if (autoRecycle)
                buffer.recycle();
        }
    }

    private void dispatchPacket(int type, byte[] data) {
        boolean async;
        try {
            async = packetHandler.handle(toNetBuffer(data), type);
        } catch (Throwable t) {
            server.traceError(DbException.convert(t));
            async = false;
        }
        if (!async)
            packetFinished();
    }

    /** 当前包(及其异步执行)完成后调用：继续处理排队的下一个包 */
    public void packetFinished() {
        if (stop) {
            busy = false;
            pendingPackets.clear();
            return;
        }
        Object[] next = pendingPackets.poll();
        if (next == null) {
            busy = false;
            return;
        }
        dispatchPacket((Integer) next[0], (byte[]) next[1]);
    }

    private NetBuffer toNetBuffer(byte[] data) {
        com.lealone.db.DataBuffer db = com.lealone.db.DataBuffer.create(data.length + 16);
        NetBuffer nb = new NetBuffer(db);
        nb.appendBytes(data, 0, data.length);
        nb.position(0);
        return nb;
    }

    /**
     * 把当前包的有效载荷复制出来。注意 NetBuffer.length() 返回的是 dataBuffer.position()，
     * 也就是"已经读到的位置"，不是包的结束位置；包尾要用底层 ByteBuffer 的 limit。
     */
    private static byte[] copyPacket(NetBuffer buffer) {
        int start = buffer.position();
        java.nio.ByteBuffer bb = buffer.getByteBuffer();
        int end = bb.limit();
        int len = end - start;
        if (len <= 0)
            return new byte[0];
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++)
            data[i] = bb.get(start + i);
        // 必须把原 buffer 的读位置推到包尾, 否则网络层会认为这个包还没消费完,
        // 把载荷当成下一个包的包头解析(Packet too large)
        buffer.position(end);
        return data;
    }
}
