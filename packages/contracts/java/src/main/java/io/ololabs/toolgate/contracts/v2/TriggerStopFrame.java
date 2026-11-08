// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Graceful stop.
 *
 * @param kind canonical kind value
 * @param reason canonical reason value
 * @param graceMs canonical graceMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStopFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "graceMs", required = true) Long graceMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param reason canonical reason value
     * @param graceMs canonical graceMs value
     */
    public TriggerStopFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "STOP")) throw new IllegalArgumentException("kind must be STOP");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(graceMs, "graceMs");
    }
}
