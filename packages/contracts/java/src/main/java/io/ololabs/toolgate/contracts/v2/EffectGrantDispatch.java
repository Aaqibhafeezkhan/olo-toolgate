// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One owner may claim the SENDING transition once.
 *
 * @param grant canonical grant value
 * @param brokerInstanceId canonical brokerInstanceId value
 * @param handlerId canonical handlerId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectGrantDispatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grant", required = true) SignedEffectGrant grant,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerInstanceId", required = true) String brokerInstanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handlerId", required = true) String handlerId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param grant canonical grant value
     * @param brokerInstanceId canonical brokerInstanceId value
     * @param handlerId canonical handlerId value
     */
    public EffectGrantDispatch {
        java.util.Objects.requireNonNull(grant, "grant");
        java.util.Objects.requireNonNull(brokerInstanceId, "brokerInstanceId");
        java.util.Objects.requireNonNull(handlerId, "handlerId");
    }
}
