// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** SDK that built the package.
 *
 * @param language canonical language value
 * @param version canonical version value
 * @param protocol canonical protocol value
 * @param descriptorGeneration canonical descriptorGeneration value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkInfo(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "language", required = true) SdkLanguage language,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "protocol", required = true) ProtocolRange protocol,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "descriptorGeneration", required = true) Long descriptorGeneration
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param language canonical language value
     * @param version canonical version value
     * @param protocol canonical protocol value
     * @param descriptorGeneration canonical descriptorGeneration value
     */
    public SdkInfo {
        java.util.Objects.requireNonNull(language, "language");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(protocol, "protocol");
        java.util.Objects.requireNonNull(descriptorGeneration, "descriptorGeneration");
    }
}
