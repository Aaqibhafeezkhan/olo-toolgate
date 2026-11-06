// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Immutable exact-effect preview created by Control from authenticated original arguments and pinned reviewed mappings. Its canonical digest must be explicitly approved; ordinary invocation approval is not exact consent. Validity never exceeds original operation and credential deadlines.
 *
 * @param consentId canonical consentId value
 * @param tenantId canonical tenantId value
 * @param operationId canonical operationId value
 * @param runId canonical runId value
 * @param toolId canonical toolId value
 * @param action canonical action value
 * @param argumentsDigest canonical argumentsDigest value
 * @param resources canonical resources value
 * @param profileDigest canonical profileDigest value
 * @param notBeforeUnixMs canonical notBeforeUnixMs value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 * @param slots canonical slots value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EffectConsentPreview(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consentId", required = true) String consentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operationId", required = true) String operationId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "runId", required = true) String runId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolId", required = true) String toolId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "action", required = true) String action,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "argumentsDigest", required = true) String argumentsDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "resources", required = true) java.util.List<io.ololabs.toolgate.contracts.ResourceDescriptor> resources,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "profileDigest", required = true) String profileDigest,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "notBeforeUnixMs", required = true) Long notBeforeUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "slots", required = true) java.util.List<EffectConsentSlot> slots
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param consentId canonical consentId value
     * @param tenantId canonical tenantId value
     * @param operationId canonical operationId value
     * @param runId canonical runId value
     * @param toolId canonical toolId value
     * @param action canonical action value
     * @param argumentsDigest canonical argumentsDigest value
     * @param resources canonical resources value
     * @param profileDigest canonical profileDigest value
     * @param notBeforeUnixMs canonical notBeforeUnixMs value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     * @param slots canonical slots value
     */
    public EffectConsentPreview {
        java.util.Objects.requireNonNull(consentId, "consentId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(operationId, "operationId");
        java.util.Objects.requireNonNull(runId, "runId");
        java.util.Objects.requireNonNull(toolId, "toolId");
        java.util.Objects.requireNonNull(action, "action");
        java.util.Objects.requireNonNull(argumentsDigest, "argumentsDigest");
        java.util.Objects.requireNonNull(resources, "resources");
        resources = resources == null ? null : java.util.List.copyOf(resources);
        java.util.Objects.requireNonNull(profileDigest, "profileDigest");
        java.util.Objects.requireNonNull(notBeforeUnixMs, "notBeforeUnixMs");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
        java.util.Objects.requireNonNull(slots, "slots");
        slots = slots == null ? null : java.util.List.copyOf(slots);
    }
}
