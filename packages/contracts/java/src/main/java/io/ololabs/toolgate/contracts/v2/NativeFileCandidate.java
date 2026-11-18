// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current installed file-only candidate; metadata is not execution authority.
 *
 * @param deviceId canonical deviceId value
 * @param toolId canonical toolId value
 * @param managedDigest canonical managedDigest value
 * @param imageDigest canonical imageDigest value
 * @param writeAccess canonical writeAccess value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileCandidate(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "managedDigest", required = true) String managedDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "imageDigest", required = true) String imageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "writeAccess", required = true) Boolean writeAccess
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param toolId canonical toolId value
     * @param managedDigest canonical managedDigest value
     * @param imageDigest canonical imageDigest value
     * @param writeAccess canonical writeAccess value
     */
    public NativeFileCandidate {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(managedDigest, "managedDigest");
        java.util.Objects.requireNonNull(imageDigest, "imageDigest");
        java.util.Objects.requireNonNull(writeAccess, "writeAccess");
    }
}
