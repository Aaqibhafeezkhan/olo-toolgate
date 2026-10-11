// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Review and enable page data: both diffs and the values to fill.
 *
 * @param packageId canonical packageId value
 * @param version canonical version value
 * @param packageDigest canonical packageDigest value
 * @param previousPackageDigest canonical previousPackageDigest value
 * @param apiChange canonical apiChange value
 * @param securityDelta canonical securityDelta value
 * @param bindingDraft canonical bindingDraft value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageReview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = true) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "previousPackageDigest", required = false) String previousPackageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "apiChange", required = true) ApiChange apiChange,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "securityDelta", required = true) java.util.List<SecurityDeltaItem> securityDelta,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingDraft", required = true) DeploymentBinding bindingDraft
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param version canonical version value
     * @param packageDigest canonical packageDigest value
     * @param previousPackageDigest canonical previousPackageDigest value
     * @param apiChange canonical apiChange value
     * @param securityDelta canonical securityDelta value
     * @param bindingDraft canonical bindingDraft value
     */
    public PackageReview {
        java.util.Objects.requireNonNull(packageId, "packageId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(apiChange, "apiChange");
        java.util.Objects.requireNonNull(securityDelta, "securityDelta");
        securityDelta = securityDelta == null ? null : java.util.List.copyOf(securityDelta);
        java.util.Objects.requireNonNull(bindingDraft, "bindingDraft");
    }
}
