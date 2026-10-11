// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/** A JSON-RPC error. Raised for protocol failures only; handler errors are tool results. */
public final class ProtocolError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int HEADER_MISMATCH = -32020;
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private final int code;
    private final transient JsonNode data;

    public ProtocolError(int code, String message, JsonNode data) {
        super(message, null, false, false);
        this.code = code;
        this.data = data;
    }

    public ProtocolError(int code, String message) {
        this(code, message, null);
    }

    /** -32021 naming one required extension, with the {@code requiredCapabilities} data the schema requires. */
    public static ProtocolError missingExtension(String extension) {
        com.fasterxml.jackson.databind.node.ObjectNode data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        data.putObject("requiredCapabilities").putObject("extensions").putObject(extension);
        return new ProtocolError(MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability", data);
    }

    public int code() { return code; }
    public JsonNode data() { return data; }
}
