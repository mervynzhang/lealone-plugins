/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.server;

import java.util.ArrayDeque;

/**
 * 连接 busy 期间收到的后续协议包的暂存队列。
 * <p>
 * 必须有上限：一条长查询执行时客户端可以不停发后续包(比如超大的 executeBatch)，旧实现用
 * 无界 ArrayDeque 且每条包都拷贝一份 payload，内存会被打满。超过条数或字节上限时
 * {@link #offer(int, byte[])} 返回 false，由连接回 ErrorResponse 并断开，绝不静默丢包
 * (静默丢弃会让客户端一直等某个永远不会来的响应)。
 */
final class PendingPackets {

    /** 最多排队多少个包。正常批处理(1000~10000 行)会排队 2*n+1 个 Bind/Execute/Sync */
    static final int DEFAULT_MAX_PACKETS = 64 * 1024;
    /** 排队的 payload 总字节数上限 */
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;

    private final ArrayDeque<Object[]> packets = new ArrayDeque<>();
    private final int maxPackets;
    private final long maxBytes;

    private long bytes;

    PendingPackets() {
        this(DEFAULT_MAX_PACKETS, DEFAULT_MAX_BYTES);
    }

    PendingPackets(int maxPackets, int maxBytes) {
        this.maxPackets = maxPackets;
        this.maxBytes = maxBytes;
    }

    /**
     * @return false 表示已经超过上限，调用方必须回错误并断开连接
     */
    boolean offer(int type, byte[] data) {
        int len = data.length;
        if (packets.size() >= maxPackets || bytes + len > maxBytes) {
            return false;
        }
        packets.add(new Object[] { type, data });
        bytes += len;
        return true;
    }

    Object[] poll() {
        Object[] packet = packets.poll();
        if (packet != null) {
            bytes -= ((byte[]) packet[1]).length;
        }
        return packet;
    }

    void clear() {
        packets.clear();
        bytes = 0;
    }

    int size() {
        return packets.size();
    }

    long getBytes() {
        return bytes;
    }

    int getMaxPackets() {
        return maxPackets;
    }

    long getMaxBytes() {
        return maxBytes;
    }
}
