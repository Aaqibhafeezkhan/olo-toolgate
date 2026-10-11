// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One applied library mapping.
 *
 * @param kind canonical kind value
 * @param from canonical from value
 * @param to canonical to value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CompositionMapping(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) MappingKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "from", required = true) String from,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "to", required = true) String to
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param from canonical from value
     * @param to canonical to value
     */
    public CompositionMapping {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(from, "from");
        java.util.Objects.requireNonNull(to, "to");
    }
}
