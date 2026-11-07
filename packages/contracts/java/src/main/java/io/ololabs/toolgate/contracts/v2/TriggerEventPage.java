// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One page of an instance's stored records, in (leaseEpoch, outboxSeq) order.
 *
 * @param items canonical items value
 * @param nextPageToken canonical nextPageToken value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerEventPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<TriggerStoredEvent> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextPageToken", required = false) String nextPageToken
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param nextPageToken canonical nextPageToken value
     */
    public TriggerEventPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
    }
}
