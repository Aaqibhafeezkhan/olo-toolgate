// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** ADR 075 NativeFileIntent; only protected qualified filesystem broker evidence.
 *
 * @param operation canonical operation value
 * @param path canonical path value
 * @param destination canonical destination value
 * @param contentSha256 canonical contentSha256 value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NativeFileIntent(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "operation", required = true) NativeFileOperation operation,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "path", required = true) String path,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "destination", required = false) String destination,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "contentSha256", required = false) String contentSha256
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param operation canonical operation value
     * @param path canonical path value
     * @param destination canonical destination value
     * @param contentSha256 canonical contentSha256 value
     */
    public NativeFileIntent {
        java.util.Objects.requireNonNull(operation, "operation");
        java.util.Objects.requireNonNull(path, "path");
    }
}
