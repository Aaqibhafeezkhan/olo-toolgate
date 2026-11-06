// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** start() completed (push) or config validated (poll).
 *
 * @param kind canonical kind value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerReadyFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     */
    public TriggerReadyFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "READY")) throw new IllegalArgumentException("kind must be READY");
    }
}
