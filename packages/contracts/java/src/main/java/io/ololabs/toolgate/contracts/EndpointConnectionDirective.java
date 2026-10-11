// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Server-authoritative response to a device presence ping; presence never grants tool access.
 *
 * @param deviceId canonical deviceId value
 * @param state canonical state value
 * @param action canonical action value
 * @param reportSequence canonical reportSequence value
 * @param nextIntervalMs canonical nextIntervalMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointConnectionDirective(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EndpointState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) EndpointConnectionAction action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reportSequence", required = true) Long reportSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextIntervalMs", required = true) Long nextIntervalMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param state canonical state value
     * @param action canonical action value
     * @param reportSequence canonical reportSequence value
     * @param nextIntervalMs canonical nextIntervalMs value
     */
    public EndpointConnectionDirective {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(reportSequence, "reportSequence");
        java.util.Objects.requireNonNull(nextIntervalMs, "nextIntervalMs");
    }
}
