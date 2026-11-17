// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.WorkflowConfigurations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorkflowConfigurationsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkflowConfigurations VALIDATOR = new WorkflowConfigurations(new ContractCodec());
    private ObjectNode config() throws Exception {
        var path = Path.of(System.getProperty("toolgate.fixtures")).getParent().resolve("../v2/valid.json");
        return (ObjectNode) JSON.readTree(Files.readString(path)).get("WorkflowConfiguration");
    }
    @Test void modelGenerationIsExplicitAndBounded() throws Exception {
        var node = config();
        var deployment = (ObjectNode) node.path("modelDeployments").get(0);
        deployment.remove("generation");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        var generation = deployment.putObject("generation").put("temperature", 0.25).put("maxOutputTokens", 256);
        assertNotNull(VALIDATOR.validate(node.toString(), r -> true));
        generation.put("maxOutputTokens", 4097);
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
    }
    @Test void orderedStagesCloseAgentReferencesAndRequireConfirmedContextSources() throws Exception {
        var node = config();
        var workflow = (ObjectNode) node.path("workflows").get(0);
        String agent = node.path("agentDefinitions").get(0).path("agentId").asText();
        var stages = workflow.putArray("agentStages");
        stages.addObject().put("agentId", agent).put("resultMode", "CONTEXT");
        assertEquals(1, VALIDATOR.validate(node.toString(), r -> true).workflows().getFirst().agentStages().size());
        ((ObjectNode) stages.get(0)).put("agentId", "foreign");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        ((ObjectNode) stages.get(0)).put("agentId", agent);
        stages.add(stages.get(0).deepCopy());
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        stages.remove(1);
        ((ObjectNode) node.path("agentDefinitions").get(0)).putArray("contextSources");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
    }
    @Test void stageModesAndHandoffBudgetsAreBounded() throws Exception {
        var node = config();
        var workflow = (ObjectNode) node.path("workflows").get(0);
        var stage = workflow.putArray("agentStages").addObject().put("agentId", node.path("agentDefinitions").get(0).path("agentId").asText());
        stage.put("resultMode", "EXECUTE");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        stage.put("resultMode", "MODEL");
        ((ObjectNode) workflow.path("limits")).put("maxHandoffs", 0);
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
    }
    @Test void contextBindingsAreBoundedDataAndCannotOverrideStaticArguments() throws Exception {
        var node = config();
        var context = (ObjectNode) node.path("agentDefinitions").get(0).path("contextSources").get(0);
        context.putObject("arguments");
        context.putObject("argumentBindings").put("path", "/message");
        assertNotNull(VALIDATOR.validate(node.toString(), reference -> true));
        context.withObject("/arguments").put("path", "other");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), reference -> true));
        context.putObject("arguments");
        context.withObject("/argumentBindings").put("path", "/secrets/key");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), reference -> true));
    }
    @Test void validConfigurationCollectsExternalDependenciesWithoutPublishing() throws Exception {
        var references = new HashSet<WorkflowConfigurations.Reference>();
        var result = VALIDATOR.validate(config().toString(), r -> { references.add(r); return true; });
        assertEquals("investigate", result.workflows().getFirst().id());
        for (var kind : WorkflowConfigurations.ResourceKind.values())
            assertTrue(references.stream().anyMatch(r -> r.kind() == kind), kind.name());
    }
    @Test void missingLocalReferencesDenyBeforeLookingOutsideTheDocument() throws Exception {
        for (String field : java.util.List.of("workflowGroupId", "modelGroupId")) {
            var node = config(); ((ObjectNode)node.path("workflows").get(0)).put(field, "foreign");
            assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> { fail("Local closure must be checked first"); return false; }));
        }
        var node = config(); ((ObjectNode)node.path("agentDefinitions").get(0)).put("modelGroupId", "foreign");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> false));
    }
    @Test void duplicateIdsAndAmbiguousDispatchDeny() throws Exception {
        for (String field : java.util.List.of("workflowGroups", "modelGroups", "agentDefinitions", "workflows", "dispatchBindings")) {
            var node = config(); ((com.fasterxml.jackson.databind.node.ArrayNode)node.path(field)).add(node.path(field).get(0).deepCopy());
            assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true), field);
        }
        var node = config(); var duplicate = node.path("dispatchBindings").get(0).deepCopy();
        ((ObjectNode)duplicate).put("id", "another");
        ((com.fasterxml.jackson.databind.node.ArrayNode)node.path("dispatchBindings")).add(duplicate);
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
    }
    @Test void foreignOrMissingDependenciesDenyWithoutLeakingTheirIdentity() throws Exception {
        String document = config().toString();
        for (var denied : WorkflowConfigurations.ResourceKind.values()) {
            var failure = assertThrows(Failure.class, () -> VALIDATOR.validate(document, r -> r.kind() != denied));
            assertEquals("A required workflow dependency is unavailable", failure.getMessage());
        }
    }
    @Test void obsoleteVersionAndUnboundedOrForgedAuthorityDeny() throws Exception {
        for (String field : java.util.List.of("tenantId", "runAs", "providerKey", "profileVersion")) {
            var node = config(); node.put(field, "forged");
            assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        }
        var node = config(); ((ObjectNode)node.path("workflows").get(0).path("limits")).put("maxSteps", 0);
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
    }
    @Test void graphSelectionIsRequiredAndPinsConfigurationWithoutGrantingAuthority() throws Exception {
        var node = config();
        var workflow = (ObjectNode)node.path("workflows").get(0);
        var graph = workflow.remove("graph");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        workflow.set("graph", graph);
        ((ObjectNode)graph).put("digest", "unverified");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), r -> true));
        ((ObjectNode)graph).put("digest", "sha256:" + "a".repeat(64));
        assertEquals("sha256:" + "a".repeat(64), VALIDATOR.validate(node.toString(), r -> true).workflows().getFirst().graph().digest());
    }
    @Test void wrongVersionNoEntryAndInvalidLimitsDeny() throws Exception {
        var node = config(); ((ObjectNode)node.path("dispatchBindings").get(0)).put("workflowVersionId", "wrong");
        String wrongVersion = node.toString(); assertThrows(Failure.class, () -> VALIDATOR.validate(wrongVersion, r -> true));
        node = config(); ((ObjectNode)node.path("workflows").get(0).path("agents").get(0)).put("entryEligible", false);
        String noEntry = node.toString(); assertThrows(Failure.class, () -> VALIDATOR.validate(noEntry, r -> true));
        node = config(); ((ObjectNode)node.path("workflows").get(0).path("limits")).put("maxHandoffs", 11);
        String limits = node.toString(); assertThrows(Failure.class, () -> VALIDATOR.validate(limits, r -> true));
    }
    @Test void modelDeploymentsRequireLocalClosureAndFixedTlsOrigins() throws Exception {
        for (String origin : java.util.List.of("http://models.example", "https://user@models.example", "https://models.example/v1", "https://models.example?key=x", "https://models.example:0", "https://models.example:99999")) {
            var node = config(); ((ObjectNode)node.path("modelDeployments").get(0)).put("origin", origin);
            assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), reference -> true), origin);
        }
        var node = config(); ((ObjectNode)node.path("modelDeployments").get(0)).put("id", "another");
        assertThrows(Failure.class, () -> VALIDATOR.validate(node.toString(), reference -> true));
    }
}
