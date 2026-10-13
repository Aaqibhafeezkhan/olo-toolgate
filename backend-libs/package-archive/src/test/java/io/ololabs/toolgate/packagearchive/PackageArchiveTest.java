// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.packagearchive;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class PackageArchiveTest {
    private static final Path ROOT = Path.of(System.getProperty("toolgate.root", "../.."));

    /** The library decides every fixture as tests/fixtures/packages/baseline.json records Control deciding it. */
    @Test
    void decidesEveryFixtureAsTheBaseline() throws Exception {
        var baseline = Json.parse(Files.readString(ROOT.resolve("tests/fixtures/packages/baseline.json"))).path("fixtures");
        assertTrue(baseline.size() > 50);
        for (var name : baseline.fieldNames()) {
            var expected = baseline.path(name);
            byte[] data = Files.readAllBytes(ROOT.resolve(name));
            if ("accept".equals(expected.path("result").asText())) {
                var verified = PackageArchive.validate(data);
                assertEquals(expected.path("packageId").asText(), verified.manifest().path("id").asText(), name);
                assertEquals(expected.path("packageDigest").asText(), verified.packageDigest(), name);
                assertEquals(expected.path("descriptorSetDigest").asText(), verified.descriptorSetDigest(), name);
                assertEquals(expected.path("files").asInt(), verified.files().size(), name);
            } else {
                var rejected = assertThrows(PackageRejected.class, () -> PackageArchive.validate(data), name);
                assertEquals(expected.path("status").asInt(), rejected.status(), name);
                assertFalse(rejected.getMessage().isBlank(), name);
            }
        }
    }

    @Test
    void refusesAnOversizePackageBeforeReadingIt() {
        assertEquals(413, assertThrows(PackageRejected.class, () -> PackageArchive.validate(new byte[PackageArchive.MAX_PACKAGE_BYTES + 1])).status());
    }

    @Test
    void parsesStrictJsonLikeControlsCodec() {
        var value = Json.parse(" {\"a\":[1,-0,2.50,1e2,\"\\u00e9\\n\",true,null],\"b\":{}} ");
        assertEquals(2, value.size());
        assertTrue(value.path("a").path(0).isIntegralNumber());
        assertTrue(value.path("a").path(1).isIntegralNumber());
        assertFalse(value.path("a").path(2).isIntegralNumber());
        assertFalse(value.path("a").path(3).isIntegralNumber());
        assertEquals("é\n", value.path("a").path(4).textValue());
        assertTrue(value.path("missing").isMissing());
        assertEquals("", value.path("missing").asText());
        for (var bad : new String[]{"", " ", "{", "{}{}", "{} x", "{\"a\":1,\"a\":2}", "[01]", "[1.]", "[.5]", "[+1]", "[-]", "[1e]",
                "[NaN]", "['a']", "{a:1}", "[1,]", "[,1]", "// c\n{}", "\ufeff{}", "[\"\t\"]", "[\"\\x\"]", "[\"\\u12g4\"]", "[tru]", "[truex]", "[1true]"})
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        assertDoesNotThrow(() -> Json.parse("[".repeat(32) + "]".repeat(32)));
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(33) + "]".repeat(33)));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"" + "x".repeat(Json.MAX_DOCUMENT_BYTES) + "\""));
    }

    @Test
    void coercesLikeJackson() {
        assertEquals(2, Json.parse("\"2\"").asInt());
        assertEquals(2, Json.parse("2.9").asInt());
        assertEquals(2, Json.parse("\" +2 \"").asInt());
        assertEquals(0, Json.parse("\"two\"").asInt());
        assertEquals(1, Json.parse("true").asInt());
        assertEquals(0, Json.parse("null").asLong(7));
        assertEquals(7, Json.parse("{}").asLong(7));
        assertEquals("2.0", Json.parse("2.0").asText());
        assertEquals("null", Json.parse("null").asText());
        assertEquals(Json.parse("1"), Json.parse("1"));
        assertNotEquals(Json.parse("1"), Json.parse("1.0"));
        assertEquals(Json.parse("{\"a\":1,\"b\":2}"), Json.parse("{\"b\":2,\"a\":1}"));
    }

    @Test
    void writesCanonicalJson() {
        var value = Json.parse("{\"b\":[0.5,\"é\",1e21,100,1E-7],\"a\":1e+21,\"\\u00e9\":true}");
        assertEquals("{\"a\":1e+21,\"b\":[0.5,\"é\",1e+21,100,1e-7],\"é\":true}", new String(Canonical.bytes(value), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> Canonical.bytes(Json.parse("{\"é\":1,\"e\\u0301\":2}")));
        assertThrows(IllegalArgumentException.class, () -> Canonical.bytes(Json.parse("1e400")));
    }
}
