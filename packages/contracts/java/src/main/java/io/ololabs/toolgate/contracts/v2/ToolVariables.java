// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared configuration and identity inputs.
 *
 * @param globals canonical globals value
 * @param secrets canonical secrets value
 * @param claims canonical claims value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolVariables(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "globals", required = true) java.util.List<GlobalDeclaration> globals,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "secrets", required = true) java.util.List<SecretDeclaration> secrets,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "claims", required = true) java.util.List<ClaimDeclaration> claims
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param globals canonical globals value
     * @param secrets canonical secrets value
     * @param claims canonical claims value
     */
    public ToolVariables {
        java.util.Objects.requireNonNull(globals, "globals");
        globals = globals == null ? null : java.util.List.copyOf(globals);
        java.util.Objects.requireNonNull(secrets, "secrets");
        secrets = secrets == null ? null : java.util.List.copyOf(secrets);
        java.util.Objects.requireNonNull(claims, "claims");
        claims = claims == null ? null : java.util.List.copyOf(claims);
    }
}
