// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Bounded tenant discovery JWK Set. Application validation requires unique key IDs and moduli.
 *
 * @param keys canonical keys value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DiscoveryKeySet(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keys", required = true) java.util.List<DiscoveryPublicKey> keys
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param keys canonical keys value
     */
    public DiscoveryKeySet {
        java.util.Objects.requireNonNull(keys, "keys");
        keys = keys == null ? null : java.util.List.copyOf(keys);
    }
}
