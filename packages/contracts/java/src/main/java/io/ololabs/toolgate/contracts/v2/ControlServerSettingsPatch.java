// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** application/merge-patch+json body, and the settings of a configuration snapshot. Env-owned clientCheckIn is not patchable.
 *
 * @param gateway canonical gateway value
 * @param deviceApproval canonical deviceApproval value
 * @param configuration canonical configuration value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlServerSettingsPatch(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "gateway", required = false) GatewaySettings gateway,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceApproval", required = false) DeviceApproval deviceApproval,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configuration", required = false) ConfigurationSettings configuration
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param gateway canonical gateway value
     * @param deviceApproval canonical deviceApproval value
     * @param configuration canonical configuration value
     */
    public ControlServerSettingsPatch {
    }
}
