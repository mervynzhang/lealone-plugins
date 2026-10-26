/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.server.handler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Bind 的 format code 规则回归：0 个 = 全部 text，1 个 = 套用到所有参数，个数少于参数
 * 个数时沿用最后一个。旧实现对 i >= formatCodes.length 返回 false(binary)，最后一个参数
 * 会被当成定长二进制解析而报错。
 */
public class CommandPacketHandlerTest {

    @Test
    public void formatCodeRules() {
        assertTrue(CommandPacketHandler.isText(new int[0], 0));
        assertTrue(CommandPacketHandler.isText(new int[0], 7));
        assertTrue(CommandPacketHandler.isText(new int[] { 0 }, 3));
        assertFalse(CommandPacketHandler.isText(new int[] { 1 }, 3));
        assertFalse(CommandPacketHandler.isText(new int[] { 0, 1 }, 1));
        // 只有 2 个 format code 但参数有 3 个: 第 3 个沿用最后一个(0=text)
        assertTrue(CommandPacketHandler.isText(new int[] { 1, 0 }, 2));
        // 最后一个 format code 是 binary
        assertFalse(CommandPacketHandler.isText(new int[] { 0, 1 }, 2));
    }
}
