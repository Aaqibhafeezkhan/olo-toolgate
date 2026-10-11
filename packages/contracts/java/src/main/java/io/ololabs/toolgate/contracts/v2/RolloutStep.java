// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One exposure step.
 *
 * @param percent canonical percent value
 * @param soakSeconds canonical soakSeconds value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RolloutStep(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "percent", required = true) Long percent,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "soakSeconds", required = true) Long soakSeconds
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param percent canonical percent value
     * @param soakSeconds canonical soakSeconds value
     */
    public RolloutStep {
        java.util.Objects.requireNonNull(percent, "percent");
        java.util.Objects.requireNonNull(soakSeconds, "soakSeconds");
    }
}
