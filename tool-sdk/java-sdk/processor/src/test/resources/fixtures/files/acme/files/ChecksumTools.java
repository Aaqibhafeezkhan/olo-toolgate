// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.files;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.context.ToolContext;

/** Descriptor vector tool-01. */
public class ChecksumTools {
    public record Checksum(@Param(description = "", pattern = "^[0-9a-f]{64}$") String sha256) {}

    @ClientTool(name = "files.checksum", title = "Checksum a file",
            description = "SHA-256 of a file in the protected HotFolder.", effect = EffectSafety.READ_ONLY)
    public Checksum checksum(
            @Param(description = "Path relative to the HotFolder", minLength = 1, maxLength = 1024) String path,
            ToolContext context) {
        return new Checksum("");
    }
}
