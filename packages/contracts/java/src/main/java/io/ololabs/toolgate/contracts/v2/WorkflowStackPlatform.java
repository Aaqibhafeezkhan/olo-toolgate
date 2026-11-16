// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority.
 *
 * @param quickstartImage canonical quickstartImage value
 * @param deviceImage canonical deviceImage value
 * @param httpPort canonical httpPort value
 * @param tlsPort canonical tlsPort value
 * @param registryPort canonical registryPort value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowStackPlatform(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "quickstartImage", required = true) String quickstartImage,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceImage", required = true) String deviceImage,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "httpPort", required = true) Long httpPort,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tlsPort", required = true) Long tlsPort,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "registryPort", required = true) Long registryPort
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param quickstartImage canonical quickstartImage value
     * @param deviceImage canonical deviceImage value
     * @param httpPort canonical httpPort value
     * @param tlsPort canonical tlsPort value
     * @param registryPort canonical registryPort value
     */
    public WorkflowStackPlatform {
        java.util.Objects.requireNonNull(quickstartImage, "quickstartImage");
        java.util.Objects.requireNonNull(deviceImage, "deviceImage");
        java.util.Objects.requireNonNull(httpPort, "httpPort");
        java.util.Objects.requireNonNull(tlsPort, "tlsPort");
        java.util.Objects.requireNonNull(registryPort, "registryPort");
    }
}
