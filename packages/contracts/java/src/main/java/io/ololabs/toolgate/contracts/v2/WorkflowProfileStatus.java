// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness.
 *
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param state canonical state value
 * @param generation canonical generation value
 * @param dependenciesReady canonical dependenciesReady value
 * @param reviewedBy canonical reviewedBy value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowProfileStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) WorkflowProfileState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dependenciesReady", required = true) Boolean dependenciesReady,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewedBy", required = false) String reviewedBy
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param state canonical state value
     * @param generation canonical generation value
     * @param dependenciesReady canonical dependenciesReady value
     * @param reviewedBy canonical reviewedBy value
     */
    public WorkflowProfileStatus {
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(dependenciesReady, "dependenciesReady");
    }
}
