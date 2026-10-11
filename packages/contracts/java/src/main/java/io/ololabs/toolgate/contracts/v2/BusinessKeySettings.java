// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Business-key settings.
 *
 * @param required canonical required value
 * @param namespace canonical namespace value
 * @param argument canonical argument value
 * @param idempotencyHeader canonical idempotencyHeader value
 * @param idempotencyScope canonical idempotencyScope value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BusinessKeySettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "required", required = true) Boolean required,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "namespace", required = true) KeyNamespace namespace,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argument", required = false) String argument,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "idempotencyHeader", required = false) String idempotencyHeader,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "idempotencyScope", required = false) IdempotencyScope idempotencyScope
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param required canonical required value
     * @param namespace canonical namespace value
     * @param argument canonical argument value
     * @param idempotencyHeader canonical idempotencyHeader value
     * @param idempotencyScope canonical idempotencyScope value
     */
    public BusinessKeySettings {
        java.util.Objects.requireNonNull(required, "required");
        java.util.Objects.requireNonNull(namespace, "namespace");
    }
}
