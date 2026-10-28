// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Whether this ToolGate reads an OLO Orgs Marketplace. The id, origin and keys are environment-owned settings, absent when disabled.
 *
 * @param enabled canonical enabled value
 * @param marketplaceId canonical marketplaceId value
 * @param baseUrl canonical baseUrl value
 * @param keys canonical keys value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record MarketplaceStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "marketplaceId", required = false) String marketplaceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "baseUrl", required = false) String baseUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keys", required = false) java.util.List<MarketplaceKey> keys
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param enabled canonical enabled value
     * @param marketplaceId canonical marketplaceId value
     * @param baseUrl canonical baseUrl value
     * @param keys canonical keys value
     */
    public MarketplaceStatus {
        java.util.Objects.requireNonNull(enabled, "enabled");
        keys = keys == null ? null : java.util.List.copyOf(keys);
    }
}
