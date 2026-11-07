// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static io.ololabs.toolgate.sdk.processor.Compilation.GENERATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.contracts.v2.TriggerDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Trigger exports (plan 5.8 and 5.9): one compile-error test per rule the processor enforces, and the generated
 * descriptor against {@code TriggerDescriptor} of {@code trigger.schema.json}.
 */
class TriggerDeclarationTest {
    private static final ObjectMapper STRICT = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** The attributes of a valid client POLL trigger; tests replace one at a time. */
    private static final String ATTRIBUTES = "name = \"new-file\", mode = TriggerMode.POLL, description = \"Reports new files\", config = Config.class, "
            + "event = Seen.class, eventType = \"acme.files.observed.v1\", sourceReplay = SourceReplay.STATE_SNAPSHOT, intervalMs = 60000, maxEventsPerHour = 600";
    private static final String METHOD = "public PollResult<Seen> check(Config config, PollContext ctx) { return PollResult.none(); }";

    private static Compilation.Result compile(String body) {
        return Compilation.compile(Map.of("acme/watch/Watch.java", """
                package acme.watch;

                import io.ololabs.toolgate.sdk.annotations.*;
                import io.ololabs.toolgate.sdk.context.*;
                import io.ololabs.toolgate.sdk.trigger.*;
                import java.util.*;

                @ToolPackage(id = "acme.watch", name = "Watch", publisher = "Acme")
                public class Watch {
                    public record Config(@Param(description = "Folder to watch") String folder, @Param(description = "Suffix", defaultValue = ".pdf") String suffix) {}
                    public record Seen(String name, long size) {}
                    public record Other(String name) {}
                    public static class Pojo { public String getName() { return ""; } }
                    public record Out(String value) {}
                %s
                }
                """.formatted(body)), GENERATION);
    }

    private static String client(String attributes, String extra, String method) {
        return "    @ClientTrigger(" + attributes + ")\n" + extra + "    " + method + "\n";
    }

    private static Compilation.Result ok(Compilation.Result r) {
        assertTrue(r.success(), r.errors());
        return r;
    }

    private static void fails(String body, String... fragments) {
        Compilation.Result r = compile(body);
        assertFalse(r.success(), "compiled:\n" + body);
        for (String f : fragments) assertTrue(r.errors().contains(f), () -> "missing \"" + f + "\" in:\n" + r.errors());
    }

    private static JsonNode descriptor(Compilation.Result r, String name) throws Exception {
        return STRICT.readTree(r.resource("META-INF/toolgate/triggers/" + name + ".json"));
    }

    @Test
    void aClientPollTriggerBecomesATriggerDescriptor() throws Exception {
        Compilation.Result r = ok(compile(client(ATTRIBUTES, "    @DeviceFileAccess\n", METHOD)));
        byte[] bytes = r.resource("META-INF/toolgate/triggers/new-file.json");
        JsonNode d = STRICT.readTree(bytes);
        // Structurally a TriggerDescriptor: the generated binding refuses unknown and missing members.
        TriggerDescriptor bound = STRICT.treeToValue(d, TriggerDescriptor.class);
        assertEquals("acme.watch/new-file", bound.triggerId());
        assertTrue(Pattern.matches("^[a-z][a-z0-9-]{1,30}(\\.[a-z][a-z0-9-]{1,30}){1,3}/[a-z][a-z0-9_-]{0,47}$", bound.triggerId()));
        assertEquals("CLIENT_TRIGGER", d.path("kind").asText());
        assertEquals("POLL", d.path("mode").asText());
        assertEquals("[\"CLIENT\"]", d.path("executors").toString());
        assertEquals("[\"DEVICE_FILES_READ\"]", d.path("permissions").toString());
        assertEquals("[\"OCI_SANDBOX\"]", d.path("tiers").toString());
        assertEquals(60000, d.path("schedule").path("intervalMs").asLong());
        assertEquals("{\"maxBrokerCallsPerMinute\":120,\"maxCheckpointBytes\":16384,\"maxEventBytes\":16384,\"maxEventsPerHour\":600,\"memoryMiB\":256,\"stopGraceMs\":10000}",
                d.path("limits").toString());
        assertEquals("STATE_SNAPSHOT", d.path("sourceReplay").asText());
        assertEquals("BLOCK", d.path("overflow").asText());
        assertEquals("HALT", d.path("onTerminalRejection").asText());
        assertEquals(1, d.path("checkpointSchemaVersion").asInt());
        assertEquals("[\"folder\"]", d.path("configSchema").path("required").toString());
        assertEquals(".pdf", d.path("configSchema").path("properties").path("suffix").path("default").asText());
        assertEquals("[\"name\",\"size\"]", d.path("eventSchema").path("required").toString());
        assertFalse(d.path("eventSchema").path("additionalProperties").asBoolean(true));
        // Canonical bytes (RFC 8785): what the index digests is exactly the file.
        assertEquals(new String(Jcs.bytes(STRICT.convertValue(d, Map.class)), StandardCharsets.UTF_8), new String(bytes, StandardCharsets.UTF_8));
        JsonNode index = STRICT.readTree(r.resource("META-INF/toolgate/package.json"));
        assertEquals("[]", index.path("tools").toString());
        JsonNode entry = index.path("triggers").path(0);
        assertEquals(Jcs.sha256(bytes), entry.path("triggerDescriptorDigest").asText());
        assertEquals("acme.watch.Watch", entry.path("handlerClass").asText());
        assertEquals("check", entry.path("handlerMethod").asText());
        assertEquals("acme.watch.Watch$Config", entry.path("configClass").asText());
        assertEquals("acme.watch.Watch$Seen", entry.path("eventClass").asText());
        assertEquals("Reports new files", entry.path("description").asText());
    }

    @Test
    void aServerTriggerWithoutCapabilitiesRunsOnBothExecutors() throws Exception {
        Compilation.Result r = ok(compile("    @ServerTrigger(" + ATTRIBUTES.replace("new-file", "tick") + ")\n    " + METHOD + "\n"));
        JsonNode d = descriptor(r, "tick");
        STRICT.treeToValue(d, TriggerDescriptor.class);
        assertEquals("SERVER_TRIGGER", d.path("kind").asText());
        assertEquals("[\"CLIENT\",\"SERVER\"]", d.path("executors").toString());
        assertEquals("[]", d.path("permissions").toString());
        assertEquals("[]", d.path("routes").toString());
        assertEquals("[]", d.path("secrets").toString());
    }

    @Test
    void examplesBecomeSelfTestsAndIndexEntries() throws Exception {
        Compilation.Result r = ok(compile(client(ATTRIBUTES, """
                    @DeviceFileAccess
                    @TriggerExample(name = "first-run", config = "{\\"folder\\":\\"in\\"}", files = "{\\"in/a.pdf\\":\\"x\\"}", expectedEvents = "[]")
                    @TriggerExample(name = "empty", config = "{\\"folder\\":\\"in\\"}", expectedEvents = "[]")
                """, METHOD)));
        assertEquals("[\"empty\",\"first-run\"]", descriptor(r, "new-file").path("selfTests").toString());
        JsonNode examples = STRICT.readTree(r.resource("META-INF/toolgate/package.json")).path("triggers").path(0).path("examples");
        assertEquals(2, examples.size());
        assertEquals("first-run", examples.path(0).path("name").asText());
        assertEquals("{\"in/a.pdf\":\"x\"}", examples.path(0).path("files").asText());
        fails(client(ATTRIBUTES, "    @TriggerExample(name = \"Bad\", config = \"{}\", expectedEvents = \"[]\")\n", METHOD), "trigger example name Bad");
        fails(client(ATTRIBUTES, "    @TriggerExample(name = \"a\", config = \"{}\", expectedEvents = \"[]\")\n    @TriggerExample(name = \"a\", config = \"{}\", expectedEvents = \"[]\")\n", METHOD),
                "trigger example a is declared twice");
        fails("    @ServerTrigger(" + ATTRIBUTES + ")\n    @TriggerExample(name = \"a\", config = \"{}\", files = \"{}\", expectedEvents = \"[]\")\n    " + METHOD,
                "only a @ClientTrigger reads");
        fails("    @ServerTool(name = \"t\", title = \"T\", description = \"D\")\n    @TriggerExample(name = \"a\", config = \"{}\", expectedEvents = \"[]\")\n    public Out t() { return null; }",
                "@TriggerExample belongs on a @ServerTrigger or @ClientTrigger method");
    }

    /** 5.8: a POLL trigger is an annotated method returning PollResult<E>. */
    @Test
    void aPollTriggerIsAMethodReturningPollResultOfTheEvent() {
        fails(client(ATTRIBUTES, "", "public Seen check(Config config, PollContext ctx) { return null; }"), "returns PollResult<Seen>");
        fails(client(ATTRIBUTES, "", "public PollResult<Other> check(Config config, PollContext ctx) { return null; }"), "returns PollResult<Seen>");
        fails(client(ATTRIBUTES, "", "public PollResult<Seen> check(PollContext ctx) { return null; }"), "PollResult<Seen> check(Config config, PollContext ctx)");
        fails(client(ATTRIBUTES, "", "public PollResult<Seen> check(Other config, PollContext ctx) { return null; }"), "check(Config config, PollContext ctx)");
        fails(client(ATTRIBUTES, "", "public static PollResult<Seen> check(Config config, PollContext ctx) { return null; }"), "must be a public instance method");
        fails(client(ATTRIBUTES, "", "PollResult<Seen> check(Config config, PollContext ctx) { return null; }"), "must be a public instance method");
        fails(client(ATTRIBUTES, "", METHOD) + "    public Watch(int x) {}\n", "needs a public no-argument constructor");
        fails(client(ATTRIBUTES.replace("TriggerMode.POLL", "TriggerMode.PUSH"), "", METHOD), "mode = PUSH is not supported");
    }

    /** 5.8: config and event types are records the descriptor generator supports. */
    @Test
    void configAndEventTypesAreRecords() {
        fails(client(ATTRIBUTES.replace("config = Config.class", "config = Pojo.class"), "", "public PollResult<Seen> check(Pojo config, PollContext ctx) { return null; }"),
                "config type acme.watch.Watch.Pojo must be a record");
        fails(client(ATTRIBUTES.replace("event = Seen.class", "event = Pojo.class"), "", "public PollResult<Pojo> check(Config config, PollContext ctx) { return null; }"),
                "event type acme.watch.Watch.Pojo must be a record");
        fails(client(ATTRIBUTES.replace("event = Seen.class", "event = String.class"), "", "public PollResult<String> check(Config config, PollContext ctx) { return null; }"),
                "must be a record");
    }

    /** 5.8: event data fits 16 KiB serialized, checkpoints 16 KiB (128 KiB only with SnapshotState). */
    @Test
    void sizeLimitsAreBounded() {
        fails(client(ATTRIBUTES + ", maxEventBytes = 16385", "", METHOD), "maxEventBytes (event data must fit 16 KiB serialized) must be 1..16384");
        fails(client(ATTRIBUTES + ", maxEventBytes = 0", "", METHOD), "maxEventBytes");
        fails(client(ATTRIBUTES + ", maxCheckpointBytes = 131072", "", METHOD), "maxCheckpointBytes above 16384 needs a STATE_SNAPSHOT source using SnapshotState");
        fails(client(ATTRIBUTES + ", maxCheckpointBytes = 0", "", METHOD), "maxCheckpointBytes must be 1..16384");
        fails(client(ATTRIBUTES + ", memoryMiB = 16", "", METHOD), "memoryMiB must be 32..4096");
        fails(client(ATTRIBUTES + ", stopGraceMs = 30001", "", METHOD), "stopGraceMs must be 0..30000");
        fails(client(ATTRIBUTES + ", maxBrokerCallsPerMinute = 6001", "", METHOD), "maxBrokerCallsPerMinute must be 0..6000");
        fails(client(ATTRIBUTES + ", checkpointSchemaVersion = 0", "", METHOD), "checkpointSchemaVersion must be 1..65535");
    }

    /** 5.8: eventType is required, reverse-DNS, and ends with a major version. */
    @Test
    void eventTypeIsReverseDnsWithAMajorVersion() {
        fails(client(ATTRIBUTES.replace("acme.files.observed.v1", "acme.files.observed"), "", METHOD), "must be reverse-DNS and end with a major version");
        fails(client(ATTRIBUTES.replace("acme.files.observed.v1", "observed.v0"), "", METHOD), "must be reverse-DNS");
        fails(client(ATTRIBUTES.replace("acme.files.observed.v1", "Acme.Files.v1"), "", METHOD), "must be reverse-DNS");
        fails(client(ATTRIBUTES.replace("acme.files.observed.v1", "io.ololabs.toolgate.trigger.state.v1"), "", METHOD), "reserved prefix io.ololabs.toolgate.");
        ok(compile(client(ATTRIBUTES.replace("acme.files.observed.v1", "acme.files.observed.v12"), "", METHOD)));
    }

    /** 5.8: POLL intervalMs ≥ 5000; maxEventsPerHour ≤ 3600. */
    @Test
    void scheduleAndRateAreBounded() {
        fails(client(ATTRIBUTES.replace("intervalMs = 60000", "intervalMs = 4999"), "", METHOD), "POLL intervalMs must be 5000..86400000");
        fails(client(ATTRIBUTES.replace(", intervalMs = 60000", ""), "", METHOD), "POLL trigger new-file needs intervalMs");
        ok(compile(client(ATTRIBUTES.replace("intervalMs = 60000", "intervalMs = 5000"), "", METHOD)));
        fails(client(ATTRIBUTES + ", resyncIntervalMs = 60000", "", METHOD), "resyncIntervalMs applies to PUSH triggers");
        fails(client(ATTRIBUTES.replace("maxEventsPerHour = 600", "maxEventsPerHour = 3601"), "", METHOD), "maxEventsPerHour must be 1..3600");
        fails(client(ATTRIBUTES.replace("maxEventsPerHour = 600", "maxEventsPerHour = 0"), "", METHOD), "maxEventsPerHour must be 1..3600");
    }

    /** 5.8: @ClientTrigger may use only @DeviceFileAccess, read-only; server-trigger capabilities are not in this SDK version. */
    @Test
    void capabilitiesFollowTheExecutor() {
        fails(client(ATTRIBUTES, "    @DeviceFileAccess(write = true)\n", METHOD), "a trigger's device file access is read-only");
        fails(client(ATTRIBUTES, "    @Secret(name = \"ApiKey\", type = ValueType.STRING)\n", METHOD), "cannot declare @Secret");
        fails(client(ATTRIBUTES, "    @JwtClaim(name = \"Sub\", claim = \"sub\", type = ValueType.STRING)\n", METHOD), "cannot declare @JwtClaim");
        fails("    @ServerTrigger(" + ATTRIBUTES + ")\n    @DeviceFileAccess\n    " + METHOD, "@DeviceFileAccess is only for a @ClientTrigger");
        fails("    @ServerTrigger(" + ATTRIBUTES + ")\n    @Secret(name = \"ApiKey\", type = ValueType.STRING)\n    " + METHOD, "cannot declare @Secret");
        fails(client(ATTRIBUTES + ", tiers = {}", "", METHOD), "a client trigger needs at least one tier");
    }

    /** 5.8: overflow is BLOCK by default; DROP_* only for lossTolerant types, and needs SKIP_AND_RECORD. */
    @Test
    void dropOverflowNeedsALossTolerantTypeAndLossRecords() throws Exception {
        fails(client(ATTRIBUTES + ", overflow = OverflowPolicy.DROP_OLDEST", "", METHOD), "allowed only for a lossTolerant event type");
        fails(client(ATTRIBUTES + ", overflow = OverflowPolicy.DROP_NEWEST, lossTolerant = true", "", METHOD), "needs onTerminalRejection = SKIP_AND_RECORD");
        Compilation.Result r = ok(compile(client(ATTRIBUTES + ", overflow = OverflowPolicy.DROP_NEWEST, lossTolerant = true, onTerminalRejection = TerminalRejectionPolicy.SKIP_AND_RECORD", "", METHOD)));
        JsonNode d = descriptor(r, "new-file");
        assertEquals("DROP_NEWEST", d.path("overflow").asText());
        assertTrue(d.path("lossTolerant").asBoolean());
        assertEquals("SKIP_AND_RECORD", d.path("onTerminalRejection").asText());
    }

    /** 5.8: trigger names share the package export namespace with tools. */
    @Test
    void triggerNamesShareTheExportNamespaceWithTools() {
        fails(client(ATTRIBUTES.replace("new-file", "lookup"), "", METHOD)
                + "    @ServerTool(name = \"lookup\", title = \"T\", description = \"D\")\n    public Out lookup() { return null; }\n",
                "trigger lookup has the name of a tool; tools and triggers share one namespace");
        fails(client(ATTRIBUTES, "", METHOD) + client(ATTRIBUTES, "", METHOD.replace("check(", "again(")), "trigger new-file is declared twice");
        fails(client(ATTRIBUTES.replace("new-file", "new.file"), "", METHOD), "trigger name new.file does not match");
        fails(client(ATTRIBUTES.replace("new-file", "toolgate-x"), "", METHOD), "reserved prefix toolgate");
        fails("    @ServerTrigger(" + ATTRIBUTES + ")\n    @ServerTool(name = \"t\", title = \"T\", description = \"D\")\n    " + METHOD,
                "a tool handler or a trigger, not both");
        fails(client(ATTRIBUTES, "    @Example(arguments = \"{}\")\n", METHOD), "use @TriggerExample");
        Compilation.Result both = ok(compile(client(ATTRIBUTES, "", METHOD)
                + "    @ServerTool(name = \"lookup\", title = \"T\", description = \"D\")\n    public Out lookup() { return null; }\n"));
        assertTrue(new String(both.resource("META-INF/toolgate/package.json"), StandardCharsets.UTF_8).contains("\"tools\":[{"));
    }

    @Test
    void theDescriptionIsRequiredAndBounded() {
        fails(client(ATTRIBUTES.replace("description = \"Reports new files\"", "description = \"\""), "", METHOD), "trigger new-file description must be 1-1024 characters");
    }
}
