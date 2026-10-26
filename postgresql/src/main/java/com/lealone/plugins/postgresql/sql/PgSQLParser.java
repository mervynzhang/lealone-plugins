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
            .compile("(?i)\\s*::\\s*(?:pg_catalog\\.)?(?:regtype|regclass)");
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

    static String normalize(String sql) {
        if (sql == null)
            return sql;
        String upper = sql.toUpperCase();
        if (sql.indexOf('~') < 0 && sql.indexOf("::") < 0 && upper.indexOf("OPERATOR") < 0
                && sql.indexOf("E'") < 0 && upper.indexOf("GENERATE_SERIES") < 0
                && upper.indexOf("CURRENT_SCHEMAS") < 0)
            return sql;
        String s = sql;
        s = rewriteOutsideLiterals(s, GENERATE_SERIES, matcher -> "SYSTEM_RANGE");
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
