// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.logs;

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
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Read-only, bounded queries over application log files selected by the administrator. */
@ToolPackage(id = "examples.logs", name = "Log insights", publisher = "ToolGate Examples",
        description = "Searches application logs, counts levels, groups errors and tails a selected file.")
@Global(name = "LogDir", type = ValueType.STRING, defaultValue = "logs", description = "Directory containing readable application logs.")
@Global(name = "LogFiles", type = ValueType.STRING, defaultValue = "*.log,*.log.[0-9],*.txt", description = "Comma-separated allowed file-name globs.")
@Global(name = "LogTimeZone", type = ValueType.STRING, defaultValue = "UTC", description = "Time zone for timestamps without an offset.")
@Global(name = "MaxScanMegabytes", type = ValueType.INT, defaultValue = "200", min = "1", max = "2048", description = "Maximum bytes read per call, in MiB.")
public class LogInsights {
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    private static final Pattern START = Pattern.compile("^\\[?(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\]?\\s*(.*)$");
    private static final Pattern LEVEL = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL|CRITICAL)\\b");
    private static final Pattern EXCEPTION = Pattern.compile("\\b(?:[a-zA-Z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error)\\b");
    private static final int MAX_FILES = 1000;
    private static final int MAX_GROUPS = 1000;
    private static final int MAX_TEXT = 2000;
    private static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> Instant.parse(e.time())).thenComparing(Entry::file).thenComparingLong(Entry::line);

    public record Entry(@Param(description = "File relative to LogDir") String file,
            @Param(description = "First physical line of the entry") long line,
            @Param(description = "Timestamp in UTC") String time,
            @Param(description = "Normalized log level") String level,
            @Param(description = "First line, at most 2000 characters") String message,
            @Param(description = "Continuation lines, at most 2000 characters") String stackTrace) {}
    public record Search(@Param(description = "Matching entries, newest first") List<Entry> entries,
            @Param(description = "True if a scan or text bound was reached") boolean truncated) {}

    @ServerTool(name = "search", title = "Search logs", description = "Finds log entries in a time window, optionally filtering by level and text.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"from\":\"not-a-time\"}", expectedError = "INVALID_ARGUMENT")
    public Search search(
            @Param(description = "Inclusive start, ISO 8601", required = false) String from,
            @Param(description = "Inclusive end, ISO 8601", required = false) String to,
            @Param(description = "Minutes before now when from is empty", defaultValue = "60", min = "1", max = "5256000") int lastMinutes,
            @Param(description = "Minimum level", defaultValue = "TRACE", enumValues = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"}) String minLevel,
            @Param(description = "Case-insensitive message or stack text", required = false, maxLength = 500) String contains,
            @Param(description = "Most entries returned", defaultValue = "50", min = "1", max = "500") int limit,
            ToolContext context) {
        Window window = window(from, to, lastMinutes, context);
        PriorityQueue<Entry> latest = new PriorityQueue<>(ORDER);
        String needle = contains == null ? "" : contains.toLowerCase(Locale.ROOT);
        Budget budget = budget(context);
        scan(context, window, budget, entry -> {
            if (LEVELS.indexOf(entry.level()) < LEVELS.indexOf(minLevel)
                    || !(entry.message() + "\n" + entry.stackTrace()).toLowerCase(Locale.ROOT).contains(needle)) return;
            latest.add(entry);
            if (latest.size() > limit) latest.poll();
        });
        return new Search(latest.stream().sorted(ORDER.reversed()).toList(), budget.truncated);
    }

    public record LevelCount(@Param(description = "Level") String level, @Param(description = "Number of entries") long count) {}
    public record FileCounts(@Param(description = "File relative to LogDir") String file, @Param(description = "Counts by level") List<LevelCount> counts) {}
    public record BucketCounts(@Param(description = "Bucket start in UTC") String time, @Param(description = "Counts by level") List<LevelCount> counts) {}
    public record Levels(@Param(description = "Overall counts") List<LevelCount> counts,
            @Param(description = "Counts per file") List<FileCounts> files,
            @Param(description = "Counts per time bucket") List<BucketCounts> buckets,
            @Param(description = "True if a scan, text or aggregation bound was reached") boolean truncated) {}

    @ServerTool(name = "levels", title = "Log levels", description = "Counts log levels overall, per file and per time bucket.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"bucketMinutes\":0}", expectedError = "INVALID_ARGUMENT")
    public Levels levels(
            @Param(description = "Inclusive start, ISO 8601", required = false) String from,
            @Param(description = "Inclusive end, ISO 8601", required = false) String to,
            @Param(description = "Minutes before now when from is empty", defaultValue = "60", min = "1", max = "5256000") int lastMinutes,
            @Param(description = "Time bucket width in minutes", defaultValue = "60", min = "1", max = "1440") int bucketMinutes,
            ToolContext context) {
        Window window = window(from, to, lastMinutes, context);
        long[] overall = new long[LEVELS.size()];
        Map<String, long[]> files = new HashMap<>();
        Map<Long, long[]> buckets = new HashMap<>();
        Budget budget = budget(context);
        scan(context, window, budget, entry -> {
            int level = LEVELS.indexOf(entry.level());
            overall[level]++;
            files.computeIfAbsent(entry.file(), ignored -> new long[LEVELS.size()])[level]++;
            long seconds = bucketMinutes * 60L;
            long bucket = Math.floorDiv(Instant.parse(entry.time()).getEpochSecond(), seconds) * seconds;
            if (buckets.containsKey(bucket) || buckets.size() < MAX_GROUPS) {
                buckets.computeIfAbsent(bucket, ignored -> new long[LEVELS.size()])[level]++;
            } else budget.truncated = true;
        });
        return new Levels(counts(overall), files.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new FileCounts(e.getKey(), counts(e.getValue()))).toList(),
                buckets.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new BucketCounts(Instant.ofEpochSecond(e.getKey()).toString(), counts(e.getValue()))).toList(), budget.truncated);
    }

    public record ErrorGroup(@Param(description = "Exception class or normalized message") String cause,
            @Param(description = "Occurrences") long count, @Param(description = "First occurrence") String firstSeen,
            @Param(description = "Last occurrence") String lastSeen, @Param(description = "Latest entry and stack") Entry latest) {}
    public record Errors(@Param(description = "Error groups, most frequent first") List<ErrorGroup> groups,
            @Param(description = "True if a scan, text or aggregation bound was reached") boolean truncated) {}

    @ServerTool(name = "errors", title = "Log errors", description = "Groups ERROR and FATAL entries by exception class or message, ignoring varying numbers and IDs.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"from\":\"not-a-time\"}", expectedError = "INVALID_ARGUMENT")
    public Errors errors(
            @Param(description = "Inclusive start, ISO 8601", required = false) String from,
            @Param(description = "Inclusive end, ISO 8601", required = false) String to,
            @Param(description = "Minutes before now when from is empty", defaultValue = "60", min = "1", max = "5256000") int lastMinutes,
            @Param(description = "Most groups returned", defaultValue = "20", min = "1", max = "200") int limit,
            ToolContext context) {
        Window window = window(from, to, lastMinutes, context);
        Map<String, ErrorGroup> groups = new LinkedHashMap<>();
        Budget budget = budget(context);
        scan(context, window, budget, entry -> {
            if (LEVELS.indexOf(entry.level()) < 4) return;
            Matcher exception = EXCEPTION.matcher(entry.message() + "\n" + entry.stackTrace());
            String cause = exception.find() ? exception.group() : entry.message()
                    .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", "<id>").replaceAll("\\b\\d+\\b", "#");
            ErrorGroup previous = groups.get(cause);
            if (previous == null) {
                if (groups.size() < MAX_GROUPS) groups.put(cause, new ErrorGroup(cause, 1, entry.time(), entry.time(), entry));
                else budget.truncated = true;
            } else {
                groups.put(cause, new ErrorGroup(cause, previous.count() + 1,
                        Instant.parse(entry.time()).isBefore(Instant.parse(previous.firstSeen())) ? entry.time() : previous.firstSeen(),
                        Instant.parse(entry.time()).isAfter(Instant.parse(previous.lastSeen())) ? entry.time() : previous.lastSeen(),
                        ORDER.compare(entry, previous.latest()) > 0 ? entry : previous.latest()));
            }
        });
        return new Errors(groups.values().stream().sorted(Comparator.comparingLong(ErrorGroup::count).reversed().thenComparing(ErrorGroup::cause))
                .limit(limit).toList(), budget.truncated);
    }

    public record Tail(@Param(description = "File relative to LogDir") String file,
            @Param(description = "Last lines within the scan bound") List<String> lines,
            @Param(description = "True if scanning stopped before EOF or text was shortened") boolean truncated) {}

    @ServerTool(name = "tail", title = "Tail log", description = "Reads the last lines of an allowed log file; truncated indicates the scan cap was reached before EOF.", effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"file\":\"../secret.log\"}", expectedError = "INVALID_ARGUMENT")
    public Tail tail(
            @Param(description = "File name relative to LogDir", maxLength = 500) String file,
            @Param(description = "Most lines returned", defaultValue = "100", min = "1", max = "1000") int lines,
            ToolContext context) {
        Path relative;
        try { relative = Path.of(file); } catch (RuntimeException e) { throw new ToolInputError("invalid log file"); }
        if (relative.isAbsolute() || relative.getNameCount() > 4 || file.isBlank()) throw new ToolInputError("invalid log file");
        for (Path part : relative) if (part.toString().equals("..")) throw new ToolInputError("file may not contain ..");
        Path root = root(context);
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !allowed(path, matchers(context))) throw new ToolInputError("file is not an allowed log");
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new ToolInputError("log links are not followed");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new ToolInputError("no such log file");
        ArrayDeque<String> last = new ArrayDeque<>();
        Budget budget = budget(context);
        read(path, budget, line -> { last.addLast(line); if (last.size() > lines) last.removeFirst(); });
        return new Tail(root.relativize(path).toString().replace('\\', '/'), List.copyOf(last), budget.truncated);
    }

    private record Window(Instant from, Instant to, ZoneId zone) {}
    private static Window window(String from, String to, int minutes, ToolContext context) {
        // Validate inputs before accessing configured files, also making @Example errors deterministic.
        Instant end = to == null || to.isBlank() ? Instant.now() : inputTime(to);
        Instant start = from == null || from.isBlank() ? end.minusSeconds(minutes * 60L) : inputTime(from);
        if (start.isAfter(end)) throw new ToolInputError("from must not be after to");
        ZoneId zone;
        try { zone = ZoneId.of(context.global().getStringValue("LogTimeZone")); }
        catch (RuntimeException e) { throw new ToolConfigurationError("LogTimeZone is invalid"); }
        return new Window(start, end, zone);
    }

    private static Instant inputTime(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException e) { throw new ToolInputError("times must be ISO 8601 with an offset"); }
    }

    private static Path root(ToolContext context) {
        try {
            Path path = Path.of(context.global().getStringValue("LogDir")).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) throw new IOException();
            return path.toRealPath();
        } catch (IOException | RuntimeException e) { throw new ToolConfigurationError("LogDir is not a readable directory"); }
    }

    private static List<PathMatcher> matchers(ToolContext context) {
        try {
            List<PathMatcher> result = new ArrayList<>();
            for (String glob : context.global().getStringValue("LogFiles").split(",")) {
                if (!glob.isBlank()) result.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.trim()));
            }
            if (result.isEmpty()) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) { throw new ToolConfigurationError("LogFiles contains no valid file-name globs"); }
    }

    private static boolean allowed(Path file, List<PathMatcher> matchers) {
        return matchers.stream().anyMatch(matcher -> matcher.matches(file.getFileName()));
    }

    private static List<LevelCount> counts(long[] counts) {
        List<LevelCount> result = new ArrayList<>();
        for (int i = 0; i < LEVELS.size(); i++) result.add(new LevelCount(LEVELS.get(i), counts[i]));
        return result;
    }

    private static final class Budget {
        long remaining;
        boolean truncated;
        Budget(long bytes) { remaining = bytes; }
    }
    private static Budget budget(ToolContext context) { return new Budget(context.global().getIntValue("MaxScanMegabytes") * 1024L * 1024L); }

    private static void scan(ToolContext context, Window window, Budget budget, Consumer<Entry> consumer) {
        Path root = root(context);
        List<PathMatcher> patterns = matchers(context);
        try (Stream<Path> walk = Files.walk(root, 4)) {
            List<Path> files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && allowed(p, patterns))
                    .limit(MAX_FILES + 1L).toList();
            if (files.size() > MAX_FILES) budget.truncated = true;
            for (Path file : files.stream().limit(MAX_FILES).sorted().toList()) {
                if (budget.remaining == 0) { budget.truncated = true; break; }
                if (Files.getLastModifiedTime(file).toInstant().isBefore(window.from())) continue;
                String name = root.relativize(file).toString().replace('\\', '/');
                Entry[] pending = {null};
                long[] number = {0};
                Consumer<Entry> emit = entry -> {
                    Instant time = Instant.parse(entry.time());
                    if (!time.isBefore(window.from()) && !time.isAfter(window.to())) consumer.accept(entry);
                };
                read(file, budget, line -> {
                    number[0]++;
                    Matcher start = START.matcher(line);
                    Instant time = null;
                    if (start.matches()) {
                        String text = start.group(1).replace(' ', 'T').replace(',', '.').replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
                        try { time = OffsetDateTime.parse(text).toInstant(); }
                        catch (RuntimeException e) {
                            try { time = LocalDateTime.parse(text).atZone(window.zone()).toInstant(); }
                            catch (RuntimeException ignored) { /* Ignore malformed timestamps. */ }
                        }
                    }
                    if (time != null) {
                        if (pending[0] != null) emit.accept(pending[0]);
                        String message = start.group(2);
                        Matcher level = LEVEL.matcher(message.substring(0, Math.min(message.length(), 160)));
                        if (!level.find()) { pending[0] = null; return; }
                        String normalized = switch (level.group()) { case "WARNING" -> "WARN"; case "SEVERE" -> "ERROR"; case "CRITICAL" -> "FATAL"; default -> level.group(); };
                        pending[0] = new Entry(name, number[0], time.toString(), normalized, message, "");
                    } else if (pending[0] != null) {
                        Entry previous = pending[0];
                        String stack = previous.stackTrace().isEmpty() ? line : previous.stackTrace() + "\n" + line;
                        if (stack.length() > MAX_TEXT) { stack = stack.substring(0, MAX_TEXT); budget.truncated = true; }
                        pending[0] = new Entry(name, previous.line(), previous.time(), previous.level(), previous.message(), stack);
                    }
                });
                if (pending[0] != null) emit.accept(pending[0]);
            }
        } catch (IOException e) { throw new ToolInputError("selected logs could not be read"); }
    }

    /** Consume a byte budget, retaining only a bounded prefix of each physical line. */
    private static void read(Path path, Budget budget, Consumer<String> consumer) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean hasBytes = false;
            while (budget.remaining > 0) {
                int value = input.read();
                if (value == -1) break;
                budget.remaining--;
                hasBytes = true;
                if (value == '\n') { consumer.accept(text(line, budget)); line.reset(); hasBytes = false; }
                else if (line.size() < MAX_TEXT * 4) line.write(value);
                else budget.truncated = true;
            }
            if (budget.remaining == 0 && input.read() != -1) budget.truncated = true;
            if (hasBytes) consumer.accept(text(line, budget));
        } catch (IOException e) { throw new ToolInputError("selected log could not be read"); }
    }

    private static String text(ByteArrayOutputStream bytes, Budget budget) {
        String text = bytes.toString(StandardCharsets.UTF_8);
        if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        if (text.length() > MAX_TEXT) { budget.truncated = true; return text.substring(0, MAX_TEXT); }
        return text;
    }
}
