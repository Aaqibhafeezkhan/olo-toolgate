// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One execution attempt: one consumed permit.
 *
 * @param attemptNumber canonical attemptNumber value
 * @param executionId canonical executionId value
 * @param executorId canonical executorId value
 * @param startedAtUnixMs canonical startedAtUnixMs value
 * @param endedAtUnixMs canonical endedAtUnixMs value
 * @param outcome canonical outcome value
 * @param durationMs canonical durationMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record InsightsAttempt(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "attemptNumber", required = true) Long attemptNumber,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executionId", required = false) String executionId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executorId", required = false) String executorId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "startedAtUnixMs", required = false) Long startedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "endedAtUnixMs", required = false) Long endedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "outcome", required = false) InsightsOutcome outcome,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "durationMs", required = false) Long durationMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param attemptNumber canonical attemptNumber value
     * @param executionId canonical executionId value
     * @param executorId canonical executorId value
     * @param startedAtUnixMs canonical startedAtUnixMs value
     * @param endedAtUnixMs canonical endedAtUnixMs value
     * @param outcome canonical outcome value
     * @param durationMs canonical durationMs value
     */
    public InsightsAttempt {
        java.util.Objects.requireNonNull(attemptNumber, "attemptNumber");
    }
}
