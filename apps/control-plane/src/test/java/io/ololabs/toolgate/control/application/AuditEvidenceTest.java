// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.contracts.ControlAuditEvidence;
import io.ololabs.toolgate.contracts.EnterpriseInvocation;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** M4.4: invocation audit rows carry subject, actor, workload, resource, operation, decision and policy as fields. */
class AuditEvidenceTest {
    private final ContractCodec codec = new ContractCodec();
    private final EnterpriseOperations operations = new EnterpriseOperations(null, codec, null, Clock.systemUTC(), "control", "host", 60000, 10);

    private EnterpriseInvocation invocation(int resources) {
        ObjectNode i = (ObjectNode) codec.value("{\"id\":\"inv-1\",\"requestDigest\":\"" + "a".repeat(64) + "\",\"evaluation\":{\"context\":{\"requestId\":\"inv-1\","
                + "\"tenantId\":\"10000000-0000-4000-8000-000000000001\",\"mode\":\"DELEGATED\",\"userId\":\"user-42\",\"agentId\":\"agent-planner\","
                + "\"workloadBindingId\":\"workload-1\",\"chain\":[{\"agentId\":\"agent-router\",\"workloadBindingId\":\"workload-0\"}],\"bindingId\":\"binding\",\"deviceId\":\"device\"},"
                + "\"toolId\":\"olo.memory\",\"action\":\"read\",\"argumentsDigest\":\"" + "b".repeat(64) + "\",\"resources\":[],\"toolDigest\":\"" + "c".repeat(64)
                + "\",\"packageDigest\":\"" + "d".repeat(64) + "\",\"nowUnixMs\":1,\"authorityRevision\":7,\"online\":true},\"state\":\"QUEUED\",\"revision\":1,"
                + "\"authorizationEpoch\":3,\"expiresAtUnixMs\":2,\"completedResources\":[],\"diagnosticId\":\"diag\"}");
        var list = ((ObjectNode) i.get("evaluation")).putArray("resources");
        for (int n = 0; n < resources; n++) list.addObject().put("kind", "CUSTOM").put("locator", "olo://t1/memory/" + n);
        return codec.model(codec.json(i), EnterpriseInvocation.class);
    }

    @Test void evidenceNamesEveryAuthorizationFactAndValidates() {
        String json = operations.evidence(invocation(2));
        var evidence = codec.model(json, ControlAuditEvidence.class);
        assertEquals("inv-1", evidence.invocationId());
        assertEquals("DELEGATED", evidence.mode());
        assertEquals("user-42", evidence.subjectUserId());
        assertEquals("agent-planner", evidence.actorAgentId());
        assertEquals(java.util.List.of("agent-router"), evidence.delegationChain());
        assertEquals("workload-1", evidence.workloadBindingId());
        assertEquals("read", evidence.operation());
        assertEquals(java.util.List.of("CUSTOM:olo://t1/memory/0", "CUSTOM:olo://t1/memory/1"), evidence.resources());
        assertEquals(2L, evidence.resourceCount().longValue());
        assertEquals("QUEUED", evidence.state());
        assertEquals(3L, evidence.authorizationEpoch().longValue());
        assertEquals(7L, evidence.policyRevision().longValue());
        assertFalse(json.contains("bbbbbbbb"), "arguments never reach the audit");
    }

    @Test void manyResourcesAreRecordedByCountAndDigestOnly() {
        JsonNode evidence = (JsonNode) codec.value(operations.evidence(invocation(40)));
        assertFalse(evidence.has("resources"));
        assertEquals(40, evidence.get("resourceCount").asInt());
        assertEquals(64, evidence.get("resourcesDigest").asText().length());
        assertTrue(evidence.toString().length() <= 4096);
    }
}
