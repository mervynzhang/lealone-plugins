/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

import org.junit.BeforeClass;
import org.junit.Test;

import com.lealone.db.LealoneDatabase;
import com.lealone.test.TestBase;

/**
 * PostgreSQL 扩展协议(Parse/Bind/Describe/Execute/Sync)的回归用例。
 * <p>
 * 之前把 Bind/Execute 攒到 Sync 才处理，导致：executeBatch 只生效最后一条(1000 行只写 10 行)、
 * 事务里第一条语句必崩、Describe 不返回 RowDescription、绑定 NULL 参数抛 NegativeArraySizeException、
 * boolean 经 JDBC 恒读成 false。这些用例逐条守住上述行为。
 */
public class PgExtendedProtocolTest extends PgTestBase {

    @BeforeClass
    public static void ensurePgUser() throws Exception {
        // PG 协议连接要求 MODE=PostgreSQL(需要 admin)，这里保证测试用户存在且有 admin 权限
        TestBase test = new TestBase();
        try (Connection c = test.getConnection(LealoneDatabase.NAME); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE USER IF NOT EXISTS postgres PASSWORD 'postgres'");
            st.executeUpdate("ALTER USER postgres ADMIN TRUE");
        }
    }

    @Test
    public void executeBatchWritesAllRows() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS ep_batch");
            st.executeUpdate("CREATE TABLE ep_batch(id int primary key, v varchar(20))");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ep_batch VALUES(?,?)")) {
            for (int i = 0; i < 1000; i++) {
                ps.setInt(1, i);
                ps.setString(2, "v" + i);
                ps.addBatch();
                if ((i + 1) % 200 == 0)
                    ps.executeBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM ep_batch")) {
            rs.next();
            int count = rs.getInt(1);
            if (count != 1000)
                throw new AssertionError("executeBatch 应该写入 1000 行, 实际 " + count);
        }
    }

    @Test
    public void transactionRollbackAndCommit() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS ep_tx");
            st.executeUpdate("CREATE TABLE ep_tx(id int primary key, v varchar(20))");
        }
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ep_tx VALUES(?,?)")) {
            ps.setInt(1, 1);
            ps.setString(2, "rollback-me");
            ps.execute();
        }
        // 事务内能看到自己未提交的写入
        if (countRows("ep_tx") != 1)
            throw new AssertionError("事务内应该能看到未提交的写入");
        conn.rollback();
        if (countRows("ep_tx") != 0)
            throw new AssertionError("回滚之后不应该看到写入");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ep_tx VALUES(?,?)")) {
            ps.setInt(1, 2);
            ps.setString(2, "commit-me");
            ps.execute();
        }
        conn.commit();
        conn.setAutoCommit(true);
        if (countRows("ep_tx") != 1)
            throw new AssertionError("提交之后应该能看到写入");
    }

    @Test
    public void describeReturnsMetadata() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS ep_desc");
            st.executeUpdate("CREATE TABLE ep_desc(id int primary key, name varchar(20))");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM ep_desc WHERE id=?")) {
            ResultSetMetaData md = ps.getMetaData();
            if (md == null)
                throw new AssertionError("Describe 必须返回 RowDescription(元数据不能为 null)");
            if (md.getColumnCount() != 2)
                throw new AssertionError("列数应该是 2, 实际 " + md.getColumnCount());
            if (!"id".equalsIgnoreCase(md.getColumnName(1)))
                throw new AssertionError("第 1 列名应该是 id, 实际 " + md.getColumnName(1));
        }
    }

    @Test
    public void nullParametersAndTypes() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS ep_null");
            st.executeUpdate(
                    "CREATE TABLE ep_null(id int primary key, b boolean, n numeric(12,2), v varchar(20))");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ep_null VALUES(?,?,?,?)")) {
            ps.setInt(1, 1);
            ps.setNull(2, Types.BOOLEAN);
            ps.setNull(3, Types.NUMERIC);
            ps.setString(4, "nulls");
            ps.execute();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT b IS NULL, n IS NULL FROM ep_null WHERE id=1")) {
            rs.next();
            if (!rs.getBoolean(1) || !rs.getBoolean(2))
                throw new AssertionError("NULL 参数没有被正确写入");
        }
        // 非 NULL 的 boolean / numeric 也要能正确往返
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ep_null VALUES(?,?,?,?)")) {
            ps.setInt(1, 2);
            ps.setBoolean(2, true);
            ps.setBigDecimal(3, new BigDecimal("12.34"));
            ps.setString(4, "values");
            ps.execute();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT b, n FROM ep_null WHERE id=2")) {
            rs.next();
            if (!rs.getBoolean(1))
                throw new AssertionError("boolean 读回应该是 true");
            if (rs.getBigDecimal(2).compareTo(new BigDecimal("12.34")) != 0)
                throw new AssertionError("numeric 读回不对: " + rs.getBigDecimal(2));
        }
    }

    @Test
    public void reusePreparedStatementDoesNotLeakParameters() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS ep_reuse");
            st.executeUpdate("CREATE TABLE ep_reuse(id int primary key, v varchar(20))");
        }
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO ep_reuse VALUES(?,?)")) {
            for (int i = 1; i <= 3; i++) {
                ins.setInt(1, i);
                ins.setString(2, "v" + i);
                ins.execute();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM ep_reuse WHERE id=?")) {
            for (int i = 1; i <= 3; i++) {
                ps.setInt(1, i);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!("v" + i).equals(rs.getString(1)))
                        throw new AssertionError("参数串了: 期望 v" + i + ", 实际 " + rs.getString(1));
                }
            }
        }
    }

    private int countRows(String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
