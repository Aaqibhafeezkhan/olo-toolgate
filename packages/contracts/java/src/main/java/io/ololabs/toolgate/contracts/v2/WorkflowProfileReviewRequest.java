// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness.
 *
 * @param profileDigest canonical profileDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowProfileReviewRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param profileDigest canonical profileDigest value
     */
    public WorkflowProfileReviewRequest {
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
    }
}
