// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Individual identity binding is authentication only; permissions derive from Agent Groups.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param enabled canonical enabled value
 * @param revision canonical revision value
 * @param agentId canonical agentId value
 * @param mode canonical mode value
 * @param issuer canonical issuer value
 * @param subject canonical subject value
 * @param audience canonical audience value
 * @param credentialSha256 canonical credentialSha256 value
 * @param credentialEpoch canonical credentialEpoch value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param delegatedUserId canonical delegatedUserId value
 * @param parentBindingId canonical parentBindingId value
 * @param delegatedSessionEpoch canonical delegatedSessionEpoch value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlWorkloadBinding(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) io.ololabs.toolgate.contracts.v2.RequestMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuer", required = true) String issuer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subject", required = true) String subject,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "audience", required = true) String audience,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialSha256", required = true) String credentialSha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialEpoch", required = true) Long credentialEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegatedUserId", required = false) String delegatedUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "parentBindingId", required = false) String parentBindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "delegatedSessionEpoch", required = false) Long delegatedSessionEpoch
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     * @param agentId canonical agentId value
     * @param mode canonical mode value
     * @param issuer canonical issuer value
     * @param subject canonical subject value
     * @param audience canonical audience value
     * @param credentialSha256 canonical credentialSha256 value
     * @param credentialEpoch canonical credentialEpoch value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param delegatedUserId canonical delegatedUserId value
     * @param parentBindingId canonical parentBindingId value
     * @param delegatedSessionEpoch canonical delegatedSessionEpoch value
     */
    public ControlWorkloadBinding {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(agentId, "agentId");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(issuer, "issuer");
        java.util.Objects.requireNonNull(subject, "subject");
        java.util.Objects.requireNonNull(audience, "audience");
        java.util.Objects.requireNonNull(credentialSha256, "credentialSha256");
        java.util.Objects.requireNonNull(credentialEpoch, "credentialEpoch");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
