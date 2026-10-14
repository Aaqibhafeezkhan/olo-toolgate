// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Error that names what the caller must resolve; IDs only, never payloads.
 *
 * @param error canonical error value
 * @param ids canonical ids value
 * @param requestId canonical requestId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlErrorDetail(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "error", required = true) ControlErrorDetailCode error,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ids", required = false) java.util.List<String> ids,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = false) String requestId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param error canonical error value
     * @param ids canonical ids value
     * @param requestId canonical requestId value
     */
    public ControlErrorDetail {
        java.util.Objects.requireNonNull(error, "error");
        ids = ids == null ? null : java.util.List.copyOf(ids);
    }
}
