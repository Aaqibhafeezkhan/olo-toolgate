// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tenant device CA. keyId is SHA-256 of certificate DER; its subject binds the same tenant UUID. It cannot reuse a discovery signing key.
 *
 * @param keyId canonical keyId value
 * @param state canonical state value
 * @param certificatePem canonical certificatePem value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DiscoveryDeviceAuthority(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keyId", required = true) String keyId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) DiscoveryAuthorityState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "certificatePem", required = true) String certificatePem
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param keyId canonical keyId value
     * @param state canonical state value
     * @param certificatePem canonical certificatePem value
     */
    public DiscoveryDeviceAuthority {
        java.util.Objects.requireNonNull(keyId, "keyId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(certificatePem, "certificatePem");
    }
}
