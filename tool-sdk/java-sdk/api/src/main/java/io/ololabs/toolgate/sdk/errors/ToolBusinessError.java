// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.errors;

import java.util.Map;

/** A business outcome with a code declared in the tool's errors. */
public class ToolBusinessError extends ToolError {
    private static final long serialVersionUID = 1L;
    private final transient Map<String, Object> data;

    public ToolBusinessError(String code, String message) { this(code, message, Map.of()); }

    public ToolBusinessError(String code, String message, Map<String, Object> data) {
        super(code, message, null);
        this.data = Map.copyOf(data);
    }

    public Map<String, Object> data() { return data; }
}
