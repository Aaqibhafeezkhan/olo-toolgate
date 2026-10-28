// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** OLO M3.7 (contract 2.3): package tools act on tenant-bound resources built from their arguments. */
class ResourceTemplatesTest {
    private static final String TENANT = "10000000-0000-4000-8000-000000000001";
    private final ContractCodec codec = new ContractCodec();

    private JsonNode json(String text) { return (JsonNode) codec.value(text); }

    private ControlResourceExtractor extractor(List<String> templates) {
        return codec.model(codec.json(java.util.Map.of("id", "extract-memory", "name", "Resources", "enabled", true, "revision", 1,
                "extractorKind", "FIELDS", "version", "1.0.0", "fields", List.of(), "fixedResources", List.of(), "maxResources", 32,
                "locatorTemplates", templates)), ControlResourceExtractor.class);
    }

    private AuthorizationRequest request(String arguments) {
        return codec.model("{\"toolId\":\"olo.memory.read\",\"action\":\"memory.read\",\"arguments\":" + arguments + "}", AuthorizationRequest.class);
    }

    @Test void templatesUseTheVerifiedTenantAndTheArgumentIds() {
        var extracted = new ResourceExtraction(codec).extract(extractor(List.of("olo://{tenant}/kb/{kbId}/doc/{docId}")),
                request("{\"kbId\":\"kb-1\",\"docId\":\"doc-9\",\"tenantId\":\"someone-else\"}"), TENANT);
        assertEquals(List.of(new ResourceDescriptor(ResourceKind.CUSTOM, "olo://" + TENANT + "/kb/kb-1/doc/doc-9")), extracted.resources());
    }

    @Test void idsCannotLeaveTheirSegmentOrBeMissing() {
        var extraction = new ResourceExtraction(codec);
        var conversation = extractor(List.of("olo://{tenant}/conversation/{conversationId}"));
        for (String arguments : List.of("{\"conversationId\":\"../other-tenant\"}", "{\"conversationId\":\"a/b\"}", "{\"conversationId\":\"..\"}",
                "{\"conversationId\":\"\"}", "{\"conversationId\":7}", "{}", "{\"conversationId\":\"a%2Fb\"}"))
            assertThrows(Failure.class, () -> extraction.extract(conversation, request(arguments), TENANT), arguments);
        assertThrows(Failure.class, () -> extraction.extract(conversation, request("{\"conversationId\":\"c-1\"}")), "no tenant, no resource");
    }

    @Test void declarationsAreCheckedAgainstTheInputSchemaAtDeployment() {
        String schema = "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"conversationId\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"conversationId\",\"limit\"]}";
        var ok = PackageAuthorization.of(json("{" + schema + ",\"authorization\":{\"action\":\"memory.read\",\"resources\":[\"olo://{tenant}/conversation/{conversationId}\"]}}"));
        assertEquals("memory.read", ok.action());
        assertEquals(List.of("olo://{tenant}/conversation/{conversationId}"), ok.templates());
        var none = PackageAuthorization.of(json("{" + schema + "}"));
        assertEquals(ServerPackages.ACTION, none.action());
        assertTrue(none.templates().isEmpty());
        for (String bad : List.of(
                "{\"action\":\"memory.read\",\"resources\":[\"olo://{tenant}/conversation/{missing}\"]}",
                "{\"action\":\"memory.read\",\"resources\":[\"olo://{tenant}/conversation/{limit}\"]}",
                "{\"action\":\"memory.read\",\"resources\":[\"olo://t1/conversation/{conversationId}\"]}",
                "{\"action\":\"memory.read\",\"resources\":[\"olo://{tenant}/../{conversationId}\"]}",
                "{\"action\":\"memory.read\",\"resources\":[\"olo://{tenant}/{tenant}/{conversationId}\"]}",
                "{\"action\":\"memory.*\",\"resources\":[\"olo://{tenant}/conversation/{conversationId}\"]}",
                "{\"action\":\"memory.read\",\"resources\":[]}"))
            assertThrows(Failure.class, () -> PackageAuthorization.of(json("{" + schema + ",\"authorization\":" + bad + "}")), bad);
    }

    @Test void grantsMatchTheResourceKindPrefixInTheirOwnTenantOnly() {
        var rule = new EnterpriseResourceRule(ResourceKind.CUSTOM, "olo://" + TENANT + "/conversation", EnterpriseResourceMatch.PREFIX);
        assertTrue(EnterpriseEvaluator.resource(rule, new ResourceDescriptor(ResourceKind.CUSTOM, "olo://" + TENANT + "/conversation/c-1")));
        assertFalse(EnterpriseEvaluator.resource(rule, new ResourceDescriptor(ResourceKind.CUSTOM, "olo://" + TENANT + "/kb/kb-1")));
        assertFalse(EnterpriseEvaluator.resource(rule, new ResourceDescriptor(ResourceKind.CUSTOM, "olo://10000000-0000-4000-8000-000000000002/conversation/c-1")));
    }
}
