// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Deployment-time package provisioning for one profile (plan.md §7). Applied by Control at deployment; removing an entry never uninstalls a package.
 *
 * @param profileVersion canonical profileVersion value
 * @param profileId canonical profileId value
 * @param packages canonical packages value
 * @param readiness canonical readiness value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeploymentProfile(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileVersion", required = true) Long profileVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packages", required = true) java.util.List<ProfilePackage> packages,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "readiness", required = false) java.util.List<ProfileReadinessCase> readiness
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileVersion canonical profileVersion value
     * @param profileId canonical profileId value
     * @param packages canonical packages value
     * @param readiness canonical readiness value
     */
    public DeploymentProfile {
        java.util.Objects.requireNonNull(profileVersion, "profileVersion");
        if (profileVersion != null && !java.util.Objects.equals(profileVersion, 1L)) throw new IllegalArgumentException("profileVersion must be 1");
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(packages, "packages");
        packages = packages == null ? null : java.util.List.copyOf(packages);
        readiness = readiness == null ? null : java.util.List.copyOf(readiness);
    }
}
