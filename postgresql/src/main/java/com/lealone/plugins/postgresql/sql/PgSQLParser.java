/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql.sql;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lealone.common.util.Utils;
import com.lealone.db.session.ServerSession;
import com.lealone.db.session.SessionSetting;
import com.lealone.plugins.postgresql.sql.expression.PgVariable;
import com.lealone.sql.SQLParserBase;
import com.lealone.sql.SQLStatement;
import com.lealone.sql.StatementBase;
import com.lealone.sql.dml.SetSession;
import com.lealone.sql.dml.TransactionStatement;
import com.lealone.sql.expression.Expression;

public class PgSQLParser extends SQLParserBase {

    public PgSQLParser(ServerSession session) {
        super(session);
        this.session.setSchemaSearchPath(new String[] { "public", "pg_catalog" });
    }

    // psql 和 pgjdbc 会生成 PG 特有的操作符写法，解析前统一改写成 Lealone 支持的写法：
    //   a OPERATOR(pg_catalog.~) 're'   -> a ~ 're'
    //   a OPERATOR(pg_catalog.!~) 're'  -> a !~ 're'
    // 核心的 readConcat 已经原生支持 ~ 和 !~；~* / !~* (大小写不敏感) 只能退化成 REGEXP。
    private static final Pattern PG_OPERATOR = Pattern
            .compile("(?i)OPERATOR\\s*\\(\\s*(?:pg_catalog\\.)?(~\\*?|!~\\*?|=|<>|<|>|<=|>=)\\s*\\)");
    // 核心的 RangeTable 已经用 SYSTEM_RANGE/GENERATE_SERIES 别名支持 FROM 里的函数表,
    // 这里按 PG 的写法统一改写成 SYSTEM_RANGE(大小写不敏感, 不碰字符串字面量/注释)
    private static final Pattern GENERATE_SERIES = Pattern
            .compile("(?i)(?<![A-Za-z0-9_$])(?:pg_catalog\\s*\\.\\s*)?generate_series\\s*(?=\\()");
    // 核心的 ANY 只支持 ANY(SELECT ...), 不支持 ANY(函数返回数组),
    // 把 pgjdbc TypeInfoCache 的 ANY(current_schemas(...)) 改写成等价的 ANY(SELECT ...)
    private static final Pattern CURRENT_SCHEMAS_ANY = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_$])ANY\\s*\\(\\s*(?:pg_catalog\\s*\\.\\s*)?current_schemas\\s*\\(\\s*(TRUE|FALSE)\\s*\\)\\s*\\)");

    // 类型强转的匹配必须带右边界: 否则 ::char 会把 ::character 的前缀吃掉
    // ('foo'::character varying(20) -> 'foo'acter varying(20))。
    // 不能带 char|varchar 分支: 核心本来就支持 CHAR/VARCHAR/CHARACTER VARYING，
    // 留着反而会误吃 ::character。
    private static final Pattern TEXT_CAST = Pattern.compile(
            "(?i)\\s*::\\s*(?:pg_catalog\\s*\\.\\s*)?(?:text|bpchar|name|int2|int4|int8|oid|bool)(?![A-Za-z0-9_])");
    private static final Pattern REGTYPE_COL = Pattern.compile(
            "(?i)\\s*::\\s*(?:pg_catalog\\s*\\.\\s*)?(?:regtype|regclass|regproc|regprocedure|regoperator|regnamespace)(?![A-Za-z0-9_])");
    // 'pg_catalog.trigger'::pg_catalog.regtype 这种"字面量 + regtype/regclass 强转"
    private static final Pattern REGTYPE_LITERAL_TAIL = Pattern.compile(
            "(?i)\\s*::\\s*(?:pg_catalog\\s*\\.\\s*)?(?:regtype|regclass)(?![A-Za-z0-9_])");
    // psql 的部分查询会给操作符加 COLLATE pg_catalog.default，Lealone 不支持 COLLATE
    private static final Pattern COLLATE_CLAUSE = Pattern.compile(
            "(?i)\\s+COLLATE\\s+(?:\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z0-9_$]*(?:\\s*\\.\\s*(?:\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_$]*))?)");

    // pgjdbc 的 getPrimaryKeys/getIndexInfo/getImportedKeys 用
    // information_schema._pg_expandarray(i.indkey) 把索引列"展开"成多行; Lealone 既没有
    // 集合返回函数, 也不支持 (func()).n 这种复合类型字段访问。pg_catalog.pg_index_columns
    // 一行一个索引列, 下面把这些展开表达式改写成对它的连接(别名 ic)。
    private static final Pattern EXPAND_FIELD_N = Pattern.compile(
            "(?i)\\(\\s*information_schema\\s*\\.\\s*_pg_expandarray\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_$.]*\\s*\\)\\s*\\)\\s*\\.\\s*n\\b");
    private static final Pattern EXPAND_FIELD_X = Pattern.compile(
            "(?i)\\(\\s*information_schema\\s*\\.\\s*_pg_expandarray\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_$.]*\\s*\\)\\s*\\)\\s*\\.\\s*x\\b");
    private static final Pattern EXPAND_CALL = Pattern.compile(
            "(?i)information_schema\\s*\\.\\s*_pg_expandarray\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_$.]*\\s*\\)");
    private static final Pattern RECORD_FIELD_X = Pattern
            .compile("\\(\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\)\\s*\\.\\s*x\\b");
    private static final Pattern PG_INDEX_JOIN = Pattern
            .compile("(?i)(JOIN\\s+pg_catalog\\.pg_index\\s+i\\s+ON\\s*\\([^)]*\\))");
    private static final Pattern PG_INDEX_FROM = Pattern
            .compile("(?i)(FROM\\s+pg_catalog\\.pg_index\\s+i\\s*)\\)");
    // getIndexInfo 的 COLUMN_NAME 用 pg_get_indexdef(ci_oid, ordinal, false), 而别名函数
    // 只能读连接建立时的索引快照(别名函数里不能查库), 同一连接里新建的索引查不到;
    // 直接改成查 pg_index_columns。
    private static final Pattern GET_INDEXDEF_ORDINAL = Pattern.compile(
            "(?i)pg_catalog\\s*\\.\\s*pg_get_indexdef\\s*\\(\\s*tmp\\.ci_oid\\s*,\\s*tmp\\.ordinal_position\\s*,\\s*false\\s*\\)");
    // 核心不支持位与 '&'; pg_index.indoption 恒为 null, 换成 0 后 CASE 走 ELSE(升序 'A')
    private static final Pattern INDOPTION_AND = Pattern.compile(
            "(?i)tmp\\.i_indoption\\s*\\[\\s*tmp\\.ordinal_position\\s*-\\s*1\\s*]\\s*&\\s*1\\s*::\\s*smallint");

    static String normalize(String sql) {
        if (sql == null)
            return sql;
        String upper = sql.toUpperCase();
        if (sql.indexOf('~') < 0 && sql.indexOf("::") < 0 && sql.indexOf('&') < 0
                && upper.indexOf("OPERATOR") < 0 && upper.indexOf("E'") < 0
                && upper.indexOf("GENERATE_SERIES") < 0 && upper.indexOf("SYSTEM_RANGE") < 0
                && upper.indexOf("CURRENT_SCHEMAS") < 0 && upper.indexOf("_PG_EXPANDARRAY") < 0
                && upper.indexOf("PG_GET_INDEXDEF") < 0 && upper.indexOf("COLLATE") < 0)
            return sql;
        String s = sql;
        s = rewriteOutsideLiterals(s, GENERATE_SERIES, matcher -> "SYSTEM_RANGE");
        s = wrapRangeTableColumnAliases(s);
        s = rewritePgExpandArray(s);
        s = rewritePgIndexDefOrdinal(s);
        s = rewriteOutsideLiterals(s, INDOPTION_AND, matcher -> "0");
        s = rewriteOutsideLiterals(s, CURRENT_SCHEMAS_ANY, matcher -> {
            if (Boolean.parseBoolean(matcher.group(1))) {
                return "ANY(SELECT 'pg_catalog' UNION SELECT 'public')";
            }
            return "ANY(SELECT 'pg_catalog')";
        });
        // 只改写 OPERATOR(...) 这种包装，保留 PG 原生 ~ / !~ 让核心的 readConcat 直接处理，
        // 这样左边是任意表达式(比如 (a || b))也能正确解析，不会残留 NOTREGEXP。
        s = rewriteOutsideLiterals(s, PG_OPERATOR, matcher -> {
            String op = matcher.group(1);
            switch (op) {
            case "~":
                return "~";
            case "!~":
                return "!~";
            case "~*":
                return "REGEXP";
            case "!~*":
                return "NOT REGEXP";
            default:
                return op;
            }
        });
        // Lealone 没有 regtype/regclass 类型, 直接换成 0(只会参与类型比较, 空的 pg_proc 里不会命中)
        s = rewriteLiteralRegtypeCasts(s);
        // 剩下的列级强转 Lealone 没有对应类型, 直接去掉(值本来就是字符串/整数)
        s = rewriteOutsideLiterals(s, REGTYPE_COL, matcher -> "");
        s = rewriteOutsideLiterals(s, TEXT_CAST, matcher -> "");
        // COLLATE 只影响排序规则, Lealone 没有对应实现, 去掉才能解析 psql 生成的查询
        s = rewriteOutsideLiterals(s, COLLATE_CLAUSE, matcher -> "");
        s = normalizeEStrings(s);
        return s;
    }

    /**
     * 只对"字符串/引号标识符/注释之外"的代码片段跑正则。所有改写都必须走这里，
     * 不能直接 replaceAll 原始 SQL：SELECT 'x::text' 这种字符串字面量会被改坏。
     */
    private static String rewriteOutsideLiterals(String sql, Pattern pattern,
            Function<Matcher, String> replacement) {
        StringBuilder sb = new StringBuilder(sql.length());
        int len = sql.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            int end = skipLiteralOrComment(sql, i);
            if (end > i) {
                appendReplaced(sb, sql, start, i, pattern, replacement);
                sb.append(sql, i, end);
                i = end;
                start = end;
            } else {
                i++;
            }
        }
        appendReplaced(sb, sql, start, len, pattern, replacement);
        return sb.toString();
    }

    /**
     * 'pg_catalog.trigger'::pg_catalog.regtype 换成 0。这条改写要"连字面量一起"处理，
     * 不能只跑在字面量之外的代码上；但扫描器仍然把"字符串 + 强转"当成一个整体表达式，
     * 不会碰字符串内部的 ::regtype，也不会碰其它字面量。
     */
    private static String rewriteLiteralRegtypeCasts(String sql) {
        if (sql.indexOf("::") < 0)
            return sql;
        int len = sql.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            if (sql.charAt(i) == '\'') {
                int literalEnd = skipSingleQuoted(sql, i, isEStringQuote(sql, i));
                int p = skipWhitespace(sql, literalEnd);
                if (p < len) {
                    Matcher matcher = REGTYPE_LITERAL_TAIL.matcher(sql);
                    matcher.region(p, len);
                    if (matcher.lookingAt()) {
                        sb.append('0');
                        i = matcher.end();
                        continue;
                    }
                }
                sb.append(sql, i, literalEnd);
                i = literalEnd;
                continue;
            }
            int end = skipLiteralOrComment(sql, i);
            if (end > i) {
                sb.append(sql, i, end);
                i = end;
            } else {
                sb.append(sql.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** 从 i 开始如果是字面量/引号标识符/注释/$$...$$，返回它结束后的位置；否则返回 -1 */
    private static int skipLiteralOrComment(String sql, int i) {
        char c = sql.charAt(i);
        if (c == '\'') {
            return skipSingleQuoted(sql, i, isEStringQuote(sql, i));
        }
        if (c == '"') {
            return skipDoubleQuoted(sql, i);
        }
        int len = sql.length();
        if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
            int end = sql.indexOf('\n', i + 2);
            return end < 0 ? len : end + 1;
        }
        if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
            // PostgreSQL 的块注释可以嵌套
            int depth = 1;
            int j = i + 2;
            while (j < len && depth > 0) {
                if (sql.startsWith("/*", j)) {
                    depth++;
                    j += 2;
                } else if (sql.startsWith("*/", j)) {
                    depth--;
                    j += 2;
                } else {
                    j++;
                }
            }
            return j;
        }
        if (c == '$') {
            int delimiterLength = dollarQuoteDelimiterLength(sql, i);
            if (delimiterLength > 0) {
                String delimiter = sql.substring(i, i + delimiterLength);
                int end = sql.indexOf(delimiter, i + delimiterLength);
                return end < 0 ? len : end + delimiterLength;
            }
        }
        return -1;
    }

    private static int skipSingleQuoted(String sql, int start, boolean backslashEscapes) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < len) {
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int skipDoubleQuoted(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            if (sql.charAt(i) == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    /** E'...' 里反斜杠是转义符，找结尾引号时要跳过 \' */
    private static boolean isEStringQuote(String sql, int quote) {
        if (quote == 0)
            return false;
        char previous = sql.charAt(quote - 1);
        if (previous != 'E' && previous != 'e')
            return false;
        return quote < 2 || !isIdentifierPart(sql.charAt(quote - 2));
    }

    /** $$ 或 $tag$ 的分隔符长度，不是 dollar-quote 返回 -1 */
    private static int dollarQuoteDelimiterLength(String sql, int i) {
        int len = sql.length();
        int j = i + 1;
        if (j >= len)
            return -1;
        if (sql.charAt(j) == '$')
            return 2;
        char c = sql.charAt(j);
        if (!Character.isLetter(c) && c != '_')
            return -1;
        j++;
        while (j < len && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_'))
            j++;
        if (j < len && sql.charAt(j) == '$')
            return j - i + 1;
        return -1;
    }

    private static void appendReplaced(StringBuilder sb, String sql, int from, int to,
            Pattern pattern, Function<Matcher, String> replacement) {
        if (from >= to)
            return;
        String code = sql.substring(from, to);
        Matcher matcher = pattern.matcher(code);
        int last = 0;
        while (matcher.find()) {
            sb.append(code, last, matcher.start());
            sb.append(replacement.apply(matcher));
            last = matcher.end();
        }
        sb.append(code, last, code.length());
    }

    /**
     * 核心的 SYSTEM_RANGE 是函数表, 但解析器不支持 PostgreSQL 的列别名写法
     * {@code SYSTEM_RANGE(1, 2) AS s(r)}(在 r 处直接报语法错)。pgjdbc 的 TypeInfoCache
     * 正是这种写法, 而且 generate_series 已经被改写成 SYSTEM_RANGE。套一层派生表后
     * 核心的 FunctionTable.setColumnAliases 就能生效:
     * {@code (SELECT * FROM SYSTEM_RANGE(1, 2)) AS s(r)}。
     * <p>
     * 只处理后面确实跟着 {@code AS alias(col, ...)} 的 SYSTEM_RANGE, 不碰字符串字面量/注释。
     */
    private static String wrapRangeTableColumnAliases(String sql) {
        if (sql.toUpperCase().indexOf("SYSTEM_RANGE") < 0) {
            return sql;
        }
        int len = sql.length();
        StringBuilder out = new StringBuilder(len + 32);
        int i = 0;
        while (i < len) {
            int start = indexOfOutsideLiterals(sql, "SYSTEM_RANGE", i);
            if (start < 0) {
                out.append(sql, i, len);
                break;
            }
            out.append(sql, i, start);
            boolean wrapped = false;
            int open = skipWhitespace(sql, start + "SYSTEM_RANGE".length());
            if (open < len && sql.charAt(open) == '(') {
                int close = findClosingParen(sql, open);
                if (close > 0) {
                    int p = skipWhitespace(sql, close + 1);
                    if (regionMatchesIgnoreCase(sql, p, "AS")) {
                        p = skipWhitespace(sql, p + 2);
                    }
                    int aliasEnd = readIdentifierEnd(sql, p);
                    if (aliasEnd > 0) {
                        int colOpen = skipWhitespace(sql, aliasEnd);
                        if (colOpen < len && sql.charAt(colOpen) == '(') {
                            int colClose = findClosingParen(sql, colOpen);
                            if (colClose > 0) {
                                out.append("(SELECT * FROM ").append(sql, start, close + 1)
                                        .append(')');
                                i = close + 1;
                                wrapped = true;
                            }
                        }
                    }
                }
            }
            if (!wrapped) {
                out.append(sql, start, start + "SYSTEM_RANGE".length());
                i = start + "SYSTEM_RANGE".length();
            }
        }
        return out.toString();
    }

    /**
     * Lealone 没有集合返回函数和复合类型字段访问, 把 pgjdbc 的
     * information_schema._pg_expandarray(i.indkey) 展开改写成 pg_index_columns 连接:
     * <ul>
     * <li>在 JOIN pg_catalog.pg_index i 后面补
     * JOIN pg_catalog.pg_index_columns ic ON (ic.indexrelid = i.indexrelid)</li>
     * <li>(expandarray(i.indkey)).n 改成 ic.key_seq</li>
     * <li>(expandarray(i.indkey)).x 和 expandarray(i.indkey) 改成 ic.attnum</li>
     * <li>(result.KEYS).x 改成 result.KEYS</li>
     * </ul>
     */
    private static String rewritePgExpandArray(String sql) {
        if (sql.toUpperCase().indexOf("_PG_EXPANDARRAY") < 0) {
            return sql;
        }
        String s = rewriteOutsideLiterals(sql, EXPAND_FIELD_N, matcher -> "ic.key_seq");
        s = rewriteOutsideLiterals(s, EXPAND_FIELD_X, matcher -> "ic.attnum");
        s = rewriteOutsideLiterals(s, EXPAND_CALL, matcher -> "ic.attnum");
        s = rewriteOutsideLiterals(s, RECORD_FIELD_X, matcher -> matcher.group(1));
        s = rewriteOutsideLiterals(s, PG_INDEX_JOIN, matcher -> matcher.group(1)
                + " JOIN pg_catalog.pg_index_columns ic ON (ic.indexrelid = i.indexrelid)");
        s = rewriteOutsideLiterals(s, PG_INDEX_FROM, matcher -> matcher.group(1)
                + " JOIN pg_catalog.pg_index_columns ic ON (ic.indexrelid = i.indexrelid))");
        return s;
    }

    /**
     * pgjdbc getIndexInfo 的 COLUMN_NAME 用 pg_get_indexdef(ci_oid, ordinal, false)。
     * 别名函数只能读连接建立时的索引快照(别名函数里不能查库), 同一连接里新建的索引查不到,
     * 这里改成直接查 pg_index_columns。
     */
    private static String rewritePgIndexDefOrdinal(String sql) {
        if (sql.toUpperCase().indexOf("PG_GET_INDEXDEF") < 0) {
            return sql;
        }
        return rewriteOutsideLiterals(sql, GET_INDEXDEF_ORDINAL,
                matcher -> "(SELECT ic2.column_name FROM pg_catalog.pg_index_columns ic2"
                        + " WHERE ic2.indexrelid = tmp.CI_OID AND ic2.key_seq = tmp.ORDINAL_POSITION)");
    }

    /** 忽略字符串字面量/引号标识符/注释, 找下一个完整单词(大小写不敏感) */
    private static int indexOfOutsideLiterals(String sql, String word, int from) {
        int len = sql.length();
        int i = from;
        while (i < len) {
            int end = skipLiteralOrComment(sql, i);
            if (end > i) {
                i = end;
                continue;
            }
            if (sql.regionMatches(true, i, word, 0, word.length())
                    && (i == 0 || !isIdentifierPart(sql.charAt(i - 1)))
                    && (i + word.length() >= len || !isIdentifierPart(sql.charAt(i + word.length())))
                    // 不处理 pg_catalog.SYSTEM_RANGE 这种带 schema 前缀的写法(极少见)
                    && (i == 0 || sql.charAt(i - 1) != '.')) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipWhitespace(String sql, int i) {
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 从 open 处的 '(' 找到配对的 ')', 跳过字符串/引号标识符/注释; 找不到返回 -1 */
    private static int findClosingParen(String sql, int open) {
        int depth = 0;
        int i = open;
        int len = sql.length();
        while (i < len) {
            int end = skipLiteralOrComment(sql, i);
            if (end > i) {
                i = end;
                continue;
            }
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean regionMatchesIgnoreCase(String sql, int index, String word) {
        return index >= 0 && index + word.length() <= sql.length()
                && sql.regionMatches(true, index, word, 0, word.length())
                && (index + word.length() >= sql.length()
                        || !isIdentifierPart(sql.charAt(index + word.length())));
    }

    private static int readIdentifierEnd(String sql, int i) {
        if (i >= sql.length())
            return -1;
        char c = sql.charAt(i);
        if (!Character.isLetter(c) && c != '_')
            return -1;
        i++;
        while (i < sql.length() && isIdentifierPart(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    @Override
    public StatementBase parse(String sql) {
        return super.parse(normalize(sql));
    }

    @Override
    protected StatementBase parseStatement(char first) {
        StatementBase s = null;
        switch (first) {
        case 'a':
        case 'A':
            if (readIf("ABORT")) { // ABORT和COMMIT是同义词
                s = parseRollback(true);
            }
            break;
        case 'e':
        case 'E':
            if (readIf("END")) { // END和COMMIT是同义词
                s = parseCommit();
            }
            break;
        case 'r':
        case 'R':
            if (readIf("RESET")) {
                s = parseReset();
            }
            break;
        }
        return s;
    }

    @Override
    protected StatementBase parseStart() {
        if (readIf("TRANSACTION")) {
            TransactionStatement command = new TransactionStatement(session, SQLStatement.BEGIN);
            parseTransactionMode().update();
            session.getTransaction(); // 如果上一条提交事务了重新启动事务
            return command;
        } else {
            return super.parseStart();
        }
    }

    @Override
    protected TransactionStatement parseBegin() {
        TransactionStatement command = super.parseBegin();
        parseTransactionMode().update();
        session.getTransaction(); // 如果上一条提交事务了重新启动事务
        return command;
    }

    @Override
    protected TransactionStatement parseCommit() {
        TransactionStatement command = super.parseCommit();
        command = parseChain(command);
        return command;
    }

    private TransactionStatement parseChain(TransactionStatement command) {
        if (readIf("AND")) {
            boolean startNewTransaction = true;
            if (readIf("NO")) {
                startNewTransaction = false;
            }
            read("CHAIN");
            if (startNewTransaction) {
                command.update();
                command = new TransactionStatement(session, SQLStatement.BEGIN);
            }
        }
        return command;
    }

    @Override
    protected TransactionStatement parseRollback() {
        return parseRollback(false);
    }

    private TransactionStatement parseRollback(boolean abort) {
        TransactionStatement command;
        if (!abort && readIf("PREPARED")) {
            command = new TransactionStatement(session, SQLStatement.ROLLBACK_TRANSACTION);
            command.setTransactionName(readUniqueIdentifier());
            return command;
        }
        if (!readIf("TRANSACTION")) {
            readIf("WORK");
        }
        if (!abort && readIf("TO")) {
            readIf("SAVEPOINT");
            command = new TransactionStatement(session, SQLStatement.ROLLBACK_TO_SAVEPOINT);
            command.setSavepointName(readUniqueIdentifier());
        } else {
            command = new TransactionStatement(session, SQLStatement.ROLLBACK);
            command = parseChain(command);
        }
        return command;
    }

    @Override
    protected boolean parseShowOther(StringBuilder buff) {
        if (readIf("CLIENT_ENCODING")) {
            buff.append("'UNICODE' AS CLIENT_ENCODING");
        } else if (readIf("DEFAULT_TRANSACTION_ISOLATION")) {
            buff.append("'read committed' AS DEFAULT_TRANSACTION_ISOLATION");
        } else if (readIf("TRANSACTION")) {
            read("ISOLATION");
            read("LEVEL");
            buff.append("'read committed' AS TRANSACTION_ISOLATION");
        } else if (readIf("DATESTYLE")) {
            buff.append("'ISO' AS DATESTYLE");
        } else if (readIf("SERVER_VERSION")) {
            buff.append("'8.1.4' AS SERVER_VERSION");
        } else if (readIf("SERVER_ENCODING")) {
            buff.append("'UTF8' AS SERVER_ENCODING");
        } else if (readIf("ALL")) {
            buff.append("* FROM INFORMATION_SCHEMA.SETTINGS");
            return true;
        } else {
            String name = readStringOrIdentifier();
            buff.append("@").append(name);
        }
        buff.append(" FROM DUAL");
        return true;
    }

    @Override
    protected StatementBase parseSetOther() {
        if (readIf("TRANSACTION")) {
            if (readIf("SNAPSHOT")) {
                readStringOrIdentifier();
                return noOperation();
            }
            return parseTransactionMode();
        } else if (readIf("CONSTRAINTS")) {
            if (!readIf("ALL")) {
                do {
                    readStringOrIdentifier();
                } while (readIf(","));
            }
            if (!readIf("DEFERRED")) {
                read("IMMEDIATE");
            }
            return noOperation();
        } else {
            if (readIf("SESSION")) {
                if (readIf("CHARACTERISTICS")) {
                    read("AS");
                    read("TRANSACTION");
                    return parseTransactionMode();
                } else if (readIf("AUTHORIZATION")) {
                    if (!readIf("DEFAULT")) {
                        readStringOrIdentifier();
                    }
                    return noOperation();
                }
            } else {
                readIf("LOCAL");
            }
            if (readIf("SESSION")) {
                read("AUTHORIZATION");
                if (!readIf("DEFAULT")) {
                    readStringOrIdentifier();
                }
                return noOperation();
            } else if (readIf("ROLE")) {
                if (!readIf("NONE")) {
                    readStringOrIdentifier();
                }
                return noOperation();
            } else if (readIf("TIME")) {
                read("ZONE");
                if (readIf("LOCAL") || readIf("DEFAULT")) {
                    return noOperation();
                } else {
                    readStringOrIdentifier();
                    return noOperation();
                }
            } else if (readIf("DATESTYLE")) {
                readIfEqualOrTo();
                if (!readIf("ISO")) {
                    String s = readString();
                    if (!equalsToken(s, "ISO")) {
                        throw getSyntaxError();
                    }
                }
                return noOperation();
            } else if (readIf("SEARCH_PATH")) {
                readIfEqualOrTo();
                SetSession command = new SetSession(session, SessionSetting.SCHEMA_SEARCH_PATH);
                ArrayList<String> list = Utils.newSmallArrayList();
                do {
                    // some PG clients will send single-quoted alias
                    String s = readStringOrIdentifier();
                    if ("$user".equals(s)) {
                        continue;
                    }
                    list.add(s);
                } while (readIf(","));
                String[] schemaNames = new String[list.size()];
                list.toArray(schemaNames);
                command.setStringArray(schemaNames);
                return command;
            } else {
                return parseSetVariable();
            }
        }
    }

    private StatementBase parseTransactionMode() {
        SetSession command = null;
        while (true) {
            if (readIf("ISOLATION")) {
                read("LEVEL");
                command = new SetSession(session, SessionSetting.TRANSACTION_ISOLATION_LEVEL);
                if (readIf("SERIALIZABLE")) {
                    command.setString("SERIALIZABLE");
                } else if (readIf("REPEATABLE")) {
                    read("READ");
                    command.setString("REPEATABLE_READ");
                } else if (readIf("READ")) {
                    if (readIf("COMMITTED"))
                        command.setString("READ_COMMITTED");
                    else if (readIf("UNCOMMITTED"))
                        command.setString("READ_UNCOMMITTED");
                }
            } else if (readIf("READ")) {
                if (!readIf("WRITE"))
                    read("ONLY");
            } else if (readIf("NOT")) {
                read("DEFERRABLE");
            } else if (!readIf("DEFERRABLE")) {
                break;
            }
        }
        return command != null ? command : noOperation();
    }

    private StatementBase parseReset() {
        if (readIf("ALL")) {
            return noOperation();
        } else if (readIf("SESSION")) {
            read("AUTHORIZATION");
            return noOperation();
        } else if (readIf("ROLE")) {
            return noOperation();
        } else {
            readAliasIdentifier();
            return noOperation();
        }
    }

    @Override
    protected Expression parseVariable() {
        read();
        return new PgVariable(session, readAliasIdentifier());
    }

    /**
     * E'\n' 这种 escape string 转成普通字符串字面量(处理 \n \t \r \\ \' 这几个常见转义)。
     * <p>
     * 注意: E'' 里的 \' 必须输出成 SQL 标准的 ''(两个单引号)，不能输出裸引号，
     * 否则 E'it\'s' 会变成语法错误的 'it's'。注释/双引号标识符/$$...$$ 里的 E' 不处理。
     */
    static String normalizeEStrings(String sql) {
        if (sql.indexOf("E'") < 0 && sql.indexOf("e'") < 0)
            return sql;
        int len = sql.length();
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if ((c == 'E' || c == 'e') && i + 1 < len && sql.charAt(i + 1) == '\''
                    && isEStringQuote(sql, i + 1)) {
                // 跳过 E 前缀，把内容按转义规则解出来
                sb.append('\'');
                i = unescapeEString(sql, i + 2, sb);
                continue;
            }
            int end = skipLiteralOrComment(sql, i);
            if (end > i) {
                sb.append(sql, i, end);
                i = end;
            } else {
                sb.append(sql.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** 从 E'' 的起始引号之后开始解转义，返回结束引号之后的位置 */
    private static int unescapeEString(String sql, int start, StringBuilder sb) {
        int len = sql.length();
        int i = start;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char n = sql.charAt(i + 1);
                switch (n) {
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '\'':
                    // SQL 标准转义: 普通字符串字面量里的单引号写成两个单引号
                    sb.append("''");
                    break;
                default:
                    sb.append(n);
                    break;
                }
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    sb.append("''");
                    i += 2;
                    continue;
                }
                sb.append('\'');
                return i + 1;
            }
            sb.append(c);
            i++;
        }
        return i;
    }
}
