// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Caller-owned HTTPS notification subscription. Source ID is a run ID or trigger assignment name. Verification and current source permissions are required before delivery.
 *
 * @param sourceType canonical sourceType value
 * @param sourceId canonical sourceId value
 * @param eventKinds canonical eventKinds value
 * @param callbackUrl canonical callbackUrl value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param after canonical after value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationWebhookCreate(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceType", required = true) ApplicationEventSource sourceType,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceId", required = true) String sourceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "eventKinds", required = true) java.util.List<ApplicationEventKind> eventKinds,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "callbackUrl", required = true) String callbackUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "after", required = true) Long after
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sourceType canonical sourceType value
     * @param sourceId canonical sourceId value
     * @param eventKinds canonical eventKinds value
     * @param callbackUrl canonical callbackUrl value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param after canonical after value
     */
    public ApplicationWebhookCreate {
        java.util.Objects.requireNonNull(sourceType, "sourceType");
        java.util.Objects.requireNonNull(sourceId, "sourceId");
        java.util.Objects.requireNonNull(eventKinds, "eventKinds");
        eventKinds = eventKinds == null ? null : java.util.List.copyOf(eventKinds);
        java.util.Objects.requireNonNull(callbackUrl, "callbackUrl");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(after, "after");
    }
}
