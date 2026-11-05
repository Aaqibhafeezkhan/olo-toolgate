// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Direct-mTLS owner request for the single AUTHORIZED to SENDING transition before a bound OLO write. A repeated request never permits another send.
 *
 * @param permit canonical permit value
 * @param brokerInstanceId canonical brokerInstanceId value
 * @param handlerId canonical handlerId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitDispatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) ServicePermit permit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "brokerInstanceId", required = true) String brokerInstanceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handlerId", required = true) String handlerId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param permit canonical permit value
     * @param brokerInstanceId canonical brokerInstanceId value
     * @param handlerId canonical handlerId value
     */
    public ServicePermitDispatch {
        java.util.Objects.requireNonNull(permit, "permit");
        java.util.Objects.requireNonNull(brokerInstanceId, "brokerInstanceId");
        java.util.Objects.requireNonNull(handlerId, "handlerId");
    }
}
