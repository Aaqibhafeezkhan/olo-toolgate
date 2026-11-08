// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** A durable socket subscription (plan 13.1).
 *
 * @param owner canonical owner value
 * @param sources canonical sources value
 * @param filter canonical filter value
 * @param includeState canonical includeState value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerSubscriptionSpec(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "owner", required = false) String owner,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sources", required = true) TriggerSubscriptionSources sources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "filter", required = false) String filter,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "includeState", required = false) Boolean includeState,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param owner canonical owner value
     * @param sources canonical sources value
     * @param filter canonical filter value
     * @param includeState canonical includeState value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public TriggerSubscriptionSpec {
        java.util.Objects.requireNonNull(sources, "sources");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
