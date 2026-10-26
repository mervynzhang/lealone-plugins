/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * busy 期间包队列的边界回归：旧实现是无界 ArrayDeque，长查询时客户端持续发包就能打满内存。
 * 这里用很小的上限验证条数/字节两条限制都生效，并且 clear 会把字节计数归零。
 */
public class PendingPacketsTest {

    private static byte[] payload(int len) {
        return new byte[len];
    }

    @Test
    public void packetCountLimit() {
        PendingPackets queue = new PendingPackets(2, 1024);
        assertTrue(queue.offer('B', payload(10)));
        assertTrue(queue.offer('E', payload(10)));
        assertFalse("超过条数上限必须拒绝", queue.offer('S', payload(1)));
        assertEquals(2, queue.size());
        assertEquals(20, queue.getBytes());
    }

    @Test
    public void byteLimit() {
        PendingPackets queue = new PendingPackets(100, 32);
        assertTrue(queue.offer('B', payload(16)));
        assertTrue(queue.offer('E', payload(16)));
        assertFalse("超过字节上限必须拒绝", queue.offer('S', payload(1)));
        assertEquals(2, queue.size());
        assertEquals(32, queue.getBytes());
    }

    @Test
    public void pollAndClearKeepAccountingConsistent() {
        PendingPackets queue = new PendingPackets(4, 64);
        assertTrue(queue.offer('B', payload(8)));
        assertTrue(queue.offer('E', payload(8)));
        Object[] first = queue.poll();
        assertEquals((int) 'B', first[0]);
        assertEquals(8, queue.getBytes());
        assertEquals(1, queue.size());
        queue.clear();
        assertEquals(0, queue.getBytes());
        assertEquals(0, queue.size());
        assertNull(queue.poll());
        assertTrue(queue.offer('S', payload(64)));
    }

    @Test
    public void defaultLimitsAreBounded() {
        PendingPackets queue = new PendingPackets();
        assertTrue(PendingPackets.DEFAULT_MAX_PACKETS > 0);
        assertTrue(PendingPackets.DEFAULT_MAX_BYTES > 0);
        assertEquals(PendingPackets.DEFAULT_MAX_PACKETS, queue.getMaxPackets());
        assertEquals(PendingPackets.DEFAULT_MAX_BYTES, queue.getMaxBytes());
    }
}
