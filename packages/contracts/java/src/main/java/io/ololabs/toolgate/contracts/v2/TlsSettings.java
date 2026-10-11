// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Upstream TLS verification.
 *
 * @param caBundle canonical caBundle value
 * @param spkiPins canonical spkiPins value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TlsSettings(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "caBundle", required = false) String caBundle,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPins", required = true) java.util.List<String> spkiPins
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param caBundle canonical caBundle value
     * @param spkiPins canonical spkiPins value
     */
    public TlsSettings {
        java.util.Objects.requireNonNull(spkiPins, "spkiPins");
        spkiPins = spkiPins == null ? null : java.util.List.copyOf(spkiPins);
    }
}
