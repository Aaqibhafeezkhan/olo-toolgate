// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Every retained call of one session, oldest first, plus calls that declared one of them as parent (nested work), to a bounded depth.
 *
 * @param tenantId canonical tenantId value
 * @param sessionId canonical sessionId value
 * @param invocations canonical invocations value
 * @param truncated canonical truncated value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsSessionTimeline(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sessionId", required = true) String sessionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocations", required = true) java.util.List<InsightsInvocation> invocations,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "truncated", required = true) Boolean truncated
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param sessionId canonical sessionId value
     * @param invocations canonical invocations value
     * @param truncated canonical truncated value
     */
    public InsightsSessionTimeline {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(invocations, "invocations");
        invocations = invocations == null ? null : java.util.List.copyOf(invocations);
        java.util.Objects.requireNonNull(truncated, "truncated");
    }
}
