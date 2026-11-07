// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** An error on the stream, with the status and code an HTTP call would get.
 *
 * @param status canonical status value
 * @param code canonical code value
 * @param detail canonical detail value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerStreamError(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "status", required = true) Long status,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "code", required = true) String code,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "detail", required = false) String detail
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param status canonical status value
     * @param code canonical code value
     * @param detail canonical detail value
     */
    public TriggerStreamError {
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(code, "code");
    }
}
