// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Signed tenant discovery v2. Exact HTTPS origins, configured/pinned UUID, lifetime, key continuity, CA purposes and active issuer are verified before enrollment or local identity access.
 *
 * @param protocolVersion canonical protocolVersion value
 * @param tenantId canonical tenantId value
 * @param issuer canonical issuer value
 * @param audiences canonical audiences value
 * @param serverId canonical serverId value
 * @param organization canonical organization value
 * @param controlUrl canonical controlUrl value
 * @param gatewayUrl canonical gatewayUrl value
 * @param verificationUri canonical verificationUri value
 * @param minimumClientVersion canonical minimumClientVersion value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param keySetVersion canonical keySetVersion value
 * @param discoveryKeys canonical discoveryKeys value
 * @param deviceIssuerKeyId canonical deviceIssuerKeyId value
 * @param deviceAuthorities canonical deviceAuthorities value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TenantDiscovery(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocolVersion", required = true) Long protocolVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuer", required = true) String issuer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "audiences", required = true) java.util.List<String> audiences,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "serverId", required = true) String serverId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "organization", required = true) String organization,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "controlUrl", required = true) String controlUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gatewayUrl", required = true) String gatewayUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "verificationUri", required = true) String verificationUri,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minimumClientVersion", required = true) String minimumClientVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keySetVersion", required = true) Long keySetVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "discoveryKeys", required = true) DiscoveryKeySet discoveryKeys,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceIssuerKeyId", required = true) String deviceIssuerKeyId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceAuthorities", required = true) java.util.List<DiscoveryDeviceAuthority> deviceAuthorities
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param protocolVersion canonical protocolVersion value
     * @param tenantId canonical tenantId value
     * @param issuer canonical issuer value
     * @param audiences canonical audiences value
     * @param serverId canonical serverId value
     * @param organization canonical organization value
     * @param controlUrl canonical controlUrl value
     * @param gatewayUrl canonical gatewayUrl value
     * @param verificationUri canonical verificationUri value
     * @param minimumClientVersion canonical minimumClientVersion value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param keySetVersion canonical keySetVersion value
     * @param discoveryKeys canonical discoveryKeys value
     * @param deviceIssuerKeyId canonical deviceIssuerKeyId value
     * @param deviceAuthorities canonical deviceAuthorities value
     */
    public TenantDiscovery {
        java.util.Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (protocolVersion != null && !java.util.Objects.equals(protocolVersion, 2L)) throw new IllegalArgumentException("protocolVersion must be 2");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(issuer, "issuer");
        java.util.Objects.requireNonNull(audiences, "audiences");
        audiences = audiences == null ? null : java.util.List.copyOf(audiences);
        java.util.Objects.requireNonNull(serverId, "serverId");
        java.util.Objects.requireNonNull(organization, "organization");
        java.util.Objects.requireNonNull(controlUrl, "controlUrl");
        java.util.Objects.requireNonNull(gatewayUrl, "gatewayUrl");
        java.util.Objects.requireNonNull(verificationUri, "verificationUri");
        java.util.Objects.requireNonNull(minimumClientVersion, "minimumClientVersion");
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(keySetVersion, "keySetVersion");
        java.util.Objects.requireNonNull(discoveryKeys, "discoveryKeys");
        java.util.Objects.requireNonNull(deviceIssuerKeyId, "deviceIssuerKeyId");
        java.util.Objects.requireNonNull(deviceAuthorities, "deviceAuthorities");
        deviceAuthorities = deviceAuthorities == null ? null : java.util.List.copyOf(deviceAuthorities);
    }
}
