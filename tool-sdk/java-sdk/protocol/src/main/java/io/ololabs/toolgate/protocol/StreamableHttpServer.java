// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MCP 2026-07-28 over Streamable HTTP: every request is a stateless POST to one path. Responses are JSON, or an SSE
 * stream when the request carries a progress token (notifications precede the result) or is
 * {@code subscriptions/listen}. Only loopback hosts and origins are accepted (DNS rebinding protection): this
 * transport has no authentication until the identity verifier (adapter A4) arrives.
 */
public final class StreamableHttpServer implements AutoCloseable {
    /** The largest request body accepted: the 1 MiB input ceiling plus the envelope. */
    public static final int MAX_BODY = 2 * 1024 * 1024;
    static final long KEEPALIVE_MS = 15_000;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final ProtocolCore core;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private final String path;
    /** Unauthenticated requests share one owner, so a task created by one POST can be polled by the next. */
    private final ProtocolCore.Connection connection = new ProtocolCore.Connection("http", "http:anonymous");
    private volatile boolean closed;

    private StreamableHttpServer(ProtocolCore core, HttpServer server, ExecutorService executor, String path) {
        this.core = core;
        this.server = server;
        this.executor = executor;
        this.path = path;
    }

    /**
     * Binds a loopback address and serves {@code path}.
     *
     * @throws IllegalArgumentException when the address is not a loopback address
     */
    public static StreamableHttpServer start(ProtocolCore core, InetSocketAddress address, String path) throws IOException {
        InetAddress ip = address.getAddress();
        if (ip == null || !ip.isLoopbackAddress()) {
            throw new IllegalArgumentException("Streamable HTTP binds only loopback addresses until the identity verifier (adapter A4) arrives: " + address);
        }
        HttpServer server = HttpServer.create(address, 64);
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "toolgate-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        StreamableHttpServer http = new StreamableHttpServer(core, server, executor, path);
        server.createContext("/", http::handle);
        server.start();
        return http;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try {
            if (!exchange.getRequestURI().getPath().equals(path)) {
                plain(exchange, 404, "Not found");
                return;
            }
            if (!allowedHost(exchange)) {
                plain(exchange, 403, "Forbidden host or origin");
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                plain(exchange, 405, "Method not allowed");
                return;
            }
            byte[] body = read(exchange.getRequestBody());
            if (body == null) {
                plain(exchange, 413, "Request too large");
                return;
            }
            JsonNode message;
            try {
                message = json.readTree(body);
            } catch (JsonProcessingException e) {
                respond(exchange, ProtocolCore.error(null, new ProtocolError(ProtocolError.PARSE_ERROR, "Parse error")));
                return;
            }
            if (message == null || message.isArray() || !message.isObject()) {
                respond(exchange, ProtocolCore.error(null, new ProtocolError(ProtocolError.INVALID_REQUEST, "Invalid request")));
                return;
            }
            if (!message.has("id") || !message.has("method")) {
                core.handle(message, connection, exchange.getRequestHeaders()::getFirst, n -> {});
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            boolean sse = accepts(exchange, "text/event-stream");
            boolean listen = "subscriptions/listen".equals(message.path("method").asText());
            boolean progress = message.path("params").path("_meta").has("progressToken");
            Stream stream = new Stream(exchange);
            if (sse && progress && !listen) stream.live();
            JsonNode response = core.handle(message, connection, exchange.getRequestHeaders()::getFirst, stream::send);
            if (listen && response == null) {
                stream.live();
                hold(stream, message.get("id"));
            } else if (stream.isLive()) {
                stream.send(response);
            } else {
                respond(exchange, response);
            }
        } catch (IOException | RuntimeException e) {
            // the client went away or the exchange broke; nothing more can be sent
        } finally {
            exchange.close();
        }
    }

    /** Keeps a listen stream open until the client goes away, the subscription ends or the server closes. */
    private void hold(Stream stream, JsonNode id) {
        try {
            while (!closed && !stream.failed() && core.subscribed(connection, id)) {
                if (stream.closedWait(KEEPALIVE_MS)) break;
                stream.comment("keepalive");
            }
        } finally {
            core.unsubscribe(connection, id);
        }
    }

    private boolean allowedHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null && !LOOPBACK_HOSTS.contains(hostOnly(host))) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) return origin == null;
        try {
            String originHost = URI.create(origin).getHost();
            return originHost != null && LOOPBACK_HOSTS.contains(originHost.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String hostOnly(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(0, end + 1);
        }
        int colon = h.lastIndexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    private static boolean accepts(HttpExchange exchange, String type) {
        for (String accept : exchange.getRequestHeaders().getOrDefault("Accept", List.of())) {
            if (accept.toLowerCase(Locale.ROOT).contains(type)) return true;
        }
        return false;
    }

    private static byte[] read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY + 1);
        return bytes.length > MAX_BODY ? null : bytes;
    }

    private void respond(HttpExchange exchange, JsonNode response) throws IOException {
        int status = response.has("error") ? ProtocolCore.httpStatus(response.path("error").path("code").asInt()) : 200;
        byte[] bytes = json.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void plain(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** One response stream: frames are buffered until it goes live as SSE, then written as they come. */
    private final class Stream {
        private final HttpExchange exchange;
        private final List<JsonNode> buffered = new ArrayList<>();
        private final CountDownLatch broken = new CountDownLatch(1);
        private OutputStream out;

        Stream(HttpExchange exchange) {
            this.exchange = exchange;
        }

        synchronized boolean isLive() {
            return out != null;
        }

        boolean failed() {
            return broken.getCount() == 0;
        }

        /** Waits up to {@code ms}; true when the stream broke meanwhile. */
        boolean closedWait(long ms) {
            try {
                return broken.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }

        synchronized void live() throws IOException {
            if (out != null) return;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            out = exchange.getResponseBody();
            for (JsonNode frame : buffered) write(frame);
            buffered.clear();
        }

        /** A frame for this stream; a failed write ends it, which ends a listen subscription. */
        synchronized void send(JsonNode frame) {
            if (out == null) {
                buffered.add(frame);
                return;
            }
            write(frame);
        }

        synchronized void comment(String text) {
            if (out == null) return;
            try {
                out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                broken.countDown();
            }
        }

        private void write(JsonNode frame) {
            try {
                out.write(("data: " + json.writeValueAsString(frame) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                broken.countDown();
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
