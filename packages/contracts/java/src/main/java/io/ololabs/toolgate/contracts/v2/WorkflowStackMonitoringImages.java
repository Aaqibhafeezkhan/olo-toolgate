// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit workflow monitoring service images; credentials remain external custody references.
 *
 * @param collector canonical collector value
 * @param prometheus canonical prometheus value
 * @param grafana canonical grafana value
 * @param tempo canonical tempo value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackMonitoringImages(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "collector", required = true) String collector,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "prometheus", required = true) String prometheus,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "grafana", required = true) String grafana,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tempo", required = true) String tempo
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param collector canonical collector value
     * @param prometheus canonical prometheus value
     * @param grafana canonical grafana value
     * @param tempo canonical tempo value
     */
    public WorkflowStackMonitoringImages {
        java.util.Objects.requireNonNull(collector, "collector");
        java.util.Objects.requireNonNull(prometheus, "prometheus");
        java.util.Objects.requireNonNull(grafana, "grafana");
        java.util.Objects.requireNonNull(tempo, "tempo");
    }
}
