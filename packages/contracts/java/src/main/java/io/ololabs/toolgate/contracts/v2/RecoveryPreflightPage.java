// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Bounded current-only preflight. Opaque cursors fence a changed tenant directory; observed facts never authorize a resend.
 *
 * @param items canonical items value
 * @param nextCursor canonical nextCursor value
 * @param observedAtUnixMs canonical observedAtUnixMs value
 * @param buildMode canonical buildMode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RecoveryPreflightPage(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "items", required = true) java.util.List<ToolRecoveryReadiness> items,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nextCursor", required = false) String nextCursor,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "observedAtUnixMs", required = true) Long observedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "buildMode", required = true) String buildMode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param items canonical items value
     * @param nextCursor canonical nextCursor value
     * @param observedAtUnixMs canonical observedAtUnixMs value
     * @param buildMode canonical buildMode value
     */
    public RecoveryPreflightPage {
        java.util.Objects.requireNonNull(items, "items");
        items = items == null ? null : java.util.List.copyOf(items);
        java.util.Objects.requireNonNull(observedAtUnixMs, "observedAtUnixMs");
        java.util.Objects.requireNonNull(buildMode, "buildMode");
        if (buildMode != null && !java.util.Objects.equals(buildMode, "CURRENT_ONLY")) throw new IllegalArgumentException("buildMode must be CURRENT_ONLY");
    }
}
