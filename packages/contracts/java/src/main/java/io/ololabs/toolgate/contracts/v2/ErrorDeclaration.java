// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared business error.
 *
 * @param code canonical code value
 * @param description canonical description value
 * @param retryable canonical retryable value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ErrorDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "code", required = true) String code,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "description", required = true) String description,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retryable", required = true) Boolean retryable
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param code canonical code value
     * @param description canonical description value
     * @param retryable canonical retryable value
     */
    public ErrorDeclaration {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(description, "description");
        java.util.Objects.requireNonNull(retryable, "retryable");
    }
}
