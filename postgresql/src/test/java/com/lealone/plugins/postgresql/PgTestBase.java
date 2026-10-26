/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import com.lealone.common.exceptions.DbException;
import com.lealone.db.LealoneDatabase;
import com.lealone.test.TestBase;
import com.lealone.test.sql.SqlTestBase;

public class PgTestBase extends SqlTestBase {

    public final static int TEST_PORT = 9510;
    // 测试进程连的 Lealone 服务端口。默认 9210; 端口被占用时可用 -Dlealone.test.port=xxxx 覆盖
    public final static int LEALONE_TEST_PORT = Integer.getInteger("lealone.test.port", 9210);
    public final static String NAME = PgPlugin.NAME;

    private static TestBase newTestBase() {
        return new TestBase().setPort(LEALONE_TEST_PORT);
    }

    @BeforeClass
    public static void createPgPlugin() throws Exception {
        TestBase test = newTestBase();
        Connection conn = test.getConnection(LealoneDatabase.NAME);
        Statement stmt = conn.createStatement();
        // File classPath = new File("target/classes");
        String sql = "create plugin if not exists " + NAME //
                + " implement by '" + PgPlugin.class.getName() + "'" //
                // + " class path '" + classPath.getCanonicalPath() + "'" //
                + " parameters(port=" + TEST_PORT + ", host='127.0.0.1')";
        stmt.executeUpdate(sql);
        stmt.executeUpdate("start plugin " + NAME);
        stmt.close();
        conn.close();
    }

    @AfterClass
    public static void dropPgPlugin() throws Exception {
        TestBase test = newTestBase();
        Connection conn = test.getConnection(LealoneDatabase.NAME);
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("stop plugin " + NAME);
        stmt.executeUpdate("drop plugin " + NAME);
        stmt.close();
        conn.close();
    }

    @Before
    @Override
    public void setUpBefore() {
        try {
            conn = getPgConnection();
            stmt = conn.createStatement();
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    public static Connection getPgConnection() throws Exception {
        return getPgConnection(0);
    }

    /**
     * @param socketTimeoutSeconds 大于 0 时设置 socket 读超时, 避免服务端不响应时测试永久挂住
     */
    public static Connection getPgConnection(int socketTimeoutSeconds) throws Exception {
        String url = "jdbc:postgresql://localhost:" + TEST_PORT + "/postgres";
        Properties info = new Properties();
        info.put("user", "postgres");
        info.put("password", "postgres");
        if (socketTimeoutSeconds > 0) {
            info.put("socketTimeout", String.valueOf(socketTimeoutSeconds));
        }
        return DriverManager.getConnection(url, info);
    }

    public static void sqlException(SQLException e) {
        while (e != null) {
            System.err.println("SQLException:" + e);
            System.err.println("-----------------------------------");
            System.err.println("Message  : " + e.getMessage());
            System.err.println("SQLState : " + e.getSQLState());
            System.err.println("ErrorCode: " + e.getErrorCode());
            System.err.println();
            System.err.println();
            e = e.getNextException();
        }
    }
}
