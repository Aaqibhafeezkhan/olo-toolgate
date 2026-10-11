// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Enable exactly the reviewed digest with this binding.
 *
 * @param packageDigest canonical packageDigest value
 * @param binding canonical binding value
 * @param acknowledgedSecurityDelta canonical acknowledgedSecurityDelta value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageEnableRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "binding", required = true) DeploymentBinding binding,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acknowledgedSecurityDelta", required = true) Boolean acknowledgedSecurityDelta
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageDigest canonical packageDigest value
     * @param binding canonical binding value
     * @param acknowledgedSecurityDelta canonical acknowledgedSecurityDelta value
     */
    public PackageEnableRequest {
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(binding, "binding");
        java.util.Objects.requireNonNull(acknowledgedSecurityDelta, "acknowledgedSecurityDelta");
    }
}
