// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Approved destination with its reviewed host.
 *
 * @param id canonical id value
 * @param scheme canonical scheme value
 * @param host canonical host value
 * @param port canonical port value
 * @param tls canonical tls value
 * @param mode canonical mode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ServiceDestination(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scheme", required = true) DestinationScheme scheme,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "host", required = true) String host,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "port", required = true) Long port,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tls", required = true) TlsSettings tls,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) EgressMode mode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param scheme canonical scheme value
     * @param host canonical host value
     * @param port canonical port value
     * @param tls canonical tls value
     * @param mode canonical mode value
     */
    public ServiceDestination {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(scheme, "scheme");
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(port, "port");
        java.util.Objects.requireNonNull(tls, "tls");
        java.util.Objects.requireNonNull(mode, "mode");
    }
}
