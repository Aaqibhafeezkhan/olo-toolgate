// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import com.fasterxml.jackson.databind.JsonNode;

/** The result of one call through {@link ToolHarness}: the MCP {@code CallToolResult}, with typed views of it. */
public final class ToolResult {
    private final JsonNode result;

    ToolResult(JsonNode result) {
        this.result = result;
    }

    /** The {@code CallToolResult} exactly as an MCP client gets it. */
    public JsonNode json() {
        return result;
    }

    public boolean isError() {
        return result.path("isError").asBoolean();
    }

    /** The structured output; fails when the call returned an error. */
    public JsonNode output() {
        if (isError()) throw new AssertionError("the call failed with " + errorCode() + ": " + errorMessage());
        return result.path("structuredContent");
    }

    /** The structured output as {@code type}. */
    public <T> T output(Class<T> type) {
        return RecordedExchange.JSON.convertValue(output(), type);
    }

    /** The ToolGate error object; fails when the call succeeded. */
    public JsonNode error() {
        if (!isError()) throw new AssertionError("the call succeeded: " + result.path("structuredContent"));
        return result.path("structuredContent").path("error");
    }

    public String errorCode() {
        return error().path("code").asText();
    }

    public String errorMessage() {
        return error().path("message").asText();
    }

    @Override
    public String toString() {
        return result.toString();
    }
}
