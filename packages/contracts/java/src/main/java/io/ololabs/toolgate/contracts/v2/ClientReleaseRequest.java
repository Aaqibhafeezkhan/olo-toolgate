// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Builds the client-tool image for one verified upload and issues its fleet release (ADR 026).
 *
 * @param packageDigest canonical packageDigest value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ClientReleaseRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "packageDigest", required = true) String packageDigest
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param packageDigest canonical packageDigest value
     */
    public ClientReleaseRequest {
        java.util.Objects.requireNonNull(packageDigest, "packageDigest");
    }
}
