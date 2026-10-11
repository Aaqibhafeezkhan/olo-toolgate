// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Supersedes kills after two-person review. Issuer is always control.
 *
 * @param eventId canonical eventId value
 * @param issuer canonical issuer value
 * @param supersedes canonical supersedes value
 * @param reviewers canonical reviewers value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param signature canonical signature value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RecoveryEvent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventId", required = true) String eventId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuer", required = true) String issuer,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "supersedes", required = true) java.util.List<String> supersedes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reviewers", required = true) java.util.List<String> reviewers,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "signature", required = true) String signature
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param eventId canonical eventId value
     * @param issuer canonical issuer value
     * @param supersedes canonical supersedes value
     * @param reviewers canonical reviewers value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param signature canonical signature value
     */
    public RecoveryEvent {
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(issuer, "issuer");
        if (issuer != null && !java.util.Objects.equals(issuer, "control")) throw new IllegalArgumentException("issuer must be control");
        java.util.Objects.requireNonNull(supersedes, "supersedes");
        supersedes = supersedes == null ? null : java.util.List.copyOf(supersedes);
        java.util.Objects.requireNonNull(reviewers, "reviewers");
        reviewers = reviewers == null ? null : java.util.List.copyOf(reviewers);
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(signature, "signature");
    }
}
