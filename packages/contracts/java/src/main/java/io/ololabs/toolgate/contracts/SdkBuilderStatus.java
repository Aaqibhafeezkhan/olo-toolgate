// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts;

/** Configured SDK and current authorized authoring contexts; availability grants no authority.
 *
 * @param sdkVersion canonical sdkVersion value
 * @param configured canonical configured value
 * @param maxSourceCharacters canonical maxSourceCharacters value
 * @param groups canonical groups value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SdkBuilderStatus(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sdkVersion", required = true) String sdkVersion,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "configured", required = true) Boolean configured,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "maxSourceCharacters", required = true) Long maxSourceCharacters,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "groups", required = true) java.util.List<SdkBuilderGroup> groups
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param sdkVersion canonical sdkVersion value
     * @param configured canonical configured value
     * @param maxSourceCharacters canonical maxSourceCharacters value
     * @param groups canonical groups value
     */
    public SdkBuilderStatus {
        java.util.Objects.requireNonNull(sdkVersion, "sdkVersion");
        java.util.Objects.requireNonNull(configured, "configured");
        java.util.Objects.requireNonNull(maxSourceCharacters, "maxSourceCharacters");
        java.util.Objects.requireNonNull(groups, "groups");
        groups = groups == null ? null : java.util.List.copyOf(groups);
    }
}
