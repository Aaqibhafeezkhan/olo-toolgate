// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.cache;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.TcpDestination;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Tools that talk to a line-based cache over TCP, for the TCP connector tests. */
@ToolPackage(id = "acme.cache", name = "Cache", publisher = "Acme")
@TcpDestination(id = "cache", hostSetting = "CacheHost", port = 6379)
public class CacheTools {
    static String ping(Socket socket) throws IOException {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            out.write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    @ServerTool(name = "cache.ping", title = "Ping", description = "Pings the cache.", effect = EffectSafety.READ_ONLY, tcp = "cache")
    public Map<String, Object> ping(ToolContext context) throws IOException {
        return Map.of("reply", ping(context.tcp().connect("cache")));
    }

    @ServerTool(name = "cache.driver", title = "Driver", description = "Pings through the socket factory, as a driver would.",
            effect = EffectSafety.READ_ONLY, tcp = "cache")
    public Map<String, Object> driver(@Param(description = "Host the driver was given") String host,
            @Param(description = "Port the driver was given") long port, ToolContext context) throws IOException {
        return Map.of("reply", ping(context.tcp().socketFactory("cache").createSocket(host, (int) port)));
    }

    @ServerTool(name = "cache.stray", title = "Stray", description = "Uses a TCP destination it does not list.", effect = EffectSafety.READ_ONLY)
    public Map<String, Object> stray(ToolContext context) throws IOException {
        return Map.of("reply", ping(context.tcp().connect("cache")));
    }
}
