// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.jsonsdk.JsonProject;
import io.ololabs.toolgate.jsonsdk.JsonSdk;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SdkBuilderTest {
    @TempDir Path directory;
    private final ContractCodec codec = new ContractCodec();
    private final Ids.TenantId tenant = io.ololabs.toolgate.control.fixtures.Tenants.id("sdk-builder");
    private final ObjectMapper json = new ObjectMapper();

    private String project() throws Exception {
        var fixtures = json.readTree(Path.of(System.getProperty("toolgate.fixtures")).toFile());
        var root = (ObjectNode) json.readTree(fixtures.path("SdkBuilderSave").path("source").asText());
        root.put("id", "acme.orders").put("version", "0.1.0").put("sdkVersion", JsonProject.sdkVersion());
        var tool = root.path("tools").get(1).deepCopy(); ((ObjectNode)tool).put("name", "items.get");
        root.putArray("tools").add(tool);
        return new String(JsonProject.parse(json.writeValueAsBytes(root)).source(), StandardCharsets.UTF_8);
    }
    /** A verified test package at the worker boundary; SDK compilation itself has separate end-to-end checks. */
    private SdkBuildWorker.Outputs output(byte[] project) throws Exception {
        var files = ServerPackagesTest.files();
        var manifest = (ObjectNode) json.readTree(files.get("toolgate-package.json"));
        ((ObjectNode)manifest.get("sdk")).put("version", JsonProject.sdkVersion());
        manifest.put("version", JsonProject.parse(project).version());
        files.put("toolgate-package.json", json.writeValueAsBytes(manifest));
        var checksums = new StringBuilder();
        for (var entry : files.entrySet()) if (!entry.getKey().equals("CHECKSUMS.sha256")) checksums.append(Canonical.sha256(entry.getValue()).substring(7)).append("  ").append(entry.getKey()).append('\n');
        files.put("CHECKSUMS.sha256", checksums.toString().getBytes(StandardCharsets.UTF_8));
        return new SdkBuildWorker.Outputs(ServerPackagesTest.zip(files), JsonSdk.sourceArchive(project));
    }
    private SdkBuildWorker worker(AtomicInteger calls) {
        return source -> { calls.incrementAndGet(); try { return output(source); } catch (Exception failure) { throw new java.io.IOException(failure); } };
    }
    private SdkBuilderService service(Store store, SdkBuildWorker worker) throws Exception {
        return new SdkBuilderService(store, codec, tenant.value(), Clock.systemUTC(), worker, new FileBuilderArtifacts(directory));
    }
    private SdkBuilderDraft save(SdkBuilderService service, DirectoryService.Actor actor, String source, long expected, String key) {
        return codec.model(service.save(actor, codec.json(new SdkBuilderSave("project", expected, "default-tools", source)), key, "request").body(), SdkBuilderDraft.class);
    }
    private SdkBuilderBuild queue(SdkBuilderService service, DirectoryService.Actor actor, SdkBuilderDraft draft, String id) {
        return codec.model(service.queue(actor, codec.json(new SdkBuilderBuildRequest(id, draft.id(), draft.revision())), "queue-" + id, "request").body(), SdkBuilderBuild.class);
    }

    @Test void revisionsReplayImmutableBuildsAndSourceSnapshotsSurviveDraftEdits() throws Exception {
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("builder.sqlite"))), codec);
        var root = TestSupport.seed(store, codec, tenant, "root"); var calls = new AtomicInteger(); var service = service(store, worker(calls));
        var draft = save(service, root, project(), 0, "save"); assertEquals(draft, save(service, root, project(), 0, "save"));
        assertEquals(409, assertThrows(Failure.class, () -> save(service, root, project(), 0, "stale")).status());
        var job = queue(service, root, draft, "build");
        save(service, root, project().replace("Hello ", "Welcome "), 1, "edit");
        assertTrue(service.workOne()); assertFalse(service.workOne()); assertEquals(1, calls.get());
        var completed = codec.model(service.builds(root, null).body(), SdkBuilderBuildPage.class).items().getFirst();
        assertEquals(SdkBuilderState.READY, completed.state()); assertEquals(job.sourceDigest(), completed.sourceDigest());
        assertArrayEquals(JsonSdk.sourceArchive(draft.source().getBytes(StandardCharsets.UTF_8)), service.download(root, job.id(), true));
        assertNotNull(PackageArchive.validate(service.download(root, job.id(), false), value -> (com.fasterxml.jackson.databind.JsonNode)codec.value(value)));
        var edited = codec.model(service.drafts(root, null).body(), SdkBuilderDraftPage.class).items().getFirst();
        queue(service, root, edited, "changed-version"); service.workOne();
        var conflict = codec.model(service.builds(root, null).body(), SdkBuilderBuildPage.class).items().stream().filter(value -> value.id().equals("changed-version")).findFirst().orElseThrow();
        assertEquals(SdkBuilderError.VERSION_CONFLICT, conflict.error());
    }

    /** Unzip, edit and zip again inside the project's folder, as a person editing the exported source would. */
    private static byte[] rezip(byte[] exported, java.util.function.UnaryOperator<String> edit) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported)); var out = new java.util.zip.ZipOutputStream(bytes)) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals(JsonProject.FILE)) content = edit.apply(new String(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                out.putNextEntry(new java.util.zip.ZipEntry("acme-orders/" + entry.getName())); out.write(content); out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test void anExportedSourceEditedAndImportedBuildsTheNextVersion() throws Exception {
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("round-trip.sqlite"))), codec);
        var root = TestSupport.seed(store, codec, tenant, "root"); var service = service(store, worker(new AtomicInteger()));
        var first = save(service, root, project(), 0, "save"); queue(service, root, first, "first"); service.workOne();
        byte[] exported = service.download(root, "first", true);
        // The unchanged export imports as exactly the saved project.
        assertEquals(first.source(), codec.model(service.source(root, exported).body(), SdkBuilderSource.class).source());
        java.util.function.UnaryOperator<String> nextVersion = text -> {
            try { var tree = (ObjectNode) json.readTree(text); tree.put("version", "0.2.0"); return json.writerWithDefaultPrettyPrinter().writeValueAsString(tree).replace("Hello ", "Welcome "); }
            catch (java.io.IOException invalid) { throw new java.io.UncheckedIOException(invalid); }
        };
        var imported = codec.model(service.source(root, rezip(exported, nextVersion)).body(), SdkBuilderSource.class);
        assertEquals("0.2.0", JsonProject.parse(imported.source().getBytes(StandardCharsets.UTF_8)).version());
        assertEquals("sha256:" + DirectoryService.digest(imported.source()), imported.sourceDigest());
        // Saved over the same project, it builds as the next version, and its own export imports back unchanged.
        var second = save(service, root, imported.source(), first.revision(), "save-imported");
        queue(service, root, second, "second"); service.workOne();
        var built = codec.model(service.builds(root, null).body(), SdkBuilderBuildPage.class).items().stream().filter(b -> b.id().equals("second")).findFirst().orElseThrow();
        assertEquals(SdkBuilderState.READY, built.state());
        assertEquals(imported.source(), codec.model(service.source(root, service.download(root, "second", true)).body(), SdkBuilderSource.class).source());
        assertEquals(400, assertThrows(Failure.class, () -> service.source(root, new byte[]{1, 2, 3})).status());
    }

    private DirectoryService.Actor editor(Store store) {
        store.transaction(tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.USER, new ControlUser("editor", "SDK author", true, 1L)); GroupGraph.addDefault(entries, codec, Ids.Kind.USER, "editor");
            var conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
            var rule = new EnterpriseManagementRule(List.of("tool_builder.read", "tool_builder.build"), EnterpriseGroupType.TOOL_GROUP, new GroupSelection(List.of("default-tools"), false), List.of(), conditions, null);
            TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole("sdk-author", "SDK author", true, 1L, UserRole.ADMINISTRATOR, EnterpriseRoleType.MANAGEMENT, List.of(rule)));
            TestSupport.put(entries, codec, Ids.Kind.TEAM, new ControlTeam("sdk-authors", "SDK authors", true, 1L, List.of("editor"), List.of("sdk-author")));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
        return new DirectoryService(store, codec, 512, 1048576).portalActor(tenant, DirectoryService.digest("editor-session"), "editor");
    }
    private void revoke(Store store) {
        store.transaction(tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            var role = codec.model(entries.get(Ids.Kind.ROLE.id("sdk-author")).document(), ControlRole.class);
            TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole(role.id(), role.name(), role.enabled(), role.revision() + 1, role.portalRole(), role.roleType(), List.of()));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
    }
    @Test void currentAuthorityIsCheckedBeforeWorkerClaimAndDownloads() throws Exception {
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("authority.sqlite"))), codec);
        TestSupport.seed(store, codec, tenant, "root"); var actor = editor(store); var calls = new AtomicInteger(); var service = service(store, worker(calls));
        byte[] archive = JsonSdk.sourceArchive(project().getBytes(StandardCharsets.UTF_8));
        assertEquals(200, service.source(actor, archive).status());
        var status = codec.model(service.status(actor).body(), SdkBuilderStatus.class);
        assertTrue(status.configured()); assertEquals(1, status.groups().size()); assertTrue(status.groups().getFirst().canBuild());
        var draft = save(service, actor, project(), 0, "save"); queue(service, actor, draft, "build"); revoke(store);
        assertFalse(service.workOne()); assertEquals(0, calls.get());
        assertEquals(403, assertThrows(Failure.class, () -> service.status(actor)).status());
        assertEquals(403, assertThrows(Failure.class, () -> service.download(actor, "build", true)).status());
        assertEquals(403, assertThrows(Failure.class, () -> service.source(actor, archive)).status());
    }
    @Test void revocationDuringCompilationPreventsArtifactPublication() throws Exception {
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(SqliteState.open(directory.resolve("during.sqlite"))), codec);
        var root = TestSupport.seed(store, codec, tenant, "root"); var actor = editor(store);
        var service = service(store, source -> { revoke(store); try { return output(source); } catch (Exception failure) { throw new java.io.IOException(failure); } });
        var draft = save(service, actor, project(), 0, "save"); queue(service, actor, draft, "build"); service.workOne();
        assertEquals(SdkBuilderError.AUTHORITY_REVOKED, codec.model(service.builds(root, null).body(), SdkBuilderBuildPage.class).items().getFirst().error());
        assertEquals(409, assertThrows(Failure.class, () -> service.download(root, "build", false)).status());
    }

    @Test void postgresSerializesRevisionRacesAndProtectsImmutableVersions() throws Exception {
        String base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL")), password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        String database = "sdk_builder_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(base, "control_migrator", password); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = base.replace("/control?", "/" + database + "?");
        var migrations = org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load(); migrations.migrate(); migrations.validate();
        var data = new org.postgresql.ds.PGSimpleDataSource(); data.setURL(url); data.setUser("control_app"); data.setPassword(password);
        var store = new PostgresStore(io.ololabs.toolgate.control.fixtures.Tenants.database(data), codec); var root = TestSupport.seed(store, codec, tenant, "root");
        var calls = new AtomicInteger(); var service = service(store, worker(calls)); String source = project();
        var first = save(service, root, source, 0, "save");
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String key : List.of("one", "two")) results.add(threads.submit(() -> {
                try { save(service, root, source, first.revision(), key); return true; }
                catch (Failure conflict) { assertEquals(409, conflict.status()); return false; }
            }));
            int winners = 0; for (var result : results) if (result.get()) winners++; assertEquals(1, winners);
        }
        var current = codec.model(service.drafts(root, null).body(), SdkBuilderDraftPage.class).items().getFirst();
        queue(service, root, current, "build");
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = threads.submit(service::workOne); var b = threads.submit(service::workOne); a.get(); b.get();
        }
        assertEquals(1, calls.get());
        assertEquals(SdkBuilderState.READY, codec.model(service.builds(root, null).body(), SdkBuilderBuildPage.class).items().getFirst().state());
        try (var connection = data.getConnection(); var statement = connection.prepareStatement("UPDATE control_builder SET revision=revision+1 WHERE tenant_id=? AND kind='VERSION'")) {
            TestSupport.scope(connection, tenant);
            statement.setString(1, tenant.value()); assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }
    }
}
