// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared secret; never a value.
 *
 * @param name canonical name value
 * @param type canonical type value
 * @param scope canonical scope value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SecretDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "type", required = true) ValueType type,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) SecretScope scope
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param type canonical type value
     * @param scope canonical scope value
     */
    public SecretDeclaration {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(scope, "scope");
    }
}
