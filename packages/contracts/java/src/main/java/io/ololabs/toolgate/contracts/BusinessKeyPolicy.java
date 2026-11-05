// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Reviewed admission key policy. Missing policy retains conservative legacy explicit-key behavior.
 *
 * @param required canonical required value
 * @param namespace canonical namespace value
 * @param argument canonical argument value
 * @param dedupeWindowMs canonical dedupeWindowMs value
 * @param retentionMs canonical retentionMs value
 * @param upstreamIdempotencyWindowMs canonical upstreamIdempotencyWindowMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BusinessKeyPolicy(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "namespace", required = true) BusinessKeyNamespace namespace,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argument", required = false) String argument,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dedupeWindowMs", required = false) Long dedupeWindowMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retentionMs", required = false) Long retentionMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "upstreamIdempotencyWindowMs", required = false) Long upstreamIdempotencyWindowMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param required canonical required value
     * @param namespace canonical namespace value
     * @param argument canonical argument value
     * @param dedupeWindowMs canonical dedupeWindowMs value
     * @param retentionMs canonical retentionMs value
     * @param upstreamIdempotencyWindowMs canonical upstreamIdempotencyWindowMs value
     */
    public BusinessKeyPolicy {
        java.util.Objects.requireNonNull(required, "required");
        java.util.Objects.requireNonNull(namespace, "namespace");
    }
}
