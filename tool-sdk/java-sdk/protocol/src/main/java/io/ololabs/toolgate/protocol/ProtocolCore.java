// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Adapter A1: the MCP 2026-07-28 server core (tool-sdk/spec/protocol-layer.md §3). Stateless per request; the only
 * connection state is the stdio era lock and the connection's {@code subscriptions/listen} streams.
 */
public final class ProtocolCore {
    public static final String VERSION = "2026-07-28";
    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    static final String META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> TOP_LEVEL = Set.of("jsonrpc", "id", "method", "params");

    private final ToolCatalog catalog;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public ProtocolCore(ToolCatalog catalog) {
        this.catalog = catalog;
        Tasks tasks = catalog.tasks();
        if (tasks != null) tasks.onChange(this::taskChanged);
    }

    /** One connection. A stdio connection is locked to the era of its first accepted message; HTTP requests are stateless. */
    public static final class Connection {
        private final String transport;
        private final String owner;
        private String era;

        /** A stdio connection: its own owner and era lock. */
        public Connection(String transport) {
            this(transport, "connection:" + java.util.UUID.randomUUID());
        }

        /** A connection whose requests share {@code owner}, such as every unauthenticated request to one HTTP endpoint. */
        public Connection(String transport, String owner) {
            this.transport = transport;
            this.owner = owner;
        }

        public String transport() { return transport; }

        synchronized void lock(String version) {
            if (!transport.equals("stdio")) return;
            if (era == null) era = version;
            else if (!era.equals(version)) {
                throw new ProtocolError(ProtocolError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", unsupported(version));
            }
        }
    }

    /** A live {@code subscriptions/listen} stream. */
    private record Subscription(Connection connection, JsonNode id, Consumer<ObjectNode> sink, boolean tools, boolean tasks, String owner) {}

    /** Handles one parsed stdio message: no headers, notifications go to {@code notify}. */
    public JsonNode handle(JsonNode message, Connection connection) {
        return handle(message, connection, null, n -> {});
    }

    /**
     * Handles one parsed message; returns the response, or null for notifications, client responses and an accepted
     * {@code subscriptions/listen}, whose frames go to {@code notify} until {@link #unsubscribe} or a failed write.
     *
     * @param headers HTTP request header lookup (case-insensitive), or null over stdio
     */
    public JsonNode handle(JsonNode message, Connection connection, Function<String, String> headers, Consumer<ObjectNode> notify) {
        if (message == null || !message.isObject()) return error(null, new ProtocolError(ProtocolError.INVALID_REQUEST, "Invalid request"));
        if (!message.has("method")) return null; // a response from the client; the core sends no requests
        if (!message.has("id")) {
            if ("notifications/cancelled".equals(message.path("method").asText())) {
                unsubscribe(connection, message.path("params").path("requestId"));
            }
            return null;
        }
        JsonNode id = message.get("id");
        try {
            validate(message);
            String method = message.get("method").asText();
            JsonNode params = message.get("params");
            if (headers != null && headers.apply("MCP-Protocol-Version") == null) {
                throw new ProtocolError(ProtocolError.HEADER_MISMATCH, "Missing MCP-Protocol-Version header");
            }
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
            JsonNode capabilities = meta.get(META_CAPABILITIES);
            if (capabilities == null || !capabilities.isObject()) {
                throw new ProtocolError(ProtocolError.INVALID_PARAMS, "_meta " + META_CAPABILITIES + " is required");
            }
            String requested = version.isTextual() ? version.asText() : version.toString();
            if (headers != null && !requested.equals(headers.apply("MCP-Protocol-Version").trim())) {
                throw new ProtocolError(ProtocolError.HEADER_MISMATCH, "MCP-Protocol-Version does not match _meta");
            }
            if (!requested.equals(VERSION)) {
                throw new ProtocolError(ProtocolError.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", unsupported(requested));
            }
            if (headers != null) checkRoutingHeaders(method, params, headers);
            connection.lock(VERSION);
            RequestMeta request = new RequestMeta(VERSION, capabilities, extensions(capabilities), connection.transport, connection.owner,
                    meta.get("progressToken"), notify);
            ObjectNode result = switch (method) {
                case "server/discover" -> discover();
                case "tools/list" -> list(request);
                case "tools/call" -> call(params, request);
                case "tasks/get", "tasks/update", "tasks/cancel" -> task(method, params, request);
                case "subscriptions/listen" -> {
                    listen(id, params, request, connection, notify);
                    yield null;
                }
                default -> throw new ProtocolError(ProtocolError.METHOD_NOT_FOUND, "Method not found");
            };
            if (result == null) return null;
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

    /** The HTTP status for a JSON-RPC error code (Streamable HTTP, 2026-07-28). */
    public static int httpStatus(int code) {
        return switch (code) {
            case ProtocolError.METHOD_NOT_FOUND -> 404;
            case ProtocolError.INTERNAL_ERROR -> 500;
            default -> 400;
        };
    }

    /** Sends {@code notifications/tools/list_changed} to every stream that asked for it. */
    public void toolsListChanged() {
        for (Subscription s : subscriptions.values()) {
            if (s.tools) send(s, "notifications/tools/list_changed", JSON.objectNode());
        }
    }

    /** Ends a listen stream (client abort, write failure or {@code notifications/cancelled}). */
    public void unsubscribe(Connection connection, JsonNode id) {
        if (id != null && !id.isMissingNode()) subscriptions.remove(key(connection, id));
    }

    /** Whether a listen stream is still open. */
    public boolean subscribed(Connection connection, JsonNode id) {
        return subscriptions.containsKey(key(connection, id));
    }

    private static void checkRoutingHeaders(String method, JsonNode params, Function<String, String> headers) {
        String header = headers.apply("Mcp-Method");
        if (header == null || !header.trim().equals(method)) throw new ProtocolError(ProtocolError.HEADER_MISMATCH, "Mcp-Method does not match the request");
        String nameKey = switch (method) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            case "tasks/get", "tasks/update", "tasks/cancel" -> "taskId";
            default -> null;
        };
        if (nameKey == null) return;
        String name = headers.apply("Mcp-Name");
        if (name == null || !name.trim().equals(params.path(nameKey).asText())) {
            throw new ProtocolError(ProtocolError.HEADER_MISMATCH, "Mcp-Name does not match the request");
        }
    }

    private void listen(JsonNode id, JsonNode params, RequestMeta request, Connection connection, Consumer<ObjectNode> sink) {
        JsonNode filter = params.path("notifications");
        if (!filter.isMissingNode() && !filter.isObject()) throw new ProtocolError(ProtocolError.INVALID_PARAMS, "notifications must be an object");
        boolean tools = filter.path("toolsListChanged").asBoolean(false);
        boolean tasks = catalog.tasks() != null && request.declares(RequestMeta.TASKS);
        Subscription s = new Subscription(connection, id, sink, tools, tasks, request.owner());
        subscriptions.put(key(connection, id), s);
        ObjectNode honoured = JSON.objectNode();
        if (tools) honoured.put("toolsListChanged", true);
        ObjectNode ack = JSON.objectNode();
        ack.set("notifications", honoured);
        send(s, "notifications/subscriptions/acknowledged", ack);
    }

    private void taskChanged(TaskRecord t) {
        for (Subscription s : subscriptions.values()) {
            if (!s.tasks || !s.owner.equals(t.owner)) continue;
            ObjectNode params = JSON.objectNode();
            params.put("taskId", t.taskId);
            params.put("status", t.status.wire());
            if (t.statusMessage != null) params.put("statusMessage", t.statusMessage);
            send(s, "notifications/tasks", params);
        }
    }

    private void send(Subscription s, String method, ObjectNode params) {
        params.putObject("_meta").set(META_SUBSCRIPTION, s.id);
        ObjectNode frame = JSON.objectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("method", method);
        frame.set("params", params);
        try {
            s.sink.accept(frame);
        } catch (RuntimeException e) {
            subscriptions.remove(key(s.connection, s.id));
        }
    }

    private static String key(Connection connection, JsonNode id) {
        return System.identityHashCode(connection) + ":" + connection.owner + ":" + id;
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
        capabilities.putObject("tools").put("listChanged", true);
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
        // Unrecognised members, such as the pre-SEP-2663 "task" hint, are ignored (SEP-2322); task creation is server-directed.
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
