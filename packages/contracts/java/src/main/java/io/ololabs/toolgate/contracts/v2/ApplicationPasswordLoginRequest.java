// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** V1 existing-user password login. Tenant is required in Toolgate-Tenant-ID; OAuth is not required.
 *
 * @param username canonical username value
 * @param password canonical password value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ApplicationPasswordLoginRequest(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "username", required = true) String username,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "password", required = true) String password
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param username canonical username value
     * @param password canonical password value
     */
    public ApplicationPasswordLoginRequest {
        java.util.Objects.requireNonNull(username, "username");
        java.util.Objects.requireNonNull(password, "password");
    }
}
