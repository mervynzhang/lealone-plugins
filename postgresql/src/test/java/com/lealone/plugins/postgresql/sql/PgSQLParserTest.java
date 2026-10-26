/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.sql;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * PgSQLParser.normalize 的回归用例。normalize 会把 psql/pgjdbc 生成的 PG 方言改写成
 * Lealone 能解析的写法，但所有改写都必须跳过字符串字面量/E''/双引号标识符/注释/$$...$$，
 * 否则会静默改坏数据(例如 SELECT 'x::text' 变成 SELECT 'x')。
 * <p>
 * 前 6 条是评审实测出来的反例，逐字节断言输入 -> 输出。
 */
public class PgSQLParserTest {

    @Test
    public void castInsideStringLiteralIsNotTouched() {
        // 旧实现: 'x::text' -> 'x'
        assertEquals("SELECT 'x::text'", PgSQLParser.normalize("SELECT 'x::text'"));
        // 旧实现: 'abc::charm' -> 'abcm' (::char 匹配了 character 的前缀)
        assertEquals("SELECT 'abc::charm'", PgSQLParser.normalize("SELECT 'abc::charm'"));
    }

    @Test
    public void characterVaryingCastIsNotCorrupted() {
        // 旧实现: 'foo'::character varying(20) -> 'foo'acter varying(20)
        assertEquals("SELECT 'foo'::character varying(20)",
                PgSQLParser.normalize("SELECT 'foo'::character varying(20)"));
        // 核心支持 varchar/character varying，不需要也不应该去掉
        assertEquals("SELECT 'foo'::varchar(20)", PgSQLParser.normalize("SELECT 'foo'::varchar(20)"));
    }

    @Test
    public void eStringEscapedQuoteUsesStandardEscaping() {
        // 旧实现把 E'it\'s' 改成 'it's' (裸引号，语法错误)
        assertEquals("SELECT 'it''s'", PgSQLParser.normalize("SELECT E'it\\'s'"));
    }

    @Test
    public void operatorOnComplexLeftExpression() {
        // 旧实现只认"标识符在左"，这里会残留 NOTREGEXP -> 语法错
        assertEquals("(a || b) !~ 'x'",
                PgSQLParser.normalize("(a || b) OPERATOR(pg_catalog.!~) 'x'"));
        assertEquals("(a || b) ~ 'x'",
                PgSQLParser.normalize("(a || b) OPERATOR(pg_catalog.~) 'x'"));
    }

    @Test
    public void literalContainingOperatorIsNotTouched() {
        // 旧实现: 'k ~ ''v''' -> 'k REGEXP ''v'''
        assertEquals("SELECT 'k ~ ''v'''", PgSQLParser.normalize("SELECT 'k ~ ''v'''"));
    }

    @Test
    public void quotedIdentifierCommentAndDollarQuoteAreNotTouched() {
        assertEquals("SELECT 1 -- 'x::text'", PgSQLParser.normalize("SELECT 1 -- 'x::text'"));
        assertEquals("SELECT 1 /* 'x::text' */", PgSQLParser.normalize("SELECT 1 /* 'x::text' */"));
        assertEquals("SELECT $$'x::text'$$", PgSQLParser.normalize("SELECT $$'x::text'$$"));
        assertEquals("SELECT $tag$'x::text' ~ 'y'$tag$",
                PgSQLParser.normalize("SELECT $tag$'x::text' ~ 'y'$tag$"));
        assertEquals("SELECT \"a::text\" FROM t", PgSQLParser.normalize("SELECT \"a::text\" FROM t"));
    }

    @Test
    public void castsOutsideLiteralsAreStillRewritten() {
        assertEquals("SELECT 'a', col", PgSQLParser.normalize("SELECT 'a'::text, col::pg_catalog.int4"));
        assertEquals("0", PgSQLParser.normalize("'pg_catalog.trigger'::pg_catalog.regtype"));
        assertEquals("SELECT ''::pg_catalog.char AS attidentity",
                PgSQLParser.normalize("SELECT ''::pg_catalog.char AS attidentity"));
    }

    @Test
    public void psqlDescribeQueryWithCollate() {
        String sql = "SELECT n.nspname as \"Schema\", c.relname as \"Name\","
                + " pg_catalog.pg_get_userbyid(c.relowner) as \"Owner\""
                + " FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relname OPERATOR(pg_catalog.~) '^(cat_t1)$' COLLATE pg_catalog.default"
                + " AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 1,2;";
        String expected = "SELECT n.nspname as \"Schema\", c.relname as \"Name\","
                + " pg_catalog.pg_get_userbyid(c.relowner) as \"Owner\""
                + " FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relname ~ '^(cat_t1)$'"
                + " AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 1,2;";
        assertEquals(expected, PgSQLParser.normalize(sql));
    }

    @Test
    public void psqlAttributeQueryWithCharAndRegtypeCasts() {
        String sql = "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),"
                + " ''::pg_catalog.char AS attidentity,"
                + " CASE WHEN c.reloftype = 0 THEN '' ELSE c.reloftype::pg_catalog.regtype::pg_catalog.text END"
                + " FROM pg_catalog.pg_attribute a, pg_catalog.pg_class c"
                + " WHERE a.attrelid = '-10' AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum;";
        String expected = "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),"
                + " ''::pg_catalog.char AS attidentity,"
                + " CASE WHEN c.reloftype = 0 THEN '' ELSE c.reloftype END"
                + " FROM pg_catalog.pg_attribute a, pg_catalog.pg_class c"
                + " WHERE a.attrelid = '-10' AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum;";
        assertEquals(expected, PgSQLParser.normalize(sql));
    }

    @Test
    public void psqlListTablesQuery() {
        String sql = "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c"
                + " LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname !~ '^pg_toast' AND c.relname OPERATOR(pg_catalog.~) '^(t1|t2)$'"
                + " AND n.nspname <> 'information_schema' ORDER BY 1, 2;";
        String expected = "SELECT n.nspname, c.relname FROM pg_catalog.pg_class c"
                + " LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname !~ '^pg_toast' AND c.relname ~ '^(t1|t2)$'"
                + " AND n.nspname <> 'information_schema' ORDER BY 1, 2;";
        assertEquals(expected, PgSQLParser.normalize(sql));
    }
}
