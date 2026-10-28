// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A route call a tool made in test mode, as the mode-equivalence suite compares them: route id and variables first,
 * then the rest of the request. {@code auth} names the route's auth; credentials are never resolved in test mode.
 */
public record RouteCallRecord(String route, String method, Map<String, String> variables, Map<String, List<String>> query,
        Map<String, String> headers, String auth, JsonNode body, boolean answered) {

    static RouteCallRecord of(JsonNode request, boolean answered) {
        Map<String, String> variables = new LinkedHashMap<>();
        request.path("variables").properties().forEach(e -> variables.put(e.getKey(), e.getValue().asText()));
        Map<String, List<String>> query = new LinkedHashMap<>();
        request.path("query").properties().forEach(e -> {
            List<String> values = new java.util.ArrayList<>();
            e.getValue().forEach(v -> values.add(v.asText()));
            query.put(e.getKey(), List.copyOf(values));
        });
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        request.path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));
        JsonNode body = request.has("body") ? request.get("body") : request.has("bodyText") ? request.get("bodyText")
                : request.get("bodyBase64");
        return new RouteCallRecord(request.path("route").asText(), request.path("method").asText(), Map.copyOf(variables),
                Map.copyOf(query), headers, request.path("auth").isTextual() ? request.get("auth").asText() : null, body, answered);
    }
}
