// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Persisted outcome of the latest profile application. state is READY only when every required package is READY.
 *
 * @param profileId canonical profileId value
 * @param profileDigest canonical profileDigest value
 * @param generation canonical generation value
 * @param state canonical state value
 * @param packages canonical packages value
 * @param appliedAtUnixMs canonical appliedAtUnixMs value
 * @param previousProfileDigest canonical previousProfileDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProvisioningStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileId", required = true) String profileId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "generation", required = true) Long generation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) ProvisioningState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packages", required = true) java.util.List<ProvisioningPackageStatus> packages,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "appliedAtUnixMs", required = true) Long appliedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previousProfileDigest", required = false) String previousProfileDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileId canonical profileId value
     * @param profileDigest canonical profileDigest value
     * @param generation canonical generation value
     * @param state canonical state value
     * @param packages canonical packages value
     * @param appliedAtUnixMs canonical appliedAtUnixMs value
     * @param previousProfileDigest canonical previousProfileDigest value
     */
    public ProvisioningStatus {
        java.util.Objects.requireNonNull(profileId, "profileId");
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(generation, "generation");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(packages, "packages");
        packages = packages == null ? null : java.util.List.copyOf(packages);
        java.util.Objects.requireNonNull(appliedAtUnixMs, "appliedAtUnixMs");
    }
}
