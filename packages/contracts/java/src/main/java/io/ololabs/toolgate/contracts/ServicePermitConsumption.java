// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Service compares these request facts before its write, then consumes the nonce online.
 *
 * @param permit canonical permit value
 * @param operation canonical operation value
 * @param resources canonical resources value
 * @param requestFingerprint canonical requestFingerprint value
 * @param executorKeySha256 canonical executorKeySha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServicePermitConsumption(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permit", required = true) ServicePermit permit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) String operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = true) java.util.List<ResourceDescriptor> resources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestFingerprint", required = true) String requestFingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorKeySha256", required = true) String executorKeySha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param permit canonical permit value
     * @param operation canonical operation value
     * @param resources canonical resources value
     * @param requestFingerprint canonical requestFingerprint value
     * @param executorKeySha256 canonical executorKeySha256 value
     */
    public ServicePermitConsumption {
        java.util.Objects.requireNonNull(permit, "permit");
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(resources, "resources");
        resources = resources == null ? null : java.util.List.copyOf(resources);
        java.util.Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        java.util.Objects.requireNonNull(executorKeySha256, "executorKeySha256");
    }
}
