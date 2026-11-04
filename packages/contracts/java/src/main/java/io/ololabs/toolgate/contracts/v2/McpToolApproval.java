// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One approved upstream tool and the ToolGate local name it gets (mcp.{slug}/{name}).
 *
 * @param upstreamName canonical upstreamName value
 * @param name canonical name value
 * @param effectPolicy canonical effectPolicy value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpToolApproval(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "upstreamName", required = true) String upstreamName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "effectPolicy", required = true) McpEffectPolicy effectPolicy
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param upstreamName canonical upstreamName value
     * @param name canonical name value
     * @param effectPolicy canonical effectPolicy value
     */
    public McpToolApproval {
        java.util.Objects.requireNonNull(upstreamName, "upstreamName");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(effectPolicy, "effectPolicy");
    }
}
