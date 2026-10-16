// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One installed export to enable, in the reviewed Tool Group named here. The export must exist in the pinned package; nothing is created to satisfy a reference.
 *
 * @param toolId canonical toolId value
 * @param toolGroupId canonical toolGroupId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ProfileToolActivation(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroupId", required = true) String toolGroupId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param toolGroupId canonical toolGroupId value
     */
    public ProfileToolActivation {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(toolGroupId, "toolGroupId");
    }
}
