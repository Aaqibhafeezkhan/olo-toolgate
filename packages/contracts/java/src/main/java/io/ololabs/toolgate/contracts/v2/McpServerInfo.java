// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** The upstream serverInfo from initialize.
 *
 * @param name canonical name value
 * @param version canonical version value
 * @param title canonical title value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record McpServerInfo(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "name", required = true) String name,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "title", required = false) String title
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param name canonical name value
     * @param version canonical version value
     * @param title canonical title value
     */
    public McpServerInfo {
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(version, "version");
    }
}
