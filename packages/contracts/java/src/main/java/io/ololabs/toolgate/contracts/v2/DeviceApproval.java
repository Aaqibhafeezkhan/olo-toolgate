// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Device approval settings.
 *
 * @param autoApprove canonical autoApprove value
 * @param durationMs canonical durationMs value
 * @param ownerUserId canonical ownerUserId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record DeviceApproval(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "autoApprove", required = true) Boolean autoApprove,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "durationMs", required = false) Long durationMs,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "ownerUserId", required = false) String ownerUserId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param autoApprove canonical autoApprove value
     * @param durationMs canonical durationMs value
     * @param ownerUserId canonical ownerUserId value
     */
    public DeviceApproval {
        java.util.Objects.requireNonNull(autoApprove, "autoApprove");
    }
}
