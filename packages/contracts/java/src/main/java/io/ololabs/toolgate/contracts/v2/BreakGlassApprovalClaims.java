// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Exact JCS offline approval payload. Issued-to-expiry window is at most ten minutes; two or three distinct current operator keys must approve identical claims.
 *
 * @param tenantId canonical tenantId value
 * @param requestId canonical requestId value
 * @param scope canonical scope value
 * @param mode canonical mode value
 * @param reason canonical reason value
 * @param issuedAtUnixMs canonical issuedAtUnixMs value
 * @param notAfterUnixMs canonical notAfterUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record BreakGlassApprovalClaims(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "requestId", required = true) String requestId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "scope", required = true) KillScope scope,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "mode", required = true) KillMode mode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "issuedAtUnixMs", required = true) Long issuedAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "notAfterUnixMs", required = true) Long notAfterUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param tenantId canonical tenantId value
     * @param requestId canonical requestId value
     * @param scope canonical scope value
     * @param mode canonical mode value
     * @param reason canonical reason value
     * @param issuedAtUnixMs canonical issuedAtUnixMs value
     * @param notAfterUnixMs canonical notAfterUnixMs value
     */
    public BreakGlassApprovalClaims {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(scope, "scope");
        java.util.Objects.requireNonNull(mode, "mode");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(issuedAtUnixMs, "issuedAtUnixMs");
        java.util.Objects.requireNonNull(notAfterUnixMs, "notAfterUnixMs");
    }
}
