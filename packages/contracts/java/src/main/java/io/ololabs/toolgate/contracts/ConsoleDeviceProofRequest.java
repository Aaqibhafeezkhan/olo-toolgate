// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Bounded local native-client proof request for the selected console origin.
 *
 * @param kind canonical kind value
 * @param consoleUrl canonical consoleUrl value
 * @param nonce canonical nonce value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ConsoleDeviceProofRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) String kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "consoleUrl", required = true) String consoleUrl,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nonce", required = true) String nonce
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param consoleUrl canonical consoleUrl value
     * @param nonce canonical nonce value
     */
    public ConsoleDeviceProofRequest {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(consoleUrl, "consoleUrl");
        java.util.Objects.requireNonNull(nonce, "nonce");
    }
}
