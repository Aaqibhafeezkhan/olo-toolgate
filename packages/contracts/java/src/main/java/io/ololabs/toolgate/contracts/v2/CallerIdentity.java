// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authenticated caller, never from arguments.
 *
 * @param agentId canonical agentId value
 * @param userId canonical userId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CallerIdentity(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = true) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = false) String userId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param agentId canonical agentId value
     * @param userId canonical userId value
     */
    public CallerIdentity {
        java.util.Objects.requireNonNull(agentId, "agentId");
    }
}
