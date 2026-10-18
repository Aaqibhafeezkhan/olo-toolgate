// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Small bounded RESP2 GET/SET client for a standalone Redis/Valkey endpoint (no redirects).
 * Wire format: https://redis.io/docs/latest/develop/reference/protocol-spec/ */
final class RedisCacheClient {
    static final int MAX_VALUE = 2 * 1024 * 1024;
    private final String host;
    private final int port;
    private final String username;
    private final byte[] password;
    private final SSLContext tls;
    private final int timeoutMs;
    private final Semaphore permits = new Semaphore(8);
    private volatile long retryAfter = System.nanoTime();

    RedisCacheClient(String host, int port, String username, byte[] password, SSLContext tls, int timeoutMs) {
        this.host = host; this.port = port; this.username = username;
        this.password = password.clone(); this.tls = tls; this.timeoutMs = timeoutMs;
    }

    byte[] get(String key) throws IOException { return exchange("GET", key, null, 0); }
    void set(String key, byte[] value, int ttlSeconds) throws IOException { exchange("SET", key, value, ttlSeconds); }

    private byte[] exchange(String command, String key, byte[] value, int ttlSeconds) throws IOException {
        if (System.nanoTime() - retryAfter < 0 || !permits.tryAcquire()) throw new IOException("Cache temporarily bypassed");
        // A virtual thread also bounds DNS, handshake and writes, not just socket reads.
        var socket = new Socket();
        var task = new FutureTask<byte[]>(() -> {
            try (socket) { return request(socket, command, key, value, ttlSeconds); }
            finally { permits.release(); }
        });
        var worker = Thread.startVirtualThread(task);
        try { return task.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Cache request interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            throw new IOException("Cache request failed");
        } finally {
            socket.close();
            worker.interrupt();
        }
    }

    private byte[] request(Socket socket, String command, String key, byte[] value, int ttlSeconds) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        Socket connected = socket;
        if (tls != null) {
            var secure = (SSLSocket) tls.getSocketFactory().createSocket(socket, host, port, true);
            var parameters = secure.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"}); secure.setSSLParameters(parameters);
            secure.startHandshake(); connected = secure;
        }
        try (var channel = connected;
             var input = new BufferedInputStream(channel.getInputStream());
             var output = new BufferedOutputStream(channel.getOutputStream())) {
            write(output, bytes("AUTH"), bytes(username), password); expectOk(input);
            if (value == null) {
                write(output, bytes(command), bytes(key)); return bulk(input);
            }
            if (value.length > MAX_VALUE) throw new IOException("Cache value limit exceeded");
            write(output, bytes(command), bytes(key), value, bytes("EX"), bytes(Integer.toString(ttlSeconds)));
            expectOk(input); return null;
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void write(OutputStream output, byte[]... arguments) throws IOException {
        output.write(bytes("*" + arguments.length + "\r\n"));
        for (var argument : arguments) {
            output.write(bytes("$" + argument.length + "\r\n")); output.write(argument); output.write(bytes("\r\n"));
        }
        output.flush();
    }
    private static String line(InputStream input) throws IOException {
        var result = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            int next = input.read();
            if (next == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid cache response");
                return result.toString();
            }
            if (next < 0 || next == '\n') throw new IOException("Invalid cache response");
            result.append((char) next);
        }
        throw new IOException("Cache response limit exceeded");
    }
    private static void expectOk(InputStream input) throws IOException {
        if (!"+OK".equals(line(input))) throw new IOException("Cache command rejected");
    }
    private static byte[] bulk(InputStream input) throws IOException {
        String header = line(input);
        if (header.equals("$-1")) return null;
        if (!header.matches("\\$[0-9]{1,7}")) throw new IOException("Invalid cache response");
        int size = Integer.parseInt(header.substring(1));
        if (size > MAX_VALUE) throw new IOException("Cache value limit exceeded");
        byte[] value = input.readNBytes(size);
        if (value.length != size || input.read() != '\r' || input.read() != '\n') throw new IOException("Truncated cache response");
        return value;
    }
}
