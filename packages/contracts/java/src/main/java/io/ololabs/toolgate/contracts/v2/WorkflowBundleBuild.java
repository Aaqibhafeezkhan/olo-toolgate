// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** WorkflowBundleBuild portable metadata.
 *
 * @param version canonical version value
 * @param sourceCommit canonical sourceCommit value
 * @param build canonical build value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record WorkflowBundleBuild(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "version", required = true) String version,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "sourceCommit", required = true) String sourceCommit,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "build", required = true) String build
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param version canonical version value
     * @param sourceCommit canonical sourceCommit value
     * @param build canonical build value
     */
    public WorkflowBundleBuild {
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(sourceCommit, "sourceCommit");
        java.util.Objects.requireNonNull(build, "build");
    }
}
