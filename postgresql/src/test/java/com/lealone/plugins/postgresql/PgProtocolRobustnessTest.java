/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import org.junit.Test;

/**
 * 协议边界的鲁棒性回归。这里的用例都是 pgjdbc 发不出来的"坏包/极端包"，直接拼字节验证
 * 服务端不会因为客户端输入而挂死、打满内存或无限递归。
 * <p>
 * 注意: 长 pipeline 用的是 Bind('B') 而不是 Sync('S')。Sync/Flush 的 payload 长度为 0，
 * 核心 NioEventLoop 在"包头跨 read 边界"的补包路径上会把这种 0 长度包丢掉(与本插件无关，
 * 未在本次修复范围内)，几百条 Sync 一起发就会随机少几个 ReadyForQuery。Bind 同样是同步
 * 包(handler 返回 false，走同一条 packetFinished 路径)，但 payload 非空，可以用来稳定地
 * 制造 N 条同步包的场景。
 */
public class PgProtocolRobustnessTest extends PgTestBase {

    /** 一条 100M 行的交叉连接(约 7 秒)，用来制造"异步命令执行中"的窗口 */
    private static final String SLOW_QUERY = "SELECT count(*) FROM system_range(1, 10000) a,"
            + " system_range(1, 10000) b";

    /** N 条 Bind(none/none) 包: portal\0 statement\0 0 0 0 */
    private static byte[] bindPackets(int count) {
        byte[] data = new byte[count * 13];
        for (int i = 0; i < count; i++) {
            int off = i * 13;
            data[off] = 'B';
            // 长度字段 = 4 + 7 字节 body
            data[off + 1] = 0;
            data[off + 2] = 0;
            data[off + 3] = 0;
            data[off + 4] = 12;
            // portal 名、statement 名、3 个 int16 计数(全 0)
            data[off + 5] = 0;
            data[off + 6] = 0;
        }
        return data;
    }

    /**
     * 长查询执行期间客户端灌入 N 条同步包：旧实现 packetFinished -> dispatchPacket ->
     * packetFinished 纯递归，N 条同步包就是 N 层调用栈，StackOverflowError 抛在
     * if(!async) packetFinished() 之外，busy 不复位，连接永久卡死。
     */
    @Test(timeout = 180000)
    public void longSynchronousPipelineDoesNotOverflowStack() throws Exception {
        final int bindCount = 60000;
        try (PgRawClient client = new PgRawClient(90000)) {
            client.authenticate("postgres", "postgres", "postgres");
            client.sendExtendedQuery(SLOW_QUERY);
            client.writeRaw(bindPackets(bindCount));
            int done = 0;
            while (done < bindCount) {
                PgRawClient.Message m;
                try {
                    m = client.read();
                } catch (java.net.SocketTimeoutException e) {
                    throw new AssertionError("读超时, 只收到 " + done + "/" + bindCount
                            + " 个 BindComplete(递归 dispatch 把连接卡死了)", e);
                }
                if (m == null) {
                    throw new AssertionError("连接被关闭, 只收到 " + done + "/" + bindCount
                            + " 个 BindComplete");
                }
                if (m.type == 'E') {
                    throw new AssertionError("执行出错: " + m.asString());
                }
                if (m.type == '2') {
                    done++;
                }
            }
            // 大量同步包处理完后连接必须还能继续用
            client.simpleQuery("SELECT 1");
        }
    }

    /**
     * 长查询执行期间持续发包：排队的包必须有条数上限。超限要回 08P01 并断开，
     * 不能像旧实现那样无界拷贝(内存打满)，也不能静默丢包(客户端永远等下去)。
     */
    @Test(timeout = 180000)
    public void pendingQueueOverflowReturnsProtocolErrorAndCloses() throws Exception {
        // 65536 是 PendingPackets.DEFAULT_MAX_PACKETS; 多灌一些确保越过上限
        final int bindCount = 70000;
        try (PgRawClient client = new PgRawClient(60000)) {
            client.authenticate("postgres", "postgres", "postgres");
            client.sendExtendedQuery(SLOW_QUERY);
            client.writeRaw(bindPackets(bindCount));
            PgRawClient.Message error = null;
            int done = 0;
            for (int i = 0; i < 100000; i++) {
                PgRawClient.Message m = client.read();
                if (m == null) {
                    break;
                }
                if (m.type == 'E') {
                    error = m;
                    break;
                }
                if (m.type == '2' && ++done >= 1000) {
                    // 长查询都执行完了还能继续处理成百上千条 Bind，说明队列根本没有上限
                    throw new AssertionError("队列没有上限: 已处理 " + done + " 条 Bind 仍未触发限制");
                }
            }
            if (error == null) {
                throw new AssertionError("队列超限没有返回 ErrorResponse");
            }
            String state = PgRawClient.sqlState(error);
            if (!"08P01".equals(state)) {
                throw new AssertionError("队列超限应返回 08P01, 实际 " + state + ": " + error.asString());
            }
            client.assertClosed("队列超限回错误之后");
        }
    }

}
