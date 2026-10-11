// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adapter A1: the MCP 2026-07-28 server core (tool-sdk/spec/protocol-layer.md §3). Stateless per request; the only
 * connection state is the stdio era lock.
 */
public final class ProtocolCore {
    public static final String VERSION = "2026-07-28";
    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> TOP_LEVEL = Set.of("jsonrpc", "id", "method", "params");
    // "task" is the pre-SEP-2663 task hint; it is tolerated and ignored, since task creation is server-directed.
    private static final Set<String> CALL_PARAMS = Set.of("name", "arguments", "_meta", "task");

    private final ToolCatalog catalog;

    public ProtocolCore(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    /** One connection's protocol era. A stdio connection is locked to the era of its first accepted message. */
    public static final class Connection {
        private final String transport;
        private final String owner = "connection:" + java.util.UUID.randomUUID();
        private String era;

        public Connection(String transport) {
            this.transport = transport;
        }

        synchronized void lock(String version) {
            if (era == null) era = version;
            else if (!era.equals(version)) {
                throw new ProtocolError(ProtocolError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                        unsupported(version));
            }
        }

        synchronized String era() {
            return era;
        }
    }

    /** Handles one parsed message; returns the response, or null for notifications and client responses. */
    public JsonNode handle(JsonNode message, Connection connection) {
        if (message == null || !message.isObject()) return error(null, new ProtocolError(ProtocolError.INVALID_REQUEST, "Invalid request"));
        if (!message.has("method")) return null; // a response from the client; the core sends no requests
        if (!message.has("id")) return null; // notifications have no reply; the core defines none
        JsonNode id = message.get("id");
        try {
            validate(message);
            String method = message.get("method").asText();
            JsonNode params = message.get("params");
            JsonNode meta = params.path("_meta");
            JsonNode version = meta.get(META_VERSION);
            if (version == null) {
                if (method.equals("initialize")) {
                    String requested = params.path("protocolVersion").asText("unknown");
                    // Refused on a 2026-07-28 connection by the era lock, and on a fresh one until the 2025-11-25
                    // session path through the official SDK arrives with the legacy adapter.
                    throw new ProtocolError(ProtocolError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", unsupported(requested));
                }
                throw new ProtocolError(ProtocolError.INVALID_PARAMS, "_meta " + META_VERSION + " is required");
            }
            if (!version.isTextual() || !version.asText().equals(VERSION)) {
                throw new ProtocolError(ProtocolError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                        unsupported(version.isTextual() ? version.asText() : version.toString()));
            }
            JsonNode capabilities = meta.get(META_CAPABILITIES);
            if (capabilities == null || !capabilities.isObject()) {
                throw new ProtocolError(ProtocolError.INVALID_PARAMS, "_meta " + META_CAPABILITIES + " is required");
            }
            connection.lock(VERSION);
            RequestMeta request = new RequestMeta(VERSION, capabilities, extensions(capabilities), connection.transport, connection.owner);
            ObjectNode result = switch (method) {
                case "server/discover" -> discover();
                case "tools/list" -> list(request);
                case "tools/call" -> call(params, request);
                case "tasks/get", "tasks/update", "tasks/cancel" -> task(method, params, request);
                default -> throw new ProtocolError(ProtocolError.METHOD_NOT_FOUND, "Method not found");
            };
            if (!result.has("resultType")) result.put("resultType", "complete");
            ObjectNode resultMeta = result.has("_meta") ? (ObjectNode) result.get("_meta") : result.putObject("_meta");
            resultMeta.set(META_SERVER_INFO, catalog.serverInfo());
            ObjectNode response = JSON.objectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (ProtocolError e) {
            return error(validId(id) ? id : null, e);
        } catch (RuntimeException e) {
            return error(validId(id) ? id : null, new ProtocolError(ProtocolError.INTERNAL_ERROR, "Internal error"));
        }
    }

    private static void validate(JsonNode message) {
        Iterator<String> names = message.fieldNames();
        while (names.hasNext()) {
            if (!TOP_LEVEL.contains(names.next())) throw new ProtocolError(ProtocolError.INVALID_REQUEST, "Invalid request");
        }
        if (!"2.0".equals(message.path("jsonrpc").textValue()) || !validId(message.get("id"))
                || !message.get("method").isTextual() || !message.path("params").isObject()) {
            throw new ProtocolError(ProtocolError.INVALID_REQUEST, "Invalid request");
        }
    }

    private static boolean validId(JsonNode id) {
        return id != null && ((id.isTextual() && id.asText().length() <= 128) || id.isIntegralNumber());
    }

    private static Set<String> extensions(JsonNode capabilities) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode ext = capabilities.path("extensions");
        if (ext.isObject()) ext.fieldNames().forEachRemaining(out::add);
        return Set.copyOf(out);
    }

    static ObjectNode unsupported(String requested) {
        ObjectNode data = JSON.objectNode();
        data.putArray("supported").add(VERSION);
        if (requested == null) data.putNull("requested");
        else data.put("requested", requested);
        return data;
    }

    private ObjectNode discover() {
        ObjectNode result = JSON.objectNode();
        result.putArray("supportedVersions").add(VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        if (catalog.tasks() != null) capabilities.putObject("extensions").putObject(RequestMeta.TASKS);
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");
        result.putObject("_meta").setAll(catalog.discoverMeta());
        return result;
    }

    private ObjectNode list(RequestMeta request) {
        ObjectNode result = JSON.objectNode();
        result.set("tools", catalog.tools(request));
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");
        return result;
    }

    private ObjectNode call(JsonNode params, RequestMeta request) {
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            if (!CALL_PARAMS.contains(names.next())) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Invalid parameters");
        }
        JsonNode name = params.get("name");
        JsonNode arguments = params.get("arguments");
        if (name == null || !name.isTextual() || (arguments != null && !arguments.isObject())) {
            throw new ProtocolError(ProtocolError.INVALID_PARAMS, "Invalid parameters");
        }
        return catalog.call(name.asText(), arguments == null ? JSON.objectNode() : arguments, request);
    }

    private ObjectNode task(String method, JsonNode params, RequestMeta request) {
        Tasks tasks = catalog.tasks();
        if (tasks == null) throw new ProtocolError(ProtocolError.METHOD_NOT_FOUND, "Method not found");
        // The capability gate comes before any lookup, so an undeclared request learns nothing about task ids.
        if (!request.declares(RequestMeta.TASKS)) throw ProtocolError.missingExtension(RequestMeta.TASKS);
        return switch (method) {
            case "tasks/get" -> tasks.get(params, request);
            case "tasks/update" -> tasks.update(params, request);
            default -> tasks.cancel(params, request);
        };
    }

    /** A JSON-RPC error response. */
    public static ObjectNode error(JsonNode id, ProtocolError e) {
        ObjectNode response = JSON.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? JSON.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", e.code());
        error.put("message", e.getMessage());
        if (e.data() != null) error.set("data", e.data());
        return response;
    }
}
