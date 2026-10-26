/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;

/**
 * pg_catalog 兼容性回归：psql 的 \d、\dt、\l、\du 和 JDBC 的 DatabaseMetaData
 * 都靠这些视图/函数，之前它们要么缺列、要么恒空、要么函数不存在。
 */
public class PgCatalogTest extends PgTestBase {

    @Test
    public void pgClassListsUserTable() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_t1");
            st.executeUpdate("CREATE TABLE cat_t1(id int primary key, name varchar(20))");
        }
        // psql \dt 用的就是这种写法（含 OPERATOR(pg_catalog.~) 和 pg_table_is_visible）
        String sql = "SELECT n.nspname, c.relname, c.relkind"
                + " FROM pg_catalog.pg_class c"
                + " LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE c.relname OPERATOR(pg_catalog.~) '^(cat_t1)$'"
                + " AND pg_catalog.pg_table_is_visible(c.oid)";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("pg_class 里找不到 cat_t1");
            if (!"public".equals(rs.getString(1)))
                throw new AssertionError("schema 应该是 public, 实际 " + rs.getString(1));
            if (!"r".equals(rs.getString(3)))
                throw new AssertionError("relkind 应该是 r(表), 实际 " + rs.getString(3));
        }
    }

    @Test
    public void pgAttributeAndFormatType() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_t2");
            st.executeUpdate("CREATE TABLE cat_t2(id int, name varchar(30))");
        }
        // psql \d 的列信息查询
        String sql = "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull"
                + " FROM pg_catalog.pg_attribute a, pg_catalog.pg_class c"
                + " WHERE a.attrelid = c.oid AND c.relname = 'cat_t2' AND a.attnum > 0"
                + " ORDER BY a.attnum";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("pg_attribute 里找不到 cat_t2 的列");
            if (!"id".equals(rs.getString(1)))
                throw new AssertionError("第一列应该是 id, 实际 " + rs.getString(1));
            if (!"integer".equals(rs.getString(2)))
                throw new AssertionError("id 的类型应该是 integer, 实际 " + rs.getString(2));
            rs.next();
            if (!"character varying(30)".equals(rs.getString(2)))
                throw new AssertionError("name 的类型不对: " + rs.getString(2));
        }
    }

    @Test
    public void catalogFunctionsExist() throws Exception {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_catalog.current_database(),"
                    + " pg_catalog.current_schema(), pg_catalog.pg_backend_pid()")) {
                if (!rs.next())
                    throw new AssertionError("catalog 函数没有返回结果");
                if (rs.getString(1) == null)
                    throw new AssertionError("current_database() 返回 null");
            }
            try (ResultSet rs = st.executeQuery("SELECT pg_catalog.array_to_string(NULL, ',')")) {
                rs.next();
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_catalog.pg_roles")) {
                rs.next();
                if (rs.getInt(1) < 1)
                    throw new AssertionError("pg_roles 不能是空的");
            }
        }
    }

    // 已知限制: pgjdbc 的 getColumns 用了 ROW_NUMBER() OVER (PARTITION BY ...) 窗口函数,
    // Lealone 尚未支持窗口函数; 等窗口函数支持后去掉 @Ignore
    @org.junit.Ignore("Lealone 不支持窗口函数, pgjdbc getColumns 需要")
    @Test
    public void databaseMetaDataWorks() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_t3");
            st.executeUpdate("CREATE TABLE cat_t3(id int primary key, name varchar(20))");
        }
        DatabaseMetaData md = conn.getMetaData();
        boolean found = false;
        try (ResultSet rs = md.getTables(null, null, "cat_t3", null)) {
            while (rs.next()) {
                if ("cat_t3".equalsIgnoreCase(rs.getString("TABLE_NAME")))
                    found = true;
            }
        }
        if (!found)
            throw new AssertionError("DatabaseMetaData.getTables 找不到 cat_t3");
        int columns = 0;
        try (ResultSet rs = md.getColumns(null, null, "cat_t3", null)) {
            while (rs.next())
                columns++;
        }
        if (columns != 2)
            throw new AssertionError("DatabaseMetaData.getColumns 应该返回 2 列, 实际 " + columns);
    }

    // 已知限制: pgjdbc 解析列类型名会发 "n.nspname = ANY(current_schemas(true))" 查询,
    // Lealone 不支持 ANY(子查询/函数返回数组) 这种写法, 需要先补 current_schemas + ANY
    @org.junit.Ignore("Lealone 不支持 ANY(current_schemas(true)), TypeInfoCache 查询失败")
    @Test
    public void describePreparedStatementTypes() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_t4");
            st.executeUpdate("CREATE TABLE cat_t4(id int, v varchar(10))");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, v FROM cat_t4 WHERE id=?")) {
            if (ps.getMetaData() == null)
                throw new AssertionError("Describe 没有返回元数据");
            if (!"integer".equals(ps.getMetaData().getColumnTypeName(1)))
                throw new AssertionError("列类型名不对: " + ps.getMetaData().getColumnTypeName(1));
        }
    }
}
