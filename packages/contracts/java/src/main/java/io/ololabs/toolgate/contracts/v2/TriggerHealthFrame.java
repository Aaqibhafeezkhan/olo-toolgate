// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** From reportHealth.
 *
 * @param kind canonical kind value
 * @param status canonical status value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerHealthFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) TriggerHealthStatus status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param status canonical status value
     * @param reason canonical reason value
     */
    public TriggerHealthFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "HEALTH")) throw new IllegalArgumentException("kind must be HEALTH");
        java.util.Objects.requireNonNull(status, "status");
    }
}
