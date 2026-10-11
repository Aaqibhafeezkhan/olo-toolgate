// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared destination; the host is a reviewed deployment setting.
 *
 * @param id canonical id value
 * @param hostSetting canonical hostSetting value
 * @param port canonical port value
 * @param spkiPins canonical spkiPins value
 * @param caBundle canonical caBundle value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DestinationDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostSetting", required = true) String hostSetting,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "port", required = true) Long port,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "spkiPins", required = true) java.util.List<String> spkiPins,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "caBundle", required = false) String caBundle
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param hostSetting canonical hostSetting value
     * @param port canonical port value
     * @param spkiPins canonical spkiPins value
     * @param caBundle canonical caBundle value
     */
    public DestinationDeclaration {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(hostSetting, "hostSetting");
        java.util.Objects.requireNonNull(port, "port");
        java.util.Objects.requireNonNull(spkiPins, "spkiPins");
        spkiPins = spkiPins == null ? null : java.util.List.copyOf(spkiPins);
    }
}
