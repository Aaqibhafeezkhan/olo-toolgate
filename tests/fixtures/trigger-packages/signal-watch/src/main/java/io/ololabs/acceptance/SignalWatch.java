// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.acceptance;

import io.ololabs.toolgate.sdk.annotations.ClientTrigger;
import io.ololabs.toolgate.sdk.annotations.DeviceFileAccess;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.SourceReplay;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.TriggerMode;
import io.ololabs.toolgate.sdk.annotations.TriggerExample;
import io.ololabs.toolgate.sdk.trigger.Checkpoint;
import io.ololabs.toolgate.sdk.trigger.PollContext;
import io.ololabs.toolgate.sdk.trigger.PollResult;
import io.ololabs.toolgate.sdk.trigger.TriggerEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Acceptance fixture for package adoption; it is not a production observability connector. */
@ToolPackage(id="acceptance.signal-watch",name="Generic package adoption fixture",publisher="OLO Labs")
public final class SignalWatch {
    public record Config(@Param(description="Relative signal file in the managed device files area",
        pattern="^[a-z0-9][a-z0-9._/-]{0,127}$") String file) {}
    public record Signal(String body) {}

    @ClientTrigger(name="signal-ready",mode=TriggerMode.POLL,sourceReplay=SourceReplay.STATE_SNAPSHOT,
        config=Config.class,event=Signal.class,eventType="acceptance.signal.ready.v1",
        description="Reports the contents of each changed bounded signal file",intervalMs=60_000,maxEventsPerHour=60)
    @TriggerExample(name="signal",
        config="{\"file\": \"signals/alert.txt\"}",
        files="{\"signals/alert.txt\": \"Connection pool exhausted\"}",
        expectedEvents="[{\"id\": \"signal:3cf421a8b3b6e0a7a61ec645fb9eaebebfb23722c6c5f6792b04646eb617b98a\", \"subject\": \"signals/alert.txt\", \"data\": {\"body\": \"Connection pool exhausted\"}}]",
        expectedCheckpoint="{\"digest\": \"3cf421a8b3b6e0a7a61ec645fb9eaebebfb23722c6c5f6792b04646eb617b98a\"}")
    @DeviceFileAccess
    public PollResult<Signal> check(Config config,PollContext context) throws Exception {
        if (!context.files().list().contains(config.file())) return PollResult.of(List.of());
        if (context.files().info(config.file()).bytes()>4096) throw new IllegalArgumentException("Signal exceeds fixture bound");
        String body=context.files().readText(config.file());
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        if (context.checkpoint().map(c -> c.getString("digest")).orElse("").equals(digest)) return PollResult.of(List.of());
        return PollResult.of(List.of(TriggerEvent.of(new Signal(body)).id("signal:"+digest).subject(config.file())))
            .checkpoint(Checkpoint.of("digest",digest));
    }
}
