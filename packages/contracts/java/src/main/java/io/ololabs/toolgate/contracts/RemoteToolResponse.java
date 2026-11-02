// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Authorized queued executor report/result. operationOutcome separately records the Control-derived business outcome; reported DONE alone does not prove completion.
 *
 * @param record canonical record value
 * @param result canonical result value
 * @param operationOutcome canonical operationOutcome value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RemoteToolResponse(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "record", required = true) RemoteToolRecord record,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) RemoteToolResult result,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operationOutcome", required = false) EnterpriseOperationOutcome operationOutcome
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param record canonical record value
     * @param result canonical result value
     * @param operationOutcome canonical operationOutcome value
     */
    public RemoteToolResponse {
        java.util.Objects.requireNonNull(record, "record");
    }
    /** Construct stored/legacy execution state without a derived response projection. */
    public RemoteToolResponse(RemoteToolRecord record, RemoteToolResult result) { this(record, result, null); }
}
