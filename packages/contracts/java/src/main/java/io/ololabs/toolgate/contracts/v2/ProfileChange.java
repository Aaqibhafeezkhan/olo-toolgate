// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One package difference from the previously applied profile, recorded for review.
 *
 * @param packageId canonical packageId value
 * @param change canonical change value
 * @param previousVersion canonical previousVersion value
 * @param previousPackageDigest canonical previousPackageDigest value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProfileChange(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "change", required = true) ProfileChangeKind change,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previousVersion", required = false) String previousVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previousPackageDigest", required = false) String previousPackageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = false) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param change canonical change value
     * @param previousVersion canonical previousVersion value
     * @param previousPackageDigest canonical previousPackageDigest value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     */
    public ProfileChange {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(change, "change");
    }
}
