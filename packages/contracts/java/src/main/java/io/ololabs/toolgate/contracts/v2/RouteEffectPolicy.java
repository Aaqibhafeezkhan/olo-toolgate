// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Operator-reviewed generic route intent. Consent-required routes remain refused until exact consent authority is implemented.
 *
 * @param routeId canonical routeId value
 * @param classification canonical classification value
 * @param consentRequired canonical consentRequired value
 * @param acknowledgedUnfencedRisk canonical acknowledgedUnfencedRisk value
 * @param intent canonical intent value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RouteEffectPolicy(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "routeId", required = true) String routeId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "classification", required = true) EffectClassification classification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consentRequired", required = true) Boolean consentRequired,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acknowledgedUnfencedRisk", required = true) Boolean acknowledgedUnfencedRisk,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "intent", required = true) EffectIntentBlueprint intent
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param routeId canonical routeId value
     * @param classification canonical classification value
     * @param consentRequired canonical consentRequired value
     * @param acknowledgedUnfencedRisk canonical acknowledgedUnfencedRisk value
     * @param intent canonical intent value
     */
    public RouteEffectPolicy {
        java.util.Objects.requireNonNull(routeId, "routeId");
        java.util.Objects.requireNonNull(classification, "classification");
        java.util.Objects.requireNonNull(consentRequired, "consentRequired");
        java.util.Objects.requireNonNull(acknowledgedUnfencedRisk, "acknowledgedUnfencedRisk");
        java.util.Objects.requireNonNull(intent, "intent");
    }
}
