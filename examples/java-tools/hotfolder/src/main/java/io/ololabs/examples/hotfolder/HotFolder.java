// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.hotfolder;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * HotFolder: text files in the device's confined files area (ADR 027). Every tool runs on the selected client device
 * and reaches files only through {@code ToolContext.files()}, which the device brokers: it confines paths to its one
 * service-owned folder, refuses links, traversal and other users' files, bounds sizes and counts, and lets a call touch
 * only the paths it was authorized with. Everything else — listing format, search, hashing, copying — is this package.
 *
 * <p>A file that does not exist is reported as data ({@code exists: false}), not as a failure, so callers can test
 * for it; refusals by the device are failures.
 */
@ToolPackage(id = "examples.hotfolder", name = "HotFolder", publisher = "ToolGate Examples",
        description = "Reads and writes text files in a protected folder on your device.")
public class HotFolder {
    static final int MAX_MATCHES = 100;

    public record Listing(@Param(description = "Files and folders, sorted") List<String> paths) {}
    public record Text(@Param(description = "Whether the file exists") boolean exists, @Param(description = "The file's text") String text) {}
    public record Info(@Param(description = "Whether the file exists") boolean exists, @Param(description = "Size in bytes") long bytes) {}
    public record Hash(@Param(description = "Whether the file exists") boolean exists, @Param(description = "SHA-256, hex") String sha256) {}
    public record Matches(@Param(description = "Whether the file exists") boolean exists, @Param(description = "Line numbers containing the query, at most 100") List<Integer> lines) {}
    public record Written(@Param(description = "True once the text is durably stored") boolean written) {}
    public record Created(@Param(description = "True once the folder exists") boolean created) {}
    public record Moved(@Param(description = "False when the source does not exist") boolean moved) {}
    public record Copied(@Param(description = "False when the source does not exist") boolean copied) {}

    private static boolean exists(DeviceFiles files, String path) { return files.list().contains(path); }

    @ClientTool(name = "list", title = "List files", description = "Lists the files and folders in a folder of the HotFolder, or all of them.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"folder\":\"inbox\"}", expectedOutput = "{\"paths\":[]}")
    public Listing list(@Param(description = "Folder to list, such as inbox; empty lists everything", maxLength = 1024, required = false) String folder,
                        ToolContext context) {
        String prefix = folder == null || folder.isEmpty() ? "" : folder + "/";
        return new Listing(context.files().list().stream().filter(path -> path.startsWith(prefix)).toList());
    }

    @ClientTool(name = "read_text", title = "Read a text file", description = "Reads a UTF-8 text file from the HotFolder.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"exists\":false,\"text\":\"\"}")
    public Text readText(@Param(description = "File path, such as notes/today.txt", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        return exists(files, path) ? new Text(true, files.readText(path)) : new Text(false, "");
    }

    @ClientTool(name = "file_info", title = "File size", description = "Reports whether a file exists and its size.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"bytes\":0,\"exists\":false}")
    public Info fileInfo(@Param(description = "File path", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        return exists(files, path) ? new Info(true, files.info(path).bytes()) : new Info(false, 0);
    }

    @ClientTool(name = "hash", title = "Hash a file", description = "SHA-256 of a text file.", effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"exists\":false,\"sha256\":\"\"}")
    public Hash hash(@Param(description = "File path", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Hash(false, "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(files.readText(path).getBytes(StandardCharsets.UTF_8));
            return new Hash(true, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @ClientTool(name = "search_text", title = "Search a file", description = "Line numbers of a text file that contain the query.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\",\"query\":\"invoice\"}", expectedOutput = "{\"exists\":false,\"lines\":[]}")
    public Matches searchText(@Param(description = "File path", maxLength = 1024) String path,
                              @Param(description = "Text to find", minLength = 1, maxLength = 256) String query, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Matches(false, List.of());
        var lines = new ArrayList<Integer>(); int number = 0;
        for (String line : files.readText(path).split("\n", -1)) {
            number++;
            if (line.contains(query) && lines.size() < MAX_MATCHES) lines.add(number);
        }
        return new Matches(true, lines);
    }

    @ClientTool(name = "write_text", title = "Write a text file", description = "Creates or replaces a text file.",
            effect = EffectSafety.IDEMPOTENT, reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"selftest.txt\",\"text\":\"ok\"}", expectedOutput = "{\"written\":true}")
    public Written writeText(@Param(description = "File path", maxLength = 1024) String path,
                             @Param(description = "UTF-8 text", maxLength = 65536) String text, ToolContext context) {
        context.files().writeText(path, text);
        return new Written(true);
    }

    @ClientTool(name = "append_text", title = "Append to a text file", description = "Appends text, creating the file when needed.",
            effect = EffectSafety.NON_IDEMPOTENT, reconciliation = Reconciliation.MANUAL)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"selftest.txt\",\"text\":\"ok\"}", expectedOutput = "{\"written\":true}")
    public Written appendText(@Param(description = "File path", maxLength = 1024) String path,
                              @Param(description = "UTF-8 text", maxLength = 65536) String text, ToolContext context) {
        context.files().appendText(path, text);
        return new Written(true);
    }

    @ClientTool(name = "mkdir", title = "Create a folder", description = "Creates a folder inside the HotFolder.", effect = EffectSafety.IDEMPOTENT, reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"selftest\"}", expectedOutput = "{\"created\":true}")
    public Created mkdir(@Param(description = "Folder path", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) files.mkdir(path);
        return new Created(true);
    }

    @ClientTool(name = "move", title = "Move a file", description = "Moves a file to a new path that does not exist yet.",
            effect = EffectSafety.NON_IDEMPOTENT, reconciliation = Reconciliation.MANUAL)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"missing.txt\",\"destination\":\"moved.txt\"}", expectedOutput = "{\"moved\":false}")
    public Moved move(@Param(description = "File to move", maxLength = 1024) String path,
                      @Param(description = "New path", maxLength = 1024) String destination, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Moved(false);
        if (exists(files, destination)) throw new ToolInputError("The destination already exists");
        files.rename(path, destination);
        return new Moved(true);
    }

    @ClientTool(name = "copy", title = "Copy a file", description = "Copies a file to a new path that does not exist yet.",
            effect = EffectSafety.NON_IDEMPOTENT, reconciliation = Reconciliation.MANUAL)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"missing.txt\",\"destination\":\"copied.txt\"}", expectedOutput = "{\"copied\":false}")
    public Copied copy(@Param(description = "File to copy", maxLength = 1024) String path,
                       @Param(description = "New path", maxLength = 1024) String destination, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Copied(false);
        if (exists(files, destination)) throw new ToolInputError("The destination already exists");
        files.writeText(destination, files.readText(path));
        return new Copied(true);
    }
}
