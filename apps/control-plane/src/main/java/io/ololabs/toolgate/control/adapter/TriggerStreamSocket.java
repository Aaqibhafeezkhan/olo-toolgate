// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.TriggerStream;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnPongMessage;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code wss://<tenant host>/api/control/v1/triggers/stream} with subprotocols {@code toolgate.triggers.v1, ticket.<t>}
 * (plan 13.2; triggers module 12). Only {@code toolgate.triggers.v1} is ever selected and echoed; the ticket is read
 * from the handshake, redeemed once and never logged. The tenant comes from the host, as for every Control call.
 *
 * <p>Ping every 20 s (configured); a socket that misses two pongs is closed. At most 5 sockets per principal; 64 KiB per
 * message (configured). A pump thread delivers within each socket's credit window every 500 ms and closes a consumer
 * without credit for 60 s with 1013, keeping its cursor.
 */
@WebSocket(path = "/api/control/v1/triggers/stream")
public class TriggerStreamSocket {
    static final String PROTOCOL = "toolgate.triggers.v1";
    static final int MAX_SOCKETS_PER_PRINCIPAL = 5;
    static final long PONG_TIMEOUT_MS = 45_000;
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(TriggerStreamSocket.class);
    private static final Map<String, AtomicInteger> SOCKETS = new ConcurrentHashMap<>();
    private static final Map<String, Open> OPEN = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService PUMP = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "toolgate-trigger-stream"); t.setDaemon(true); return t; });
    static { PUMP.scheduleWithFixedDelay(TriggerStreamSocket::pumpAll, 500, 500, TimeUnit.MILLISECONDS); }

    record Open(WebSocketConnection connection, TriggerStream.Session session, String principal, long[] lastPong) {}

    @Inject WebSocketConnection connection;
    @Inject TriggerBootstrap triggers;
    @Inject TenantRequestResolver resolver;

    /** The ticket from {@code Sec-WebSocket-Protocol}, or null; the value is never logged. */
    static String ticket(List<String> headers) {
        String ticket = null;
        boolean protocol = false;
        for (String header : headers) for (String value : header.split(",")) {
            String item = value.strip();
            if (item.equals(PROTOCOL)) protocol = true;
            else if (item.startsWith("ticket.")) { if (ticket != null) return null; ticket = item.substring(7); }
        }
        return protocol ? ticket : null;
    }

    /**
     * The pump thread sends on {@code connection}, outside any socket callback. The injected {@link #connection} is a
     * session-scoped client proxy that is unusable there (ContextNotActiveException), so the instance kept for the pump
     * is the one Quarkus passes to this callback.
     */
    @OnOpen
    public void open(WebSocketConnection connection) {
        var handshake = connection.handshakeRequest();
        try {
            if (!"https".equalsIgnoreCase(handshake.scheme()) && !"wss".equalsIgnoreCase(handshake.scheme())) throw Failure.validation();
            var host = handshake.header("Host");
            var bound = resolver.resolve(host == null ? List.of() : List.of(host), 443, null, handshake.headers("Toolgate-Tenant-ID") == null ? List.of() : handshake.headers("Toolgate-Tenant-ID"));
            var session = triggers.stream(bound.tenant()).open(ticket(handshake.headers("Sec-WebSocket-Protocol") == null ? List.of() : handshake.headers("Sec-WebSocket-Protocol")), handshake.header("Origin"));
            String principal = bound.tenant().value() + "/" + session.principal();
            var count = SOCKETS.computeIfAbsent(principal, ignored -> new AtomicInteger());
            if (count.incrementAndGet() > MAX_SOCKETS_PER_PRINCIPAL) {
                count.decrementAndGet();
                connection.closeAndAwait(new CloseReason(1008, "At most " + MAX_SOCKETS_PER_PRINCIPAL + " sockets per principal"));
                return;
            }
            OPEN.put(connection.id(), new Open(connection, session, principal, new long[]{System.currentTimeMillis()}));
        } catch (RuntimeException refused) {
            LOG.info("trigger_stream refused");
            connection.closeAndAwait(new CloseReason(1008, "Stream ticket refused"));
        }
    }

    @OnTextMessage
    public void message(String text) {
        var open = OPEN.get(connection.id());
        if (open == null) return;
        synchronized (open.session()) {
            try { for (String reply : open.session().receive(text)) connection.sendTextAndAwait(reply); }
            catch (RuntimeException failed) { LOG.infof("trigger_stream message failed: %s", where(failed)); }
        }
    }

    @OnPongMessage
    public void pong(Buffer data) {
        var open = OPEN.get(connection.id());
        if (open != null) open.lastPong()[0] = System.currentTimeMillis();
    }

    @OnClose
    public void close() {
        var open = OPEN.remove(connection.id());
        if (open != null) { var count = SOCKETS.get(open.principal()); if (count != null) count.decrementAndGet(); }
    }

    /** The failure's class and the frame that threw it; never its message, which may quote event data. */
    static String where(Throwable failure) {
        var frames = failure.getStackTrace();
        var own = java.util.Arrays.stream(frames).filter(f -> f.getClassName().startsWith("io.ololabs.") && !f.getClassName().contains("_ClientProxy")).findFirst();
        return failure.getClass().getName() + (frames.length == 0 ? "" : " at " + frames[0]) + own.map(f -> " from " + f).orElse("");
    }

    static void pumpAll() {
        for (var open : List.copyOf(OPEN.values())) {
            try {
                synchronized (open.session()) {
                    if (System.currentTimeMillis() - open.lastPong()[0] > PONG_TIMEOUT_MS) {
                        open.connection().closeAndAwait(new CloseReason(1001, "Missed pongs"));
                        continue;
                    }
                    for (String message : open.session().pump()) open.connection().sendTextAndAwait(message);
                    if (open.session().closeCode() != null) open.connection().closeAndAwait(new CloseReason(open.session().closeCode(), open.session().closeReason()));
                }
            } catch (RuntimeException failed) { LOG.infof("trigger_stream pump failed: %s", where(failed)); }
        }
    }
}
