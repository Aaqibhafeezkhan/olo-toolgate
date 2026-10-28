// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.cleanup;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolConfigurationError;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Housekeeping: finds files older than a given age in an approved directory (old logs, temp exports, stale uploads)
 * and, only when asked, deletes them.
 *
 * <p>The guard rails are the point of this example. It does nothing until the administrator sets {@code CleanupRoots};
 * a dry run is the default and lists what would go; deletes need an age of at least {@code MinAgeDays}; one call
 * deletes at most {@code MaxDeletesPerCall} files; links are never followed; directories are never removed. The tool
 * is declared {@code destructive}, so an administrator sees that when approving it.
 */
@ToolPackage(id = "examples.cleanup", name = "File cleanup", publisher = "ToolGate Examples",
        description = "Finds and, when asked, deletes old files in approved directories.")
@Global(name = "CleanupRoots", type = ValueType.STRING, defaultValue = "none",
        description = "Comma-separated directories the tool may clean, such as /var/log/app,D:\\exports. 'none' disables it.")
@Global(name = "MinAgeDays", type = ValueType.INT, defaultValue = "7", min = "1", max = "3650",
        description = "Youngest age, in days, a file may have to be deleted, whatever the caller asks.")
@Global(name = "MaxDeletesPerCall", type = ValueType.INT, defaultValue = "500", min = "1", max = "100000",
        description = "Most files one call deletes.")
public class FileCleanup {
    static final Pattern GLOB = Pattern.compile("[A-Za-z0-9*?._\\[\\]{},-]{1,100}");
    static final int MAX_LISTED = 100;

    public record OldFile(
            @Param(description = "Path relative to the directory") String path,
            @Param(description = "Size in bytes") long sizeBytes,
            @Param(description = "Last modified, ISO 8601") String modified) {}

    public record Cleanup(
            @Param(description = "The directory cleaned") String directory,
            @Param(description = "True when nothing was deleted because this was a dry run") boolean dryRun,
            @Param(description = "Files older than the age that match the pattern") long matched,
            @Param(description = "Their total size in bytes") long matchedBytes,
            @Param(description = "Files deleted") long deleted,
            @Param(description = "Bytes freed") long freedBytes,
            @Param(description = "Files that could not be deleted") long failed,
            @Param(description = "True when MaxDeletesPerCall stopped the deletes; call again for the rest") boolean limitReached,
            @Param(description = "Oldest matching files, at most 100") List<OldFile> files) {}

    @ServerTool(name = "old-files", title = "Clean up old files",
            description = "Finds files older than a number of days, matching a name pattern, in an approved directory."
                    + " Lists them by default; deletes them only with dryRun false.",
            useWhen = "Use it to free disk space from old logs or exports. Run it as a dry run first and show the user"
                    + " what would be deleted before deleting.",
            effect = EffectSafety.IDEMPOTENT, destructive = true, maxDurationMs = 60_000, maxConcurrency = 1, reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @Example(arguments = "{\"path\":\"/\",\"olderThanDays\":30}", expectedError = "CONFIGURATION")
    public Cleanup oldFiles(
            @Param(description = "Directory to clean: one of the cleanup roots or a directory inside one", maxLength = 500)
            String path,
            @Param(description = "Only files last modified more than this many days ago", min = "1", max = "3650")
            int olderThanDays,
            @Param(description = "File name pattern, such as *.log or export-*.csv", defaultValue = "*", maxLength = 100)
            String pattern,
            @Param(description = "Look in sub-directories too", defaultValue = "false") boolean recursive,
            @Param(description = "List only; set false to delete", defaultValue = "true") boolean dryRun,
            ToolContext context) {
        if (!GLOB.matcher(pattern).matches() || pattern.contains("**")) {
            throw new ToolInputError("pattern must be a file name pattern such as *.log, without directories");
        }
        int minAge = context.global().getIntValue("MinAgeDays");
        if (!dryRun && olderThanDays < minAge) {
            throw new ToolInputError("files younger than " + minAge + " days may not be deleted");
        }
        Path dir = allowed(context, path);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        Instant cutoff = Instant.now().minus(Duration.ofDays(olderThanDays));

        List<OldFile> found = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        long[] bytes = {0};
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(java.nio.file.FileVisitOption.class), recursive ? 32 : 1, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && matcher.matches(file.getFileName())
                            && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        paths.add(file);
                        bytes[0] += attrs.size();
                        found.add(new OldFile(dir.relativize(file).toString().replace('\\', '/'), attrs.size(),
                                attrs.lastModifiedTime().toInstant().toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolInputError("the directory cannot be read");
        }

        long deleted = 0;
        long freed = 0;
        long failed = 0;
        boolean limitReached = false;
        if (!dryRun) {
            int max = context.global().getIntValue("MaxDeletesPerCall");
            for (int i = 0; i < paths.size(); i++) {
                if (deleted >= max) {
                    limitReached = true;
                    break;
                }
                try {
                    // Re-check just before deleting: the file may have been replaced since the walk.
                    BasicFileAttributes now = Files.readAttributes(paths.get(i), BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (!now.isRegularFile() || !now.lastModifiedTime().toInstant().isBefore(cutoff)) continue;
                    Files.delete(paths.get(i));
                    deleted++;
                    freed += now.size();
                } catch (IOException e) {
                    failed++;
                }
            }
            context.log().info("deleted " + deleted + " files (" + freed + " bytes) under " + dir);
        }
        found.sort((a, b) -> a.modified().compareTo(b.modified()));
        return new Cleanup(dir.toString(), dryRun, paths.size(), bytes[0], deleted, freed, failed, limitReached,
                List.copyOf(found.subList(0, Math.min(MAX_LISTED, found.size()))));
    }

    static Path allowed(ToolContext context, String path) {
        String roots = context.global().getStringValue("CleanupRoots").trim();
        if (roots.equals("none") || roots.isEmpty()) throw new ToolConfigurationError("CleanupRoots is not set, so cleanup is disabled");
        if (Arrays.asList(path.split("[/\\\\]")).contains("..")) throw new ToolInputError("path may not contain ..");
        Path dir;
        try {
            dir = Path.of(path).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ToolInputError("no such directory");
        }
        if (!Files.isDirectory(dir)) throw new ToolInputError("path is not a directory");
        for (String root : roots.split(",")) {
            if (root.isBlank()) continue;
            try {
                if (dir.startsWith(Path.of(root.trim()).toRealPath())) return dir;
            } catch (IOException | RuntimeException e) {
                // A root missing on this machine allows nothing.
            }
        }
        throw new ToolInputError("path is outside the cleanup roots");
    }
}
