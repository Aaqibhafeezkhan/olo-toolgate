// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.hotfolder;

import io.ololabs.toolgate.sdk.annotations.ClientTool;
import io.ololabs.toolgate.sdk.annotations.ClientTrigger;
import io.ololabs.toolgate.sdk.annotations.Reconciliation;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import io.ololabs.toolgate.sdk.annotations.ModelValidation;
import io.ololabs.toolgate.sdk.annotations.Training;
import io.ololabs.toolgate.sdk.annotations.TrainingPrompt;
import io.ololabs.toolgate.sdk.annotations.TrainingValues;
import io.ololabs.toolgate.sdk.context.DeviceFiles;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolInputError;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * HotFolder: text files in the device's confined files area (ADR 027). Every tool runs on the selected client device
 * and reaches files only through {@code ToolContext.files()}, which the device brokers: it confines paths to its one
 * service-owned folder, refuses links, traversal and other users' files, bounds sizes and counts, and lets a call touch
 * only the paths it was authorized with. Everything else — listing format, search, hashing, copying — is this package.
 *
 * <p>A file that does not exist is reported as data ({@code exists: false}), not as a failure, so callers can test
 * for it; refusals by the device are failures.
 *
 * <p>The {@code file-dropped} client trigger watches one folder (the assignment's {@code folder}, such as
 * {@code inbox}) and reports each text file that appears there. An agent subscribed to those events can read the file
 * with {@code read_text} and file it away with {@code move}, for example to {@code processed/}.
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
                "What files are waiting in {folder}?",
                "Anything new in {folder}?",
                "What's in {folder}?",
                "Show the contents of {folder}"
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
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Anything new in processed?", arguments = "{\"folder\":\"processed\"}"),
                @TrainingPrompt(prompt = "Show the contents of reports", arguments = "{\"folder\":\"reports\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "what files are sitting in inbox right now", arguments = "{\"folder\":\"inbox\"}"),
                @TrainingPrompt(prompt = "List everything in the hot folder", arguments = "{}")
            },
            negatives = {
                "How do I list files in a directory with PowerShell?"
            })
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
                "cat {path}",
                "Show me {path}",
                "Open {path}",
                "Print {path}",
                "Let me see the contents of {path}",
                "What's written in {path}?"
            },
            negatives = "How do I read a text file in Python?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "notes/today.txt",
                        "inbox/order-1042.txt",
                        "reports/summary.md",
                        "config/settings.ini",
                        "inbox/readme.txt"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Show me config/settings.ini", arguments = "{\"path\":\"config/settings.ini\"}"),
                @TrainingPrompt(prompt = "What does reports/summary.md say?", arguments = "{\"path\":\"reports/summary.md\"}"),
                @TrainingPrompt(prompt = "Please read inbox/order-1042.txt", arguments = "{\"path\":\"inbox/order-1042.txt\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "open up notes/ideas.txt for me", arguments = "{\"path\":\"notes/ideas.txt\"}"),
                @TrainingPrompt(prompt = "What's inside outbox/reply.txt right now?", arguments = "{\"path\":\"outbox/reply.txt\"}")
            },
            negatives = {
                "How do I read a file line by line in C#?"
            })
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
                "File size of {path}?",
                "Info on {path}",
                "File size of {path}",
                "Is there a file {path}?"
            },
            negatives = "What's the largest file size NTFS allows?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "inbox/order-1042.txt",
                        "reports/summary.md",
                        "archive/2026-09.zip",
                        "notes/today.txt"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Info on inbox/order-1042.txt", arguments = "{\"path\":\"inbox/order-1042.txt\"}"),
                @TrainingPrompt(prompt = "How big is reports/summary.md?", arguments = "{\"path\":\"reports/summary.md\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "is archive/2026-09.zip there and how large is it?", arguments = "{\"path\":\"archive/2026-09.zip\"}"),
                @TrainingPrompt(prompt = "size of notes/todo.md", arguments = "{\"path\":\"notes/todo.md\"}")
            },
            negatives = {
                "What's the largest file size FAT32 allows?"
            })
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
                "Compute the sha256 for {path}",
                "Fingerprint {path}",
                "Checksum for {path}?",
                "Compute the hash of {path}"
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
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Give me the checksum of reports/summary.md", arguments = "{\"path\":\"reports/summary.md\"}"),
                @TrainingPrompt(prompt = "Hash inbox/order-1042.txt", arguments = "{\"path\":\"inbox/order-1042.txt\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "sha256 for outbox/invoice.txt?", arguments = "{\"path\":\"outbox/invoice.txt\"}"),
                @TrainingPrompt(prompt = "Has notes/today.txt changed? Give me its hash", arguments = "{\"path\":\"notes/today.txt\"}")
            },
            negatives = {
                "Out of curiosity, is SHA-256 better than MD5?"
            })
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
                "Does {path} contain {query}?",
                "Does {query} appear in {path}?",
                "On which lines does {path} mention {query}?",
                "Look for {query} inside {path}"
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
            },
            prompts = {
                @TrainingPrompt(prompt = "Find ERROR in logs/app.log", arguments = "{\"path\":\"logs/app.log\",\"query\":\"ERROR\"}"),
                @TrainingPrompt(prompt = "Which lines of logs/app.log mention timeout?", arguments = "{\"path\":\"logs/app.log\",\"query\":\"timeout\"}"),
                @TrainingPrompt(prompt = "grep invoice in reports/summary.md", arguments = "{\"path\":\"reports/summary.md\",\"query\":\"invoice\"}"),
                @TrainingPrompt(prompt = "Does inbox/order-1042.txt contain TODO?", arguments = "{\"path\":\"inbox/order-1042.txt\",\"query\":\"TODO\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "is the word refund anywhere in inbox/mail-77.txt?", arguments = "{\"path\":\"inbox/mail-77.txt\",\"query\":\"refund\"}"),
                @TrainingPrompt(prompt = "Line numbers where notes/todo.md says urgent", arguments = "{\"path\":\"notes/todo.md\",\"query\":\"urgent\"}")
            },
            negatives = {
                "What is the grep command used for?"
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
                "Replace the contents of {path} with {text}",
                "Save {text} into {path}",
                "Make a file {path} that says {text}",
                "Overwrite {path} with {text}",
                "Put {text} in {path}",
                "Create {path} with the text {text}",
                "Store {text} as {path}",
                "Write a new file {path}: {text}",
                "Set the contents of {path} to {text}"
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
            },
            prompts = {
                @TrainingPrompt(prompt = "Save \"All checks passed\" into outbox/reply.txt", arguments = "{\"path\":\"outbox/reply.txt\",\"text\":\"All checks passed\"}"),
                @TrainingPrompt(prompt = "Make a file notes/today.txt that says Hello team", arguments = "{\"path\":\"notes/today.txt\",\"text\":\"Hello team\"}"),
                @TrainingPrompt(prompt = "Overwrite outbox/status.txt with Remember to call Jane", arguments = "{\"path\":\"outbox/status.txt\",\"text\":\"Remember to call Jane\"}"),
                @TrainingPrompt(prompt = "Replace whatever is in notes/todo.md with Order 1042 shipped", arguments = "{\"path\":\"notes/todo.md\",\"text\":\"Order 1042 shipped\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "pls write 'deploy ok' into status/today.txt", arguments = "{\"path\":\"status/today.txt\",\"text\":\"deploy ok\"}"),
                @TrainingPrompt(prompt = "New file outbox/note.txt, content: Back at 2pm", arguments = "{\"path\":\"outbox/note.txt\",\"text\":\"Back at 2pm\"}"),
                @TrainingPrompt(prompt = "Wipe notes/draft.md and put Final version in it", arguments = "{\"path\":\"notes/draft.md\",\"text\":\"Final version\"}")
            },
            negatives = {
                "What's the difference between overwrite and append?"
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
                "Log {text} in {path}",
                "Add {text} to the end of {path}",
                "Tack {text} onto {path}",
                "Append {text} at the bottom of {path}",
                "Add a line {text} to {path}",
                "Log {text} in {path} without replacing it"
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
            },
            prompts = {
                @TrainingPrompt(prompt = "Add \"Backup done\" at the end of logs/journal.txt", arguments = "{\"path\":\"logs/journal.txt\",\"text\":\"Backup done\"}"),
                @TrainingPrompt(prompt = "Tack Reviewed PR 42 onto notes/todo.md", arguments = "{\"path\":\"notes/todo.md\",\"text\":\"Reviewed PR 42\"}"),
                @TrainingPrompt(prompt = "Add a line Buy milk to notes/today.txt, keep what's there", arguments = "{\"path\":\"notes/today.txt\",\"text\":\"Buy milk\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "add 'call the bank' to my notes/todo.md list", arguments = "{\"path\":\"notes/todo.md\",\"text\":\"call the bank\"}"),
                @TrainingPrompt(prompt = "Append Server rebooted at 03:10 to logs/ops.txt", arguments = "{\"path\":\"logs/ops.txt\",\"text\":\"Server rebooted at 03:10\"}")
            },
            negatives = {
                "How do I append to a list in JavaScript?"
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
                "Add a folder {path} in the hot folder",
                "New folder {path}"
            },
            negatives = "How do I create a folder on the desktop?",
            parameters = @TrainingValues(name = "path",
                    examples = {
                        "archive/2026-10",
                        "inbox/urgent",
                        "reports/q4",
                        "processed"
                    }),
            prompts = {
                @TrainingPrompt(prompt = "Make a new directory called archive/2026-10", arguments = "{\"path\":\"archive/2026-10\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "set up an empty folder reports/2027", arguments = "{\"path\":\"reports/2027\"}"),
                @TrainingPrompt(prompt = "I need a directory named outbox/old", arguments = "{\"path\":\"outbox/old\"}")
            },
            negatives = {
                "How do I create a folder on my desktop?"
            })
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
                "mv {path} {destination}",
                "Rename {path} as {destination}",
                "Relocate {path} to {destination}",
                "Put {path} under {destination} and remove the original"
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
            },
            prompts = {
                @TrainingPrompt(prompt = "Rename inbox/order-1042.txt to archive/today.txt", arguments = "{\"path\":\"inbox/order-1042.txt\",\"destination\":\"archive/today.txt\"}"),
                @TrainingPrompt(prompt = "Rename reports/summary.md as reports/q4/summary.md", arguments = "{\"path\":\"reports/summary.md\",\"destination\":\"reports/q4/summary.md\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "rename notes/draft.txt to notes/final.txt", arguments = "{\"path\":\"notes/draft.txt\",\"destination\":\"notes/final.txt\"}"),
                @TrainingPrompt(prompt = "Shift inbox/scan-5.pdf over to processed/scan-5.pdf", arguments = "{\"path\":\"inbox/scan-5.pdf\",\"destination\":\"processed/scan-5.pdf\"}")
            },
            negatives = {
                "How do I rename a branch in git?"
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
                "cp {path} {destination}",
                "Clone {path} to {destination}",
                "Back up {path} to {destination}"
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
            },
            prompts = {
                @TrainingPrompt(prompt = "Clone notes/today.txt to archive/order-1042.txt", arguments = "{\"path\":\"notes/today.txt\",\"destination\":\"archive/order-1042.txt\"}"),
                @TrainingPrompt(prompt = "Duplicate reports/summary.md as outbox/summary.md", arguments = "{\"path\":\"reports/summary.md\",\"destination\":\"outbox/summary.md\"}")
            })
    @ModelValidation(
            prompts = {
                @TrainingPrompt(prompt = "make a backup copy of config/app.ini at config/app.ini.bak", arguments = "{\"path\":\"config/app.ini\",\"destination\":\"config/app.ini.bak\"}"),
                @TrainingPrompt(prompt = "Copy inbox/order-77.txt into archive/order-77.txt, keep the original", arguments = "{\"path\":\"inbox/order-77.txt\",\"destination\":\"archive/order-77.txt\"}")
            },
            negatives = {
                "What's the keyboard shortcut for copy on a Mac?"
            })
    public Copied copy(@Param(description = "File to copy", maxLength = 1024) String path,
                       @Param(description = "New path", maxLength = 1024) String destination, ToolContext context) {
        var files = context.files();
        if (!exists(files, path)) return new Copied(false);
        if (exists(files, destination)) throw new ToolInputError("The destination already exists");
        files.writeText(destination, files.readText(path));
        return new Copied(true);
    }

    /** The assignment config an administrator sets per device group, usually in the deployment profile. */
    public record WatchConfig(
            @Param(description = "Folder of the HotFolder to watch, such as inbox", pattern = "^[a-z0-9][a-z0-9._ -]{0,127}(/[a-z0-9][a-z0-9._ -]{0,127}){0,7}$")
            String folder,
            @Param(description = "File name suffix to report", defaultValue = ".txt", pattern = "^\\.[a-z0-9]{1,16}$")
            String extension) {}

    /** A file version present in the watched folder that had not been reported. */
    public record FileDropped(
            @Param(description = "File name inside the watched folder") String name,
            @Param(description = "Path in the HotFolder, for read_text and move") String path,
            @Param(description = "Size in bytes") long size) {}

    /**
     * A {@code STATE_SNAPSHOT} poll: an event means "this file version was present at check time and had not been
     * reported", never "this file was created". A file dropped and moved away between two checks is never reported.
     * The checkpoint holds the reported version (path and size) of every file still present, so a file moved out of
     * the folder is pruned and the checkpoint stays as small as the folder.
     */
    @ClientTrigger(name = "file-dropped", mode = TriggerMode.POLL, sourceReplay = SourceReplay.STATE_SNAPSHOT,
            description = "Reports each new text file that appears in a folder of the HotFolder",
            config = WatchConfig.class, event = FileDropped.class, eventType = "examples.hotfolder.file-dropped.v1",
            intervalMs = 10_000, maxEventsPerHour = 600)
    @DeviceFileAccess
    @TriggerExample(name = "new-file",
            config = """
                    {"folder": "inbox"}""",
            files = """
                    {"inbox/order-1042.txt": "2 x widget", "inbox/scan.pdf": "%PDF", "processed/old.txt": "done"}""",
            expectedEvents = """
                    [{"id": "file:inbox/order-1042.txt:10", "subject": "inbox/order-1042.txt",
                      "data": {"name": "order-1042.txt", "path": "inbox/order-1042.txt", "size": 10}}]""",
            expectedCheckpoint = """
                    {"reported": {"inbox/order-1042.txt": 10}}""")
    @TriggerExample(name = "moved-away",
            config = """
                    {"folder": "inbox"}""",
            checkpoint = """
                    {"reported": {"inbox/order-1042.txt": 10}}""",
            files = """
                    {"processed/order-1042.txt": "2 x widget"}""",
            expectedEvents = "[]",
            expectedCheckpoint = """
                    {"reported": {}}""")
    public PollResult<FileDropped> fileDropped(WatchConfig config, PollContext ctx) {
        Map<String, Object> reported = ctx.checkpoint().map(c -> c.getMap("reported")).orElse(Map.of());
        String prefix = config.folder() + "/";
        // A complete listing, or the check fails and the checkpoint stays where it is: a failed listing never prunes.
        List<String> present = new ArrayList<>();
        for (String path : ctx.files().list()) {
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0 && path.endsWith(config.extension())) present.add(path);
        }
        present.sort(null);
        Map<String, Object> next = new TreeMap<>();
        List<TriggerEvent<FileDropped>> events = new ArrayList<>();
        for (String path : present) {
            long size = ctx.files().info(path).bytes();
            next.put(path, size);
            if (Objects.equals(reported.get(path), size)) continue;
            // A stable source identity: the same version gets the same id after a restart, so Control stores it once.
            events.add(TriggerEvent.of(new FileDropped(path.substring(prefix.length()), path, size)).id("file:" + path + ":" + size).subject(path));
        }
        if (!events.isEmpty()) ctx.log().info(events.size() + " new file(s) in " + config.folder());
        return PollResult.of(events).checkpoint(Checkpoint.of("reported", next));
    }
}
