// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explorer results, newest first. next continues the search.
 *
 * @param items canonical items value
 * @param next canonical next value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsInvocationPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<InsightsInvocation> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "next", required = false) String next
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param next canonical next value
     */
    public InsightsInvocationPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
    }
}
