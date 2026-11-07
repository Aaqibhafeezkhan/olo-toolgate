// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Bounded tenant export of current derived operation outcomes with grade, coverage and fencing counts. Ordered by operation id; never execution authority.
 *
 * @param items canonical items value
 * @param next canonical next value
 * @param exportedAtUnixMs canonical exportedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EnterpriseOperationEvidenceExportPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<EnterpriseOperationOutcome> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "next", required = false) String next,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "exportedAtUnixMs", required = true) Long exportedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param next canonical next value
     * @param exportedAtUnixMs canonical exportedAtUnixMs value
     */
    public EnterpriseOperationEvidenceExportPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
        java.util.Objects.requireNonNull(exportedAtUnixMs, "exportedAtUnixMs");
    }
}
