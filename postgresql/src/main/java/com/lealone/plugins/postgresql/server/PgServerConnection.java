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
        if (closed)
            return;
        // 关闭时排队中的包不可能再被处理，必须清掉(否则 payload 一直占着内存)
        pendingPackets.clear();
        busy = false;
        awaitingAsync = false;
        try {
            stop = true;
            ServerSession current = session;
            session = null;
            if (current != null) {
                current.close();
                server.trace("Close");
            }
        } catch (Exception e) {
            server.traceError(e);
        } finally {
            // 认证失败时 session 还是 null，也必须关掉底层 socket，
            // 否则坏密码客户端可以一直占着 fd。
            super.close();
            server.removeConnection(this);
        }
    }

    /** 把已经写好的响应(比如认证失败的 ErrorResponse)刷到 socket 后再断开 */
    public void flushAndClose() {
        flushPendingWrites();
        close();
    }

    @Override
    public int getPacketLengthByteCount() {
        if (initDone)
            return 5;
        else
            return 4;
    }

    private int x;
    /** 当前包 payload 的真实长度(getPacketLength 里记下来，拷贝时不能越过它) */
    private int packetLength;

    @Override
    public int getPacketLength(ByteBuffer buffer) {
        if (initDone) {
            x = (buffer.get() & 0xff);
        }
        int len = buffer.getInt();
        packetLength = len - 4;
        return packetLength;
    }

    // 一条消息处理完(含异步执行完)才处理下一条：Execute/Query 是异步的，执行期间收到的
    // 后续包先复制排队，完成后继续按顺序处理，保证协议顺序且不阻塞调度线程。
    //
    // 注意: 绝不能在异步回调的调用栈里直接 dispatch 下一条包。核心的 YieldableBase 是
    // "先 asyncHandler.handle(...) 再 stop()"，而 stop() -> ServerSession.stopCurrentCommand()
    // 最后会 endYieldableCommand() 把 yieldableCommand 清空；如果回调里递归 dispatch 并提交了
    // 新的 YieldableCommand，就会被这次清空顺手抹掉，新语句永远不执行、packetFinished 也
    // 不会再被调用，busy 永远为 true，客户端只能等到超时。
    // 所以这里用 trampoline: 回调只登记"前一条命令完成了"，真正的 dispatch 由调度器上的
    // drain 任务用 while 循环跑(同步包在循环里连续处理，不产生递归调用栈)。
    private boolean busy;
    /** 当前包已经交给异步命令执行，等它的回调调用 packetFinished() */
    private boolean awaitingAsync;
    /** 正在 drain 循环里，此时同步包完成不需要再做任何事，循环会继续 */
    private boolean draining;
    /** 已经往调度器里提交了一个 drain 任务 */
    private boolean drainScheduled;
    private final PendingPackets pendingPackets = new PendingPackets();

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
            if (x < 0 || packetLength < 0) {
                close();
                return;
            }
            int type = x;
            byte[] data = copyPacket(buffer);
            if (busy) {
                if (!pendingPackets.offer(type, data)) {
                    // 队列有上限: 超限时回协议错误并断开，不能静默丢包(客户端/服务端会互相等)
                    sendProtocolErrorAndClose("too many pending packets, pending="
                            + pendingPackets.size() + ", bytes=" + pendingPackets.getBytes());
                }
                return;
            }
            busy = true;
            if (dispatchPacket(type, data)) {
                awaitingAsync = true;
                return;
            }
            drain();
        } finally {
            if (autoRecycle)
                buffer.recycle();
        }
    }

    /** 回一个 08P01 协议错误, 把响应刷到 socket 后再断开连接 */
    public void sendProtocolErrorAndClose(String message) {
        packetHandler.sendProtocolError(message);
        flushPendingWrites();
        close();
    }

    /** 把 output buffer 里攒着的响应同步写到 socket; 关闭连接前必须调用，否则最后的错误包会丢 */
    private void flushPendingWrites() {
        try {
            com.lealone.net.NetEventLoop eventLoop = getWritableChannel().getEventLoop();
            if (eventLoop != null) {
                eventLoop.write();
            }
        } catch (Throwable t) {
            server.traceError(DbException.convert(t));
        }
    }

    /** @return true 表示已交给异步命令，完成后由 packetFinished() 继续 */
    private boolean dispatchPacket(int type, byte[] data) {
        try {
            return packetHandler.handle(toNetBuffer(data), type);
        } catch (Throwable t) {
            server.traceError(DbException.convert(t));
            return false;
        }
    }

    /** 依次处理排队的包，直到队列为空(结束本次 busy)或某条命令变成异步(等回调) */
    private void drain() {
        if (draining)
            return;
        draining = true;
        try {
            while (!stop && busy && !awaitingAsync) {
                Object[] next = pendingPackets.poll();
                if (next == null) {
                    busy = false;
                    break;
                }
                if (dispatchPacket((Integer) next[0], (byte[]) next[1])) {
                    awaitingAsync = true;
                }
            }
        } finally {
            draining = false;
        }
    }

    /** 当前包(及其异步执行)完成后调用：继续处理排队的下一个包 */
    public void packetFinished() {
        if (!busy || stop)
            return;
        if (draining) // 同步包在 drain 循环里完成，循环会继续
            return;
        awaitingAsync = false;
        // 不能在这里 dispatch: 当前还在异步回调的栈里，见字段上的注释。交给调度器异步执行。
        if (drainScheduled)
            return;
        drainScheduled = true;
        scheduler.handle(() -> {
            drainScheduled = false;
            drain();
        });
    }

    private NetBuffer toNetBuffer(byte[] data) {
        // limit 必须精确等于 payload 长度：多出来的容量会让读越界变成"读到 0"，
        // Bind 里 paramLen 声明一个超大值时就发现不了协议违规。
        com.lealone.db.DataBuffer db = com.lealone.db.DataBuffer
                .create(Math.max(16, data.length));
        NetBuffer nb = new NetBuffer(db);
        nb.appendBytes(data, 0, data.length);
        nb.limit(data.length);
        nb.position(0);
        return nb;
    }

    /**
     * 把当前包的有效载荷复制出来。注意 NetBuffer.length() 返回的是 dataBuffer.position()，
     * 也就是"已经读到的位置"，不是包的结束位置。
     * <p>
     * 包尾必须用 getPacketLength() 记下的真实 payload 长度：底层 buffer 里可能还有同一次
     * read() 读进来的后续包，用 bb.limit() 会把后面的包一起拷进来(排队时每条小包都拷贝
     * 整个读缓冲区，内存被放大几十倍)。
     */
    private byte[] copyPacket(NetBuffer buffer) {
        int start = buffer.position();
        java.nio.ByteBuffer bb = buffer.getByteBuffer();
        int end = start + packetLength;
        if (end > bb.limit())
            end = bb.limit();
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
