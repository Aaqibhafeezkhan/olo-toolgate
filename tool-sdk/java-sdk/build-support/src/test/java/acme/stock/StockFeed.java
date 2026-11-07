// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.stock;

import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTrigger;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.util.List;

/** A trigger for the build library tests: one example passes, one expects the wrong event. */
public class StockFeed {
    public record Config(@Param(description = "SKU") String sku) {}
    public record Low(String sku, long level) {}

    @ServerTrigger(name = "stock-low", mode = TriggerMode.POLL, description = "Reports a low stock level", config = Config.class,
            event = Low.class, eventType = "acme.stock.low.v1", sourceReplay = SourceReplay.STATE_SNAPSHOT, intervalMs = 60_000,
            maxEventsPerHour = 60)
    @TriggerExample(name = "low", config = "{\"sku\":\"a-1\"}", expectedEvents = "[{\"id\":\"low:a-1\",\"data\":{\"sku\":\"a-1\",\"level\":1}}]",
            expectedCheckpoint = "{\"sku\":\"a-1\"}")
    @TriggerExample(name = "wrong", config = "{\"sku\":\"b-2\"}", expectedEvents = "[]")
    public PollResult<Low> check(Config config, PollContext ctx) {
        return PollResult.of(List.of(TriggerEvent.of(new Low(config.sku(), 1)).id("low:" + config.sku()))).checkpoint(Checkpoint.of("sku", config.sku()));
    }
}
