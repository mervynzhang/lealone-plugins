/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.sql.ResultSet;
import java.sql.Statement;

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

    /**
     * 字符串字面量不能被 normalize 的类型强转/操作符改写碰到：
     * 旧实现 SELECT 'x::text' 会变成 SELECT 'x'，E'it\'s' 会变成语法错误的 'it's'。
     */
    @Test
    public void testStringLiteralsAreNotCorrupted() throws Exception {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT E'it\\'s' AS e, 'x::text' AS lit, 'k ~ ''v''' AS op")) {
            if (!rs.next())
                throw new AssertionError("没有返回行");
            if (!"it's".equals(rs.getString(1)))
                throw new AssertionError("E'' 转义结果不对: " + rs.getString(1));
            if (!"x::text".equals(rs.getString(2)))
                throw new AssertionError("字符串里的 ::text 被改坏了: " + rs.getString(2));
            if (!"k ~ 'v'".equals(rs.getString(3)))
                throw new AssertionError("字符串里的 ~ 被改坏了: " + rs.getString(3));
        }
    }

    /**
     * psql 生成的查询会带 OPERATOR(pg_catalog.~) ... COLLATE pg_catalog.default，
     * Lealone 不支持 COLLATE，normalize 要把它去掉(字面量原样保留)。
     */
    @Test
    public void testPsqlOperatorAndCollate() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS syn_collate");
            st.executeUpdate("CREATE TABLE syn_collate(id int)");
        }
        String sql = "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c"
                + " LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relname OPERATOR(pg_catalog.~) '^(syn_collate)$'"
                + " COLLATE pg_catalog.default AND pg_catalog.pg_table_is_visible(c.oid)";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("psql 的 OPERATOR+COLLATE 查询没有返回行");
            if (!"public".equalsIgnoreCase(rs.getString(1)))
                throw new AssertionError("schema 不对: " + rs.getString(1));
            if (!"syn_collate".equalsIgnoreCase(rs.getString(2)))
                throw new AssertionError("表名不对: " + rs.getString(2));
        }
        // 复杂左值上的 OPERATOR(pg_catalog.!~) 不能残留 NOTREGEXP
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT ('ab' || 'c') OPERATOR(pg_catalog.!~) '^x$'")) {
            rs.next();
            if (!rs.getBoolean(1))
                throw new AssertionError("(a||b) OPERATOR(!~) 结果不对");
        }
    }
}
