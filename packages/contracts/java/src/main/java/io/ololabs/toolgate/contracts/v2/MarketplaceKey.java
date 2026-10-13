// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A Marketplace release signing key Control trusts, by id and SHA-256 fingerprint of its raw Ed25519 public key.
 *
 * @param keyId canonical keyId value
 * @param fingerprint canonical fingerprint value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record MarketplaceKey(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keyId", required = true) String keyId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fingerprint", required = true) String fingerprint
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param keyId canonical keyId value
     * @param fingerprint canonical fingerprint value
     */
    public MarketplaceKey {
        java.util.Objects.requireNonNull(keyId, "keyId");
        java.util.Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
