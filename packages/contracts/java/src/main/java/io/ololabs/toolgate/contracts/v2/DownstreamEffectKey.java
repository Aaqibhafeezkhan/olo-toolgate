// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Supervisor-only broker-injected key. Persisted once per effect; tools cannot select its value.
 *
 * @param header canonical header value
 * @param value canonical value value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DownstreamEffectKey(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "header", required = true) String header,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "value", required = true) String value
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param header canonical header value
     * @param value canonical value value
     */
    public DownstreamEffectKey {
        java.util.Objects.requireNonNull(header, "header");
        java.util.Objects.requireNonNull(value, "value");
    }
}
