// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Current target lookup response. Terminal evidence must match the stored key/body and include a stable business receipt and result digest; unknown/pending never authorize execution. FAILED means a durable target witness that the effect was not applied; an ordinary failed HTTP/business response is insufficient.
 *
 * @param key canonical key value
 * @param state canonical state value
 * @param bodyDigest canonical bodyDigest value
 * @param businessReceipt canonical businessReceipt value
 * @param resultDigest canonical resultDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TargetEffectStatusReply(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "key", required = true) String key,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) TargetEffectStatus state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bodyDigest", required = false) String bodyDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessReceipt", required = false) String businessReceipt,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resultDigest", required = false) String resultDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param key canonical key value
     * @param state canonical state value
     * @param bodyDigest canonical bodyDigest value
     * @param businessReceipt canonical businessReceipt value
     * @param resultDigest canonical resultDigest value
     */
    public TargetEffectStatusReply {
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(state, "state");
    }
}
