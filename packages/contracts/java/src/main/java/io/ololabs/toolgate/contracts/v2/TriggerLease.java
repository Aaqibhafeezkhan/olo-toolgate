// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Run lease for one instance (plan 7). It authorizes running and reporting only.
 *
 * @param instanceId canonical instanceId value
 * @param leaseEpoch canonical leaseEpoch value
 * @param leaseToken canonical leaseToken value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerLease(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instanceId", required = true) String instanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseEpoch", required = true) Long leaseEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseToken", required = true) String leaseToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param instanceId canonical instanceId value
     * @param leaseEpoch canonical leaseEpoch value
     * @param leaseToken canonical leaseToken value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public TriggerLease {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(leaseEpoch, "leaseEpoch");
        java.util.Objects.requireNonNull(leaseToken, "leaseToken");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
