// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.io.*;
import java.util.Set;

/** Fixed protocol routes and an allowlist: never log credentials, arguments or outputs. */
@Provider
public class PacketTelemetry implements ContainerRequestFilter, ContainerResponseFilter {
    @Inject Correlation correlation;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(PacketTelemetry.class);
    private static final Set<String> ROUTES = Set.of("/api/control/v1/endpoint/check-in", "/api/control/v1/endpoint/enrollments", "/api/control/v1/endpoint/enrollments/poll", "/api/control/v1/mcp/catalog", "/api/control/v1/mcp/requests", "/api/control/v1/mcp/responses", "/api/control/v1/mcp/authorize", "/api/control/v1/mcp/results", "/.well-known/olo-toolgate-client");
    private static final Set<String> FIELDS = Set.of("requestId", "deviceId", "toolId", "action", "state", "code", "error", "sequence", "reportSequence", "clientVersion", "platform", "revision", "appliedRevision", "serverTimeUnixMs", "nextIntervalMs", "expiresAtUnixMs", "digest", "permissionDigest", "enabled", "decision", "protocolVersion");
    private static final Set<String> NESTED = Set.of("report", "localTools", "configuration", "permissions", "task", "request", "record", "context", "input");
    static JsonNode summary(JsonNode value, int depth) {
        var result = JSON.createObjectNode();
        if(value == null || !value.isObject() || depth > 8) return JSON.getNodeFactory().textNode("[redacted]");
        var fields = value.properties().iterator(); int count = 0;
        while(fields.hasNext() && count++ < 40) {
            var field = fields.next(); var name = field.getKey(); var item = field.getValue();
            if(!name.matches("[a-zA-Z0-9]{1,64}")) continue;
            if(FIELDS.contains(name) && (item.isNumber() || item.isBoolean() || item.isNull() || item.isTextual() && item.asText().matches("[a-zA-Z0-9._-]{0,128}"))) result.set(name, item);
            else if(NESTED.contains(name) && item.isObject()) result.set(name, summary(item, depth + 1));
            else if(item.isArray()) result.set(name, JSON.createObjectNode().put("count", item.size()));
            else result.put(name, "[redacted]");
        }
        return result;
    }
    private void log(ContainerRequestContext request, String direction, Integer status, byte[] bytes) {
        var path = request.getUriInfo().getPath(); if(!path.startsWith("/")) path = "/" + path;
        if(!ROUTES.contains(path)) return;
        var event = JSON.createObjectNode().put("service", "control").put("event", "protocol_packet").put("direction", direction).put("path", path).put("requestId", correlation.id()).put("bytes", bytes.length);
        if(status != null) event.put("status", status);
        var caller = request.getHeaderString("X-Request-ID");
        if(caller != null && caller.matches("(?:[a-f0-9]{32}|gw-[a-f0-9]{32})")) event.put("peerRequestId", caller);
        try { event.set("message", bytes.length == 0 ? JSON.createObjectNode() : summary(JSON.readTree(bytes), 0)); }
        catch(Exception ignored) { event.put("message", "[invalid or oversized JSON]"); }
        LOG.info(event.toString());
    }
    public void filter(ContainerRequestContext request) throws IOException {
        var path = request.getUriInfo().getPath(); if(!path.startsWith("/")) path = "/" + path;
        if(!ROUTES.contains(path)) return;
        var stream = request.getEntityStream(); var bytes = stream.readNBytes(65537);
        // Restore exactly what was read; existing transport/application limits still apply.
        request.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(bytes), stream));
        log(request, "RECEIVE", null, bytes.length > 65536 ? new byte[0] : bytes);
    }
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        try {
            var entity = response.getEntity();
            var bytes = entity instanceof String text ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8) : JSON.writeValueAsBytes(entity);
            log(request, "SEND", response.getStatus(), bytes.length > 131072 ? new byte[0] : bytes);
        } catch(Exception ignored) { log(request, "SEND", response.getStatus(), new byte[0]); }
    }
}
