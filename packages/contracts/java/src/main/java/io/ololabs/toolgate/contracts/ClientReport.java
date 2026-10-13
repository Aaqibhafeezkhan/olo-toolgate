// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Batched report of device inventory and applied desired revision.
 *
 * @param deviceId canonical deviceId value
 * @param clientVersion canonical clientVersion value
 * @param appliedRevision canonical appliedRevision value
 * @param packages canonical packages value
 * @param power canonical power value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientReport(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "clientVersion", required = true) String clientVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "appliedRevision", required = true) Long appliedRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packages", required = true) java.util.List<ReportedPackage> packages,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "power", required = false) DevicePower power
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param clientVersion canonical clientVersion value
     * @param appliedRevision canonical appliedRevision value
     * @param packages canonical packages value
     * @param power canonical power value
     */
    public ClientReport {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(clientVersion, "clientVersion");
        java.util.Objects.requireNonNull(appliedRevision, "appliedRevision");
        java.util.Objects.requireNonNull(packages, "packages");
        packages = packages == null ? null : java.util.List.copyOf(packages);
    }
}
