// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.arc.ClientProxy;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ContextNotActiveException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TriggerStreamSocketTest {
    @Test
    void retainedConnectionCanSendAfterTheCallbackContextEnds() throws Exception {
        var active = new AtomicBoolean(true);
        var sent = new ArrayList<String>();
        var connection = (WebSocketConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{WebSocketConnection.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendTextAndAwait")) sent.add((String) args[0]);
                return null;
            });
        var scoped = (WebSocketConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{WebSocketConnection.class, ClientProxy.class}, (proxy, method, args) -> {
                if (!active.get()) throw new ContextNotActiveException();
                if (method.getName().equals("arc_contextualInstance")) return connection;
                return method.invoke(connection, args);
            });
        var open = new TriggerStreamSocket.Open(scoped, null, "tenant/user", new long[]{0});
        active.set(false);
        assertThrows(ContextNotActiveException.class, () -> scoped.sendTextAndAwait("event"));
        try (var pump = Executors.newSingleThreadExecutor()) {
            pump.submit(() -> open.connection().sendTextAndAwait("event")).get();
        }
        assertSame(connection, open.connection());
        assertEquals(java.util.List.of("event"), sent);
    }
}
