// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control-owned manual-run user and admitted resource ceiling; current grants and identity must still be checked. Never accepted from an external caller.
 *
 * @param userId canonical userId value
 * @param principalId canonical principalId value
 * @param identityDigest canonical identityDigest value
 * @param humanScopes canonical humanScopes value
 * @param secretScopes canonical secretScopes value
 * @param agentScopes canonical agentScopes value
 * @param agentSecretScopes canonical agentSecretScopes value
 * @param requiredScopes canonical requiredScopes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowUserAuthority(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = true) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "principalId", required = true) String principalId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "identityDigest", required = true) String identityDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "humanScopes", required = true) java.util.List<io.ololabs.toolgate.contracts.EnterpriseScope> humanScopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secretScopes", required = true) java.util.List<io.ololabs.toolgate.contracts.EnterpriseScope> secretScopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentScopes", required = true) java.util.List<io.ololabs.toolgate.contracts.EnterpriseScope> agentScopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentSecretScopes", required = true) java.util.List<io.ololabs.toolgate.contracts.EnterpriseScope> agentSecretScopes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requiredScopes", required = true) java.util.List<io.ololabs.toolgate.contracts.EnterpriseScope> requiredScopes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param userId canonical userId value
     * @param principalId canonical principalId value
     * @param identityDigest canonical identityDigest value
     * @param humanScopes canonical humanScopes value
     * @param secretScopes canonical secretScopes value
     * @param agentScopes canonical agentScopes value
     * @param agentSecretScopes canonical agentSecretScopes value
     * @param requiredScopes canonical requiredScopes value
     */
    public WorkflowUserAuthority {
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(principalId, "principalId");
        java.util.Objects.requireNonNull(identityDigest, "identityDigest");
        java.util.Objects.requireNonNull(humanScopes, "humanScopes");
        humanScopes = humanScopes == null ? null : java.util.List.copyOf(humanScopes);
        java.util.Objects.requireNonNull(secretScopes, "secretScopes");
        secretScopes = secretScopes == null ? null : java.util.List.copyOf(secretScopes);
        java.util.Objects.requireNonNull(agentScopes, "agentScopes");
        agentScopes = agentScopes == null ? null : java.util.List.copyOf(agentScopes);
        java.util.Objects.requireNonNull(agentSecretScopes, "agentSecretScopes");
        agentSecretScopes = agentSecretScopes == null ? null : java.util.List.copyOf(agentSecretScopes);
        java.util.Objects.requireNonNull(requiredScopes, "requiredScopes");
        requiredScopes = requiredScopes == null ? null : java.util.List.copyOf(requiredScopes);
    }
}
