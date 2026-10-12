// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.disk;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
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
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Where did the disk space go? {@code space} lists every volume; {@code largest} finds the biggest files and
 * sub-directories under a directory, but only under the roots the administrator allows, without following links,
 * and within a cap on files visited, so a call cannot walk the whole machine.
 */
@ToolPackage(id = "examples.disk", name = "Disk usage", publisher = "ToolGate Examples",
        description = "Reports free space per volume and finds the largest files and folders in allowed directories.")
@Global(name = "AllowedRoots", type = ValueType.STRING, defaultValue = ".",
        description = "Comma-separated directories that disk.largest may look inside, such as /var,/opt/app or D:\\data.")
@Global(name = "MaxFilesVisited", type = ValueType.INT, defaultValue = "200000", min = "100", max = "5000000",
        description = "Most files one disk.largest call looks at; the result says when it stopped early.")
public class DiskUsage {
    public record Volume(
            @Param(description = "Mount point or drive") String mount,
            @Param(description = "File system type, such as ext4 or NTFS") String type,
            @Param(description = "Total size in bytes") long totalBytes,
            @Param(description = "Bytes free for ordinary users") long freeBytes,
            @Param(description = "Percentage in use") double percentUsed,
            @Param(description = "True when the volume is mounted read-only") boolean readOnly) {}

    public record Volumes(@Param(description = "Volumes, fullest first") List<Volume> volumes) {}

    @ServerTool(name = "space", title = "Disk space",
            description = "Lists this machine's disk volumes with size, free space and percent used, fullest first.",
            useWhen = "Use it for 'is any disk full?' or 'how much space is left on D:?'.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{}")
    public Volumes space(
            @Param(description = "Include read-only and in-memory file systems", defaultValue = "false") boolean all,
            ToolContext context) {
        List<Volume> volumes = new ArrayList<>();
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            try {
                long total = store.getTotalSpace();
                if (total == 0 || (!all && (store.isReadOnly() || PSEUDO.contains(store.type())))) continue;
                long free = store.getUsableSpace();
                volumes.add(new Volume(mount(store), store.type(), total, free,
                        Math.round(1000.0 * (total - free) / total) / 10.0, store.isReadOnly()));
            } catch (IOException e) {
                // An unreadable volume, such as an empty drive, is skipped.
            }
        }
        volumes.sort(Comparator.comparingDouble(Volume::percentUsed).reversed());
        return new Volumes(volumes);
    }

    public record Entry(
            @Param(description = "Path relative to the directory searched") String path,
            @Param(description = "Size in bytes; for a directory, the files found in it") long sizeBytes,
            @Param(description = "Last modified, ISO 8601") String modified) {}

    public record Largest(
            @Param(description = "The directory searched") String directory,
            @Param(description = "Largest files, biggest first") List<Entry> files,
            @Param(description = "Immediate sub-directories by total size, biggest first") List<Entry> directories,
            @Param(description = "Total size of the files visited") long totalBytes,
            @Param(description = "Files visited") long filesVisited,
            @Param(description = "True when the walk stopped at MaxFilesVisited") boolean truncated) {}

    @ServerTool(name = "largest", title = "Largest files",
            description = "Finds the largest files and sub-directories under a directory inside the allowed roots,"
                    + " to show what is filling a disk.",
            useWhen = "Use it after disk.space shows a volume nearly full.",
            effect = EffectSafety.READ_ONLY, maxDurationMs = 60_000)
    @Example(arguments = "{\"path\":\"/etc/../..\"}", expectedError = "INVALID_ARGUMENT")
    public Largest largest(
            @Param(description = "Directory to search: one of the allowed roots or a directory inside one", maxLength = 500)
            String path,
            @Param(description = "How many files and directories to return", defaultValue = "20", min = "1", max = "200")
            int limit,
            @Param(description = "How deep to look", defaultValue = "8", min = "1", max = "32") int depth,
            ToolContext context) {
        if (Arrays.asList(path.split("[/\\\\]")).contains("..")) throw new ToolInputError("path may not contain ..");
        Path dir = allowed(context, path);
        int maxFiles = context.global().getIntValue("MaxFilesVisited");
        PriorityQueue<Entry> top = new PriorityQueue<>(Comparator.comparingLong(Entry::sizeBytes));
        Map<Path, Long> children = new HashMap<>();
        long[] totals = {0, 0};
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (++totals[1] > maxFiles) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    totals[0] += attrs.size();
                    top.add(new Entry(relative(dir, file), attrs.size(), attrs.lastModifiedTime().toInstant().toString()));
                    if (top.size() > limit) top.poll();
                    Path rel = dir.relativize(file);
                    if (rel.getNameCount() > 1) children.merge(dir.resolve(rel.getName(0)), attrs.size(), Long::sum);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // No permission to read one directory is not a reason to stop.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolInputError("the directory cannot be read");
        }
        List<Entry> files = new ArrayList<>(top);
        files.sort(Comparator.comparingLong(Entry::sizeBytes).reversed());
        List<Entry> directories = children.entrySet().stream()
                .sorted(Map.Entry.<Path, Long>comparingByValue().reversed())
                .limit(limit)
                .map(c -> new Entry(relative(dir, c.getKey()) + "/", c.getValue(), modified(c.getKey())))
                .toList();
        return new Largest(dir.toString(), files, directories, totals[0], Math.min(totals[1], maxFiles), truncated[0]);
    }

    /** The real directory, which must be one of the allowed roots or inside one. */
    static Path allowed(ToolContext context, String path) {
        Path dir;
        try {
            dir = Path.of(path).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ToolInputError("no such directory");
        }
        if (!Files.isDirectory(dir)) throw new ToolInputError("path is not a directory");
        for (String root : context.global().getStringValue("AllowedRoots").split(",")) {
            if (root.isBlank()) continue;
            try {
                if (dir.startsWith(Path.of(root.trim()).toRealPath())) return dir;
            } catch (IOException | RuntimeException e) {
                // A root missing on this machine allows nothing.
            }
        }
        throw new ToolInputError("path is outside the allowed roots: " + context.global().getStringValue("AllowedRoots"));
    }

    static final List<String> PSEUDO = List.of("tmpfs", "devtmpfs", "proc", "sysfs", "overlay", "squashfs", "cgroup",
            "cgroup2", "devpts", "mqueue", "debugfs", "tracefs", "securityfs", "pstore", "bpf", "autofs", "hugetlbfs",
            "configfs", "fusectl", "nsfs", "ramfs", "binfmt_misc", "efivarfs");

    static String mount(FileStore store) {
        String s = store.toString();
        int paren = s.lastIndexOf(" (");
        return paren > 0 && !s.endsWith(":)") ? s.substring(0, paren) : s;
    }

    static String relative(Path dir, Path p) {
        return dir.relativize(p).toString().replace('\\', '/');
    }

    static String modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant().toString();
        } catch (IOException e) {
            return "";
        }
    }
}
