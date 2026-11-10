// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A delivered subscription cursor for server-side workflow admission. Caller/event fields cannot select tenant, workflow, version, identity or prompts.
 *
 * @param subscriptionUid canonical subscriptionUid value
 * @param cursor canonical cursor value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowDispatchRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionUid", required = true) String subscriptionUid,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "cursor", required = true) String cursor
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param subscriptionUid canonical subscriptionUid value
     * @param cursor canonical cursor value
     */
    public WorkflowDispatchRequest {
        java.util.Objects.requireNonNull(subscriptionUid, "subscriptionUid");
        java.util.Objects.requireNonNull(cursor, "cursor");
    }
}
