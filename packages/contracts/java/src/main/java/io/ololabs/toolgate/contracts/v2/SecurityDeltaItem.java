// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One security delta. UNCLASSIFIED counts as widening.
 *
 * @param kind canonical kind value
 * @param toolId canonical toolId value
 * @param widening canonical widening value
 * @param summary canonical summary value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SecurityDeltaItem(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) SecurityDeltaKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = false) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "widening", required = true) Boolean widening,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "summary", required = true) String summary
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param toolId canonical toolId value
     * @param widening canonical widening value
     * @param summary canonical summary value
     */
    public SecurityDeltaItem {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(widening, "widening");
        java.util.Objects.requireNonNull(summary, "summary");
    }
}
