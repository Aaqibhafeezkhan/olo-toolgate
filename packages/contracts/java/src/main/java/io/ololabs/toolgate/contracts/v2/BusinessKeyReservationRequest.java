// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** reserveBusinessOperation input, carried on POST /access/invocations.
 *
 * @param toolId canonical toolId value
 * @param namespace canonical namespace value
 * @param namespaceIdentity canonical namespaceIdentity value
 * @param businessKeyHash canonical businessKeyHash value
 * @param source canonical source value
 * @param fingerprint canonical fingerprint value
 * @param caller canonical caller value
 * @param dedupeWindowMs canonical dedupeWindowMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BusinessKeyReservationRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "namespace", required = true) KeyNamespace namespace,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "namespaceIdentity", required = false) String namespaceIdentity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "businessKeyHash", required = true) String businessKeyHash,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) BusinessKeySource source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fingerprint", required = true) String fingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "caller", required = true) CallerIdentity caller,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "dedupeWindowMs", required = false) Long dedupeWindowMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param namespace canonical namespace value
     * @param namespaceIdentity canonical namespaceIdentity value
     * @param businessKeyHash canonical businessKeyHash value
     * @param source canonical source value
     * @param fingerprint canonical fingerprint value
     * @param caller canonical caller value
     * @param dedupeWindowMs canonical dedupeWindowMs value
     */
    public BusinessKeyReservationRequest {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(namespace, "namespace");
        java.util.Objects.requireNonNull(businessKeyHash, "businessKeyHash");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(fingerprint, "fingerprint");
        java.util.Objects.requireNonNull(caller, "caller");
    }
}
