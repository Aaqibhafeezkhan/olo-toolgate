// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** One page of a frozen executor snapshot (plan 8.5). Pages from different generations are never combined.
 *
 * @param snapshotGeneration canonical snapshotGeneration value
 * @param snapshotDigest canonical snapshotDigest value
 * @param page canonical page value
 * @param pageCount canonical pageCount value
 * @param pageDigest canonical pageDigest value
 * @param instances canonical instances value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSnapshotPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotGeneration", required = true) Long snapshotGeneration,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "snapshotDigest", required = true) String snapshotDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "page", required = true) Long page,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pageCount", required = true) Long pageCount,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "pageDigest", required = true) String pageDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "instances", required = true) java.util.List<TriggerInstanceReport> instances
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param snapshotGeneration canonical snapshotGeneration value
     * @param snapshotDigest canonical snapshotDigest value
     * @param page canonical page value
     * @param pageCount canonical pageCount value
     * @param pageDigest canonical pageDigest value
     * @param instances canonical instances value
     */
    public TriggerSnapshotPage {
        java.util.Objects.requireNonNull(snapshotGeneration, "snapshotGeneration");
        java.util.Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        java.util.Objects.requireNonNull(page, "page");
        java.util.Objects.requireNonNull(pageCount, "pageCount");
        java.util.Objects.requireNonNull(pageDigest, "pageDigest");
        java.util.Objects.requireNonNull(instances, "instances");
        instances = instances == null ? null : java.util.List.copyOf(instances);
    }
}
