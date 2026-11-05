// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.CredentialKind;
import io.ololabs.toolgate.contracts.v2.RequestContext;
import io.ololabs.toolgate.contracts.v2.RequestMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessKeyPoliciesTest {
    private static final String TENANT = "10000000-0000-4000-8000-000000000001";
    private static final String HASH = "a".repeat(64);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private EnterpriseInvocationRequest request(RequestMode mode, String user, String agent,
                                               Map<String, JsonNode> arguments, String hash) {
        var context = new RequestContext("request", TENANT, mode, CredentialKind.STATIC_TOKEN,
                null, user, agent, agent == null ? null : "workload", List.of(), 1L, 1L,
                null, null, "binding", HASH);
        return new EnterpriseInvocationRequest(context, new AuthorizationRequest("tool", "write", arguments),
                HASH, HASH, null, null, hash);
    }
    private EnterpriseInvocationRequest request(Map<String, JsonNode> arguments, String hash) {
        return request(RequestMode.DELEGATED, "alice", "agent", arguments, hash);
    }
    private BusinessKeyPolicy policy(boolean required, BusinessKeyNamespace namespace, String argument) {
        return new BusinessKeyPolicy(required, namespace, argument, null, null, null);
    }

    @Test void absentPolicyPreservesLegacyExplicitKeyScopeAndRetention() {
        assertNull(BusinessKeyPolicies.bind(TENANT, request(Map.of(), null), null));
        var value = BusinessKeyPolicies.bind(TENANT, request(Map.of(), HASH), null);
        assertEquals("AGENT", value.namespace());
        assertEquals("agent", value.identity());
        assertEquals("HEADER", value.source());
        assertEquals(2592000000L, value.retentionMs());
        var human = request(RequestMode.HUMAN, "alice", null, Map.of(), HASH);
        assertEquals("DELEGATED_USER", BusinessKeyPolicies.bind(TENANT, human, null).namespace());
        assertEquals("alice", BusinessKeyPolicies.bind(TENANT, human, null).identity());
    }

    @Test void namespaceIdentityComesOnlyFromVerifiedContext() {
        var input = request(Map.of("namespaceIdentity", JSON.textNode("mallory")), HASH);
        var tenant = BusinessKeyPolicies.bind(TENANT, input, policy(true, BusinessKeyNamespace.TENANT, null));
        assertEquals("", tenant.identity());
        var user = BusinessKeyPolicies.bind(TENANT, input, policy(true, BusinessKeyNamespace.DELEGATED_USER, null));
        assertEquals("alice", user.identity());
        var service = request(RequestMode.SERVICE, "alice", "agent", Map.of(), HASH);
        assertEquals(403, assertThrows(Failure.class, () -> BusinessKeyPolicies.bind(TENANT, service,
                policy(true, BusinessKeyNamespace.DELEGATED_USER, null))).status());
        var human = request(RequestMode.HUMAN, "alice", null, Map.of(), HASH);
        assertEquals(403, assertThrows(Failure.class, () -> BusinessKeyPolicies.bind(TENANT, human,
                policy(true, BusinessKeyNamespace.AGENT, null))).status());
    }

    @Test void dedupeWindowCannotSubstituteForARequiredKey() {
        var policy = new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, 5000L, null, null);
        assertEquals(400, assertThrows(Failure.class,
                () -> BusinessKeyPolicies.bind(TENANT, request(Map.of(), null), policy)).status());
        var optional = new BusinessKeyPolicy(false, BusinessKeyNamespace.AGENT, null, 5000L, null, null);
        var binding = BusinessKeyPolicies.bind(TENANT, request(Map.of(), null), optional);
        assertEquals("DEDUPE_WINDOW", binding.source());
        assertNull(binding.hash());
        assertEquals(5000L, binding.dedupeWindowMs());
    }

    @Test void exactArgumentKeyUsesTheTenantHashDomainAndMatchesAnExplicitKey() {
        var policy = policy(true, BusinessKeyNamespace.AGENT, "paymentKey");
        var arguments = Map.<String, JsonNode>of("paymentKey", JSON.textNode("payment-42"));
        var expected = DirectoryService.digest("toolgate-business-key-v1\0" + TENANT + "\0payment-42");
        var binding = BusinessKeyPolicies.bind(TENANT, request(arguments, null), policy);
        assertEquals(expected, binding.hash());
        assertEquals("ARGUMENT", binding.source());
        assertFalse(binding.toString().contains("payment-42"));
        assertEquals(expected, BusinessKeyPolicies.bind(TENANT, request(arguments, expected), policy).hash());
        assertEquals(409, assertThrows(Failure.class,
                () -> BusinessKeyPolicies.bind(TENANT, request(arguments, HASH), policy)).status());
        assertNotEquals(binding.hash(), BusinessKeyPolicies.bind("20000000-0000-4000-8000-000000000002",
                request(arguments, null), policy).hash());
    }

    @Test void malformedPresentArgumentsAreRefusedEvenWithAHeaderKey() {
        for (JsonNode invalid : List.of(JSON.nullNode(), JSON.numberNode(42), JSON.textNode(""),
                JSON.textNode("key with spaces"), JSON.objectNode())) {
            assertEquals(400, assertThrows(Failure.class, () -> BusinessKeyPolicies.bind(TENANT,
                    request(Map.of("paymentKey", invalid), HASH),
                    policy(true, BusinessKeyNamespace.AGENT, "paymentKey"))).status());
        }
        assertEquals(400, assertThrows(Failure.class, () -> BusinessKeyPolicies.bind(TENANT,
                request(Map.of(), HASH), policy(true, BusinessKeyNamespace.AGENT, "nested.key"))).status());
    }

    @Test void retentionMustCoverTheDeclaredDownstreamWindow() {
        var valid = new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, null, 60000L, 60000L);
        assertEquals(60000L, BusinessKeyPolicies.bind(TENANT, request(Map.of(), HASH), valid).retentionMs());
        for (var invalid : List.of(
                new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, null, 1000L, 1001L),
                new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, null, 0L, null),
                new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, 999L, null, null),
                new BusinessKeyPolicy(true, BusinessKeyNamespace.AGENT, null, null, null, 31536000000L))) {
            assertEquals(400, assertThrows(Failure.class,
                    () -> BusinessKeyPolicies.bind(TENANT, request(Map.of(), HASH), invalid)).status());
        }
    }
    @Test void copiedMetadataCannotWeakenThePinnedPackagePolicy() {
        var descriptor=JSON.objectNode();
        descriptor.putObject("businessKey").put("required",true).put("namespace","TENANT").put("argument","paymentKey");
        var packaged=BusinessKeyPolicies.packaged(descriptor);
        assertEquals(packaged,BusinessKeyPolicies.effective(null,packaged));
        assertEquals(packaged,BusinessKeyPolicies.effective(packaged,packaged));
        assertEquals(409,assertThrows(Failure.class,()->BusinessKeyPolicies.effective(
                policy(false,BusinessKeyNamespace.TENANT,"paymentKey"),packaged)).status());
        assertEquals(409,assertThrows(Failure.class,()->BusinessKeyPolicies.effective(
                policy(true,BusinessKeyNamespace.AGENT,"paymentKey"),packaged)).status());
        assertEquals(409,assertThrows(Failure.class,()->BusinessKeyPolicies.effective(
                policy(true,BusinessKeyNamespace.TENANT,"otherField"),packaged)).status());
    }

    @Test void resolvedOptionalAgentDefaultsDoNotForceAnExistingHumanCallIntoAnAgentNamespace() {
        var descriptor=JSON.objectNode();
        descriptor.putObject("businessKey").put("required",false).put("namespace","AGENT");
        assertNull(BusinessKeyPolicies.packaged(descriptor));
        ((com.fasterxml.jackson.databind.node.ObjectNode)descriptor.path("businessKey")).remove("namespace");
        assertNull(BusinessKeyPolicies.packaged(descriptor));
        assertNull(BusinessKeyPolicies.packaged(JSON.objectNode()));
        var human=request(RequestMode.HUMAN,"alice",null,Map.of(),HASH);
        assertEquals("DELEGATED_USER",BusinessKeyPolicies.bind(TENANT,human,BusinessKeyPolicies.packaged(descriptor)).namespace());
    }

}
