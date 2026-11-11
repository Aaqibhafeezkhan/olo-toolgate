// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.control.fixtures.Tenants;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real-store lifecycle tests; injected readiness here is not live model/tool qualification. */
final class WorkflowProfilesTest {
    @TempDir Path temp;
    private final ContractCodec codec = new ContractCodec();
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Rig(PostgresStore store, DataSource data, Ids.TenantId tenant, DirectoryService.Actor admin) {}
    private TenantVaultKeys keys() { return new TenantVaultKeys(codec, new LocalVaultWrappingProvider("fixture", Map.of("fixture", new byte[32]))); }
    private Rig rig(boolean postgres) throws Exception {
        DataSource data;
        if (postgres) {
            String base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Use the provisioned PostgreSQL gate");
            String password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
            String name = "workflow_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(base, "control_migrator", password); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + name);
            }
            String url = base.replace("/control?", "/" + name + "?");
            org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).cleanDisabled(true).load().migrate();
            var source = new org.postgresql.ds.PGSimpleDataSource(); source.setURL(url); source.setUser("control_app"); source.setPassword(password);
            data = Tenants.database(source);
        } else data = Tenants.database(SqliteState.open(temp.resolve(UUID.randomUUID() + ".sqlite")));
        var store = new PostgresStore(data, codec); var tenant = Tenants.id("tenant");
        return new Rig(store, data, tenant, TestSupport.seed(store, codec, tenant, "root"));
    }
    private ObjectNode profile() throws Exception {
        var path = Path.of(System.getProperty("toolgate.fixtures")).getParent().resolve("../v2/valid.json");
        var profile = JSON.createObjectNode().put("profileVersion", 2).put("profileId", "support");
        profile.putArray("packages"); profile.set("workflows", JSON.readTree(Files.readString(path)).get("WorkflowConfiguration"));
        return profile;
    }
    private JsonNode node(Store.Reply reply) { return (JsonNode)codec.value(reply.body()); }
    private String review(String digest) { return codec.json(Map.of("profileDigest", digest)); }
    private String publish(String digest, long generation) { return codec.json(Map.of("profileDigest", digest, "expectedGeneration", generation)); }
    @Test void sqliteLifecycleIsAtomicAndPublishedSeparately() throws Exception { lifecycle(false); }
    @Test void postgresLifecycleIsAtomicAndPublishedSeparately() throws Exception { lifecycle(true); }
    @Test void sqliteCatalogIsPagedPrivateAndTenantScoped() throws Exception { catalog(false); }
    @Test void postgresCatalogIsPagedPrivateAndTenantScoped() throws Exception { catalog(true); }
    @Test void malformedPackageDeclarationsCannotBeStaged() throws Exception {
        var rig = rig(false);
        var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys());
        var value = profile();
        var entry = ((ArrayNode)value.get("packages")).addObject();
        entry.put("packageId", "examples.inbox-watch").put("version", "0.1.0")
            .put("source", "PREBUILT_CORE").put("required", true);
        entry.putArray("deviceGroupIds").add("devices").add("devices");

        assertEquals(400, assertThrows(Failure.class,
            () -> service.upload(rig.admin, value.toString(), "duplicate-groups")).status());
        assertEquals(0, node(service.list(rig.admin, null, null, 50)).size());

        entry.putArray("deviceGroupIds").add("devices");
        assertEquals(201, service.upload(rig.admin, value.toString(), "valid-declarations").status());
    }

    private void catalog(boolean postgres) throws Exception {
        var rig = rig(postgres); var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys());
        var expected = new HashSet<String>();
        for (int i = 0; i < 3; i++) {
            var value = profile(); ((ObjectNode)value.path("workflows").path("agentDefinitions").get(0)).put("systemPrompt", "Private prompt " + i);
            expected.add(node(service.upload(rig.admin, value.toString(), "catalog-" + i)).path("profileDigest").asText());
        }
        var found = new HashSet<String>(); String afterId = null, afterDigest = null;
        for (int i = 0; i < 4; i++) {
            var page = node(service.list(rig.admin, afterId, afterDigest, 1)); assertTrue(page.isArray());
            if (i == 3) { assertEquals(0, page.size()); break; }
            assertEquals(1, page.size()); var entry = page.get(0);
            assertFalse(entry.has("document")); assertFalse(entry.toString().contains("Private prompt"));
            afterId = entry.path("profileId").asText(); afterDigest = entry.path("profileDigest").asText();
            assertTrue(found.add(afterDigest));
        }
        assertEquals(expected, found);
        var other = TestSupport.seed(rig.store, codec, Tenants.id("other"), "root");
        assertEquals(0, node(service.list(other, null, null, 50)).size());
        var forged = new DirectoryService.Actor(rig.tenant, DirectoryService.digest("forged"), true, true, "intruder");
        assertEquals(403, assertThrows(Failure.class, () -> service.list(forged, null, "bad", 0)).status());
        assertEquals(400, assertThrows(Failure.class, () -> service.list(rig.admin, null, null, 51)).status());
        assertEquals(400, assertThrows(Failure.class, () -> service.list(rig.admin, "support", null, 50)).status());
        assertEquals(400, assertThrows(Failure.class, () -> service.list(rig.admin, "support", "bad", 50)).status());
    }
    private void lifecycle(boolean postgres) throws Exception {
        var rig = rig(postgres); var ready = new AtomicBoolean(true);
        var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> ready.get(), keys());
        String document = profile().toString();
        var staged = node(service.upload(rig.admin, document, "stage")); String digest = staged.path("profileDigest").asText();
        assertEquals("DRAFT", staged.path("state").asText()); assertFalse(staged.path("dependenciesReady").asBoolean());
        assertEquals(200, service.upload(rig.admin, document, "repeat").status());
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(digest, 0), "early")).status());
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.workflowProfiles().publication("support")));
        assertEquals("REVIEWED", node(service.review(rig.admin, "support", review(digest), "review")).path("state").asText());
        assertTrue(node(service.get(rig.admin, "support", digest)).path("dependenciesReady").asBoolean(), "reviewed ready revisions must be publishable from the console");
        service.review(rig.admin, "support", review(digest), "review-repeat");
        ready.set(false);
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(digest, 0), "unready")).status());
        ready.set(true);
        var activated = node(service.publish(rig.admin, "support", publish(digest, 0), "publish"));
        assertEquals("PUBLISHED", activated.path("state").asText()); assertEquals(1, activated.path("generation").asLong());
        service.publish(rig.admin, "support", publish(digest, 0), "publish-repeat");
        assertEquals(1, node(service.get(rig.admin, "support", digest)).path("generation").asLong());
        var changed = profile(); ((ObjectNode)changed.path("workflows").path("workflows").get(0)).put("versionId", "revision-2").put("systemPrompt", "Changed supervisor");
        ((ObjectNode)changed.path("workflows").path("dispatchBindings").get(0)).put("workflowVersionId", "revision-2");
        String next = node(service.upload(rig.admin, changed.toString(), "next")).path("profileDigest").asText();
        assertEquals(digest, rig.store.transaction(rig.tenant, false, tx -> tx.workflowProfiles().publication("support").digest()));
        service.review(rig.admin, "support", review(next), "next-review");
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(next, 0), "stale")).status());
        assertEquals(2, node(service.publish(rig.admin, "support", publish(next, 1), "next-publish")).path("generation").asLong());
        assertEquals("REVIEWED", node(service.get(rig.admin, "support", digest)).path("state").asText());
        ready.set(false);
        assertFalse(node(service.get(rig.admin, "support", next)).path("dependenciesReady").asBoolean());
        assertEquals(403, assertThrows(Failure.class, () -> rig.store.transaction(rig.tenant, false, tx -> service.published(tx, rig.tenant, "support"))).status());
        // Stored content cannot be rewritten, and a pointer cannot refer to an absent revision.
        assertThrows(SQLException.class, () -> {
            try (var connection = rig.data.getConnection()) {
                if (postgres) TestSupport.scope(connection, rig.tenant);
                try (var statement = connection.prepareStatement("UPDATE control_workflow_profile_revisions SET document='{}' WHERE tenant_id=?")) {
                    statement.setString(1, rig.tenant.value()); statement.executeUpdate();
                }
            }
        });
        if (postgres) try (var connection = rig.data.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM control_workflow_profile_revisions")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1), "no tenant context exposes no revisions");
        }
    }
    @Test void ownershipAndVersionConflictsRollBackWithoutPublishing() throws Exception {
        var rig = rig(false); var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys());
        var initial = profile(); String digest = node(service.upload(rig.admin, initial.toString(), "initial")).path("profileDigest").asText();
        var changed = initial.deepCopy(); changed.put("profileId", "competitor");
        assertEquals(409, assertThrows(Failure.class, () -> service.upload(rig.admin, changed.toString(), "steal")).status());
        var rewritten = initial.deepCopy(); ((ObjectNode)rewritten.path("workflows").path("workflows").get(0)).put("systemPrompt", "Rewrite same version");
        assertEquals(409, assertThrows(Failure.class, () -> service.upload(rig.admin, rewritten.toString(), "rewrite")).status());
        assertEquals("DRAFT", node(service.get(rig.admin, "support", digest)).path("state").asText());
    }
    @Test void foreignTenantAndForgedCachedSuperAdminNeverReadOrModifyARevision() throws Exception {
        var rig = rig(false); var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys());
        String digest = node(service.upload(rig.admin, profile().toString(), "initial")).path("profileDigest").asText();
        var other = TestSupport.seed(rig.store, codec, Tenants.id("other"), "root");
        assertEquals(404, assertThrows(Failure.class, () -> service.get(other, "support", digest)).status());
        assertEquals(201, service.upload(other, profile().toString(), "separate").status());
        var forged = new DirectoryService.Actor(rig.tenant, DirectoryService.digest("forged"), true, true, "intruder");
        assertEquals(403, assertThrows(Failure.class, () -> service.get(forged, "support", digest)).status());
        assertEquals(403, assertThrows(Failure.class, () -> service.review(forged, "support", review(digest), "forged")).status());
    }
    @Test void auditFailureRollsBackRevisionAndOwnership() throws Exception {
        var rig = rig(false);
        Store failing = new Store() {
            public <T> T transaction(Ids.TenantId tenant, boolean write, java.util.function.Function<Store.Session, T> work) {
                return rig.store.transaction(tenant, write, tx -> work.apply((Store.Session)java.lang.reflect.Proxy.newProxyInstance(
                    Store.Session.class.getClassLoader(), new Class<?>[]{Store.Session.class}, (proxy, method, args) -> {
                        if (method.getName().equals("audit")) throw Failure.unavailable();
                        try { return method.invoke(tx, args); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    })));
            }
        };
        var value = profile(); var service = new WorkflowProfiles(failing, codec, (tx, p) -> true, keys());
        assertEquals(503, assertThrows(Failure.class, () -> service.upload(rig.admin, value.toString(), "fail-audit")).status());
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.workflowProfiles().owner("WORKFLOW", "investigate")));
        assertEquals(201, new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys()).upload(rig.admin, value.toString(), "retry").status());
    }
    @Test void currentFormatOnlyAndUnreadyProductionDependenciesNeverPublish() throws Exception {
        var rig = rig(false); var deps = new WorkflowProfileDependencies(codec, java.time.Clock.systemUTC(), null, null, rig.tenant);
        var service = new WorkflowProfiles(rig.store, codec, deps, keys()); var value = profile();
        var obsolete = value.deepCopy().put("profileVersion", 1);
        assertEquals(400, assertThrows(Failure.class, () -> service.upload(rig.admin, obsolete.toString(), "obsolete")).status());
        String digest = node(service.upload(rig.admin, value.toString(), "draft")).path("profileDigest").asText();
        service.review(rig.admin, "support", review(digest), "review");
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(digest, 0), "not-ready")).status());
        assertNull(rig.store.transaction(rig.tenant, false, tx -> tx.workflowProfiles().publication("support")));
    }
    @Test void concurrentIdenticalUploadCreatesOneRevision() throws Exception {
        var rig = rig(false); var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys()); String value = profile().toString();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> service.upload(rig.admin, value, "thread-a").status());
            var b = executor.submit(() -> service.upload(rig.admin, value, "thread-b").status());
            assertEquals(Set.of(200, 201), Set.of(a.get(15, java.util.concurrent.TimeUnit.SECONDS), b.get(15, java.util.concurrent.TimeUnit.SECONDS)));
        } finally { executor.shutdownNow(); }
    }
    @Test void realDependencyChecksRequireVaultScopeAndCurrentAgentMembership() throws Exception {
        var rig = rig(false); var deps = new WorkflowProfileDependencies(codec, java.time.Clock.systemUTC(), null, null, rig.tenant);
        var service = new WorkflowProfiles(rig.store, codec, deps, keys()); var value = profile();
        ((ObjectNode)value.path("workflows")).putArray("dispatchBindings");
        ((ObjectNode)value.path("workflows").path("agentDefinitions").get(0)).putArray("contextSources");
        rig.store.transaction(rig.tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.AGENT, new io.ololabs.toolgate.contracts.ControlAgent("investigator", "Investigator", null, true, 1L, "root"));
            TestSupport.put(entries, codec, Ids.Kind.AGENT_GROUP, new io.ololabs.toolgate.contracts.ControlAgentGroup("support-agents", "Support", null, true, 1L, List.of("investigator"), List.of(), null));
            TestSupport.put(entries, codec, Ids.Kind.TOOL_GROUP, new io.ololabs.toolgate.contracts.ControlToolGroup("model-tools", "Models", true, 1L, List.of()));
            TestSupport.put(entries, codec, Ids.Kind.DEVICE_GROUP, new io.ololabs.toolgate.contracts.ControlDeviceGroup("model-devices", "Models", true, 1L, List.of()));
            tx.save(before, new io.ololabs.toolgate.control.domain.Directory(before.revision() + 1, entries)); return null;
        });
        String digest = node(service.upload(rig.admin, value.toString(), "stage")).path("profileDigest").asText();
        service.review(rig.admin, "support", review(digest), "review");
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(digest, 0), "no-secret")).status());
        var keys = new TenantVaultKeys(codec, new LocalVaultWrappingProvider("fixture", Map.of("fixture", new byte[32])));
        new VaultService(rig.store, codec, keys).write(rig.admin,
            codec.json(Map.of("name", "model-key", "toolGroupId", "default-tools", "deviceGroupId", "default-devices", "value", "fixture-value")), "wrong-scope");
        assertEquals(409, assertThrows(Failure.class, () -> service.publish(rig.admin, "support", publish(digest, 0), "wrong-secret-scope")).status());
        new VaultService(rig.store, codec, keys).write(rig.admin,
            codec.json(Map.of("name", "model-key", "toolGroupId", "model-tools", "deviceGroupId", "model-devices", "value", "fixture-value")), "secret");
        assertTrue(node(service.publish(rig.admin, "support", publish(digest, 0), "publish")).path("dependenciesReady").asBoolean());
        rig.store.transaction(rig.tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.AGENT_GROUP, new io.ololabs.toolgate.contracts.ControlAgentGroup("support-agents", "Support", null, true, 2L, List.of(), List.of(), null));
            tx.save(before, new io.ololabs.toolgate.control.domain.Directory(before.revision() + 1, entries)); return null;
        });
        assertFalse(node(service.get(rig.admin, "support", digest)).path("dependenciesReady").asBoolean());
        assertEquals(403, assertThrows(Failure.class, () -> rig.store.transaction(rig.tenant, false, tx -> service.published(tx, rig.tenant, "support"))).status());
    }
    @Test void privateDocumentsAreEncryptedBoundToTenantAndSurviveReadOnlyKeyRotation() throws Exception {
        var rig = rig(false); var keys = keys(); var documents = new WorkflowProfileDocuments(codec, keys);
        var service = new WorkflowProfiles(rig.store, codec, (tx, p) -> true, keys); var value = profile();
        String privatePrompt = "private-profile-content-" + "文".repeat(20000);
        ((ObjectNode)value.path("workflows").path("workflows").get(0)).put("systemPrompt", privatePrompt);
        String digest = node(service.upload(rig.admin, value.toString(), "large")).path("profileDigest").asText();
        var stored = rig.store.transaction(rig.tenant, false, tx -> tx.workflowProfiles().revision("support", digest));
        assertFalse(stored.document().contains("private-profile-content"));
        assertTrue(((JsonNode)codec.value(stored.document())).path("chunks").size() > 1);
        assertTrue(service.document(rig.admin, "support", digest, false).body().contains(privatePrompt));
        rig.store.transaction(rig.tenant, true, tx -> { keys.rotate(rig.tenant, tx); return null; });
        assertTrue(service.document(rig.admin, "support", digest, false).body().contains(privatePrompt));
        var corrupt = (ObjectNode)codec.value(stored.document()); var chunks = (com.fasterxml.jackson.databind.node.ArrayNode)corrupt.path("chunks");
        var first = chunks.get(0); chunks.set(0, chunks.get(1)); chunks.set(1, first);
        var tampered = new WorkflowProfileStore.Revision(stored.profileId(), stored.digest(), corrupt.toString(), stored.keyVersion(), stored.state(), null);
        assertThrows(Failure.class, () -> rig.store.transaction(rig.tenant, false, tx -> documents.open(tx, rig.tenant, tampered)));
        assertThrows(Failure.class, () -> rig.store.transaction(Tenants.id("other"), false, tx -> documents.open(tx, Tenants.id("other"), stored)));
    }
}
