/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.fulltext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Test;

import com.lealone.test.TestBase;

/**
 * 内置全文检索(FT_*)的端到端用例: 建索引 -> 插入 -> 检索 -> 更新 -> 删除 -> 重建索引 -> 删索引。
 * <p>
 * 特意使用"全新的库"并且**第一次插入就带全文索引**, 因为触发器在嵌套连接上运行,
 * 之前复用带 "?" 占位符的 PreparedStatement 时, 参数传不到服务端, 首次插入会报
 * {@code Parameter "#1" is not set}, 本用例是这条回归的护栏。
 */
public class FullTextIndexTest extends TestBase {

    private static final String[] ROWS = { //
            "1|Lealone 数据库|async database with high throughput", //
            "2|Lucene search|full text search capability", //
            "3|unrelated|completely unrelated content xyzabc" };

    public FullTextIndexTest() {
        setEmbedded(true);
        setInMemory(true);
    }

    @Test
    public void run() throws Exception {
        String dbName = "fulltext_" + System.currentTimeMillis();
        String url = "jdbc:lealone:embed:" + dbName;
        try (Connection conn = DriverManager.getConnection(url)) {
            FullText.init(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE article(id INT PRIMARY KEY, title VARCHAR, body VARCHAR)");
                st.execute("CALL FT_CREATE_INDEX('PUBLIC','ARTICLE','TITLE,BODY')");
            }
            insertRows(conn);
            check(conn, "数据库", 1, "首次插入后应能被检索到");
            check(conn, "lucene", 1, "英文关键字应命中 1 行");
            check(conn, "xyzabc", 1, "第三行的关键字应命中 1 行");
            check(conn, "no_such_word", 0, "不存在的词不应命中");

            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE article SET body='rewritten with elephant' WHERE id=1");
            }
            check(conn, "elephant", 1, "更新后的新词应命中");
            check(conn, "throughput", 0, "更新前的旧词不应再命中");

            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM article WHERE id=2");
            }
            check(conn, "lucene", 0, "删除后不应再命中");

            try (Statement st = conn.createStatement()) {
                st.execute("CALL FT_REINDEX()");
            }
            check(conn, "elephant", 1, "重建索引后仍应命中");

            try (Statement st = conn.createStatement()) {
                st.execute("CALL FT_DROP_INDEX('PUBLIC','ARTICLE')");
            }
            check(conn, "elephant", 0, "删除索引后不应再命中");
        } finally {
            dropDatabase(dbName);
        }
    }

    private static void insertRows(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO article VALUES(?,?,?)")) {
            for (String line : ROWS) {
                String[] f = line.split("\\|");
                ps.setInt(1, Integer.parseInt(f[0]));
                ps.setString(2, f[1]);
                ps.setString(3, f[2]);
                ps.execute();
            }
        }
    }

    private static void check(Connection conn, String word, int expected, String message) throws SQLException {
        String sql = "SELECT count(*) FROM FT_SEARCH_DATA('" + word + "',0,0)";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            int actual = rs.getInt(1);
            if (actual != expected)
                throw new AssertionError(message + ": 期望命中 " + expected + " 行, 实际 " + actual + " (词: " + word + ")");
        }
    }

    private static void dropDatabase(String dbName) {
        try (Connection conn = DriverManager.getConnection("jdbc:lealone:embed:lealone")) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + dbName);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
