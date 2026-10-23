// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Server-verified administrator portal session.
 *
 * @param permissions canonical permissions value
 * @param role canonical role value
 * @param bootstrapAdminUserId canonical bootstrapAdminUserId value
 * @param tenantId canonical tenantId value
 * @param tenantName canonical tenantName value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record AdminSession(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "permissions", required = false) java.util.List<String> permissions,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "role", required = true) UserRole role,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "bootstrapAdminUserId", required = false) String bootstrapAdminUserId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantId", required = false) String tenantId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "tenantName", required = false) String tenantName
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param permissions canonical permissions value
     * @param role canonical role value
     * @param bootstrapAdminUserId canonical bootstrapAdminUserId value
     * @param tenantId canonical tenantId value
     * @param tenantName canonical tenantName value
     */
    public AdminSession {
        permissions = permissions == null ? null : java.util.List.copyOf(permissions);
        java.util.Objects.requireNonNull(role, "role");
    }
    /** Construct a record without optional installation metadata.
     *
     * @param role canonical role value
     */
    public AdminSession(UserRole role) { this(null, role, null, null, null); }
    /** Construct a session without an optional tenant display name. */
    public AdminSession(java.util.List<String> permissions, UserRole role, String bootstrapAdminUserId, String tenantId) { this(permissions, role, bootstrapAdminUserId, tenantId, null); }
}
