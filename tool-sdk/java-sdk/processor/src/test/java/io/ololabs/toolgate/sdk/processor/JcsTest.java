// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.sdk.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RFC 8785 serialisation details the vectors do not reach. */
class JcsTest {
    @Test
    void numbersUseEcmaScriptFormatting() {
        assertEquals("1", Jcs.number(1.0));
        assertEquals("0.1", Jcs.number(0.1));
        assertEquals("-2.5", Jcs.number(-2.5));
        assertEquals("0", Jcs.number(-0.0));
        assertEquals("1e+21", Jcs.number(1e21));
        assertEquals("123456789012345680000", Jcs.number(123456789012345680000.0));
        assertEquals("0.000001", Jcs.number(0.000001));
        assertEquals("1e-7", Jcs.number(1e-7));
        assertEquals("5e-324", Jcs.number(Double.MIN_VALUE));
        assertEquals("1.7976931348623157e+308", Jcs.number(Double.MAX_VALUE));
        assertEquals("333333333.3333333", Jcs.number(333333333.33333329));
        assertThrows(IllegalArgumentException.class, () -> Jcs.number(Double.NaN));
    }

    @Test
    void stringsEscapeOnlyWhatJsonRequires() {
        assertEquals("\"\\u0001\\n\\\"\\\\/ é\"", new String(Jcs.bytes("\u0001\n\"\\/ é"), StandardCharsets.UTF_8));
    }

    @Test
    void keysAreNfcAndSortedByUtf16() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Ａlpha", 1L);
        m.put("😀note", 2L);
        m.put("état", List.of());
        assertEquals("{\"état\":[],\"😀note\":2,\"Ａlpha\":1}", new String(Jcs.bytes(m), StandardCharsets.UTF_8));
        m.put("état", true);
        assertThrows(IllegalArgumentException.class, () -> Jcs.bytes(m));
    }
}
