// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Directory;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit human ceilings, independent of management roles and token role names. */
final class WorkflowUserAccessTest {
    private final ContractCodec codec = new ContractCodec();
    private final WorkflowUserAccess access = new WorkflowUserAccess(codec);
    private final Map<Ids.RecordId, Directory.Entry> entries = new HashMap<>();
    private final GroupSelection all = new GroupSelection(List.of(), true);
    private final EnterpriseConditions conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
    private WorkflowConfiguration config() throws Exception {
        var path = Path.of(System.getProperty("toolgate.fixtures")).getParent().resolve("../v2/valid.json");
        var document = ((com.fasterxml.jackson.databind.JsonNode)codec.value(Files.readString(path))).path("WorkflowConfiguration");
        return codec.model(document.toString(), WorkflowConfiguration.class);
    }
    private EnterpriseScope scope(List<String> actions, String... locators) {
        return new EnterpriseScope(all, all, actions, false, Arrays.stream(locators)
            .map(s -> new EnterpriseResourceRule(ResourceKind.CUSTOM, s, EnterpriseResourceMatch.EXACT)).toList(), conditions);
    }
    private void put(Ids.Kind kind, Object value) { TestSupport.put(entries, codec, kind, value); }
    private Directory graph() { return new Directory(1, entries); }
    private DirectoryService.Actor actor(String user) {
        return new DirectoryService.Actor(new Ids.TenantId("48558ebe-a9a8-4680-8da8-129deba2e65c"), DirectoryService.digest(user), false, false, user);
    }
    private void seed() {
        GroupGraph.defaults(entries, codec);
        put(Ids.Kind.USER, new ControlUser("alice", "Alice", true, 1L, null, null, null));
        put(Ids.Kind.USER, new ControlUser("bob", "Bob", true, 1L, null, null, null));
        put(Ids.Kind.TEAM, new ControlTeam("callers", "Callers", true, 1L, List.of("alice", "bob"), List.of(), null));
        put(Ids.Kind.AGENT, new ControlAgent("investigator", "Investigator", null, true, 1L, "alice"));
        put(Ids.Kind.ROLE,new ControlRole("bot-service","Bot service",true,1L,UserRole.BASIC,EnterpriseRoleType.ACTOR_SERVICE,List.of()));
        put(Ids.Kind.AGENT_GROUP, new ControlAgentGroup("support-agents", "Support", null, true, 1L, List.of("investigator"), List.of("bot-service"), null));
        put(Ids.Kind.GRANT, new ControlAccessGrant("service", "Service", true, 1L, EnterpriseSourceType.ROLE, "bot-service", EnterpriseGrantPurpose.SERVICE,
            scope(List.of("workflow.execute", "model.infer"), "workflow-group:support", "model-group:models")));
        put(Ids.Kind.GRANT, new ControlAccessGrant("human", "Human", true, 1L, EnterpriseSourceType.TEAM, "callers", EnterpriseGrantPurpose.HUMAN,
            scope(List.of("workflow.execute", "workflow.progress.read", "workflow.result.read", "workflow.subscribe", "agent.execute", "model.infer"),
                "workflow:investigate", "workflow-group:support", "agent:investigator", "model-group:models")));
    }
    private void superuser(String user) {
        put(Ids.Kind.ROLE, new ControlRole("tenant-super", "Tenant superuser", true, 1L,
            UserRole.SUPER_ADMIN, EnterpriseRoleType.MANAGEMENT, List.of()));
        put(Ids.Kind.TEAM, new ControlTeam("tenant-owners", "Tenant owners", true, 1L,
            List.of(user), List.of("tenant-super"), null));
    }
    @Test void liveSuperuserRunsWithoutHumanGrantsButStillNeedsServiceAuthority() throws Exception {
        seed(); entries.remove(Ids.Kind.GRANT.id("human")); superuser("alice");
        var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("alice"), config, workflow, 1000);
        assertTrue(authority.humanScopes().getFirst().allActions());
        assertDoesNotThrow(() -> access.require(graph(), authority, config, workflow, "workflow.execute", 1001));
        entries.remove(Ids.Kind.GRANT.id("service"));
        assertEquals(403, assertThrows(Failure.class, () -> access.require(graph(), authority, config, workflow, "workflow.execute", 1002)).status());
    }
    @Test void roleRevocationAndCachedSuperuserFlagsCannotPreserveAuthority() throws Exception {
        seed(); entries.remove(Ids.Kind.GRANT.id("human")); superuser("alice");
        var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("alice"), config, workflow, 1000);
        entries.remove(Ids.Kind.ROLE.id("tenant-super"));
        assertEquals(403, assertThrows(Failure.class, () -> access.require(graph(), authority, config, workflow, "workflow.execute", 1001)).status());
        var forged = new DirectoryService.Actor(actor("alice").tenant(), actor("alice").id(), true, true, "alice");
        assertEquals(403, assertThrows(Failure.class, () -> access.capture(graph(), forged, config, workflow, 1001)).status());
    }
    @Test void currentSuperuserCanReadAnotherUsersRunOnlyWhileRoleIsLive() throws Exception {
        seed(); var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("bob"), config, workflow, 1000);
        var run = new WorkflowRunStore.QueuedRun("run-1", "support", "sha256:"+"a".repeat(64), "investigate", "revision-1",
            "investigator", "support-agents", "PREPARE", 1000, 1, "sealed", "sha256:"+"b".repeat(64), "sha256:"+"c".repeat(64), codec.json(authority));
        superuser("alice");
        assertDoesNotThrow(() -> access.observe(graph(), actor("alice"), run, "workflow.result.read", 1001));
        entries.remove(Ids.Kind.TEAM.id("tenant-owners"));
        assertEquals(403, assertThrows(Failure.class, () -> access.observe(graph(), actor("alice"), run, "workflow.result.read", 1002)).status());
    }
    @Test void basicUserCanAdmitAndObserveOnlyTheirOwnRun() throws Exception {
        seed(); var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("alice"), config, workflow, 1000);
        assertFalse(actor("alice").admin());
        var run = new WorkflowRunStore.QueuedRun("run-1", "support", "sha256:"+"a".repeat(64), "investigate", "revision-1",
            "investigator", "support-agents", "PREPARE", 1000, 1, "sealed", "sha256:"+"b".repeat(64), "sha256:"+"c".repeat(64), codec.json(authority));
        assertDoesNotThrow(() -> access.observe(graph(), actor("alice"), run, "workflow.result.read", 1001));
        assertEquals(403, assertThrows(Failure.class, () -> access.observe(graph(), actor("bob"), run, "workflow.result.read", 1001)).status());
    }
    @Test void missingWorkflowAgentOrModelGrantIsDenied() throws Exception {
        for (String missing : List.of("workflow:investigate", "agent:investigator", "model-group:models")) {
            entries.clear(); seed();
            var grant = codec.model(entries.get(Ids.Kind.GRANT.id("human")).document(), ControlAccessGrant.class);
            var s = grant.scope();
            put(Ids.Kind.GRANT, new ControlAccessGrant("human", "Human", true, 2L, grant.sourceType(), grant.sourceId(), grant.purpose(),
                new EnterpriseScope(all, all, s.actions(), false, s.resources().stream().filter(r -> !r.locator().equals(missing)).toList(), conditions)));
            var config = config();
            assertEquals(403, assertThrows(Failure.class, () -> access.capture(graph(), actor("alice"), config, config.workflows().getFirst(), 1000)).status(), missing);
        }
    }
    @Test void revocationAndAgentScopeExpansionCannotUseAdmittedAuthority() throws Exception {
        seed(); var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("alice"), config, workflow, 1000);
        put(Ids.Kind.GRANT, new ControlAccessGrant("extra", "New tool capability", true, 1L, EnterpriseSourceType.AGENT_GROUP,
            "support-agents", EnterpriseGrantPurpose.CAPABILITY, scope(List.of("write"), "tool://files/write")));
        assertEquals(403, assertThrows(Failure.class, () -> access.require(graph(), authority, config, workflow, "workflow.execute", 1001)).status());
        entries.remove(Ids.Kind.GRANT.id("extra")); entries.remove(Ids.Kind.GRANT.id("human"));
        assertEquals(403, assertThrows(Failure.class, () -> access.require(graph(), authority, config, workflow, "workflow.execute", 1001)).status());
    }
    @Test void disabledUserAndLostTeamMembershipAreDenied() throws Exception {
        seed(); var config = config(); var workflow = config.workflows().getFirst();
        var authority = access.capture(graph(), actor("alice"), config, workflow, 1000);
        put(Ids.Kind.TEAM, new ControlTeam("callers", "Callers", false, 2L, List.of("alice"), List.of(), null));
        assertEquals(403, assertThrows(Failure.class, () -> access.require(graph(), authority, config, workflow, "workflow.execute", 1001)).status());
        put(Ids.Kind.USER, new ControlUser("alice", "Alice", false, 2L, null, null, null));
        assertEquals(403, assertThrows(Failure.class, () -> access.capture(graph(), actor("alice"), config, workflow, 1001)).status());
    }
}
