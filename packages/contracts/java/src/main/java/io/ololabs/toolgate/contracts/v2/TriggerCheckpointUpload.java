// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
package io.ololabs.toolgate.contracts.v2;

/** Started upload.
 *
 * @param uploadId canonical uploadId value
 * @param chunkBytes canonical chunkBytes value
 * @param expiresAtUnixMs canonical expiresAtUnixMs value
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TriggerCheckpointUpload(
    @com.fasterxml.jackson.annotation.JsonProperty(value = "uploadId", required = true) String uploadId,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "chunkBytes", required = true) Long chunkBytes,
    @com.fasterxml.jackson.annotation.JsonProperty(value = "expiresAtUnixMs", required = true) Long expiresAtUnixMs
) {
    /** Reject absent required references and copy collections to retain value semantics.
     *
     * @param uploadId canonical uploadId value
     * @param chunkBytes canonical chunkBytes value
     * @param expiresAtUnixMs canonical expiresAtUnixMs value
     */
    public TriggerCheckpointUpload {
        java.util.Objects.requireNonNull(uploadId, "uploadId");
        java.util.Objects.requireNonNull(chunkBytes, "chunkBytes");
        if (chunkBytes != null && !java.util.Objects.equals(chunkBytes, 65536L)) throw new IllegalArgumentException("chunkBytes must be 65536");
        java.util.Objects.requireNonNull(expiresAtUnixMs, "expiresAtUnixMs");
    }
}
