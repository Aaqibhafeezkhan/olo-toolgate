// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Admin-approved private address range.
 *
 * @param host canonical host value
 * @param cidr canonical cidr value
 * @param ports canonical ports value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PrivateRangeApproval(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "host", required = true) String host,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cidr", required = true) String cidr,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ports", required = true) java.util.List<Long> ports
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param host canonical host value
     * @param cidr canonical cidr value
     * @param ports canonical ports value
     */
    public PrivateRangeApproval {
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(cidr, "cidr");
        java.util.Objects.requireNonNull(ports, "ports");
        ports = ports == null ? null : java.util.List.copyOf(ports);
    }
}
