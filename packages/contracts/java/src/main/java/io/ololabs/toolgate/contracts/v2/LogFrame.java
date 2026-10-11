// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Redacted, size-capped log line.
 *
 * @param kind canonical kind value
 * @param level canonical level value
 * @param message canonical message value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LogFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "level", required = true) LogLevel level,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = true) String message
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param level canonical level value
     * @param message canonical message value
     */
    public LogFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "LOG")) throw new IllegalArgumentException("kind must be LOG");
        java.util.Objects.requireNonNull(level, "level");
        java.util.Objects.requireNonNull(message, "message");
    }
}
