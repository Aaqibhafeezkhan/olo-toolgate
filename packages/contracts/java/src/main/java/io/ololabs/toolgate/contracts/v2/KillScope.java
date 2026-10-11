// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Kill target. value is absent only for TENANT.
 *
 * @param kind canonical kind value
 * @param value canonical value value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillScope(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) KillScopeKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "value", required = false) String value
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param value canonical value value
     */
    public KillScope {
        java.util.Objects.requireNonNull(kind, "kind");
    }
}
