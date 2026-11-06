// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Every emit of this process up to emitSeq is resolved at Control.
 *
 * @param kind canonical kind value
 * @param emitSeq canonical emitSeq value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerAcceptedUpToFrame(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "emitSeq", required = true) Long emitSeq
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param emitSeq canonical emitSeq value
     */
    public TriggerAcceptedUpToFrame {
        java.util.Objects.requireNonNull(kind, "kind");
        if (kind != null && !java.util.Objects.equals(kind, "ACCEPTED_UPTO")) throw new IllegalArgumentException("kind must be ACCEPTED_UPTO");
        java.util.Objects.requireNonNull(emitSeq, "emitSeq");
    }
}
