// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Authoritative current kill state, registered delivery status and retained archive counts.
 *
 * @param state canonical state value
 * @param components canonical components value
 * @param retainedEventCount canonical retainedEventCount value
 * @param archivedEventCount canonical archivedEventCount value
 * @param observedAtUnixMs canonical observedAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record KillOverview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) KillPush state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "components", required = true) java.util.List<KillComponentDelivery> components,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "retainedEventCount", required = true) Long retainedEventCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "archivedEventCount", required = true) Long archivedEventCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedAtUnixMs", required = true) Long observedAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param state canonical state value
     * @param components canonical components value
     * @param retainedEventCount canonical retainedEventCount value
     * @param archivedEventCount canonical archivedEventCount value
     * @param observedAtUnixMs canonical observedAtUnixMs value
     */
    public KillOverview {
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(components, "components");
        components = components == null ? null : java.util.List.copyOf(components);
        java.util.Objects.requireNonNull(retainedEventCount, "retainedEventCount");
        java.util.Objects.requireNonNull(archivedEventCount, "archivedEventCount");
        java.util.Objects.requireNonNull(observedAtUnixMs, "observedAtUnixMs");
    }
}
