// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The tool's single permission and the resources it acts on (OLO M3.7). Absent: action invoke on tool://{toolId}.
 *
 * @param action canonical action value
 * @param resources canonical resources value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolAuthorization(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = true) java.util.List<String> resources
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param action canonical action value
     * @param resources canonical resources value
     */
    public ToolAuthorization {
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(resources, "resources");
        resources = resources == null ? null : java.util.List.copyOf(resources);
    }
}
