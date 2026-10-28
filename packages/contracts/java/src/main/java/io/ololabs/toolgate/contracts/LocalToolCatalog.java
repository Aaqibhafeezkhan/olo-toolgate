// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Only enabled local definitions applicable to this verified agent and device.
 *
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record LocalToolCatalog(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) java.util.List<LocalToolInfo> tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tools canonical tools value
     */
    public LocalToolCatalog {
        java.util.Objects.requireNonNull(tools, "tools");
        tools = tools == null ? null : java.util.List.copyOf(tools);
    }
}
