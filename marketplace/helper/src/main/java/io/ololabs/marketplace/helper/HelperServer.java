// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The helper's internal HTTP API (api.md §3). Every route but {@code /health} needs the bearer token. Validations run
 * at most {@code MARKETPLACE_HELPER_THREADS} at a time, so a burst of uploads gets {@code 503} rather than exhausting
 * memory.
 */
public final class HelperServer {
    private static final Logger LOG = Logger.getLogger(HelperServer.class.getName());
    private static final int MAX_BODY = 64 * 1024;

    private final Config config;
    private final FileStore files;
    private final Inspector inspector;
    private final Signer signer;
    private final byte[] token;
    private final Semaphore slots;
    private HttpServer server;
    private ExecutorService pool;

    public HelperServer(Config config, FileStore files, Inspector inspector, Signer signer) {
        this.config = config;
        this.files = files;
        this.inspector = inspector;
        this.signer = signer;
        this.slots = new Semaphore(config.threads());
        this.token = ("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8);
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        pool = Executors.newFixedThreadPool(config.threads() * 2);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            try {
                if (path.equals("/health") && method.equals("GET")) {
                    send(exchange, 200, Map.of("status", "ok"));
                    return;
                }
                if (!authorized(exchange)) {
                    send(exchange, 401, error("UNAUTHORIZED", "missing or wrong bearer token"));
                    return;
                }
                switch (method + " " + path) {
                    case "POST /inspect" -> send(exchange, 200, bounded(() -> inspect(body(exchange))));
                    case "POST /sign" -> send(exchange, 200, bounded(() -> sign(body(exchange))));
                    case "GET /keys" -> {
                        Map<String, Object> keys = new LinkedHashMap<>(signer.keySet(config.marketplaceId()));
                        keys.put("toolgateRevision", config.toolgateRevision());
                        send(exchange, 200, keys);
                    }
                    default -> send(exchange, 404, error("NOT_FOUND", "no such endpoint"));
                }
            } catch (HelperException e) {
                send(exchange, e.status(), error(e.code(), e.getMessage()));
            } catch (IllegalArgumentException e) {
                send(exchange, 400, error("INVALID_REQUEST", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "request failed", e);
                send(exchange, 500, error("SERVER_ERROR", "internal error"));
            }
        }
    }

    private interface Work {
        Map<String, Object> run() throws IOException;
    }

    /** At most {@code MARKETPLACE_HELPER_THREADS} validations at once; more get 503 so memory stays bounded. */
    private Map<String, Object> bounded(Work work) throws IOException {
        if (!slots.tryAcquire()) {
            throw new HelperException(503, "UNAVAILABLE", "the helper is busy; retry shortly");
        }
        try {
            return work.run();
        } finally {
            slots.release();
        }
    }

    private Map<String, Object> inspect(Map<String, Object> body) {
        byte[] bytes = files.read(Json.str(body.get("fileRef")));
        Map<String, Object> previous = Json.obj(body.get("previousVersion"));
        byte[] previousBytes = null;
        String previousVersion = null;
        if (!previous.isEmpty()) {
            previousBytes = files.read(Json.str(previous.get("fileRef")));
            previousVersion = Json.str(previous.get("version"));
        }
        return inspector.inspect(bytes, previousBytes, previousVersion);
    }

    private Map<String, Object> sign(Map<String, Object> body) {
        byte[] bytes = files.read(Json.str(body.get("fileRef")));
        Map<String, Object> record = inspector.sign(bytes, Json.str(body.get("packageDigest")),
                Json.str(body.get("authorizedPublisherSlug")));
        LOG.info("signed " + record.get("packageId") + "@" + record.get("version") + " for " + record.get("publisherSlug")
                + " " + record.get("packageDigest"));
        return record;
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && MessageDigest.isEqual(token, header.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            throw new HelperException(415, "INVALID_REQUEST", "Content-Type must be application/json");
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new HelperException(413, "INVALID_REQUEST", "request body is too large");
            }
            Object value = Json.parse(bytes);
            if (!(value instanceof Map<?, ?>)) {
                throw new HelperException(400, "INVALID_REQUEST", "request body must be a JSON object");
            }
            return Json.obj(value);
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        return e;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
