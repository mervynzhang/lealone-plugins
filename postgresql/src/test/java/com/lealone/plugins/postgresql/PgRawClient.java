/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.plugins.postgresql;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 测试用的极简 PostgreSQL 前端协议(v3)客户端。
 * <p>
 * pgjdbc 只会发"合法"的包，测不了协议边界(超大 paramLen、超长 pipeline、认证失败后
 * 服务端是否关 socket)，所以这里直接拼字节。
 */
final class PgRawClient implements AutoCloseable {

    static final class Message {
        final char type;
        final byte[] body;

        Message(char type, byte[] body) {
            this.type = type;
            this.body = body;
        }

        String asString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        int firstInt() {
            return ((body[0] & 0xff) << 24) | ((body[1] & 0xff) << 16) | ((body[2] & 0xff) << 8)
                    | (body[3] & 0xff);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    PgRawClient(int socketTimeoutMillis) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PgTestBase.TEST_PORT), 5000);
        socket.setSoTimeout(socketTimeoutMillis);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    void sendStartup(String user, String database) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(196608); // protocol 3.0
        data.write(("user\0" + user + "\0database\0" + database + "\0client_encoding\0UTF8\0\0")
                .getBytes(StandardCharsets.UTF_8));
        data.flush();
        byte[] payload = bytes.toByteArray();
        writeInt(payload.length + 4);
        out.write(payload);
        out.flush();
    }

    void sendPassword(String password) throws IOException {
        send('p', (password + "\0").getBytes(StandardCharsets.UTF_8));
    }

    void authenticate(String user, String database, String password) throws IOException {
        sendStartup(user, database);
        Message auth = readExpect('R');
        if (auth.firstInt() != 3) {
            throw new IOException("期望明文密码认证请求, 实际类型 " + auth.firstInt());
        }
        sendPassword(password);
        Message m;
        while ((m = read()) != null) {
            if (m.type == 'E') {
                throw new IOException("认证失败: " + m.asString());
            }
            if (m.type == 'Z') {
                return;
            }
        }
        throw new EOFException("认证过程中连接被关闭");
    }

    void send(char type, byte[] body) throws IOException {
        out.write(type);
        writeInt(body.length + 4);
        out.write(body);
        out.flush();
    }

    void send(char type) throws IOException {
        send(type, new byte[0]);
    }

    /** 不经过缓冲直接写原始字节(用于一次灌入大量 Sync 包) */
    void writeRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /** 只发 Parse 并读 ParseComplete, 用于之后批量发 Bind */
    void sendExtendedQueryPrepareOnly(String sql) throws IOException {
        sendExtendedQueryPrepareOnly(sql, new int[0]);
    }

    /** 带参数类型 OID 的 Parse(类型个数必须和 SQL 里的参数个数一致) */
    void sendExtendedQueryPrepareOnly(String sql, int[] paramTypes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(0);
        d.write(sql.getBytes(StandardCharsets.UTF_8));
        d.writeByte(0);
        d.writeShort(paramTypes.length);
        for (int type : paramTypes) {
            d.writeInt(type);
        }
        d.flush();
        send('P', body.toByteArray());
        readExpect('1');
    }

    /** 发 Bind: formats 是参数 format code(0=text,1=binary), params 是每个参数的原始字节(null 表示 NULL) */
    void sendBind(int[] formats, byte[][] params) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(0); // portal 名
        d.writeByte(0); // statement 名
        d.writeShort(formats.length);
        for (int f : formats) {
            d.writeShort(f);
        }
        d.writeShort(params.length);
        for (byte[] p : params) {
            if (p == null) {
                d.writeInt(-1);
            } else {
                d.writeInt(p.length);
                d.write(p);
            }
        }
        d.writeShort(0); // 结果 format code 个数
        d.flush();
        send('B', body.toByteArray());
    }

    void sendExecute() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(0); // portal 名
        d.writeInt(0); // maxRows
        d.flush();
        send('E', body.toByteArray());
    }

    /** Parse/Bind(none)/Execute, 读完 ParseComplete 和 BindComplete 后返回(Execute 的响应还没读) */
    void sendExtendedQuery(String sql) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        d.writeByte(0); // statement 名
        d.write(sql.getBytes(StandardCharsets.UTF_8));
        d.writeByte(0);
        d.writeShort(0); // 参数类型个数
        d.flush();
        send('P', body.toByteArray());
        readExpect('1');

        body.reset();
        d.writeByte(0); // portal 名
        d.writeByte(0); // statement 名
        d.writeShort(0); // format code 个数
        d.writeShort(0); // 参数个数
        d.writeShort(0); // 结果 format code 个数
        d.flush();
        send('B', body.toByteArray());
        readExpect('2');

        body.reset();
        d.writeByte(0); // portal 名
        d.writeInt(0); // maxRows
        d.flush();
        send('E', body.toByteArray());
    }

    /** 简单查询, 断言没有 ErrorResponse 并读到 ReadyForQuery */
    void simpleQuery(String sql) throws IOException {
        send('Q', (sql + "\0").getBytes(StandardCharsets.UTF_8));
        Message m = readUntilReadyOrError();
        if (m.type != 'Z') {
            throw new AssertionError("简单查询失败: " + m.asString());
        }
    }

    private void writeInt(int v) throws IOException {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    /** @return null 表示对端关闭了连接 */
    Message read() throws IOException {
        int type = in.read();
        if (type < 0) {
            return null;
        }
        byte[] lenBytes = readN(4);
        int len = ((lenBytes[0] & 0xff) << 24) | ((lenBytes[1] & 0xff) << 16)
                | ((lenBytes[2] & 0xff) << 8) | (lenBytes[3] & 0xff);
        if (len < 4) {
            throw new IOException("非法包长度: " + len);
        }
        return new Message((char) type, readN(len - 4));
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new EOFException("连接已关闭, 还差 " + (n - off) + " 字节");
            }
            off += r;
        }
        return b;
    }

    Message readExpect(char type) throws IOException {
        Message m = read();
        if (m == null) {
            throw new EOFException("期望 " + type + " 包, 连接已关闭");
        }
        if (m.type != type) {
            throw new IOException("期望 " + type + " 包, 实际 " + m.type + ": " + m.asString());
        }
        return m;
    }

    /** 读包直到 ReadyForQuery(连接正常)或 ErrorResponse(返回该错误包) */
    Message readUntilReadyOrError() throws IOException {
        Message m;
        while ((m = read()) != null) {
            if (m.type == 'Z' || m.type == 'E') {
                return m;
            }
        }
        throw new EOFException("等待 ReadyForQuery 时连接被关闭");
    }

    /** 断言服务端已经关闭连接(读到 EOF); 还在等数据则抛 AssertionError 而不是卡住 */
    void assertClosed(String when) throws IOException {
        try {
            int b = in.read();
            if (b >= 0) {
                throw new AssertionError(when + ": 服务端没有关闭连接, 还能读到数据 " + b);
            }
        } catch (SocketTimeoutException e) {
            throw new AssertionError(when + ": 服务端没有关闭连接(读超时)", e);
        }
    }

    /** 取 DataRow 第一列的文本值(测试里的结果都是 text 格式) */
    static String firstColumn(Message dataRow) {
        byte[] b = dataRow.body;
        int len = ((b[2] & 0xff) << 24) | ((b[3] & 0xff) << 16) | ((b[4] & 0xff) << 8)
                | (b[5] & 0xff);
        if (len < 0) {
            return null;
        }
        return new String(b, 6, len, StandardCharsets.UTF_8);
    }

    /** 解析 ErrorResponse 里的 SQLSTATE */
    static String sqlState(Message error) {
        byte[] b = error.body;
        int i = 0;
        while (i < b.length) {
            int field = b[i++] & 0xff;
            if (field == 0) {
                break;
            }
            int end = i;
            while (end < b.length && b[end] != 0) {
                end++;
            }
            String value = new String(b, i, end - i, StandardCharsets.UTF_8);
            if (field == 'C') {
                return value;
            }
            i = end + 1;
        }
        return null;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
