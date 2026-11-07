// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current registered consumer and its latest authenticated acknowledgement, if any.
 *
 * @param kind canonical kind value
 * @param componentId canonical componentId value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param acknowledgedAtUnixMs canonical acknowledgedAtUnixMs value
 * @param deliveryPath canonical deliveryPath value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillComponentDelivery(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) KillComponentKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "componentId", required = true) String componentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = false) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acknowledgedAtUnixMs", required = false) Long acknowledgedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deliveryPath", required = false) KillDeliveryPath deliveryPath
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param componentId canonical componentId value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param acknowledgedAtUnixMs canonical acknowledgedAtUnixMs value
     * @param deliveryPath canonical deliveryPath value
     */
    public KillComponentDelivery {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(componentId, "componentId");
    }
}
