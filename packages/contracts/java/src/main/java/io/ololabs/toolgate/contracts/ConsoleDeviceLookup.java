// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Exactly one enrollment code or existing device identifier for establishing console device context.
 *
 * @param userCode canonical userCode value
 * @param deviceId canonical deviceId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConsoleDeviceLookup(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userCode", required = false) String userCode,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "deviceId", required = false) String deviceId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param userCode canonical userCode value
     * @param deviceId canonical deviceId value
     */
    public ConsoleDeviceLookup {
    }
}
