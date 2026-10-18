// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** The canonical JSON SDK project read from an uploaded source ZIP; nothing is saved until the project is saved as a draft.
 *
 * @param source canonical source value
 * @param sourceDigest canonical sourceDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderSource(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "source", required = true) String source,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceDigest", required = true) String sourceDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param source canonical source value
     * @param sourceDigest canonical sourceDigest value
     */
    public SdkBuilderSource {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(sourceDigest, "sourceDigest");
    }
}
