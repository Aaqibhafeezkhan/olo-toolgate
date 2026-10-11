// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Tenant-scoped users configuration record. Not a runtime credential or policy grant.
 *
 * @param id canonical id value
 * @param name canonical name value
 * @param enabled canonical enabled value
 * @param revision canonical revision value
 * @param email canonical email value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ControlUser(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "id", required = true) String id,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "enabled", required = true) Boolean enabled,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) Long revision,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "email", required = false) String email
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     * @param email canonical email value
     */
    public ControlUser {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(enabled, "enabled");
        java.util.Objects.requireNonNull(revision, "revision");
    }
    /** Construct a record without optional installation metadata.
     *
     * @param id canonical id value
     * @param name canonical name value
     * @param enabled canonical enabled value
     * @param revision canonical revision value
     */
    public ControlUser(String id, String name, Boolean enabled, Long revision) { this(id, name, enabled, revision, null); }
}
