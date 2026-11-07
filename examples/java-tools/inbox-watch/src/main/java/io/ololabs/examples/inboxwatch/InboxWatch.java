// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.inboxwatch;

import io.ololabs.toolgate.sdk.annotations.ClientTrigger;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A client trigger in POLL form: every minute it lists one folder of the device files area and reports each file
 * version present there that it has not reported before.
 *
 * <p>The source is a {@code STATE_SNAPSHOT}: an event means "this file was present at check time with this size and had
 * not been reported", never "this file was created". A file created and deleted between two checks, or while the
 * device was off, is never reported. A version is the file's path and size, because that is what the files area
 * reports in this release; a rewrite that keeps the size is not reported again. The checkpoint holds the reported
 * version of every file still present, so a removed file's entry is pruned and the checkpoint stays as small as the
 * folder.
 */
@ToolPackage(id = "examples.inbox-watch", name = "Inbox watch", publisher = "OLO Labs examples",
        description = "Reports new files in a folder of the device files area")
public final class InboxWatch {
    /** The assignment config an administrator sets per device group. */
    public record Config(
            @Param(description = "Folder in the device files area to watch, such as inbox", pattern = "^[a-z0-9][a-z0-9._ -]{0,127}(/[a-z0-9][a-z0-9._ -]{0,127}){0,7}$")
            String folder,
            @Param(description = "File name suffix to report", defaultValue = ".pdf", pattern = "^\\.[a-z0-9]{1,16}$")
            String extension) {}

    /** One file version present in the folder. */
    public record FileObserved(String name, String path, long size) {}

    @ClientTrigger(name = "new-file", mode = TriggerMode.POLL, sourceReplay = SourceReplay.STATE_SNAPSHOT,
            description = "Reports each file version present in a folder that has not been reported before",
            config = Config.class, event = FileObserved.class, eventType = "examples.files.observed.v1",
            intervalMs = 60_000, maxEventsPerHour = 600)
    @DeviceFileAccess
    @TriggerExample(name = "first-check",
            config = """
                    {"folder": "inbox"}""",
            files = """
                    {"inbox/invoice-1031.pdf": "%PDF-1.7", "inbox/notes.txt": "not a pdf", "outbox/sent.pdf": "%PDF"}""",
            expectedEvents = """
                    [{"id": "file:inbox/invoice-1031.pdf:8", "subject": "inbox/invoice-1031.pdf",
                      "data": {"name": "invoice-1031.pdf", "path": "inbox/invoice-1031.pdf", "size": 8}}]""",
            expectedCheckpoint = """
                    {"reported": {"inbox/invoice-1031.pdf": 8}}""")
    @TriggerExample(name = "already-reported",
            config = """
                    {"folder": "inbox"}""",
            checkpoint = """
                    {"reported": {"inbox/invoice-1031.pdf": 8, "inbox/gone.pdf": 3}}""",
            files = """
                    {"inbox/invoice-1031.pdf": "%PDF-1.7"}""",
            expectedEvents = "[]",
            expectedCheckpoint = """
                    {"reported": {"inbox/invoice-1031.pdf": 8}}""")
    public PollResult<FileObserved> check(Config config, PollContext ctx) {
        Map<String, Object> reported = ctx.checkpoint().map(c -> c.getMap("reported")).orElse(Map.of());
        String prefix = config.folder() + "/";
        // A complete listing, or the check fails and the checkpoint stays where it is: a failed listing never prunes.
        List<String> present = new ArrayList<>();
        for (String path : ctx.files().list()) {
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0 && path.endsWith(config.extension())) present.add(path);
        }
        present.sort(null);   // deterministic emit order
        Map<String, Object> next = new TreeMap<>();
        List<TriggerEvent<FileObserved>> events = new ArrayList<>();
        for (String path : present) {
            long size = ctx.files().info(path).bytes();
            next.put(path, size);
            if (Objects.equals(reported.get(path), size)) continue;
            String name = path.substring(prefix.length());
            // A stable source identity: the same version gets the same id after a restart, so Control stores it once.
            events.add(TriggerEvent.of(new FileObserved(name, path, size)).id("file:" + path + ":" + size).subject(path));
        }
        if (!events.isEmpty()) ctx.log().info(events.size() + " new file version(s) in " + config.folder());
        return PollResult.of(events).checkpoint(Checkpoint.of("reported", next));
    }
}
