// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One recorded route exchange (spec/authoring/testing.md): what a route call must look like, and the reply it gets in
 * test mode. A request names the route id and, optionally, the method, variables, query, headers and body it must
 * have; every field it names must match.
 *
 * <pre>{@code
 * RecordedExchange.on("orders.get").variable("orderId", "123").replyJson(200, Map.of("id", "123"))
 * }</pre>
 */
public final class RecordedExchange {
    /** An injected transport failure, as the mode-equivalence suite names them (runtime-modes.md §3). */
    public enum Failure { CONNECTION_REFUSED, RESET_AFTER_SEND, TIMEOUT_AFTER_SEND }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REQUEST_FIELDS = Set.of("route", "method", "variables", "query", "headers", "body", "bodyText", "bodyBase64");
    private static final Set<String> RESPONSE_FIELDS = Set.of("status", "headers", "body", "bodyText", "bodyBase64", "failure");

    private final ObjectNode request;
    private final ObjectNode response;

    private RecordedExchange(ObjectNode request, ObjectNode response) {
        this.request = request;
        this.response = response;
    }

    /** An exchange on route {@code routeId} that replies 200 with no body until a reply is set. */
    public static RecordedExchange on(String routeId) {
        ObjectNode request = JSON.createObjectNode().put("route", routeId);
        return new RecordedExchange(request, JSON.createObjectNode().put("status", 200));
    }

    public RecordedExchange method(String method) {
        request.put("method", method);
        return this;
    }

    public RecordedExchange variable(String name, Object value) {
        request.withObjectProperty("variables").put(name, String.valueOf(value));
        return this;
    }

    public RecordedExchange query(String name, Object... values) {
        for (Object v : values) request.withObjectProperty("query").withArrayProperty(name).add(String.valueOf(v));
        return this;
    }

    /** A header the call must send. Other headers are not compared. */
    public RecordedExchange header(String name, String value) {
        request.withObjectProperty("headers").put(name, value);
        return this;
    }

    /** The JSON body the call must send. */
    public RecordedExchange body(Object value) {
        request.set("body", JSON.valueToTree(value));
        return this;
    }

    public RecordedExchange reply(int status) {
        response.removeAll();
        response.put("status", status);
        return this;
    }

    public RecordedExchange replyJson(int status, Object body) {
        reply(status);
        response.set("body", JSON.valueToTree(body));
        return this;
    }

    public RecordedExchange replyText(int status, String body) {
        reply(status);
        response.put("bodyText", body);
        return this;
    }

    public RecordedExchange replyHeader(String name, String value) {
        response.withObjectProperty("headers").put(name, value);
        return this;
    }

    public RecordedExchange fail(Failure failure) {
        response.removeAll();
        response.put("failure", failure.name());
        return this;
    }

    public String route() {
        return request.path("route").asText();
    }

    /** The recorded form: {@code {"request": {...}, "response": {...}}}. */
    public ObjectNode toJson() {
        ObjectNode o = JSON.createObjectNode();
        o.set("request", request.deepCopy());
        o.set("response", response.deepCopy());
        return o;
    }

    /** Reads recorded exchanges: a JSON array of {@code {"request": {...}, "response": {...}}}. */
    public static List<RecordedExchange> load(InputStream in) {
        JsonNode root;
        try {
            root = JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("recorded exchanges are not JSON", e);
        }
        if (root == null || !root.isArray()) throw new IllegalArgumentException("recorded exchanges are a JSON array");
        List<RecordedExchange> out = new ArrayList<>();
        for (JsonNode e : root) {
            JsonNode request = e.path("request");
            JsonNode response = e.path("response");
            if (!request.isObject() || !request.path("route").isTextual()) {
                throw new IllegalArgumentException("a recorded exchange needs request.route");
            }
            if (!response.isObject()) throw new IllegalArgumentException("recorded exchange on " + request.get("route").asText() + " needs a response");
            known(request, REQUEST_FIELDS, "request");
            known(response, RESPONSE_FIELDS, "response");
            if (response.has("failure")) Failure.valueOf(response.get("failure").asText());
            out.add(new RecordedExchange((ObjectNode) request.deepCopy(), (ObjectNode) response.deepCopy()));
        }
        return out;
    }

    private static void known(JsonNode node, Set<String> fields, String what) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!fields.contains(n)) throw new IllegalArgumentException("a recorded " + what + " has no field " + n);
        }
    }

    /** Whether {@code call}, in the runtime's request form, is this exchange's request. */
    boolean matches(JsonNode call) {
        for (Map.Entry<String, JsonNode> f : request.properties()) {
            JsonNode actual = call.get(f.getKey());
            if (f.getKey().equals("headers")) {
                for (Map.Entry<String, JsonNode> h : f.getValue().properties()) {
                    if (!h.getValue().equals(header(actual, h.getKey()))) return false;
                }
            } else if (!f.getValue().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode header(JsonNode headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, JsonNode> h : headers.properties()) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    ObjectNode response() {
        return response.deepCopy();
    }
}
