// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Opt-in administrative deployment view. Default device responses retain their existing closed schema.
 *
 * @param items canonical items value
 * @param deployments canonical deployments value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointManagedDeviceDeploymentPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<EndpointManagedDevice> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deployments", required = false) java.util.List<DeviceDeploymentRecord> deployments
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param deployments canonical deployments value
     */
    public EndpointManagedDeviceDeploymentPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
        deployments = deployments == null ? null : java.util.List.copyOf(deployments);
    }
}
