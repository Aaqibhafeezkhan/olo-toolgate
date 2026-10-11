// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Declared TCP destination (tunnel mode).
 *
 * @param id canonical id value
 * @param hostSetting canonical hostSetting value
 * @param port canonical port value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TcpDeclaration(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "hostSetting", required = true) String hostSetting,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "port", required = true) Long port
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param hostSetting canonical hostSetting value
     * @param port canonical port value
     */
    public TcpDeclaration {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(hostSetting, "hostSetting");
        java.util.Objects.requireNonNull(port, "port");
    }
}
