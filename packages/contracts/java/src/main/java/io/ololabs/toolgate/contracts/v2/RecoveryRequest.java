// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Request a reviewed recovery event.
 *
 * @param supersedes canonical supersedes value
 * @param reason canonical reason value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record RecoveryRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "supersedes", required = true) java.util.List<String> supersedes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "reason", required = true) String reason
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param supersedes canonical supersedes value
     * @param reason canonical reason value
     */
    public RecoveryRequest {
        java.util.Objects.requireNonNull(supersedes, "supersedes");
        supersedes = supersedes == null ? null : java.util.List.copyOf(supersedes);
        java.util.Objects.requireNonNull(reason, "reason");
    }
}
