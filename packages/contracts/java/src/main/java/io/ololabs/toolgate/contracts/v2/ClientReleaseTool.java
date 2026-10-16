// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One export and whether a client device can run it; an unsupported export is never weakened to fit.
 *
 * @param toolId canonical toolId value
 * @param supported canonical supported value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientReleaseTool(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "supported", required = true) Boolean supported,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = false) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param toolId canonical toolId value
     * @param supported canonical supported value
     * @param reason canonical reason value
     */
    public ClientReleaseTool {
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(supported, "supported");
    }
}
