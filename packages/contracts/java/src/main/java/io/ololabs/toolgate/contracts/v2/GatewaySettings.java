// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Gateway settings.
 *
 * @param gatewayName canonical gatewayName value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record GatewaySettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gatewayName", required = true) String gatewayName
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param gatewayName canonical gatewayName value
     */
    public GatewaySettings {
        java.util.Objects.requireNonNull(gatewayName, "gatewayName");
    }
}
