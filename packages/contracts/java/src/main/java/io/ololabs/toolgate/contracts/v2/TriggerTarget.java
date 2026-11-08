// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Where an assignment runs (plan 9.1). CLIENT: devices and Device Groups, minus excluded devices. Entries are unique; Control refuses repeats.
 *
 * @param executor canonical executor value
 * @param devices canonical devices value
 * @param deviceGroups canonical deviceGroups value
 * @param excludeDevices canonical excludeDevices value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerTarget(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "executor", required = true) TriggerExecutor executor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "devices", required = false) java.util.List<String> devices,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceGroups", required = false) java.util.List<String> deviceGroups,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "excludeDevices", required = false) java.util.List<String> excludeDevices
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param executor canonical executor value
     * @param devices canonical devices value
     * @param deviceGroups canonical deviceGroups value
     * @param excludeDevices canonical excludeDevices value
     */
    public TriggerTarget {
        java.util.Objects.requireNonNull(executor, "executor");
        devices = devices == null ? null : java.util.List.copyOf(devices);
        deviceGroups = deviceGroups == null ? null : java.util.List.copyOf(deviceGroups);
        excludeDevices = excludeDevices == null ? null : java.util.List.copyOf(excludeDevices);
    }
}
