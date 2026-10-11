// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Package-level ceilings.
 *
 * @param maxTools canonical maxTools value
 * @param maxDescriptorBytes canonical maxDescriptorBytes value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageLimits(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxTools", required = true) Long maxTools,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxDescriptorBytes", required = true) Long maxDescriptorBytes
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param maxTools canonical maxTools value
     * @param maxDescriptorBytes canonical maxDescriptorBytes value
     */
    public PackageLimits {
        java.util.Objects.requireNonNull(maxTools, "maxTools");
        java.util.Objects.requireNonNull(maxDescriptorBytes, "maxDescriptorBytes");
    }
}
