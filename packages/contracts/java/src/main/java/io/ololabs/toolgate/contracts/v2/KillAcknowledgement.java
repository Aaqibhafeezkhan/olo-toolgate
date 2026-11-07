// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Acknowledgement from a socket-authenticated component; identity is never caller supplied.
 *
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param deliveryPath canonical deliveryPath value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillAcknowledgement(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deliveryPath", required = true) KillDeliveryPath deliveryPath
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param deliveryPath canonical deliveryPath value
     */
    public KillAcknowledgement {
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(deliveryPath, "deliveryPath");
    }
}
