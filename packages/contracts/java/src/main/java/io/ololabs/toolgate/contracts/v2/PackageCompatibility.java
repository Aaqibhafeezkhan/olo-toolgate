// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Version requirements checked at enable.
 *
 * @param contractsVersion canonical contractsVersion value
 * @param minimumToolHostVersion canonical minimumToolHostVersion value
 * @param minimumClientVersion canonical minimumClientVersion value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PackageCompatibility(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contractsVersion", required = true) String contractsVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minimumToolHostVersion", required = false) String minimumToolHostVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "minimumClientVersion", required = false) String minimumClientVersion
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param contractsVersion canonical contractsVersion value
     * @param minimumToolHostVersion canonical minimumToolHostVersion value
     * @param minimumClientVersion canonical minimumClientVersion value
     */
    public PackageCompatibility {
        java.util.Objects.requireNonNull(contractsVersion, "contractsVersion");
    }
}
