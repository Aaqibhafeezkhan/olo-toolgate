// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Logical trigger mappings restored to destination identities.
 *
 * @param id canonical id value
 * @param assignment canonical assignment value
 * @param subscription canonical subscription value
 * @param assignmentSpec canonical assignmentSpec value
 * @param subscriptionSpec canonical subscriptionSpec value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowBundleConnection(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignment", required = true) String assignment,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscription", required = true) String subscription,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "assignmentSpec", required = true) TriggerAssignmentSpec assignmentSpec,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "subscriptionSpec", required = true) TriggerSubscriptionSpec subscriptionSpec
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param assignment canonical assignment value
     * @param subscription canonical subscription value
     * @param assignmentSpec canonical assignmentSpec value
     * @param subscriptionSpec canonical subscriptionSpec value
     */
    public WorkflowBundleConnection {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(assignment, "assignment");
        java.util.Objects.requireNonNull(subscription, "subscription");
        java.util.Objects.requireNonNull(assignmentSpec, "assignmentSpec");
        java.util.Objects.requireNonNull(subscriptionSpec, "subscriptionSpec");
    }
}
