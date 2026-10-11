// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Whether this request declared the MCP Tasks extension, and the owner digest a task would be bound to (invocation-state-v2.md §7).
 *
 * @param declared canonical declared value
 * @param ownerDigest canonical ownerDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskCapability(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "declared", required = true) Boolean declared,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ownerDigest", required = true) String ownerDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param declared canonical declared value
     * @param ownerDigest canonical ownerDigest value
     */
    public TaskCapability {
        java.util.Objects.requireNonNull(declared, "declared");
        java.util.Objects.requireNonNull(ownerDigest, "ownerDigest");
    }
}
