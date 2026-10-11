// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Tool Host registration with its workload identity (mTLS).
 *
 * @param hostId canonical hostId value
 * @param poolId canonical poolId value
 * @param version canonical version value
 * @param protocol canonical protocol value
 * @param launcherVersion canonical launcherVersion value
 * @param tiers canonical tiers value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ToolHostRegistration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostId", required = true) String hostId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "poolId", required = true) String poolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocol", required = true) ProtocolRange protocol,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "launcherVersion", required = true) String launcherVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tiers", required = true) java.util.List<RuntimeTier> tiers
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param hostId canonical hostId value
     * @param poolId canonical poolId value
     * @param version canonical version value
     * @param protocol canonical protocol value
     * @param launcherVersion canonical launcherVersion value
     * @param tiers canonical tiers value
     */
    public ToolHostRegistration {
        java.util.Objects.requireNonNull(hostId, "hostId");
        java.util.Objects.requireNonNull(poolId, "poolId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(protocol, "protocol");
        java.util.Objects.requireNonNull(launcherVersion, "launcherVersion");
        java.util.Objects.requireNonNull(tiers, "tiers");
        tiers = tiers == null ? null : java.util.List.copyOf(tiers);
    }
}
