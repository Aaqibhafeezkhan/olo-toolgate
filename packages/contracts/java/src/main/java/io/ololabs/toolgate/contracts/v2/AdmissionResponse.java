// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool Host to Gateway, sent before any sandbox starts.
 *
 * @param kind canonical kind value
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AdmissionResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) AdmissionKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = false) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) RefusalReason reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param reason canonical reason value
     */
    public AdmissionResponse {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
    }
}
