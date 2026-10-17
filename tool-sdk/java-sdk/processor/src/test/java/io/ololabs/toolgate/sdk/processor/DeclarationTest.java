// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static io.ololabs.toolgate.sdk.processor.Compilation.GENERATION;
import static io.ololabs.toolgate.sdk.processor.Compilation.tools;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conformance: the sdk-unit declarations cases and descriptors/generation-required for Java. */
class DeclarationTest {
    private static final String TOOL = """
                @ServerTool(name = "%s", title = "T", description = "D")
                public Out run(@Param(description = "Id") String id) { return null; }
            """;

    private static Compilation.Result ok(Compilation.Result r) {
        assertTrue(r.success(), r.errors());
        return r;
    }

    private static void fails(Compilation.Result r, String... fragments) {
        assertFalse(r.success(), "compiled");
        for (String f : fragments) assertTrue(r.errors().contains(f), () -> "missing \"" + f + "\" in:\n" + r.errors());
    }

    @Test
    void generationIsRequiredAndHasNoDefault() {
        fails(tools("", TOOL.formatted("items.list")), "toolgate.descriptorGeneration");
        fails(tools("", TOOL.formatted("items.list"), "-Atoolgate.descriptorGeneration=2"), "is not supported");
        ok(tools("", TOOL.formatted("items.list"), GENERATION));
    }

    @Test
    void identifiersFollowTheirGrammars() {
        fails(tools("", TOOL.formatted("Items.list"), GENERATION), "tool name Items.list");
        fails(tools("", TOOL.formatted("a.b.c.d"), GENERATION), "tool name a.b.c.d");
        ok(tools("", TOOL.formatted("a.b.c"), GENERATION));
        fails(tools("", TOOL.formatted("items.list").replace("String id", "@Param(name = \"1id\", description = \"x\") String other").replace("@Param(description = \"Id\") ", ""), GENERATION), "input name 1id");
        fails(tools("@HttpRoute(id = \"Bad\", destination = \"api\", method = HttpMethod.GET, path = \"/x\")", TOOL.formatted("items.list"), GENERATION), "route id Bad");
        fails(tools("", """
                @ServerTool(name = "items.list", title = "T", description = "D", errors = @ErrorDecl(code = "AB", description = "x"))
                public Out run() { return null; }
            """, GENERATION), "error code AB");
        fails(compileWithPackageId("Acme.demo"), "package id Acme.demo");
        fails(compileWithPackageId("acme"), "package id acme");
    }

    @Test
    void toolIdIsAtMost128Characters() {
        // "acme.demo/" is 10 characters, so a 118-character local name gives a 128-character toolId.
        String seg = "a".repeat(38);
        ok(tools("", TOOL.formatted(seg + "." + seg + "." + "c".repeat(40)), GENERATION));
        fails(tools("", TOOL.formatted(seg + "." + seg + "." + "c".repeat(41)), GENERATION), "longer than 128");
    }

    @Test
    void deviceFileAccessIsAClientToolDeclarationThatDerivesItsPermissions() {
        var r = ok(tools("", """
                @ClientTool(name = "notes.read", title = "T", description = "D")
                @DeviceFileAccess
                public Out read(@Param(description = "Path") String path) { return null; }
                @ClientTool(name = "notes.save", title = "T", description = "D")
                @DeviceFileAccess(write = true)
                public Out save(@Param(description = "Path") String path) { return null; }
                """, GENERATION));
        assertTrue(r.descriptor("notes.read").contains("DEVICE_FILES_READ"), r.descriptor("notes.read"));
        assertFalse(r.descriptor("notes.read").contains("DEVICE_FILES_WRITE"), r.descriptor("notes.read"));
        assertTrue(r.descriptor("notes.save").contains("DEVICE_FILES_WRITE"), r.descriptor("notes.save"));
        fails(tools("", TOOL.formatted("items.list").replace("@ServerTool", "@DeviceFileAccess @ServerTool"), GENERATION), "@DeviceFileAccess is only for a @ClientTool");
    }

    private static Compilation.Result compileWithPackageId(String id) {
        return Compilation.compile(Map.of("acme/demo/Tools.java", """
                package acme.demo;
                import io.ololabs.toolgate.sdk.annotations.*;
                @ToolPackage(id = "%s", name = "Demo", publisher = "Acme")
                public class Tools {
                    public record Out(String value) {}
                    @ServerTool(name = "items.list", title = "T", description = "D")
                    public Out run() { return null; }
                }
                """.formatted(id)), GENERATION);
    }

    @Test
    void reservedPrefixesAreRejected() {
        fails(compileWithPackageId("toolgate.demo"), "reserved prefix toolgate.");
    }

    @Test
    void variableNamesAreUniqueIgnoringCase() {
        fails(tools("@Global(name = \"ID\", type = ValueType.STRING)", TOOL.formatted("items.list"), GENERATION), "input id", "global ID");
        fails(tools("@Secret(name = \"Token\") @JwtClaim(name = \"token\", claim = \"sub\")", TOOL.formatted("items.list"), GENERATION), "claim token", "secret Token");
    }

    @Test
    void toolLevelDeclarationsNeverOverrideScopeLevelOnes() {
        fails(tools("@Global(name = \"Limit\", type = ValueType.INT)", """
                @ServerTool(name = "items.list", title = "T", description = "D")
                @Global(name = "Limit", type = ValueType.INT, defaultValue = "5")
                public Out run() { return null; }
            """, GENERATION), "cannot override", "global Limit");
    }

    @Test
    void toolAttributesTakeTheSpecifiedDefaults() {
        Compilation.Result r = ok(tools("", """
                @ServerTool(name = "items.get", title = "T", description = "D", routes = "items.get")
                public Out get() { return null; }
                @ClientTool(name = "items.local", title = "T", description = "D")
                public Out local() { return null; }
            """, GENERATION));
        String server = r.descriptor("items.get");
        String client = r.descriptor("items.local");
        assertTrue(server.contains("\"effect\":{\"destructive\":false,\"openWorld\":true,\"safety\":\"UNKNOWN\"}"), server);
        assertTrue(client.contains("\"effect\":{\"destructive\":false,\"openWorld\":false,\"safety\":\"UNKNOWN\"}"), client);
        for (String d : new String[] {server, client}) {
            assertTrue(d.contains("\"execution\":{\"async\":false,\"cancelIfAbandoned\":false,\"maxDurationMs\":30000,\"stateless\":false}"), d);
            assertTrue(d.contains("\"businessKey\":{\"namespace\":\"AGENT\",\"required\":false}"), d);
        }
        assertTrue(client.contains("\"tiers\":[\"OCI_SANDBOX\"]"), client);
        assertFalse(server.contains("\"tiers\""), server);
        assertTrue(server.contains("\"permissions\":[\"HTTP_ROUTE\"]"), server);
        assertTrue(server.contains("\"destinations\":[{\"hostSetting\":\"ApiHost\",\"id\":\"api\",\"port\":443,\"spkiPins\":[]}]"), server);
    }

    @Test
    void nonIdempotentDestructiveToolsRequireABusinessKey() {
        Compilation.Result r = ok(tools("", """
                @ServerTool(name = "items.delete", title = "T", description = "D", effect = EffectSafety.NON_IDEMPOTENT, destructive = true)
                public Out delete() { return null; }
                @ServerTool(name = "items.create", title = "T", description = "D", effect = EffectSafety.NON_IDEMPOTENT)
                public Out create() { return null; }
                @ServerTool(name = "items.purge", title = "T", description = "D", effect = EffectSafety.NON_IDEMPOTENT, destructive = true,
                        requiresBusinessKey = BusinessKey.NOT_REQUIRED)
                public Out purge() { return null; }
            """, GENERATION));
        assertTrue(r.descriptor("items.delete").contains("\"businessKey\":{\"namespace\":\"AGENT\",\"required\":true}"));
        assertTrue(r.descriptor("items.create").contains("\"businessKey\":{\"namespace\":\"AGENT\",\"required\":false}"));
        assertTrue(r.descriptor("items.purge").contains("\"businessKey\":{\"namespace\":\"AGENT\",\"required\":false}"));
    }

    @Test
    void asyncToolsDefaultTo900000Ms() {
        Compilation.Result r = ok(tools("", """
                @ServerTool(name = "items.export", title = "T", description = "D", async = true)
                public Out export() { return null; }
            """, GENERATION));
        assertTrue(r.descriptor("items.export").contains("\"maxDurationMs\":900000"));
        fails(tools("", """
                @ServerTool(name = "items.slow", title = "T", description = "D", maxDurationMs = 120000)
                public Out slow() { return null; }
            """, GENERATION), "make the tool async");
    }

    @Test
    void titleAndTextAreExclusiveAliases() {
        Compilation.Result r = ok(tools("", """
                @ServerTool(name = "items.list", text = "Listed", description = "D")
                public Out run() { return null; }
            """, GENERATION));
        assertTrue(r.descriptor("items.list").contains("\"title\":\"Listed\""));
        assertFalse(r.descriptor("items.list").contains("\"text\""));
        fails(tools("", """
                @ServerTool(name = "items.list", title = "A", text = "B", description = "D")
                public Out run() { return null; }
            """, GENERATION), "exactly one of title and text");
        fails(tools("", """
                @ServerTool(name = "items.list", description = "D")
                public Out run() { return null; }
            """, GENERATION), "exactly one of title and text");
    }

    @Test
    void clientToolsCannotDeclareEgressOrSecrets() {
        String client = """
                @ClientTool(name = "items.local", title = "T", description = "D"%s)
                public Out local() { return null; }
            """;
        // routes and tcp are not members of @ClientTool, so javac refuses them.
        assertFalse(tools("", client.formatted(", routes = \"items.get\""), GENERATION).success());
        assertFalse(tools("", client.formatted(", tcp = \"db\""), GENERATION).success());
        fails(tools("@Secret(name = \"Token\")", client.formatted(""), GENERATION), "client tool cannot use secrets");
        fails(tools("@HttpAuth(id = \"key\", type = AuthType.API_KEY, header = \"X-Key\")", client.formatted(""), GENERATION), "cannot be declared next to @HttpAuth");
    }

    @Test
    void typedAndGroupedFormsGiveIdenticalBytes() {
        Compilation.Result typed = ok(tools("", """
                @ServerTool(name = "items.find", title = "T", description = "D")
                public Out find(@Param(description = "Order number", pattern = "^[0-9]+$") String orderId,
                        @Param(description = "How many", min = "1", required = false) Integer count,
                        @Param(description = "Sort", enumValues = {"ASC", "DESC"}, defaultValue = "ASC") String sort,
                        @Param(description = "Token", sensitive = true) String token,
                        ToolContext context) { return null; }
            """, GENERATION));
        Compilation.Result grouped = ok(tools("", """
                @ServerTool(name = "items.find", title = "T", description = "D")
                @ToolVariables(inputs = {
                    @Variable(name = "orderId", description = "Order number", type = ValueType.STRING, pattern = "^[0-9]+$"),
                    @Variable(name = "count", description = "How many", type = ValueType.INT, min = "1", required = false),
                    @Variable(name = "sort", description = "Sort", type = ValueType.ENUM, enumValues = {"ASC", "DESC"}, defaultValue = "ASC"),
                    @Variable(name = "token", description = "Token", type = ValueType.STRING, sensitive = true)
                })
                public Out find(ToolContext context) { return null; }
            """, GENERATION));
        assertEquals(typed.descriptor("items.find"), grouped.descriptor("items.find"));
        assertTrue(typed.descriptor("items.find").contains(
                "\"count\":{\"description\":\"How many\",\"maximum\":2147483647,\"minimum\":1,\"type\":\"integer\"}"));
        assertTrue(typed.descriptor("items.find").contains("\"required\":[\"orderId\",\"token\"]"));
        assertTrue(typed.descriptor("items.find").contains("\"x-toolgate-sensitive\":true"));
    }

    @Test
    void objectsAreClosedUnlessExplicitMaps() {
        Compilation.Result r = ok(tools("", """
                public record Address(String street, Optional<String> zip) {}
                @ServerTool(name = "items.put", title = "T", description = "D")
                public Out put(@Param(description = "Where") Address address,
                        @Param(description = "Counts") Map<String, Integer> counts,
                        @Param(description = "Ids") List<Long> ids) { return null; }
            """, GENERATION));
        String d = r.descriptor("items.put");
        assertTrue(d.contains("\"address\":{\"additionalProperties\":false,\"description\":\"Where\",\"properties\":{\"street\":{\"type\":\"string\"},"
                + "\"zip\":{\"type\":\"string\"}},\"required\":[\"street\"],\"type\":\"object\"}"), d);
        assertTrue(d.contains("\"counts\":{\"additionalProperties\":{\"maximum\":2147483647,\"minimum\":-2147483648,\"type\":\"integer\"},"
                + "\"description\":\"Counts\",\"type\":\"object\"}"), d);
        assertTrue(d.contains("\"ids\":{\"description\":\"Ids\",\"items\":{\"maximum\":9007199254740991,\"minimum\":-9007199254740991,\"type\":\"integer\"},\"type\":\"array\"}"), d);
    }

    @Test
    void recursiveTypesUseDefs() {
        Compilation.Result r = ok(tools("", """
                public record Node(String name, List<Node> children) {}
                @ServerTool(name = "items.tree", title = "T", description = "D")
                public Node tree() { return null; }
            """, GENERATION));
        String d = r.descriptor("items.tree");
        assertTrue(d.contains("\"outputSchema\":{\"$defs\":{\"Node\":{\"additionalProperties\":false,\"properties\":{\"children\":{\"items\":{\"$ref\":\"#/$defs/Node\"},"
                + "\"type\":\"array\"},\"name\":{\"type\":\"string\"}},\"required\":[\"children\",\"name\"],\"type\":\"object\"}},\"$ref\":\"#/$defs/Node\"}"), d);
    }

    @Test
    void atMostEightExamples() {
        String eight = "@Example(arguments = \"{}\")".repeat(8);
        ok(tools("", eight + TOOL.formatted("items.list"), GENERATION));
        fails(tools("", eight + "@Example(arguments = \"{}\")" + TOOL.formatted("items.list"), GENERATION), "9 examples");
    }

    @Test
    void routesAreChecked() {
        fails(tools("@HttpRoute(id = \"items.all\", destination = \"api\", method = HttpMethod.GET, path = \"/items/{rest:.*}\")",
                TOOL.formatted("items.list"), GENERATION), "may not use .*");
        fails(tools("@HttpRoute(id = \"items.all\", destination = \"api\", method = HttpMethod.GET, path = \"/items/{rest:.+}\")",
                TOOL.formatted("items.list"), GENERATION), "may not use .+");
        fails(tools("@HttpRoute(id = \"items.one\", destination = \"api\", method = HttpMethod.GET, path = \"/items/{id}\")",
                TOOL.formatted("items.list"), GENERATION), "path variable id needs a pattern");
        assertTrue(tools("@HttpRoute(id = \"items.one\", destination = \"api\", method = HttpMethod.GET, path = \"/items/{id:[0-9]+}\")",
                TOOL.formatted("items.list"), GENERATION).success());
        fails(tools("@HttpRoute(id = \"items.all\", destination = \"api\", method = HttpMethod.GET, path = \"/items\", headers = \"Cookie\")",
                TOOL.formatted("items.list"), GENERATION), "may not allow header Cookie");
        fails(tools("@HttpRoute(id = \"items.all\", destination = \"api\", method = HttpMethod.GET, path = \"/items\", headers = \"X-Forwarded-For\")",
                TOOL.formatted("items.list"), GENERATION), "may not allow header X-Forwarded-For");
        fails(tools("@HttpRoute(id = \"items.all\", destination = \"nowhere\", method = HttpMethod.GET, path = \"/items\")",
                TOOL.formatted("items.list"), GENERATION), "undeclared destination nowhere");
        fails(tools("", """
                @ServerTool(name = "items.list", title = "T", description = "D", routes = "items.missing")
                public Out run() { return null; }
            """, GENERATION), "undeclared route items.missing");
    }

    @Test
    void permissionsAreDerived() {
        Compilation.Result r = ok(tools("""
                @HttpAuth(id = "exchange", type = AuthType.TOKEN_EXCHANGE, audience = "api")
                @HttpRoute(id = "items.mine", destination = "api", method = HttpMethod.GET, path = "/me", auth = "exchange")
                @TcpDestination(id = "db", hostSetting = "DbHost", port = 5432)
                @Secret(name = "Personal", scope = SecretScope.USER)
                """, """
                @ServerTool(name = "items.mine", title = "T", description = "D", routes = "items.mine", tcp = "db")
                public Out mine() { return null; }
            """, GENERATION));
        assertTrue(r.descriptor("items.mine").contains(
                "\"permissions\":[\"CREDENTIALS_IN_SANDBOX\",\"HTTP_ROUTE\",\"TCP\",\"TOKEN_EXCHANGE\",\"USER_SECRET\"]"), r.descriptor("items.mine"));
    }
}
