// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Acknowledged snapshot page.
 *
 * @param snapshotGeneration canonical snapshotGeneration value
 * @param page canonical page value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSnapshotPageAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotGeneration", required = true) Long snapshotGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "page", required = true) Long page
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param snapshotGeneration canonical snapshotGeneration value
     * @param page canonical page value
     */
    public TriggerSnapshotPageAck {
        java.util.Objects.requireNonNull(snapshotGeneration, "snapshotGeneration");
        java.util.Objects.requireNonNull(page, "page");
    }
}
