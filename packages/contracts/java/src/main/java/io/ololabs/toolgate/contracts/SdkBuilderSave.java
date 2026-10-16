// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Save a canonical SDK source revision in an authorized Tool Group.
 *
 * @param id canonical id value
 * @param expectedRevision canonical expectedRevision value
 * @param toolGroupId canonical toolGroupId value
 * @param source canonical source value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderSave(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedRevision", required = true) Long expectedRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroupId", required = true) String toolGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param expectedRevision canonical expectedRevision value
     * @param toolGroupId canonical toolGroupId value
     * @param source canonical source value
     */
    public SdkBuilderSave {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
        java.util.Objects.requireNonNull(toolGroupId, "toolGroupId");
        java.util.Objects.requireNonNull(source, "source");
    }
}
