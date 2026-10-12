// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonTest {
    private JsonTest() {
    }

    public static void testRoundTrip() {
        Object value = Json.parse("{\"a\":[1,2.5,true,null,\"x\\n\\u00e9\"],\"b\":{}}");
        Check.equal("{\"a\":[1,2.5,true,null,\"x\\né\"],\"b\":{}}", Json.write(value));
    }

    public static void testCanonicalSortsByUtf16AndEscapesMinimally() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", "line\nbreak \u0001 \"q\" é €");
        value.put("a", 1L);
        value.put("€", true);
        value.put("Z", List.of());
        String canonical = new String(Json.canonical(value), StandardCharsets.UTF_8);
        Check.equal("{\"Z\":[],\"a\":1,\"b\":\"line\\nbreak \\u0001 \\\"q\\\" é €\",\"€\":true}", canonical);
    }

    public static void testRejectsDuplicatesTrailingDataAndDeepNesting() {
        Check.fails(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        Check.fails(IllegalArgumentException.class, () -> Json.parse("{} x"));
        Check.fails(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
        Check.fails(IllegalArgumentException.class, () -> Json.parse("01"));
    }

    public static void testCanonicalRefusesFractions() {
        Check.fails(IllegalArgumentException.class, () -> Json.canonical(Json.parse("[1.5]")));
    }
}
