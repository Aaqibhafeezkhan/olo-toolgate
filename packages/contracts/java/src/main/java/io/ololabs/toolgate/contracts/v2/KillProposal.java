// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Immutable request and append-only reviews; no execution authority.
 *
 * @param id canonical id value
 * @param requesterUserId canonical requesterUserId value
 * @param kind canonical kind value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param authorizationEpoch canonical authorizationEpoch value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param scope canonical scope value
 * @param mode canonical mode value
 * @param reason canonical reason value
 * @param supersedes canonical supersedes value
 * @param reviews canonical reviews value
 * @param eventId canonical eventId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillProposal(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requesterUserId", required = true) String requesterUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) KillProposalKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) KillProposalState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authorizationEpoch", required = true) Long authorizationEpoch,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = false) KillScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = false) KillMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "supersedes", required = true) java.util.List<String> supersedes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviews", required = true) java.util.List<KillReview> reviews,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = false) String eventId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param requesterUserId canonical requesterUserId value
     * @param kind canonical kind value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param authorizationEpoch canonical authorizationEpoch value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param scope canonical scope value
     * @param mode canonical mode value
     * @param reason canonical reason value
     * @param supersedes canonical supersedes value
     * @param reviews canonical reviews value
     * @param eventId canonical eventId value
     */
    public KillProposal {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(requesterUserId, "requesterUserId");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(authorizationEpoch, "authorizationEpoch");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(supersedes, "supersedes");
        supersedes = supersedes == null ? null : java.util.List.copyOf(supersedes);
        java.util.Objects.requireNonNull(reviews, "reviews");
        reviews = reviews == null ? null : java.util.List.copyOf(reviews);
    }
}
