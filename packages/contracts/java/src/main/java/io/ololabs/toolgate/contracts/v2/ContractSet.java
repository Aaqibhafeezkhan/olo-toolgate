// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Identifies the shared contract set.
 *
 * @param name canonical name value
 * @param version canonical version value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ContractSet(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param version canonical version value
     */
    public ContractSet {
        java.util.Objects.requireNonNull(name, "name");
        if (name != null && !java.util.Objects.equals(name, "olo-toolgate-contracts")) throw new IllegalArgumentException("name must be olo-toolgate-contracts");
        java.util.Objects.requireNonNull(version, "version");
    }
}
