/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import org.junit.Test;

import com.lealone.db.LealoneDatabase;
import com.lealone.test.TestBase;

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

    @Test
    public void currentSchemasFunction() throws Exception {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_catalog.current_schemas(true)")) {
            if (!rs.next())
                throw new AssertionError("current_schemas(true) 没有返回结果");
            String schemas = rs.getString(1);
            if (schemas == null || schemas.isEmpty())
                throw new AssertionError("current_schemas(true) 返回空");
            if (!schemas.contains("public"))
                throw new AssertionError("current_schemas(true) 应该包含 public: " + schemas);
        }
    }

    @Test
    public void typeInfoCacheQueryWorks() throws Exception {
        // pgjdbc 42.3.8 TypeInfoCache 的原始查询, 这里把 $1 换成真实存在的 oid=23(integer)
        String sql = "SELECT n.nspname = ANY(current_schemas(true)), n.nspname, t.typname"
                + " FROM pg_catalog.pg_type t"
                + " JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
                + " WHERE t.oid = 23";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next())
                throw new AssertionError("TypeInfoCache 查询没有返回行");
            if (!rs.getBoolean(1))
                throw new AssertionError("integer 的类型命名空间不在 current_schemas 中");
            if (!"pg_catalog".equals(rs.getString(2)))
                throw new AssertionError("integer 的命名空间应该是 pg_catalog: " + rs.getString(2));
            if (!"integer".equals(rs.getString(3)))
                throw new AssertionError("oid=23 的类型名应该是 integer: " + rs.getString(3));
        }
    }

    @Test
    public void generateSeriesWorks() throws Exception {
        try (Statement st = conn.createStatement()) {
            int rows = 0;
            try (ResultSet rs = st.executeQuery("SELECT * FROM generate_series(1,5)")) {
                while (rs.next())
                    rows++;
            }
            if (rows != 5)
                throw new AssertionError("generate_series(1,5) 应该返回 5 行, 实际 " + rows);
            rows = 0;
            try (ResultSet rs = st.executeQuery("SELECT * FROM generate_series(1,5,2)")) {
                while (rs.next())
                    rows++;
            }
            if (rows != 3)
                throw new AssertionError("generate_series(1,5,2) 应该返回 3 行, 实际 " + rows);
            // 改写不能碰字符串字面量
            try (ResultSet rs = st.executeQuery("SELECT 'generate_series(1,5)'")) {
                rs.next();
                if (!"generate_series(1,5)".equals(rs.getString(1)))
                    throw new AssertionError("字符串字面量被改写了: " + rs.getString(1));
            }
        }
    }

    @Test
    public void getSchemasReflectsCreatedSchema() throws Exception {
        // 兼容性矩阵里 getSchemas 对比失败(Lealone 少 m_s1)其实是用例顺序问题:
        // meta.get_schemas 用例本身不建 m_s1, 真 PG 的库是常驻的, m_s1 是上一轮
        // misc.schema_ddl 留下的; Lealone 每轮重启会清库。这里把"建完 schema 就能看到"
        // 固化下来, 同时验证 information_schema.schemas 和插件 pg_namespace 两条路径。
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP SCHEMA IF EXISTS m_s1");
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE SCHEMA m_s1");
        }
        try {
            boolean found = false;
            boolean performanceSchema = false;
            try (ResultSet rs = conn.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if ("m_s1".equalsIgnoreCase(schema))
                        found = true;
                    if ("performance_schema".equalsIgnoreCase(schema))
                        performanceSchema = true;
                }
            }
            if (!found)
                throw new AssertionError("getSchemas() 里没有刚建的 m_s1");
            // performance_schema 是 Lealone 内部 schema, 真 PG 没有, pg_namespace 要过滤掉
            if (performanceSchema)
                throw new AssertionError("getSchemas() 不应该返回 Lealone 内部的 performance_schema");
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT schema_name FROM information_schema.schemas"
                            + " WHERE schema_name = 'M_S1'")) {
                if (!rs.next())
                    throw new AssertionError("information_schema.schemas 里没有 M_S1");
            }
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT nspname FROM pg_catalog.pg_namespace"
                            + " WHERE nspname = 'm_s1'")) {
                if (!rs.next())
                    throw new AssertionError("pg_catalog.pg_namespace 里没有 m_s1");
            }
        } finally {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP SCHEMA IF EXISTS m_s1");
            }
        }
    }

    @Test
    public void typeInfoReturnsRows() throws Exception {
        // pgjdbc 的 getTypeInfo 会先跑 TypeInfoCache 的 catalog 查询,
        // 原来缺 ARRAY_UPPER / 不支持 SYSTEM_RANGE(...) AS s(r) 的列别名, 直接报 90022。
        java.util.Set<String> names = new java.util.HashSet<>();
        int rows = 0;
        try (ResultSet rs = conn.getMetaData().getTypeInfo()) {
            while (rs.next()) {
                rows++;
                names.add(rs.getString("TYPE_NAME"));
            }
        }
        if (rows < 10)
            throw new AssertionError("getTypeInfo() 只返回 " + rows + " 行");
        if (!names.contains("varchar"))
            throw new AssertionError("getTypeInfo() 缺少 varchar: " + names);
    }

    @Test
    public void primaryKeysReturnsRows() throws Exception {
        // pgjdbc 的 getPrimaryKeys 用 information_schema._pg_expandarray(indkey) 展开主键列;
        // Lealone 没有集合返回函数也没有复合类型字段访问, 靠 PgSQLParser.normalize 改写成
        // pg_index_columns 连接, 这里守住"从报错变成返回行"。
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_pk");
            st.executeUpdate("CREATE TABLE cat_pk(id int primary key, name varchar(10))");
        }
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "cat_pk")) {
            if (!rs.next())
                throw new AssertionError("getPrimaryKeys 没有返回行");
            if (!"id".equalsIgnoreCase(rs.getString("COLUMN_NAME")))
                throw new AssertionError("主键列应该是 id, 实际 " + rs.getString("COLUMN_NAME"));
            if (rs.getInt("KEY_SEQ") != 1)
                throw new AssertionError("KEY_SEQ 应该是 1, 实际 " + rs.getInt("KEY_SEQ"));
            if (!"cat_pk_pkey".equalsIgnoreCase(rs.getString("PK_NAME")))
                throw new AssertionError("PK_NAME 应该是 cat_pk_pkey, 实际 " + rs.getString("PK_NAME"));
            if (rs.next())
                throw new AssertionError("单列主键不应该返回多行");
        }
    }

    @Test
    public void indexInfoReturnsRows() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_idx");
            st.executeUpdate("CREATE TABLE cat_idx(id int primary key, name varchar(20))");
            st.executeUpdate("CREATE INDEX cat_idx_name ON cat_idx(name)");
        }
        java.util.Map<String, String> indexToColumn = new java.util.HashMap<>();
        java.util.Map<String, Boolean> indexToUnique = new java.util.HashMap<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "cat_idx", false, false)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col == null)
                    continue;
                indexToColumn.put(rs.getString("INDEX_NAME"), col);
                indexToUnique.put(rs.getString("INDEX_NAME"), rs.getBoolean("NON_UNIQUE"));
            }
        }
        if (!"id".equalsIgnoreCase(indexToColumn.get("cat_idx_pkey")))
            throw new AssertionError("cat_idx_pkey 的列应该是 id, 实际 " + indexToColumn);
        if (!Boolean.FALSE.equals(indexToUnique.get("cat_idx_pkey")))
            throw new AssertionError("cat_idx_pkey 应该是唯一索引, 实际 " + indexToUnique);
        if (!"name".equalsIgnoreCase(indexToColumn.get("cat_idx_name")))
            throw new AssertionError("cat_idx_name 的列应该是 name, 实际 " + indexToColumn);
        if (!Boolean.TRUE.equals(indexToUnique.get("cat_idx_name")))
            throw new AssertionError("cat_idx_name 应该是非唯一索引, 实际 " + indexToUnique);
    }

    @Test
    public void maxColumnNameLengthIs63() throws Exception {
        // pgjdbc 的 getMaxColumnNameLength() = pg_type 里 name 类型的 typlen - 1,
        // 真 PG 是 64 - 1 = 63; 之前 pg_type 把 name 的 typlen 写成 -1, 返回 -2。
        int len = conn.getMetaData().getMaxColumnNameLength();
        if (len != 63)
            throw new AssertionError("getMaxColumnNameLength 应该是 63, 实际 " + len);
        // pgjdbc 的 getMaxColumnsInIndex() 读 pg_settings.max_index_keys, 缺这一行时
        // getImportedKeys 直接抛 "Unable to determine a value for MaxIndexKeys"
        int keys = conn.getMetaData().getMaxColumnsInIndex();
        if (keys != 32)
            throw new AssertionError("getMaxColumnsInIndex 应该是 32, 实际 " + keys);
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

    // pgjdbc 解析列类型名会发 "n.nspname = ANY(current_schemas(true))" 查询,
    // TypeInfoCache 能工作后 Describe 才能返回正确的列类型名(pgjdbc 用的是 PG 类型名 int4/varchar)
    @Test
    public void describePreparedStatementTypes() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_t4");
            st.executeUpdate("CREATE TABLE cat_t4(id int, v varchar(10))");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, v FROM cat_t4 WHERE id=?")) {
            if (ps.getMetaData() == null)
                throw new AssertionError("Describe 没有返回元数据");
            if (!"int4".equals(ps.getMetaData().getColumnTypeName(1)))
                throw new AssertionError("列类型名不对: " + ps.getMetaData().getColumnTypeName(1));
        }
    }

    /**
     * current_schema() 不能像以前那样在别名函数里嵌套查库(call schema())：那会把外层
     * 结果集游标冲掉。改成直接返回 search_path 的第一项(public, pg_catalog)。
     */
    @Test
    public void currentSchemaReturnsSearchPath() throws Exception {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_catalog.current_schema()")) {
            rs.next();
            if (!"public".equals(rs.getString(1)))
                throw new AssertionError("current_schema() 应该是 public, 实际 " + rs.getString(1));
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_schema");
            st.executeUpdate("CREATE TABLE cat_schema(id int)");
            st.executeUpdate("INSERT INTO cat_schema VALUES(1),(2),(3)");
        }
        int rows = 0;
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_catalog.current_schema(), id FROM cat_schema")) {
            while (rs.next()) {
                rows++;
                if (!"public".equals(rs.getString(1)))
                    throw new AssertionError("多行结果里的 current_schema() 不对: " + rs.getString(1));
            }
        }
        if (rows != 3)
            throw new AssertionError("current_schema() 把外层游标冲掉了, 只返回 " + rows + " 行");
    }

    /**
     * catalog 快照必须按 database 分桶。以前是 static volatile Map，每来一个连接就整体
     * 覆盖：先连 postgres 的会话在建好索引后，只要有人连一下别的库，再查自己的
     * pg_get_indexdef 就变成空串(串台/丢失)。
     */
    @Test
    public void catalogSnapshotIsolatedPerDatabase() throws Exception {
        TestBase test = new TestBase().setPort(LEALONE_TEST_PORT);
        try (Connection c = test.getConnection(LealoneDatabase.NAME);
                Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS pg_iso_other");
        }
        try (Connection c = test.getConnection("pg_iso_other"); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE USER IF NOT EXISTS postgres PASSWORD 'postgres'");
            st.executeUpdate("ALTER USER postgres ADMIN TRUE");
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS cat_iso");
            st.executeUpdate("CREATE TABLE cat_iso(id int, v int)");
            st.executeUpdate("CREATE INDEX cat_iso_v ON cat_iso(v)");
        }
        // 快照是连接建立时拍的，这里重新建立连接，让快照里有刚建的索引
        try (Connection session1 = getPgConnection()) {
            int indexId;
            try (Statement st = session1.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT oid FROM pg_catalog.pg_class WHERE relname = 'cat_iso_v'")) {
                if (!rs.next())
                    throw new AssertionError("pg_class 里找不到 cat_iso_v");
                indexId = rs.getInt(1);
            }
            String before = indexDef(session1, indexId);
            if (before == null || before.toUpperCase().indexOf("CAT_ISO_V") < 0)
                throw new AssertionError("pg_get_indexdef 读不到索引定义: [" + before + "]");

            // 第二个连接连另一个库: 旧实现会用这个库的快照覆盖全局 static Map
            String url = "jdbc:postgresql://localhost:" + TEST_PORT + "/pg_iso_other";
            Properties info = new Properties();
            info.put("user", "postgres");
            info.put("password", "postgres");
            try (Connection other = DriverManager.getConnection(url, info);
                    Statement st = other.createStatement();
                    ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }

            String after = indexDef(session1, indexId);
            if (!before.equals(after))
                throw new AssertionError(
                        "catalog 快照串台了: 连接别的库之前 [" + before + "], 之后 [" + after + "]");

            // 未命中必须返回 null, 而不是返回别的库的数据
            try (Statement st = session1.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT pg_catalog.pg_get_userbyid(999999) IS NULL")) {
                rs.next();
                if (!rs.getBoolean(1))
                    throw new AssertionError("pg_get_userbyid(未命中) 应该返回 NULL");
            }
        }
    }

    private String indexDef(Connection c, int indexId) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT pg_catalog.pg_get_indexdef(" + indexId + ", 0, false)")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
