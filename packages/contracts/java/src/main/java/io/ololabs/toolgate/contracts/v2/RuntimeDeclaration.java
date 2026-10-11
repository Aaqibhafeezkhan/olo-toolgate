// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Language runtime a tool needs.
 *
 * @param runtimeId canonical runtimeId value
 * @param language canonical language value
 * @param version canonical version value
 * @param baseImageDigest canonical baseImageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RuntimeDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runtimeId", required = true) String runtimeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "language", required = true) String language,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "baseImageDigest", required = false) String baseImageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param runtimeId canonical runtimeId value
     * @param language canonical language value
     * @param version canonical version value
     * @param baseImageDigest canonical baseImageDigest value
     */
    public RuntimeDeclaration {
        java.util.Objects.requireNonNull(runtimeId, "runtimeId");
        java.util.Objects.requireNonNull(language, "language");
        java.util.Objects.requireNonNull(version, "version");
    }
}
