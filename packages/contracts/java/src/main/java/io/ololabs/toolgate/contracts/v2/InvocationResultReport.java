// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool Host result to Control (effects/report, v2 body), keyed by invocation and fencing token. notStarted, with outcome FAILED, reports a claim the host abandoned before any sandbox started (invocation-state-v2.md T10).
 *
 * @param invocationId canonical invocationId value
 * @param fencingToken canonical fencingToken value
 * @param outcome canonical outcome value
 * @param result canonical result value
 * @param notStarted canonical notStarted value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InvocationResultReport(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "invocationId", required = true) String invocationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fencingToken", required = true) String fencingToken,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = true) InvocationOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "result", required = false) ResultFrame result,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "notStarted", required = false) RefusalReason notStarted
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param invocationId canonical invocationId value
     * @param fencingToken canonical fencingToken value
     * @param outcome canonical outcome value
     * @param result canonical result value
     * @param notStarted canonical notStarted value
     */
    public InvocationResultReport {
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        java.util.Objects.requireNonNull(fencingToken, "fencingToken");
        java.util.Objects.requireNonNull(outcome, "outcome");
    }
}
