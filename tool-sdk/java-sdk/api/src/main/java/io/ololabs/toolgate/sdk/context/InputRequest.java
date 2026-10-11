// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.context;

import java.util.Map;

/**
 * A question for the caller: an MRTR round in sync calls, {@code input_required} in tasks.
 *
 * @param <T> the answer type
 */
public final class InputRequest<T> {
    private final String message;
    private final Map<String, Object> requestedSchema;
    private final Class<T> answerType;

    private InputRequest(String message, Map<String, Object> requestedSchema, Class<T> answerType) {
        this.message = message;
        this.requestedSchema = Map.copyOf(requestedSchema);
        this.answerType = answerType;
    }

    public static <T> InputRequest<T> of(String message, Map<String, Object> requestedSchema, Class<T> answerType) {
        return new InputRequest<>(message, requestedSchema, answerType);
    }

    public String message() { return message; }
    public Map<String, Object> requestedSchema() { return requestedSchema; }
    public Class<T> answerType() { return answerType; }
}
