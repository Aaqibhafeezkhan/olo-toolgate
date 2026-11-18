// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current tenant-scoped publication blockers, without credentials or remote payloads. Observational only; publication rechecks readiness.
 *
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param blockers canonical blockers value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowProfileDiagnostics(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "blockers", required = true) java.util.List<String> blockers
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param blockers canonical blockers value
     */
    public WorkflowProfileDiagnostics {
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(blockers, "blockers");
        blockers = blockers == null ? null : java.util.List.copyOf(blockers);
    }
}
