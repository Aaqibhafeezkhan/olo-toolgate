// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Explicit operator review for one native MCP business call. Upstream annotations do not confer effect authority.
 *
 * @param classification canonical classification value
 * @param consentRequired canonical consentRequired value
 * @param acknowledgedUnfencedRisk canonical acknowledgedUnfencedRisk value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpEffectPolicy(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "classification", required = true) EffectClassification classification,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consentRequired", required = true) Boolean consentRequired,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "acknowledgedUnfencedRisk", required = true) Boolean acknowledgedUnfencedRisk
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param classification canonical classification value
     * @param consentRequired canonical consentRequired value
     * @param acknowledgedUnfencedRisk canonical acknowledgedUnfencedRisk value
     */
    public McpEffectPolicy {
        java.util.Objects.requireNonNull(classification, "classification");
        java.util.Objects.requireNonNull(consentRequired, "consentRequired");
        java.util.Objects.requireNonNull(acknowledgedUnfencedRisk, "acknowledgedUnfencedRisk");
    }
}
