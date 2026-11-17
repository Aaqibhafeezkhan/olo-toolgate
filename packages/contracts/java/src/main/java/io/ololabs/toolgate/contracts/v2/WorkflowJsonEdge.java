// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One edge or explicit all-predecessor join. START and END are reserved boundary names.
 *
 * @param from canonical from value
 * @param to canonical to value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowJsonEdge(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "from", required = true) java.util.List<String> from,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "to", required = true) String to
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param from canonical from value
     * @param to canonical to value
     */
    public WorkflowJsonEdge {
        java.util.Objects.requireNonNull(from, "from");
        from = from == null ? null : java.util.List.copyOf(from);
        java.util.Objects.requireNonNull(to, "to");
    }
}
