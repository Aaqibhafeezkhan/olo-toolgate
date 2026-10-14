// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Look up one pending enrollment by the code its client shows.
 *
 * @param userCode canonical userCode value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record EndpointEnrollmentLookup(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "userCode", required = true) String userCode
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param userCode canonical userCode value
     */
    public EndpointEnrollmentLookup {
        java.util.Objects.requireNonNull(userCode, "userCode");
    }
}
