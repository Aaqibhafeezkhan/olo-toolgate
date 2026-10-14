// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Endpoint identity foundation wire model. keyFingerprint is returned only to callers who may approve through a group rule.
 *
 * @param deviceId canonical deviceId value
 * @param tenantId canonical tenantId value
 * @param userId canonical userId value
 * @param keyFingerprint canonical keyFingerprint value
 * @param state canonical state value
 * @param revision canonical revision value
 * @param lastSeenUnixMs canonical lastSeenUnixMs value
 * @param reportSequence canonical reportSequence value
 * @param report canonical report value
 * @param connectionExpiresAtUnixMs canonical connectionExpiresAtUnixMs value
 * @param connectionApproved canonical connectionApproved value
 * @param approvalRevision canonical approvalRevision value
 * @param systemName canonical systemName value
 * @param ipAddress canonical ipAddress value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointDeviceRecord(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = true) String deviceId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = true) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userId", required = true) String userId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "keyFingerprint", required = false) String keyFingerprint,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "state", required = true) EndpointState state,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "lastSeenUnixMs", required = true) Long lastSeenUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reportSequence", required = true) Long reportSequence,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "report", required = false) ClientReport report,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connectionExpiresAtUnixMs", required = false) Long connectionExpiresAtUnixMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "connectionApproved", required = false) Boolean connectionApproved,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "approvalRevision", required = false) Long approvalRevision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "systemName", required = false) String systemName,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ipAddress", required = false) String ipAddress
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param deviceId canonical deviceId value
     * @param tenantId canonical tenantId value
     * @param userId canonical userId value
     * @param keyFingerprint canonical keyFingerprint value
     * @param state canonical state value
     * @param revision canonical revision value
     * @param lastSeenUnixMs canonical lastSeenUnixMs value
     * @param reportSequence canonical reportSequence value
     * @param report canonical report value
     * @param connectionExpiresAtUnixMs canonical connectionExpiresAtUnixMs value
     * @param connectionApproved canonical connectionApproved value
     * @param approvalRevision canonical approvalRevision value
     * @param systemName canonical systemName value
     * @param ipAddress canonical ipAddress value
     */
    public EndpointDeviceRecord {
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(revision, "revision");
        java.util.Objects.requireNonNull(lastSeenUnixMs, "lastSeenUnixMs");
        java.util.Objects.requireNonNull(reportSequence, "reportSequence");
    }
}
