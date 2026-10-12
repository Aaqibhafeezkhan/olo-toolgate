// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.marketplace.helper;

import java.util.Map;

final class ApiDiffTest {
    private ApiDiffTest() {
    }

    /** A manifest with one tool per {@code name=inputSchema=outputSchema} triple. */
    private static Map<String, Object> manifest(String... tools) {
        StringBuilder json = new StringBuilder("{\"id\":\"acme.x\",\"tools\":[");
        for (int i = 0; i < tools.length; i += 3) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"descriptor\":{\"toolId\":\"acme.x/").append(tools[i]).append("\",\"inputSchema\":")
                    .append(tools[i + 1]).append(",\"outputSchema\":").append(tools[i + 2]).append("}}");
        }
        return Json.obj(Json.parse(json.append("]}").toString()));
    }

    private static final String IN = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\"]}";
    private static final String OUT = "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"string\"}}}";

    private static String diff(Map<String, Object> before, Map<String, Object> after) {
        return ApiDiff.between(before, after).apiChange();
    }

    public static void testIdenticalIsNone() {
        Check.equal("none", diff(manifest("t", IN, OUT), manifest("t", IN, OUT)));
    }

    public static void testToolAddedIsCompatibleAndRemovedIsBreaking() {
        Check.equal("compatible", diff(manifest("t", IN, OUT), manifest("t", IN, OUT, "u", IN, OUT)));
        Check.equal("breaking", diff(manifest("t", IN, OUT, "u", IN, OUT), manifest("t", IN, OUT)));
    }

    public static void testInputChanges() {
        String optionalAdded = IN.replace("\"b\":{\"type\":\"integer\"}", "\"b\":{\"type\":\"integer\"},\"c\":{\"type\":\"string\"}");
        Check.equal("compatible", diff(manifest("t", IN, OUT), manifest("t", optionalAdded, OUT)));
        String requiredAdded = optionalAdded.replace("[\"a\"]", "[\"a\",\"c\"]");
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", requiredAdded, OUT)));
        String nowRequired = IN.replace("[\"a\"]", "[\"a\",\"b\"]");
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", nowRequired, OUT)));
        String removed = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}";
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", removed, OUT)));
        String narrowed = IN.replace("\"b\":{\"type\":\"integer\"}", "\"b\":{\"type\":\"integer\",\"enum\":[1,2]}");
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", narrowed, OUT)));
        String widened = IN.replace("\"b\":{\"type\":\"integer\"}", "\"b\":{\"type\":\"number\"}");
        Check.equal("compatible", diff(manifest("t", IN, OUT), manifest("t", widened, OUT)));
        String typeNarrowed = IN.replace("\"b\":{\"type\":\"integer\"}", "\"b\":{\"type\":\"boolean\"}");
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", typeNarrowed, OUT)));
    }

    public static void testOutputChanges() {
        String added = "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"string\"},\"s\":{\"type\":\"string\"}}}";
        Check.equal("compatible", diff(manifest("t", IN, OUT), manifest("t", IN, added)));
        Check.equal("breaking", diff(manifest("t", IN, added), manifest("t", IN, OUT)));
        String retyped = "{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"integer\"}}}";
        Check.equal("breaking", diff(manifest("t", IN, OUT), manifest("t", IN, retyped)));
    }
}
