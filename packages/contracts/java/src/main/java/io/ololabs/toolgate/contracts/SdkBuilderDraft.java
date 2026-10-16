// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Canonical JSON SDK source project at an optimistic revision; no executable registration.
 *
 * @param id canonical id value
 * @param revision canonical revision value
 * @param toolGroupId canonical toolGroupId value
 * @param source canonical source value
 * @param sourceDigest canonical sourceDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderDraft(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroupId", required = true) String toolGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceDigest", required = true) String sourceDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param revision canonical revision value
     * @param toolGroupId canonical toolGroupId value
     * @param source canonical source value
     * @param sourceDigest canonical sourceDigest value
     */
    public SdkBuilderDraft {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(toolGroupId, "toolGroupId");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(sourceDigest, "sourceDigest");
    }
}
