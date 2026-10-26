/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import org.junit.Test;

public class PgSyntaxTest extends PgTestBase {
    @Test
    public void testFormatType() throws Exception {
        // format_type 要返回 PG 的类型名(psql \d 依赖它显示类型)，不是内部常量名
        executeQuery("select format_type(4, 0)");
        assertEquals("integer", getStringValue(1));
        executeQuery("select format_type(23, 0)");
        assertEquals("integer", getStringValue(1));
        executeQuery("select format_type(1043, 24)");
        assertEquals("character varying(20)", getStringValue(1));
    }

    @Test
    public void testShowStatement() throws Exception {
        executeQuery("SHOW SEARCH_PATH");
        assertEquals("public,pg_catalog", getStringValue(1));
        executeQuery("SHOW ALL");
    }

    @Test
    public void testTransactionStatement() throws Exception {
        executeUpdate("BEGIN ISOLATION LEVEL SERIALIZABLE READ WRITE DEFERRABLE");
        executeUpdate("END");
        executeUpdate("START TRANSACTION ISOLATION LEVEL SERIALIZABLE READ WRITE DEFERRABLE");
        executeUpdate("COMMIT");
    }
}
