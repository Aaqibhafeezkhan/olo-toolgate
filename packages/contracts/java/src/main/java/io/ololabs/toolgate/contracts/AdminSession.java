// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Server-verified administrator portal session.
 *
 * @param role canonical role value
 * @param bootstrapAdminUserId canonical bootstrapAdminUserId value
 * @param tenantId canonical tenantId value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AdminSession(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "role", required = true) UserRole role,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bootstrapAdminUserId", required = false) String bootstrapAdminUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = false) String tenantId
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param role canonical role value
     * @param bootstrapAdminUserId canonical bootstrapAdminUserId value
     * @param tenantId canonical tenantId value
     */
    public AdminSession {
        java.util.Objects.requireNonNull(role, "role");
    }
    /** Construct a record without optional installation metadata.
     *
     * @param role canonical role value
     */
    public AdminSession(UserRole role) { this(role, null, null); }
}
