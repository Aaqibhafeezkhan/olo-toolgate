// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A registered upstream MCP server of the routed tenant.
 *
 * @param slug canonical slug value
 * @param url canonical url value
 * @param auth canonical auth value
 * @param bindingId canonical bindingId value
 * @param strict canonical strict value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param createdAtUnixMs canonical createdAtUnixMs value
 * @param latestSnapshotRevision canonical latestSnapshotRevision value
 * @param approvalSequence canonical approvalSequence value
 * @param verification canonical verification value
 * @param quarantine canonical quarantine value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpServerRegistration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slug", required = true) String slug,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "url", required = true) String url,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "auth", required = true) McpUpstreamAuth auth,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bindingId", required = true) String bindingId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "strict", required = true) Boolean strict,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) McpServerState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "createdAtUnixMs", required = true) Long createdAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "latestSnapshotRevision", required = false) Long latestSnapshotRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalSequence", required = false) Long approvalSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "verification", required = true) McpVerification verification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "quarantine", required = false) McpQuarantine quarantine
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param slug canonical slug value
     * @param url canonical url value
     * @param auth canonical auth value
     * @param bindingId canonical bindingId value
     * @param strict canonical strict value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param createdAtUnixMs canonical createdAtUnixMs value
     * @param latestSnapshotRevision canonical latestSnapshotRevision value
     * @param approvalSequence canonical approvalSequence value
     * @param verification canonical verification value
     * @param quarantine canonical quarantine value
     */
    public McpServerRegistration {
        java.util.Objects.requireNonNull(slug, "slug");
        java.util.Objects.requireNonNull(url, "url");
        java.util.Objects.requireNonNull(auth, "auth");
        java.util.Objects.requireNonNull(bindingId, "bindingId");
        java.util.Objects.requireNonNull(strict, "strict");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(createdAtUnixMs, "createdAtUnixMs");
        java.util.Objects.requireNonNull(verification, "verification");
    }
}
