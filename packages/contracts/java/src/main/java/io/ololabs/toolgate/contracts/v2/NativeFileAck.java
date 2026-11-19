// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileAck; only protected qualified filesystem broker evidence.
 *
 * @param accepted canonical accepted value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileAck(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "accepted", required = true) Boolean accepted
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param accepted canonical accepted value
     */
    public NativeFileAck {
        java.util.Objects.requireNonNull(accepted, "accepted");
        if (accepted != null && !java.util.Objects.equals(accepted, true)) throw new IllegalArgumentException("accepted must be True");
    }
}
