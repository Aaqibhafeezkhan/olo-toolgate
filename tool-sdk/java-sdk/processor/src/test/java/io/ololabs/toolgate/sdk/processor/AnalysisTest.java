// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static io.ololabs.toolgate.sdk.processor.Compilation.GENERATION;
import static io.ololabs.toolgate.sdk.processor.Compilation.tools;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conformance: the route-analysis cases of http.md §3, the declared-value checks and the typed accessors of java.md. */
class AnalysisTest {
    private static final String ROUTES = """
            @HttpRoute(id = "items.list", destination = "api", method = HttpMethod.GET, path = "/items")
            @HttpRoute(id = "admin.get", destination = "api", method = HttpMethod.GET, path = "/admin")
            """;

    /** A server tool listing {@code items.get} and {@code items.list}, whose body is {@code statement}. */
    private static Compilation.Result handler(String annotations, String statement) {
        return tools(ROUTES + annotations, """
                @ServerTool(name = "items.get", title = "T", description = "D", routes = {"items.get", "items.list"})
                public Out run(@Param(description = "Id") String id, ToolContext context) throws Exception {
                    %s
                    return null;
                }
            """.formatted(statement), GENERATION);
    }

    private static Compilation.Result ok(Compilation.Result r) {
        assertTrue(r.success(), r.errors());
        return r;
    }

    private static void fails(Compilation.Result r, String... fragments) {
        assertFalse(r.success(), "compiled");
        for (String f : fragments) assertTrue(r.errors().contains(f), () -> "missing \"" + f + "\" in:\n" + r.errors());
    }

    @Test
    void aLiteralCallMatchingOneListedRoutePackages() {
        ok(handler("", "context.http().get(\"api\", \"/items/{id}\").pathVar(\"id\", id).send(String.class);"));
        ok(handler("", "context.http().get(\"api\", \"/items/{id:[0-9]+}\").pathVar(\"id\", id).send(String.class);"));
        ok(handler("", "HttpClient http = context.http(); http.get(\"api\", \"/items\").send(String.class);"));
    }

    @Test
    void aConcatenatedTemplateFailsAtTheCallSite() {
        Compilation.Result r = handler("", "context.http().get(\"api\", \"/items/\" + id).send(String.class);");
        fails(r, "string literals");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.getLineNumber() > 0), "the error names the call site");
    }

    @Test
    void aTemplateOrDestinationFromAGlobalFails() {
        fails(handler("@Global(name = \"Path\", type = ValueType.STRING)",
                "context.http().get(\"api\", context.global().getStringValue(\"Path\")).send(String.class);"), "string literals");
        fails(handler("@Global(name = \"Dest\", type = ValueType.STRING)",
                "context.http().get(context.global().getStringValue(\"Dest\"), \"/items\").send(String.class);"), "string literals");
    }

    @Test
    void aTemplateMatchingTwoRoutesFailsNamingBoth() {
        fails(handler("@HttpRoute(id = \"items.also\", destination = \"api\", method = HttpMethod.GET, path = \"/items/{id:[a-z]+}\")",
                "context.http().get(\"api\", \"/items/{id}\").send(String.class);"), "more than one route", "items.get", "items.also");
    }

    @Test
    void aRouteTheToolDoesNotListFails() {
        fails(handler("", "context.http().get(\"api\", \"/admin\").send(String.class);"), "route admin.get", "does not list");
        fails(handler("", "context.http().post(\"api\", \"/items\").send(String.class);"), "matches no declared route", "candidates");
    }

    @Test
    void callsOutsideAHandlerFail() {
        fails(tools(ROUTES, """
                @ServerTool(name = "items.get", title = "T", description = "D", routes = "items.get")
                public Out run(ToolContext context) { return null; }
                static Object helper(HttpClient http) { return http.get("api", "/items").send(String.class); }
            """, GENERATION), "outside a tool handler");
    }

    @Test
    void reflectiveAccessToTheNetworkApiFails() {
        fails(handler("", "HttpClient.class.getMethod(\"get\", String.class, String.class).invoke(context.http(), \"api\", \"/items\");"),
                "reflective access to HttpClient");
        fails(handler("", "Object c = TcpConnector.class;"), "reflective access to TcpConnector");
    }

    @Test
    void literalValueNamesMustBeDeclaredAndReadWithTheirType() {
        ok(handler("@Global(name = \"Limit\", type = ValueType.INT)", "context.global().getIntValue(\"Limit\"); context.global().getLongValue(\"Limit\");"));
        fails(handler("@Global(name = \"Limit\", type = ValueType.INT)", "context.global().getIntValue(\"Limt\");"),
                "declares no global Limt", "declared: Limit");
        fails(handler("@Global(name = \"Limit\", type = ValueType.INT)", "context.global().getStringValue(\"Limit\");"),
                "global Limit of tool items.get is INT; getStringValue does not read it");
        fails(handler("@Secret(name = \"Token\")", "context.secret().getStringValue(\"Tokn\");"), "declares no secret Tokn");
        fails(handler("@JwtClaim(name = \"tenant\", claim = \"tid\")", "context.jwt().getIntValue(\"tenant\");"), "claim tenant", "is STRING");
        ok(handler("@JwtClaim(name = \"tenant\", claim = \"tid\")", "context.jwt().getStringValue(\"tenant\");"));
        fails(handler("", "context.getStringValue(\"nope\");"), "declares no input nope");
        ok(handler("", "context.getStringValue(\"id\");"));
        // A name the build cannot see is left to the runtime.
        ok(handler("", "String n = \"no\" + \"pe\"; context.global().getStringValue(n);"));
    }

    @Test
    void typedAccessorsReadEachDeclaredValueWithItsGetter() throws Exception {
        Compilation.Result r = ok(tools(ROUTES + """
                @Global(name = "Limit", type = ValueType.INT)
                @Global(name = "default", type = ValueType.BOOLEAN)
                @Secret(name = "API_KEY")
                @JwtClaim(name = "tenant", claim = "tid")
                """, """
                @ServerTool(name = "items.get", title = "T", description = "D", routes = "items.get")
                public Out run(ToolContext context) {
                    int limit = ToolsVars.global(context).limit();
                    boolean d = ToolsVars.global(context).defaultValue();
                    String key = ToolsVars.secret(context).apiKey();
                    String tenant = ToolsVars.jwt(context).tenant();
                    return new Out(limit + key + tenant + d);
                }
            """, GENERATION));
        assertTrue(Files.exists(r.out().resolve("acme/demo/ToolsVars.class")));
    }

    @Test
    void noAccessorsAreGeneratedWithoutDeclaredValues() {
        Compilation.Result r = ok(tools("", """
                @ServerTool(name = "items.list", title = "T", description = "D")
                public Out run() { return null; }
            """, GENERATION));
        assertFalse(Files.exists(r.out().resolve("acme/demo/ToolsVars.class")));
    }

    @Test
    void accessorNamesAreJavaIdentifiers() {
        assertEquals("limit", Accessors.method("Limit"));
        assertEquals("apiKey", Accessors.method("API_KEY"));
        assertEquals("maxItemsPerPage", Accessors.method("maxItemsPerPage"));
        assertEquals("classValue", Accessors.method("class"));
        assertEquals("hashCodeValue", Accessors.method("hashCode"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("max_items", "INT");
        values.put("maxItems", "INT");
        assertEquals("max_items and maxItems", Accessors.collision(values));
    }
}
