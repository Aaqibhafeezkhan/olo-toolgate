// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Conformance: descriptors/canonical-bytes, set-digest, excludes-deployment and reproducibility for Java. */
class DescriptorVectorTest {
    private static final Path VECTORS = Path.of(System.getProperty("toolgate.vectors"));

    private static byte[] vector(String file) throws IOException {
        return Files.readAllBytes(VECTORS.resolve(file));
    }

    private static String expected(String file) throws IOException {
        String json = Files.readString(VECTORS.resolve("expected.json"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"" + Pattern.quote(file) + "\"\\s*:\\s*\"(sha256:[0-9a-f]{64})\"").matcher(json);
        assertTrue(m.find(), file);
        return m.group(1);
    }

    private static void assertVector(Compilation.Result result, String tool, String vector) throws IOException {
        assertTrue(result.success(), result.errors());
        byte[] emitted = result.resource("META-INF/toolgate/tools/" + tool + ".json");
        assertEquals(new String(vector(vector.replace(".json", ".jcs")), StandardCharsets.UTF_8),
                new String(emitted, StandardCharsets.UTF_8));
        assertArrayEquals(vector(vector.replace(".json", ".jcs")), emitted);
        assertEquals(expected(vector), Jcs.sha256(emitted));
    }

    @Test
    void clientToolMatchesTool01() throws IOException {
        assertVector(Compilation.fixture("files", Compilation.GENERATION), "files.checksum", "tool-01-checksum.json");
    }

    @Test
    void serverToolMatchesTool02() throws IOException {
        assertVector(Compilation.fixture("orders", Compilation.GENERATION), "orders.lookup", "tool-02-orders-lookup.json");
    }

    @Test
    void taskToolWithNonAsciiNamesMatchesTool03() throws IOException {
        assertVector(Compilation.fixture("orders", Compilation.GENERATION), "orders.refund", "tool-03-refund.json");
    }

    @Test
    void setDigestMatchesSet01() throws IOException {
        Compilation.Result result = Compilation.fixture("orders", Compilation.GENERATION);
        assertTrue(result.success(), result.errors());
        List<Object> entries = new ArrayList<>();
        for (String tool : List.of("orders.lookup", "orders.refund")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolId", "acme.orders/" + tool);
            entry.put("toolDescriptorDigest", Jcs.sha256(result.resource("META-INF/toolgate/tools/" + tool + ".json")));
            entries.add(entry);
        }
        assertEquals(expected("set-01-orders.json"), Jcs.sha256(Jcs.bytes(entries)));
    }

    @Test
    void packageIndexCarriesDigestsAndHandlers() {
        Compilation.Result result = Compilation.fixture("orders", Compilation.GENERATION);
        assertTrue(result.success(), result.errors());
        String index = new String(result.resource("META-INF/toolgate/package.json"), StandardCharsets.UTF_8);
        assertTrue(index.contains("\"handlerClass\":\"acme.orders.RefundTools\",\"handlerMethod\":\"refund\""), index);
        assertTrue(index.contains("\"parameters\":[\"orderId\",\"reason\",\"refundKey\",\"$context\"]"), index);
        assertTrue(index.contains("\"toolDescriptorDigest\":\"" + Jcs.sha256(result.resource("META-INF/toolgate/tools/orders.refund.json")) + "\""), index);
        assertTrue(index.startsWith("{\"descriptorGeneration\":1,\"id\":\"acme.orders\""), index);
    }

    @Test
    void sameSourceGivesIdenticalBytes() {
        Compilation.Result a = Compilation.fixture("orders", Compilation.GENERATION);
        Compilation.Result b = Compilation.fixture("orders", Compilation.GENERATION);
        assertTrue(a.success() && b.success());
        for (String file : List.of("tools/orders.lookup.json", "tools/orders.refund.json", "tools/orders.refund-status.json", "package.json")) {
            assertArrayEquals(a.resource("META-INF/toolgate/" + file), b.resource("META-INF/toolgate/" + file), file);
        }
    }

    @Test
    void deploymentValuesAndExamplesDoNotChangeTheDigest() throws IOException {
        Path dir = Path.of("src/test/resources/fixtures/orders/acme/orders");
        String pkg = Files.readString(dir.resolve("OrdersPackage.java"), StandardCharsets.UTF_8)
                .replace("defaultHost = \"api.acme.com\"", "defaultHost = \"staging.acme.example\"");
        String lookup = Files.readString(dir.resolve("LookupTools.java"), StandardCharsets.UTF_8)
                .replace("    public Order lookup(", "    @io.ololabs.toolgate.sdk.annotations.Example(arguments = \"{\\\"orderId\\\":\\\"123456\\\"}\")\n    public Order lookup(");
        String refund = Files.readString(dir.resolve("RefundTools.java"), StandardCharsets.UTF_8);
        Compilation.Result changed = Compilation.compile(Map.of("acme/orders/OrdersPackage.java", pkg,
                "acme/orders/LookupTools.java", lookup, "acme/orders/RefundTools.java", refund), Compilation.GENERATION);
        assertVector(changed, "orders.lookup", "tool-02-orders-lookup.json");
    }
}
