// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** POLL interval or PUSH resync interval.
 *
 * @param intervalMs canonical intervalMs value
 * @param resyncIntervalMs canonical resyncIntervalMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSchedule(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "intervalMs", required = false) Long intervalMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resyncIntervalMs", required = false) Long resyncIntervalMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param intervalMs canonical intervalMs value
     * @param resyncIntervalMs canonical resyncIntervalMs value
     */
    public TriggerSchedule {
    }
}
