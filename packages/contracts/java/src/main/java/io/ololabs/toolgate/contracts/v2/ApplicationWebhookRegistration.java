// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Registration response, including a private per-subscription signing secret. Exact authenticated retries may recover the same secret; it is encrypted at rest and absent from list/read responses.
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
 * @param signingSecret canonical signingSecret value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhookRegistration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceType", required = true) ApplicationEventSource sourceType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceId", required = true) String sourceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventKinds", required = true) java.util.List<ApplicationEventKind> eventKinds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "callbackUrl", required = true) String callbackUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) ApplicationWebhookState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextCursor", required = true) Long nextCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attempts", required = true) Long attempts,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "signingSecret", required = true) String signingSecret
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
     * @param signingSecret canonical signingSecret value
     */
    public ApplicationWebhookRegistration {
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
        java.util.Objects.requireNonNull(signingSecret, "signingSecret");
    }
}
