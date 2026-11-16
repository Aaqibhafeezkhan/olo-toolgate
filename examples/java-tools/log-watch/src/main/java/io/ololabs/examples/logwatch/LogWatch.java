// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.logwatch;

import com.google.re2j.Pattern;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, read-only log observation in the managed device files area. No ambient host-log authority. */
@ToolPackage(id = "examples.log-watch", name = "Log watch", publisher = "OLO Labs examples",
        description = "Reports matching complete records in a bounded managed log file")
public final class LogWatch {
    private static final int MAX_FILE_BYTES = 65_536;
    private static final int MAX_RECORDS = 256;
    private final Clock clock;

    public LogWatch() { this(Clock.systemUTC()); }
    LogWatch(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public record Config(
            @Param(description = "Relative managed log path", pattern = "^[a-z0-9][a-z0-9._ -]{0,127}(/[a-z0-9][a-z0-9._ -]{0,127}){0,7}$") String file,
            @Param(description = "RE2 expression matched independently against each complete line", minLength = 1, maxLength = 256,
                    defaultValue = "ERROR|FATAL") String pattern,
            @Param(description = "Matching records needed within the observation window", defaultValue = "1", min = "1", max = "100") int threshold,
            @Param(description = "Rolling observation window in seconds", defaultValue = "60", min = "1", max = "86400") int windowSeconds,
            @Param(description = "Seconds to suppress further notifications after an alert", defaultValue = "60", min = "0", max = "86400") int cooldownSeconds) {}

    /** The source interval is in UTF-8 bytes, with an exclusive end. Content is untrusted and may be sensitive. */
    public record MatchObserved(String file, long startByte, long endByte, int matchCount,
            String sample, int windowSeconds, boolean rewritten) {}

    @ClientTrigger(name = "error-spike", mode = TriggerMode.POLL, sourceReplay = SourceReplay.STATE_SNAPSHOT,
            description = "Matches new complete managed log records; coalesces error spikes before workflow admission",
            config = Config.class, event = MatchObserved.class, eventType = "examples.logs.match.v1",
            intervalMs = 5_000, maxEventsPerHour = 720, maxEventBytes = 8192)
    @DeviceFileAccess
    @TriggerExample(name = "noise-only", config = "{\"file\":\"logs/app.log\"}",
            files = "{\"logs/app.log\":\"INFO ready\\n\"}", expectedEvents = "[]",
            expectedCheckpoint = """
                    {"format":1,"config":"51ac3dd3088037796eb01d48481ba94f9016d76a926c117833d697f9b3312a32",
                     "offset":11,"prefix":"e049e2eea218a889a27326315851b728b4d38fa452a3524899bcf336817b0f45",
                     "generation":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                     "observedAt":0,"cooldownUntil":0,"matches":[]}
                    """)
    public PollResult<MatchObserved> check(Config config, PollContext ctx) {
        validate(config);
        Pattern pattern = Pattern.compile(config.pattern());
        String identity = hash((config.file() + "\n" + config.pattern() + "\n" + config.threshold()
                + "\n" + config.windowSeconds() + "\n" + config.cooldownSeconds()).getBytes(StandardCharsets.UTF_8));
        Checkpoint prior = ctx.checkpoint().orElse(null);
        if (prior != null && (prior.getLong("format") != 1 || !prior.getString("config").equals(identity))) {
            throw new IllegalStateException("Log checkpoint belongs to another format/configuration; create a fresh source instance");
        }
        if (ctx.files().info(config.file()).bytes() > MAX_FILE_BYTES) {
            throw new IllegalStateException("LogFileTooLarge: managed log must fit 65536 bytes; no records skipped");
        }
        byte[] bytes = ctx.files().readText(config.file()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("LogFileTooLarge: no records skipped");
        int offset = prior == null ? 0 : Math.toIntExact(prior.getLong("offset"));
        if (offset < 0 || offset > MAX_FILE_BYTES) throw new IllegalStateException("Invalid log checkpoint offset");
        boolean rewritten = prior != null && (bytes.length < offset
                || !hash(Arrays.copyOf(bytes, offset)).equals(prior.getString("prefix")));
        String generation = prior == null ? hash(new byte[0]) : prior.getString("generation");
        if (rewritten) {
            // Deterministic until committed, including after an emit retry/crash. This is an observed rewrite,
            // not proof of inode identity or recovery of records removed between polls.
            generation = hash((generation + ":" + hash(bytes)).getBytes(StandardCharsets.UTF_8));
            offset = 0;
        }
        long now = clock.instant().getEpochSecond();
        // A backwards clock must not expire pending matches or shorten a committed cooldown.
        if (prior != null) now = Math.max(now, prior.getLong("observedAt"));
        long cooldown = prior == null ? 0 : prior.getLong("cooldownUntil");
        List<Long> matches = new ArrayList<>();
        if (prior != null && !rewritten) {
            for (Object value : prior.getList("matches")) {
                if (!(value instanceof Long time) || time > now) throw new IllegalStateException("Invalid log checkpoint window");
                if (time > now - config.windowSeconds()) matches.add(time);
            }
            if (matches.size() >= config.threshold()) throw new IllegalStateException("Invalid log checkpoint match count");
        }
        int start = offset;
        int count = matches.size();
        String sample = "";
        int records = 0;
        for (int i = offset; i < bytes.length && records < MAX_RECORDS; i++) {
            if (bytes[i] != '\n') continue;
            if (ctx.stopping()) throw new IllegalStateException("Log check stopped before checkpoint");
            int end = i > offset && bytes[i - 1] == '\r' ? i - 1 : i;
            String line = new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
            if (pattern.matcher(line).find() && now >= cooldown) {
                count++;
                if (matches.size() < config.threshold()) matches.add(now);
                if (sample.isEmpty()) sample = line.substring(0, Math.min(512, line.length()));
            }
            offset = i + 1;
            records++;
        }
        List<TriggerEvent<MatchObserved>> events = new ArrayList<>();
        String prefix = hash(Arrays.copyOf(bytes, offset));
        if (count >= config.threshold() && now >= cooldown) {
            MatchObserved data = new MatchObserved(config.file(), start, offset, count, sample, config.windowSeconds(), rewritten);
            // The same source interval has the same identity if retried before its checkpoint commits.
            String id = "log:" + hash((identity + ":" + generation + ":" + start + ":" + offset + ":" + prefix + ":" + count)
                    .getBytes(StandardCharsets.UTF_8));
            events.add(TriggerEvent.of(data).id(id).subject(config.file()));
            matches.clear();
            cooldown = now + config.cooldownSeconds();
        }
        Checkpoint next = Checkpoint.of(Map.of("format", 1, "config", identity, "offset", offset, "prefix", prefix,
                "generation", generation, "observedAt", count == 0 && cooldown == 0 ? 0 : now,
                "cooldownUntil", cooldown, "matches", matches));
        return PollResult.of(events).checkpoint(next);
    }

    private static void validate(Config c) {
        if (c.file() == null || !c.file().matches("[a-z0-9][a-z0-9._ -]{0,127}(/[a-z0-9][a-z0-9._ -]{0,127}){0,7}"))
            throw new IllegalArgumentException("Invalid managed log path");
        if (c.pattern() == null || c.pattern().isEmpty() || c.pattern().length() > 256
                || c.threshold() < 1 || c.threshold() > 100 || c.windowSeconds() < 1 || c.windowSeconds() > 86400
                || c.cooldownSeconds() < 0 || c.cooldownSeconds() > 86400)
            throw new IllegalArgumentException("Invalid log observation bounds");
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
