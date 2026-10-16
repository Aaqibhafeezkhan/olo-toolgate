// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Queue an immutable project revision for the shared SDK worker.
 *
 * @param id canonical id value
 * @param draftId canonical draftId value
 * @param expectedRevision canonical expectedRevision value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderBuildRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "draftId", required = true) String draftId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expectedRevision", required = true) Long expectedRevision
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param draftId canonical draftId value
     * @param expectedRevision canonical expectedRevision value
     */
    public SdkBuilderBuildRequest {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(draftId, "draftId");
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
    }
}
