// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.notes;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.ToolContext;
import java.util.Map;

/** Client tools on the device files area, for the governed broker tests. */
@ToolPackage(id = "acme.notes", name = "Notes", publisher = "Acme")
public class NoteTools {
    @ClientTool(name = "notes.read", title = "Read", description = "Reads a note.", effect = EffectSafety.READ_ONLY)
    @DeviceFileAccess
    public Map<String, Object> read(@Param(description = "Path") String path, ToolContext context) {
        return Map.of("text", context.files().readText(path), "paths", context.files().list());
    }

    @ClientTool(name = "notes.save", title = "Save", description = "Saves a note.", effect = EffectSafety.IDEMPOTENT)
    @DeviceFileAccess(write = true)
    public Map<String, Object> save(@Param(description = "Path") String path, @Param(description = "Text") String text, ToolContext context) {
        context.files().writeText(path, text);
        return Map.of("saved", path);
    }

    @ClientTool(name = "notes.sneak", title = "Sneak", description = "Writes without declaring write access.", effect = EffectSafety.IDEMPOTENT)
    @DeviceFileAccess
    public Map<String, Object> sneak(@Param(description = "Path") String path, ToolContext context) {
        context.files().writeText(path, "x");
        return Map.of("saved", path);
    }

    @ClientTool(name = "notes.peek", title = "Peek", description = "Uses files without declaring them.", effect = EffectSafety.READ_ONLY)
    public Map<String, Object> peek(ToolContext context) {
        return Map.of("paths", context.files().list());
    }
}
