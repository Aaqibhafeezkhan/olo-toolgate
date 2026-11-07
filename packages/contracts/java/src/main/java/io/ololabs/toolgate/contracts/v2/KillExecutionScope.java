// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Immutable per-attempt Control facts returned only after online permit consumption. Absent facts are unknown and match scoped kills conservatively; empty pool/image means known not applicable.
 *
 * @param packageId canonical packageId value
 * @param packageDigest canonical packageDigest value
 * @param publisher canonical publisher value
 * @param toolId canonical toolId value
 * @param poolId canonical poolId value
 * @param imageDigest canonical imageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillExecutionScope(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageId", required = false) String packageId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "publisher", required = false) String publisher,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "poolId", required = false) String poolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "imageDigest", required = false) String imageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageId canonical packageId value
     * @param packageDigest canonical packageDigest value
     * @param publisher canonical publisher value
     * @param toolId canonical toolId value
     * @param poolId canonical poolId value
     * @param imageDigest canonical imageDigest value
     */
    public KillExecutionScope {
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
        java.util.Objects.requireNonNull(toolId, "toolId");
    }
}
