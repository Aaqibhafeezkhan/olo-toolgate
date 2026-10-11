// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Catalog scope.
 *
 * @param kind canonical kind value
 * @param agentId canonical agentId value
 * @param agentGroupId canonical agentGroupId value
 * @param toolsets canonical toolsets value
 * @param revision canonical revision value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CatalogScope(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "kind", required = true) CatalogScopeKind kind,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentId", required = false) String agentId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "agentGroupId", required = false) String agentGroupId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "toolsets", required = true) java.util.List<String> toolsets,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "revision", required = true) String revision
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param kind canonical kind value
     * @param agentId canonical agentId value
     * @param agentGroupId canonical agentGroupId value
     * @param toolsets canonical toolsets value
     * @param revision canonical revision value
     */
    public CatalogScope {
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(toolsets, "toolsets");
        toolsets = toolsets == null ? null : java.util.List.copyOf(toolsets);
        java.util.Objects.requireNonNull(revision, "revision");
    }
}
