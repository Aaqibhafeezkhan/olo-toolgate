// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** SaaS connection the package needs (plan.md §14.3).
 *
 * @param id canonical id value
 * @param provider canonical provider value
 * @param scopes canonical scopes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConnectionRequirement(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "provider", required = true) String provider,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scopes", required = true) java.util.List<String> scopes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param provider canonical provider value
     * @param scopes canonical scopes value
     */
    public ConnectionRequirement {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(provider, "provider");
        java.util.Objects.requireNonNull(scopes, "scopes");
        scopes = scopes == null ? null : java.util.List.copyOf(scopes);
    }
}
