// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Pinned package build and fenced worker lease; artifacts confer no runtime authority.
 *
 * @param id canonical id value
 * @param draftId canonical draftId value
 * @param draftRevision canonical draftRevision value
 * @param toolGroupId canonical toolGroupId value
 * @param source canonical source value
 * @param sourceDigest canonical sourceDigest value
 * @param creatorUserId canonical creatorUserId value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param leaseId canonical leaseId value
 * @param artifactDigest canonical artifactDigest value
 * @param sourceArchiveDigest canonical sourceArchiveDigest value
 * @param packageDigest canonical packageDigest value
 * @param error canonical error value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderBuild(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "draftId", required = true) String draftId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "draftRevision", required = true) Long draftRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolGroupId", required = true) String toolGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceDigest", required = true) String sourceDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "creatorUserId", required = true) String creatorUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) SdkBuilderState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "leaseId", required = true) String leaseId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "artifactDigest", required = false) String artifactDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceArchiveDigest", required = false) String sourceArchiveDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = false) String packageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = false) SdkBuilderError error
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param draftId canonical draftId value
     * @param draftRevision canonical draftRevision value
     * @param toolGroupId canonical toolGroupId value
     * @param source canonical source value
     * @param sourceDigest canonical sourceDigest value
     * @param creatorUserId canonical creatorUserId value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param leaseId canonical leaseId value
     * @param artifactDigest canonical artifactDigest value
     * @param sourceArchiveDigest canonical sourceArchiveDigest value
     * @param packageDigest canonical packageDigest value
     * @param error canonical error value
     */
    public SdkBuilderBuild {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(draftId, "draftId");
        java.util.Objects.requireNonNull(draftRevision, "draftRevision");
        java.util.Objects.requireNonNull(toolGroupId, "toolGroupId");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(sourceDigest, "sourceDigest");
        java.util.Objects.requireNonNull(creatorUserId, "creatorUserId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(leaseId, "leaseId");
    }
}
