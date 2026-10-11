// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Progress frame.
 *
 * @param kind canonical kind value
 * @param fraction canonical fraction value
 * @param message canonical message value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProgressFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fraction", required = true) Double fraction,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "message", required = false) String message
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param fraction canonical fraction value
     * @param message canonical message value
     */
    public ProgressFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "PROGRESS")) throw new IllegalArgumentException("kind must be PROGRESS");
        java.util.Objects.requireNonNull(fraction, "fraction");
    }
}
