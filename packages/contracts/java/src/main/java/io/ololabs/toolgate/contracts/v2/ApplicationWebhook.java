// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Caller-owned registration metadata; no signing key or user token is exposed.
 *
 * @param id canonical id value
 * @param sourceType canonical sourceType value
 * @param sourceId canonical sourceId value
 * @param eventKinds canonical eventKinds value
 * @param callbackUrl canonical callbackUrl value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param state canonical state value
 * @param nextCursor canonical nextCursor value
 * @param attempts canonical attempts value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhook(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceType", required = true) ApplicationEventSource sourceType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceId", required = true) String sourceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventKinds", required = true) java.util.List<ApplicationEventKind> eventKinds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "callbackUrl", required = true) String callbackUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) ApplicationWebhookState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextCursor", required = true) Long nextCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) Long attempts
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param sourceType canonical sourceType value
     * @param sourceId canonical sourceId value
     * @param eventKinds canonical eventKinds value
     * @param callbackUrl canonical callbackUrl value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param state canonical state value
     * @param nextCursor canonical nextCursor value
     * @param attempts canonical attempts value
     */
    public ApplicationWebhook {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(sourceType, "sourceType");
        java.util.Objects.requireNonNull(sourceId, "sourceId");
        java.util.Objects.requireNonNull(eventKinds, "eventKinds");
        eventKinds = eventKinds == null ? null : java.util.List.copyOf(eventKinds);
        java.util.Objects.requireNonNull(callbackUrl, "callbackUrl");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(nextCursor, "nextCursor");
        java.util.Objects.requireNonNull(attempts, "attempts");
    }
}
