// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.care;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthcareTest {
    @Test
    void summarisesAPatient() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode p = tools.call("patient", Map.of("mrn", "mrn-1001")).output();
            assertEquals("Alex Moreno", p.path("name").asText(), p.toString());
            assertEquals("[\"penicillin\"]", p.path("allergies").toString());
        }
    }

    @Test
    void flagsAbnormalLabs() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode labs = tools.call("labs", Map.of("mrn", "MRN-1001")).output();
            assertEquals(3, labs.path("results").size());
            assertEquals(1, labs.path("abnormal").asInt());
            assertEquals(1, tools.call("labs", Map.of("mrn", "MRN-1001", "test", "inr")).output().path("results").size());
        }
    }

    @Test
    void findsInteractionsAndAllergies() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("interactions", Map.of("drugs", List.of("tramadol", "sulfonamides"), "mrn", "MRN-1003")).output();
            assertEquals("MAJOR", out.path("interactions").get(0).path("severity").asText(), out.toString());
            assertEquals("[\"sulfonamides\"]", out.path("allergyAlerts").toString());
            JsonNode none = tools.call("interactions", Map.of("drugs", List.of("paracetamol"))).output();
            assertEquals(0, none.path("interactions").size());
        }
    }

    @Test
    void booksOncePerReference() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            Map<String, Object> args = Map.of("mrn", "MRN-1002", "slotId", "S-202", "reason", "BP review", "reference", "req-5");
            ToolResult first = tools.call("book", args);
            assertTrue(first.output().path("confirmation").asText().startsWith("APT-"), first.toString());
            assertEquals(first.output(), tools.call("book", args).output());
        }
    }

    @Test
    void patientIdsStayOutOfErrors() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            ToolResult missing = tools.call("patient", Map.of("mrn", "MRN-424242"));
            assertEquals("PATIENT_NOT_FOUND", missing.errorCode());
            assertFalse(missing.toString().contains("424242"), missing.toString());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
