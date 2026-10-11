// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Secret handle, redeemed per call with the fencing token. Never a value.
 *
 * @param name canonical name value
 * @param handle canonical handle value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SecretHandle(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "handle", required = true) String handle
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param handle canonical handle value
     */
    public SecretHandle {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(handle, "handle");
    }
}
