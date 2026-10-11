// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool asks for input (MRTR or tasks/update).
 *
 * @param kind canonical kind value
 * @param requestId canonical requestId value
 * @param request canonical request value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InputRequestFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "request", required = true) java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> request
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param requestId canonical requestId value
     * @param request canonical request value
     */
    public InputRequestFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "INPUT_REQUEST")) throw new IllegalArgumentException("kind must be INPUT_REQUEST");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(request, "request");
        request = request == null ? null : java.util.Map.copyOf(request);
    }
}
