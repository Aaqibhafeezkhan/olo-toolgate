// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Registrations of the routed tenant.
 *
 * @param items canonical items value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpServerRegistrationPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<McpServerRegistration> items
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     */
    public McpServerRegistrationPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
    }
}
