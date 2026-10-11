// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A library merged into the package (composition.md §2).
 *
 * @param libraryId canonical libraryId value
 * @param version canonical version value
 * @param fragmentDigest canonical fragmentDigest value
 * @param namespacePrefix canonical namespacePrefix value
 * @param mappings canonical mappings value
 * @param excluded canonical excluded value
 * @param narrowed canonical narrowed value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CompositionRecord(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "libraryId", required = true) String libraryId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "fragmentDigest", required = true) String fragmentDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "namespacePrefix", required = true) String namespacePrefix,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mappings", required = true) java.util.List<CompositionMapping> mappings,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "excluded", required = true) java.util.List<String> excluded,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "narrowed", required = true) java.util.List<String> narrowed
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param libraryId canonical libraryId value
     * @param version canonical version value
     * @param fragmentDigest canonical fragmentDigest value
     * @param namespacePrefix canonical namespacePrefix value
     * @param mappings canonical mappings value
     * @param excluded canonical excluded value
     * @param narrowed canonical narrowed value
     */
    public CompositionRecord {
        java.util.Objects.requireNonNull(libraryId, "libraryId");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(fragmentDigest, "fragmentDigest");
        java.util.Objects.requireNonNull(namespacePrefix, "namespacePrefix");
        java.util.Objects.requireNonNull(mappings, "mappings");
        mappings = mappings == null ? null : java.util.List.copyOf(mappings);
        java.util.Objects.requireNonNull(excluded, "excluded");
        excluded = excluded == null ? null : java.util.List.copyOf(excluded);
        java.util.Objects.requireNonNull(narrowed, "narrowed");
        narrowed = narrowed == null ? null : java.util.List.copyOf(narrowed);
    }
}
