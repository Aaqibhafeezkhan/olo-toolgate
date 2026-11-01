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
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
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
    @Training(
            phrasings = {
                "What's in the {folder} folder?",
                "List the files in {folder}",
                "Show me everything in the HotFolder",
                "Which files are in the hot folder?",
                "ls {folder}",
                "What files are waiting in {folder}?"
            },
            defaultPhrasings = {
                "List the HotFolder",
                "What files are in the hot folder?",
                "What's in the hot folder?",
                "List everything in the HotFolder",
                "Show all hot folder files",
                "ls the hot folder",
                "Which files are in the HotFolder?",
                "List all files and folders",
                "What's in there?",
                "Show the HotFolder contents",
                "hot folder contents?",
                "List the files"
            },
            negatives = {
                "What is a hot folder workflow?",
                "How do I sort files by date in Explorer?"
            },
            parameters = @TrainingValues(name = "folder",
                    examples = {
                        "inbox",
                        "outbox",
                        "archive",
                        "notes",
                        "reports",
                        "processed"
                    }))
    public Listing list(@Param(description = "Folder to list, such as inbox; empty lists everything", maxLength = 1024, required = false) String folder,
                        ToolContext context) {
        String prefix = folder == null || folder.isEmpty() ? "" : folder + "/";
        return new Listing(context.files().list().stream().filter(path -> path.startsWith(prefix)).toList());
    }

    @ClientTool(name = "read_text", title = "Read a text file", description = "Reads a UTF-8 text file from the HotFolder.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"exists\":false,\"text\":\"\"}")
    @Training(
            phrasings = {
                "Read {path}",
                "What does {path} say?",
                "Open {path} and show me the text",
                "Show the contents of {path}",
                "cat {path}"
            },
            negatives = "How do I read a text file in Python?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "notes/today.txt",
                        "inbox/order-1042.txt",
                        "reports/summary.md",
                        "config/settings.ini",
                        "inbox/readme.txt"
                    }))
    public Text readText(@Param(description = "File path, such as notes/today.txt", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        return exists(files, path) ? new Text(true, files.readText(path)) : new Text(false, "");
    }

    @ClientTool(name = "file_info", title = "File size", description = "Reports whether a file exists and its size.",
            effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"bytes\":0,\"exists\":false}")
    @Training(
            phrasings = {
                "Does {path} exist?",
                "How big is {path}?",
                "Check whether {path} is there",
                "File size of {path}?"
            },
            negatives = "What's the largest file size NTFS allows?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "inbox/order-1042.txt",
                        "reports/summary.md",
                        "archive/2026-09.zip",
                        "notes/today.txt"
                    }))
    public Info fileInfo(@Param(description = "File path", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        return exists(files, path) ? new Info(true, files.info(path).bytes()) : new Info(false, 0);
    }

    @ClientTool(name = "hash", title = "Hash a file", description = "SHA-256 of a text file.", effect = EffectSafety.READ_ONLY, stateless = true)
    @DeviceFileAccess
    @Example(arguments = "{\"path\":\"missing.txt\"}", expectedOutput = "{\"exists\":false,\"sha256\":\"\"}")
    @Training(
            phrasings = {
                "SHA-256 of {path}",
                "Give me the checksum of {path}",
                "Hash {path}",
                "Compute the sha256 for {path}"
            },
            negatives = {
                "Is SHA-256 better than MD5?",
                "What is a hash function?"
            },
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "inbox/order-1042.txt",
                        "reports/summary.md",
                        "notes/today.txt"
                    }))
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
    @Training(
            phrasings = {
                "Which lines of {path} mention {query}?",
                "Search {path} for {query}",
                "Find {query} in {path}",
                "grep {query} in {path}",
                "Does {path} contain {query}?"
            },
            negatives = "How do I search inside files on Windows?",
            parameters = {
                @TrainingValues(name = "path",
                    examples = {
                        "notes/today.txt",
                        "logs/app.log",
                        "inbox/order-1042.txt",
                        "reports/summary.md"
                    }),
                @TrainingValues(name = "query",
                    examples = {
                        "ERROR",
                        "invoice",
                        "TODO",
                        "timeout",
                        "Berlin",
                        "refund"
                    })
            })
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
    @Training(
            phrasings = {
                "Write {text} to {path}",
                "Create {path} containing {text}",
                "Save a file {path} with the text {text}",
                "Replace the contents of {path} with {text}"
            },
            negatives = {
                "Write me a short poem about autumn",
                "How do I write to a file in Java?"
            },
            parameters = {
                @TrainingValues(name = "path",
                    examples = {
                        "notes/today.txt",
                        "outbox/reply.txt",
                        "notes/todo.md",
                        "outbox/status.txt"
                    }),
                @TrainingValues(name = "text",
                    examples = {
                        "Meeting moved to 3pm",
                        "Order 1042 shipped",
                        "Remember to call Jane",
                        "All checks passed",
                        "Hello team"
                    })
            })
    public Written writeText(@Param(description = "File path", maxLength = 1024) String path,
                             @Param(description = "UTF-8 text", maxLength = 65536) String text, ToolContext context) {
        context.files().writeText(path, text);
        return new Written(true);
    }

    @ClientTool(name = "append_text", title = "Append to a text file", description = "Appends text, creating the file when needed.",
            effect = EffectSafety.NON_IDEMPOTENT, reconciliation = Reconciliation.MANUAL)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"selftest.txt\",\"text\":\"ok\"}", expectedOutput = "{\"written\":true}")
    @Training(
            phrasings = {
                "Append {text} to {path}",
                "Add the line {text} to {path}",
                "Add {text} at the end of {path}",
                "Log {text} in {path}"
            },
            negatives = "What does append mean in Python lists?",
            parameters = {
                @TrainingValues(name = "path",
                    examples = {
                        "notes/today.txt",
                        "logs/journal.txt",
                        "notes/todo.md"
                    }),
                @TrainingValues(name = "text",
                    examples = {
                        "Called the supplier",
                        "Backup done",
                        "Buy milk",
                        "Reviewed PR 42"
                    })
            })
    public Written appendText(@Param(description = "File path", maxLength = 1024) String path,
                              @Param(description = "UTF-8 text", maxLength = 65536) String text, ToolContext context) {
        context.files().appendText(path, text);
        return new Written(true);
    }

    @ClientTool(name = "mkdir", title = "Create a folder", description = "Creates a folder inside the HotFolder.", effect = EffectSafety.IDEMPOTENT, reconciliation = Reconciliation.DOWNSTREAM_IDEMPOTENCY)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"selftest\"}", expectedOutput = "{\"created\":true}")
    @Training(
            phrasings = {
                "Create a folder {path}",
                "Make a new directory called {path}",
                "mkdir {path}",
                "Add a folder {path} in the hot folder"
            },
            negatives = "How do I create a folder on the desktop?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "archive/2026-10",
                        "inbox/urgent",
                        "reports/q4",
                        "processed"
                    }))
    public Created mkdir(@Param(description = "Folder path", maxLength = 1024) String path, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) files.mkdir(path);
        return new Created(true);
    }

    @ClientTool(name = "move", title = "Move a file", description = "Moves a file to a new path that does not exist yet.",
            effect = EffectSafety.NON_IDEMPOTENT, reconciliation = Reconciliation.MANUAL)
    @DeviceFileAccess(write = true)
    @Example(arguments = "{\"path\":\"missing.txt\",\"destination\":\"moved.txt\"}", expectedOutput = "{\"moved\":false}")
    @Training(
            phrasings = {
                "Move {path} to {destination}",
                "Rename {path} to {destination}",
                "Put {path} into {destination}",
                "mv {path} {destination}"
            },
            negatives = "How do I move files between drives?",
            parameters = {
                @TrainingValues(name = "path",
                    examples = {
                        "inbox/order-1042.txt",
                        "notes/today.txt",
                        "reports/summary.md"
                    }),
                @TrainingValues(name = "destination",
                    examples = {
                        "processed/order-1042.txt",
                        "archive/today.txt",
                        "reports/q4/summary.md"
                    })
            })
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
    @Training(
            phrasings = {
                "Copy {path} to {destination}",
                "Duplicate {path} as {destination}",
                "Make a copy of {path} at {destination}",
                "cp {path} {destination}"
            },
            negatives = "Can you copy-edit this sentence: their going home",
            parameters = {
                @TrainingValues(name = "path",
                    examples = {
                        "inbox/order-1042.txt",
                        "notes/today.txt",
                        "reports/summary.md"
                    }),
                @TrainingValues(name = "destination",
                    examples = {
                        "archive/order-1042.txt",
                        "notes/today-backup.txt",
                        "outbox/summary.md"
                    })
            })
    public Copied copy(@Param(description = "File to copy", maxLength = 1024) String path,
                       @Param(description = "New path", maxLength = 1024) String destination, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Copied(false);
        if (exists(files, destination)) throw new ToolInputError("The destination already exists");
        files.writeText(destination, files.readText(path));
        return new Copied(true);
    }
}
