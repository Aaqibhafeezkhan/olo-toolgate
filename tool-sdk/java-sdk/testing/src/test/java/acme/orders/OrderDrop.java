// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.orders;

import io.ololabs.toolgate.sdk.annotations.ClientTrigger;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.util.ArrayList;
import java.util.List;

/** A trigger for the TriggerHarness tests: reports order files dropped into a folder, by name, after the last one. */
public class OrderDrop {
    public record Config(@Param(description = "Folder") String folder) {}
    public record Dropped(String path, String text) {}

    @ClientTrigger(name = "order-dropped", mode = TriggerMode.POLL, description = "Reports order files dropped into a folder",
            config = Config.class, event = Dropped.class, eventType = "acme.orders.dropped.v1", sourceReplay = SourceReplay.STATE_SNAPSHOT,
            intervalMs = 30_000, maxEventsPerHour = 120)
    @DeviceFileAccess
    @TriggerExample(name = "one-file", config = "{\"folder\":\"drop\"}", files = "{\"drop/1.json\":\"{}\"}",
            expectedEvents = "[{\"id\":\"drop/1.json\",\"data\":{\"path\":\"drop/1.json\",\"text\":\"{}\"}}]", expectedCheckpoint = "{\"last\":\"drop/1.json\"}")
    public PollResult<Dropped> check(Config config, PollContext ctx) {
        String last = ctx.checkpoint().map(c -> c.getString("last")).orElse("");
        List<TriggerEvent<Dropped>> events = new ArrayList<>();
        String newest = last;
        for (String path : ctx.files().list()) {
            if (!path.startsWith(config.folder() + "/") || path.compareTo(last) <= 0) continue;
            events.add(TriggerEvent.of(new Dropped(path, ctx.files().readText(path))).id(path));
            newest = path;
        }
        return newest.isEmpty() ? PollResult.of(events) : PollResult.of(events).checkpoint(Checkpoint.of("last", newest));
    }
}
