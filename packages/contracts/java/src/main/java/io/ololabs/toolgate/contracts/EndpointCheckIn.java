// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Endpoint identity foundation wire model.
 *
 * @param sequence canonical sequence value
 * @param report canonical report value
 * @param adoptionDigest canonical adoptionDigest value
 * @param localTools canonical localTools value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointCheckIn(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sequence", required = true) Long sequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "report", required = true) ClientReport report,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "adoptionDigest", required = false) String adoptionDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "localTools", required = false) java.util.List<LocalToolInfo> localTools
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sequence canonical sequence value
     * @param report canonical report value
     * @param adoptionDigest canonical adoptionDigest value
     * @param localTools canonical localTools value
     */
    public EndpointCheckIn {
        java.util.Objects.requireNonNull(sequence, "sequence");
        java.util.Objects.requireNonNull(report, "report");
        localTools = localTools == null ? null : java.util.List.copyOf(localTools);
    }
}
