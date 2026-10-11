// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Admin kill request; reviewed when the enterprise review workflow is on.
 *
 * @param scope canonical scope value
 * @param mode canonical mode value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) KillScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) KillMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param scope canonical scope value
     * @param mode canonical mode value
     * @param reason canonical reason value
     */
    public KillRequest {
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
