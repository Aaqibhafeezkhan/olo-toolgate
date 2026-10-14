// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Installs a new static token hash; delegated bindings are rebound to their user's current session epoch.
 *
 * @param credentialSha256 canonical credentialSha256 value
 * @param enable canonical enable value
 * @param bindingId canonical bindingId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlWorkloadCredentialRotation(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "credentialSha256", required = true) String credentialSha256,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enable", required = true) Boolean enable,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = false) String bindingId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param credentialSha256 canonical credentialSha256 value
     * @param enable canonical enable value
     * @param bindingId canonical bindingId value
     */
    public ControlWorkloadCredentialRotation {
        java.util.Objects.requireNonNull(credentialSha256, "credentialSha256");
        java.util.Objects.requireNonNull(enable, "enable");
    }
}
