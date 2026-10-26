// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One caller-declared session in a range: its calls by acceptance time. Session IDs are declared by the caller and not verified.
 *
 * @param tenantId canonical tenantId value
 * @param sessionId canonical sessionId value
 * @param firstAcceptedAtUnixMs canonical firstAcceptedAtUnixMs value
 * @param lastActivityUnixMs canonical lastActivityUnixMs value
 * @param calls canonical calls value
 * @param succeeded canonical succeeded value
 * @param failed canonical failed value
 * @param unfinished canonical unfinished value
 * @param agentRuns canonical agentRuns value
 * @param tools canonical tools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSession(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionId", required = true) String sessionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "firstAcceptedAtUnixMs", required = true) Long firstAcceptedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastActivityUnixMs", required = true) Long lastActivityUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "calls", required = true) Long calls,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "succeeded", required = true) Long succeeded,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "failed", required = true) Long failed,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "unfinished", required = true) Long unfinished,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentRuns", required = true) Long agentRuns,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tools", required = true) Long tools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param sessionId canonical sessionId value
     * @param firstAcceptedAtUnixMs canonical firstAcceptedAtUnixMs value
     * @param lastActivityUnixMs canonical lastActivityUnixMs value
     * @param calls canonical calls value
     * @param succeeded canonical succeeded value
     * @param failed canonical failed value
     * @param unfinished canonical unfinished value
     * @param agentRuns canonical agentRuns value
     * @param tools canonical tools value
     */
    public InsightsSession {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(firstAcceptedAtUnixMs, "firstAcceptedAtUnixMs");
        java.util.Objects.requireNonNull(lastActivityUnixMs, "lastActivityUnixMs");
        java.util.Objects.requireNonNull(calls, "calls");
        java.util.Objects.requireNonNull(succeeded, "succeeded");
        java.util.Objects.requireNonNull(failed, "failed");
        java.util.Objects.requireNonNull(unfinished, "unfinished");
        java.util.Objects.requireNonNull(agentRuns, "agentRuns");
        java.util.Objects.requireNonNull(tools, "tools");
    }
}
