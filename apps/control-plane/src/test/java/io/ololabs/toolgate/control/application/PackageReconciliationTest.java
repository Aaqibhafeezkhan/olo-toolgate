// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import org.junit.jupiter.api.Test;

/** OLO M5.4: deployment refuses side-effecting tools without a consistent reconciliation method. */
class PackageReconciliationTest {
    private final ContractCodec codec = new ContractCodec();

    private JsonNode tool(String safety, String extra) {
        return (JsonNode) codec.value("{\"effect\":{\"safety\":\"" + safety + "\"}" + extra + "}");
    }

    @Test void sideEffectsNeedADeclaredConsistentMethod() {
        assertNull(PackageReconciliation.of(tool("READ_ONLY", "")));
        assertEquals("MANUAL", PackageReconciliation.of(tool("UNKNOWN", "")));
        assertEquals("MANUAL", PackageReconciliation.of(tool("NON_IDEMPOTENT", ",\"reconciliation\":\"MANUAL\"")));
        assertEquals("STATUS_LOOKUP", PackageReconciliation.of(tool("NON_IDEMPOTENT", ",\"reconciliation\":\"STATUS_LOOKUP\",\"reconcileTool\":\"x.status\"")));
        assertEquals("DOWNSTREAM_IDEMPOTENCY", PackageReconciliation.of(tool("NON_IDEMPOTENT",
                ",\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\",\"businessKey\":{\"idempotencyHeader\":\"Idempotency-Key\"}")));
        assertEquals("DOWNSTREAM_IDEMPOTENCY", PackageReconciliation.of(tool("IDEMPOTENT", ",\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\"")));
        for (var bad : new String[][]{{"NON_IDEMPOTENT", ""}, {"IDEMPOTENT", ""}, {"READ_ONLY", ",\"reconciliation\":\"MANUAL\""},
                {"NON_IDEMPOTENT", ",\"reconciliation\":\"STATUS_LOOKUP\""}, {"NON_IDEMPOTENT", ",\"reconciliation\":\"DOWNSTREAM_IDEMPOTENCY\""},
                {"NON_IDEMPOTENT", ",\"reconciliation\":\"RETRY\""}})
            assertThrows(Failure.class, () -> PackageReconciliation.of(tool(bad[0], bad[1])), bad[0] + bad[1]);
    }
}
