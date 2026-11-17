// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Actual JSON compiler features and disposable cache statistics. This is not a readiness or execution grant.
 *
 * @param entries canonical entries value
 * @param capacity canonical capacity value
 * @param compilerDigest canonical compilerDigest value
 * @param features canonical features value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowGraphCapabilities(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "entries", required = true) Long entries,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "capacity", required = true) Long capacity,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "compilerDigest", required = true) String compilerDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "features", required = true) java.util.List<String> features
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param entries canonical entries value
     * @param capacity canonical capacity value
     * @param compilerDigest canonical compilerDigest value
     * @param features canonical features value
     */
    public WorkflowGraphCapabilities {
        java.util.Objects.requireNonNull(entries, "entries");
        java.util.Objects.requireNonNull(capacity, "capacity");
        if (capacity != null && !java.util.Objects.equals(capacity, 128L)) throw new IllegalArgumentException("capacity must be 128");
        java.util.Objects.requireNonNull(compilerDigest, "compilerDigest");
        java.util.Objects.requireNonNull(features, "features");
        features = features == null ? null : java.util.List.copyOf(features);
    }
}
