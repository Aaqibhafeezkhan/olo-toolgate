// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool Host or device consumes the single-use nonce; returns the fencing token.
 *
 * @param invocationId canonical invocationId value
 * @param permit canonical permit value
 * @param argumentsDigest canonical argumentsDigest value
 * @param toolDescriptorDigest canonical toolDescriptorDigest value
 * @param packageDigest canonical packageDigest value
 * @param artifactDigest canonical artifactDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PermitConsumption(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) SignedInvocationPermit permit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argumentsDigest", required = true) String argumentsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolDescriptorDigest", required = true) String toolDescriptorDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactDigest", required = true) String artifactDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param permit canonical permit value
     * @param argumentsDigest canonical argumentsDigest value
     * @param toolDescriptorDigest canonical toolDescriptorDigest value
     * @param packageDigest canonical packageDigest value
     * @param artifactDigest canonical artifactDigest value
     */
    public PermitConsumption {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(permit, "permit");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        java.util.Objects.requireNonNull(toolDescriptorDigest, "toolDescriptorDigest");
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(artifactDigest, "artifactDigest");
    }
}
