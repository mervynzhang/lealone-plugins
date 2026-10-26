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
    //   a OPERATOR(pg_catalog.~) 're'   -> a REGEXP 're'
    //   a OPERATOR(pg_catalog.!~) 're'  -> NOT (a REGEXP 're')
    //   a ~ 're'                        -> a REGEXP 're'
    //   a !~ 're'                       -> NOT (a REGEXP 're')
    private static final Pattern PG_OPERATOR = Pattern
            .compile("(?i)OPERATOR\\s*\\(\\s*(?:pg_catalog\\.)?(~\\*?|!~\\*?|=|<>|<|>|<=|>=)\\s*\\)");
    private static final Pattern PG_REGEX_MATCH = Pattern
            .compile("([A-Za-z_][A-Za-z0-9_$.]*)\\s+~\\s+('[^']*')");
    private static final Pattern REGTYPE_CAST = Pattern
            .compile("(?i)'[^']*'\\s*::\\s*(?:pg_catalog\\.)?regtype");
    private static final Pattern REGCLASS_CAST = Pattern
            .compile("(?i)'[^']*'\\s*::\\s*(?:pg_catalog\\.)?regclass");

    private static final Pattern REGTYPE_COL = Pattern
            .compile("(?i)\\s*::\\s*(?:pg_catalog\\.)?(?:regtype|regclass|regproc|regprocedure|regoperator|regnamespace)");
    private static final Pattern TEXT_CAST = Pattern
            .compile("(?i)\\s*::\\s*(?:pg_catalog\\.)?(?:text|bpchar|name|char|varchar|int2|int4|int8|oid|bool)");
    private static final Pattern PG_REGEX_NOT_MATCH = Pattern
            .compile("([A-Za-z_][A-Za-z0-9_$.]*)\\s+!~\\s+('[^']*')");
    // 核心的 RangeTable 已经用 SYSTEM_RANGE/GENERATE_SERIES 别名支持 FROM 里的函数表,
    // 这里按 PG 的写法统一改写成 SYSTEM_RANGE(大小写不敏感, 不碰字符串字面量/注释)
    private static final Pattern GENERATE_SERIES = Pattern
            .compile("(?i)(?<![A-Za-z0-9_$])(?:pg_catalog\\s*\\.\\s*)?generate_series\\s*(?=\\()");
    // 核心的 ANY 只支持 ANY(SELECT ...), 不支持 ANY(函数返回数组),
    // 把 pgjdbc TypeInfoCache 的 ANY(current_schemas(...)) 改写成等价的 ANY(SELECT ...)
    private static final Pattern CURRENT_SCHEMAS_ANY = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_$])ANY\\s*\\(\\s*(?:pg_catalog\\s*\\.\\s*)?current_schemas\\s*\\(\\s*(TRUE|FALSE)\\s*\\)\\s*\\)");

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
    static String normalize(String sql) {
        if (sql == null)
            return sql;
        String upper = sql.toUpperCase();
        if (sql.indexOf('~') < 0 && sql.indexOf("::") < 0 && upper.indexOf("OPERATOR") < 0
                && sql.indexOf("E'") < 0 && upper.indexOf("GENERATE_SERIES") < 0
                && upper.indexOf("SYSTEM_RANGE") < 0 && upper.indexOf("CURRENT_SCHEMAS") < 0
                && upper.indexOf("_PG_EXPANDARRAY") < 0 && upper.indexOf("PG_GET_INDEXDEF") < 0)
            return sql;
        String s = sql;
        s = rewriteOutsideLiterals(s, GENERATE_SERIES, matcher -> "SYSTEM_RANGE");
        s = wrapRangeTableColumnAliases(s);
        s = rewritePgExpandArray(s);
        s = rewriteOutsideLiterals(s, CURRENT_SCHEMAS_ANY, matcher -> {
            if (Boolean.parseBoolean(matcher.group(1))) {
                return "ANY(SELECT 'pg_catalog' UNION SELECT 'public')";
            }
            return "ANY(SELECT 'pg_catalog')";
        });
        Matcher m = PG_OPERATOR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(1);
            String rep;
            switch (op) {
            case "~":
            case "~*":
                rep = "REGEXP";
                break;
            case "!~":
            case "!~*":
                rep = "NOTREGEXP";
                break;
            default:
                rep = op;
                break;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();
        if (s.contains("NOTREGEXP")) {
            // a NOTREGEXP 're' -> NOT (a REGEXP 're')
            s = s.replaceAll("([A-Za-z_][A-Za-z0-9_$.]*)\\s+NOTREGEXP\\s+('[^']*')", "NOT ($1 REGEXP $2)");
        }
        s = PG_REGEX_NOT_MATCH.matcher(s).replaceAll("NOT ($1 REGEXP $2)");
        s = PG_REGEX_MATCH.matcher(s).replaceAll("$1 REGEXP $2");
        // 'pg_catalog.trigger'::pg_catalog.regtype 这类强转, Lealone 没有 regtype/regclass 类型,
        // 直接换成 0(只会参与类型比较, 空的 pg_proc 里不会命中)
        s = REGTYPE_CAST.matcher(s).replaceAll("0");
        s = REGCLASS_CAST.matcher(s).replaceAll("0");
        // 剩下的列级强转 Lealone 没有对应类型, 直接去掉(值本来就是字符串/整数)
        s = REGTYPE_COL.matcher(s).replaceAll("");
        s = TEXT_CAST.matcher(s).replaceAll("");
        s = normalizeEStrings(s);
        return s;
    }

    private static String rewriteOutsideLiterals(String sql, Pattern pattern,
            Function<Matcher, String> replacement) {
        StringBuilder sb = new StringBuilder(sql.length());
        int len = sql.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            int end;
            if (c == '\'' || c == '"') {
                end = skipQuoted(sql, i, c);
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                end = sql.indexOf('\n', i + 2);
                if (end < 0)
                    end = len;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                end = sql.indexOf("*/", i + 2);
                end = end < 0 ? len : end + 2;
            } else {
                i++;
                continue;
            }
            appendReplaced(sb, sql, start, i, pattern, replacement);
            sb.append(sql, i, end);
            i = end;
            start = end;
        }
        appendReplaced(sb, sql, start, len, pattern, replacement);
        return sb.toString();
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static void appendReplaced(StringBuilder sb, String sql, int from, int to,
            Pattern pattern, Function<Matcher, String> replacement) {
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
        String s = sql;
        s = EXPAND_FIELD_N.matcher(s).replaceAll("ic.key_seq");
        s = EXPAND_FIELD_X.matcher(s).replaceAll("ic.attnum");
        s = EXPAND_CALL.matcher(s).replaceAll("ic.attnum");
        s = RECORD_FIELD_X.matcher(s).replaceAll("$1");
        s = PG_INDEX_JOIN.matcher(s).replaceAll(
                "$1 JOIN pg_catalog.pg_index_columns ic ON (ic.indexrelid = i.indexrelid)");
        s = PG_INDEX_FROM.matcher(s).replaceAll(
                "$1 JOIN pg_catalog.pg_index_columns ic ON (ic.indexrelid = i.indexrelid))");
        return s;
    }

    /** 忽略字符串字面量/引号标识符/注释, 找下一个完整单词(大小写不敏感) */
    private static int indexOfOutsideLiterals(String sql, String word, int from) {
        int len = sql.length();
        int i = from;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? len : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
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
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                i = end < 0 ? len : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
                continue;
            }
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

    /** E'\n' 这种 escape string 转成普通字符串字面量(处理 \\n \\t \\r \\\\ \\' 这几个常见转义) */
    static String normalizeEStrings(String sql) {
        if (sql.indexOf("E'") < 0 && sql.indexOf("e'") < 0)
            return sql;
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        boolean inString = false;
        while (i < len) {
            char ch = sql.charAt(i);
            if (!inString && (ch == 'E' || ch == 'e') && i + 1 < len && sql.charAt(i + 1) == '\'') {
                // 跳过 E 前缀, 把内容按转义规则解出来
                i += 2;
                sb.append('\'');
                while (i < len) {
                    char c = sql.charAt(i);
                    if (c == '\\' && i + 1 < len) {
                        char n = sql.charAt(i + 1);
                        switch (n) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '\'':
                            sb.append('\'');
                            break;
                        default:
                            sb.append(n);
                            break;
                        }
                        i += 2;
                        continue;
                    }
                    if (c == '\'') {
                        sb.append(c);
                        i++;
                        break;
                    }
                    sb.append(c);
                    i++;
                }
                continue;
            }
            if (ch == '\'')
                inString = !inString;
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
