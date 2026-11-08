// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Control's terminal disposition of a stored record: a loss record, or a Control-side rejection on re-validation (plan 6.6).
 *
 * @param disposition canonical disposition value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerDispositionView(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "disposition", required = true) TriggerDispositionKind disposition,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param disposition canonical disposition value
     * @param reason canonical reason value
     */
    public TriggerDispositionView {
        java.util.Objects.requireNonNull(disposition, "disposition");
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
