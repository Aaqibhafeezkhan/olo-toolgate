// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ololabs.toolgate.control.adapter.*;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.domain.*;
import io.ololabs.toolgate.contracts.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** MCP proxy registration and scans in Control (docs/planning/mcp-proxy/plan.md T3), on SQLite and PostgreSQL. */
final class McpServersTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContractCodec codec = new ContractCodec();

    /** A clock the test moves. */
    static final class Ticking extends java.time.Clock {
        long now = 1_800_000_000_000L;
        public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    private record Fixture(boolean postgres, javax.sql.DataSource runtime, PostgresStore store, TenantRegistry registry, VaultService vault, ServerPackages packages, McpServers servers, Ticking clock, RsaEffectSigner signer) {}

    private Fixture fixture(boolean postgres) throws Exception {
        javax.sql.DataSource runtime, platform;
        if (postgres) {
            var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL")); var password = System.getenv("CONTROL_TEST_PASSWORD"); var name = "mcp_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
            var url = base.replace("/control?", "/" + name + "?"); org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).load().migrate();
            var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password); runtime = app;
            var operator = new org.postgresql.ds.PGSimpleDataSource(); operator.setURL(url); operator.setUser("control_provisioner"); operator.setPassword(password); platform = operator;
        } else { runtime = SqliteState.open(temp.resolve(UUID.randomUUID() + ".sqlite")); platform = runtime; }
        var keys = new TenantVaultKeys(codec, new LocalVaultWrappingProvider("first", Map.of("first", new byte[32])));
        var store = new PostgresStore(runtime, codec); var clock = new Ticking();
        var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var signer = new RsaEffectSigner((java.security.interfaces.RSAPrivateCrtKey) generator.generateKeyPair().getPrivate(), "effect-1", codec);
        var operations = new EnterpriseOperations(store, codec, null, clock, "control", "toolgate-client", 60000, 256);
        var packages = new ServerPackages(store, codec, operations, clock);
        return new Fixture(postgres, runtime, store, new TenantRegistry(platform, codec, keys), new VaultService(store, codec, keys),
            packages, new McpServers(store, codec, signer, clock, packages, operations), clock, signer);
    }

    /** A tenant with an administrator, a pool of one Tool Host and an execution binding onto it, and the upstream secret in the binding's groups. */
    private DirectoryService.Actor tenant(Fixture f, String alias, String host) {
        var row = f.registry.allocate(alias, alias);
        assertTrue(f.registry.initialize(row.id(), "a".repeat(64), "[]", unit -> {
            TestSupport.seed(unit, codec, row.id(), "root"); unit.transaction(row.id(), true, tx -> { tx.protectBootstrapAdmin("root"); return null; });
        }));
        var actor = new DirectoryService.Actor(row.id(), "a".repeat(64), true, false, "root");
        TestSupport.pool(f.store, codec, row.id(), "pool", host);
        TestSupport.pool(f.store, codec, row.id(), "other", host + "-other");
        f.store.transaction(row.id(), true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries());
            TestSupport.put(entries, codec, Ids.Kind.BINDING, new ControlExecutionBinding("mcp-binding", "MCP", true, 1L, "default-tools", "pool", List.of("invoke"), List.of(), true, false));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
        for (var id : List.of(host, host + "-other"))
            f.packages.register(row.id(), tx -> device(row.id(), id), "{\"hostId\":\"" + id + "\",\"poolId\":\"" + (id.equals(host) ? "pool" : "other") + "\",\"version\":\"0.10.0\",\"protocol\":{\"min\":2,\"max\":2},\"launcherVersion\":\"0.10.0\",\"tiers\":[\"HOST_NATIVE\"]}", "register-" + id);
        f.vault.write(actor, codec.json(new EnterpriseVaultWrite("orders/mcp-token", "upstream-" + alias, "default-tools", "pool")), "secret-1");
        f.vault.write(actor, codec.json(new EnterpriseVaultWrite("other/token", "other", "default-tools", "other")), "secret-2");
        return actor;
    }
    private static EndpointDeviceRecord device(Ids.TenantId tenant, String id) { return new EndpointDeviceRecord(id, tenant.value(), "root", "f".repeat(64), EndpointState.ACTIVE, 1L, 0L, 0L, null, null, true, 1L, null, null); }
    private static String registration(String slug, String secret) {
        return "{\"slug\":\"" + slug + "\",\"url\":\"https://mcp.example.com/mcp\",\"auth\":{\"secretName\":\"" + secret + "\",\"scheme\":\"BEARER\"},\"bindingId\":\"mcp-binding\",\"strict\":false}";
    }
    private JsonNode node(String document) throws Exception { return JSON.readTree(document); }
    private static int status(org.junit.jupiter.api.function.Executable call) { return assertThrows(Failure.class, call).status(); }
    private static String tool(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}";
    }
    private static String result(String scanId, String fencing, String... tools) {
        return "{\"scanId\":\"" + scanId + "\",\"fencingToken\":\"" + fencing + "\",\"serverInfo\":{\"name\":\"orders-mcp\",\"version\":\"1.0.0\"},\"protocolVersion\":\"2025-11-25\",\"spkiPin\":\"sha256/" + "A".repeat(43) + "=\",\"tools\":[" + String.join(",", tools) + "]}";
    }
    /** Claims the host's next scan and consumes its permit: the claims, as the host verified them. */
    private io.ololabs.toolgate.contracts.v2.McpScanClaims admit(Fixture f, Ids.TenantId tenant, String host) throws Exception {
        var dispatch = node(f.servers.next(tenant, tx -> device(tenant, host)).body());
        var claims = f.signer.verifyScan(dispatch.get("permit").asText());
        assertEquals("ADMITTED", node(f.servers.consume(tenant, tx -> device(tenant, host), claims.scanId(), codec.json(claims)).body()).get("state").asText());
        return claims;
    }

    @Test void sqliteRegistrationAndScans() throws Exception { scenario(fixture(false)); }
    @Test void postgresRegistrationAndScans() throws Exception { scenario(fixture(true)); }

    private void scenario(Fixture f) throws Exception {
        var a = tenant(f, "tenant-a", "host-a"); var b = tenant(f, "tenant-b", "host-b");
        var tenant = a.tenant();

        // Slugs are one PackageId component: 2–31 characters, starting with a letter, lowercase.
        for (var slug : List.of("a", "1server", "-server", "x".repeat(32), "Orders"))
            assertEquals(400, status(() -> f.servers.register(a, registration(slug, "orders/mcp-token"), "k-" + slug.length(), "r")), slug);
        // The secret must belong to exactly the binding's groups; the binding must exist.
        assertEquals(403, status(() -> f.servers.register(a, registration("orders", "other/token"), "k-foreign", "r")));
        assertEquals(403, status(() -> f.servers.register(a, registration("orders", "orders/mcp-token").replace("mcp-binding", "nowhere"), "k-binding", "r")));
        var registered = node(f.servers.register(a, registration("orders", "orders/mcp-token"), "k-1", "r").body());
        assertEquals("REGISTERED", registered.get("state").asText());
        assertEquals(registered, node(f.servers.register(a, registration("orders", "orders/mcp-token"), "k-1", "r").body()), "a retry returns the registration");
        assertEquals(409, status(() -> f.servers.register(a, registration("orders", "orders/mcp-token"), "k-2", "r")));
        var longest = "x" + "y".repeat(30);
        assertEquals(longest, node(f.servers.register(a, registration(longest, "orders/mcp-token"), "k-3", "r").body()).get("slug").asText());

        // A DISCOVER scan is queued on the pool's Tool Host; one open scan per server.
        var job = node(f.servers.scan(a, "orders", "scan-1", "r").body());
        assertEquals("host-a", job.get("hostId").asText());
        assertEquals("SCANNING", node(f.servers.server(a, "orders").body()).get("state").asText());
        assertEquals(409, status(() -> f.servers.scan(a, "orders", "scan-2", "r")));

        // Another host of the tenant gets nothing; the lease holder gets a 10 s permit with a 30 s signed deadline.
        assertEquals(204, f.servers.next(tenant, tx -> device(tenant, "host-a-other")).status());
        var dispatch = node(f.servers.next(tenant, tx -> device(tenant, "host-a")).body());
        var claims = f.signer.verifyScan(dispatch.get("permit").asText());
        assertEquals("host-a", claims.aud()); assertEquals(10_000, claims.exp() - claims.iat()); assertEquals(claims.iat() + 30_000, claims.deadlineUnixMs());
        assertEquals("orders/mcp-token", claims.auth().secretName()); assertTrue(claims.spkiPins().isEmpty());
        String scanId = claims.scanId(), fencing = claims.fencingToken();
        // Only the lease holder consumes, and no secret before consumption.
        assertEquals(403, status(() -> f.servers.consume(tenant, tx -> device(tenant, "host-a-other"), scanId, codec.json(claims))));
        assertEquals(403, status(() -> f.vault.deliverScan(tenant, tx -> "host-a", scanId, fencing, f.servers)));
        assertEquals("ADMITTED", node(f.servers.consume(tenant, tx -> device(tenant, "host-a"), scanId, codec.json(claims)).body()).get("state").asText());
        // Secret delivery: the admitted lease holder with its fencing token, the registration's secret only.
        assertEquals(403, status(() -> f.vault.deliverScan(tenant, tx -> "host-a", scanId, "wrong", f.servers)));
        assertEquals(403, status(() -> f.vault.deliverScan(tenant, tx -> "host-a-other", scanId, fencing, f.servers)));
        assertEquals("upstream-tenant-a", node(f.vault.deliverScan(tenant, tx -> "host-a", scanId, fencing, f.servers).body()).get("value").asText());

        // The result is accepted once, from the lease holder; Control recomputes the digests.
        var getOrder = tool("get_order", "Read one order.");
        assertEquals(403, status(() -> f.servers.result(tenant, tx -> device(tenant, "host-a-other"), scanId, result(scanId, fencing, getOrder))));
        var finished = node(f.servers.result(tenant, tx -> device(tenant, "host-a"), scanId, result(scanId, fencing, getOrder)).body());
        assertEquals("FINISHED", finished.get("state").asText()); assertEquals(1, finished.get("snapshotRevision").asLong());
        assertEquals(409, status(() -> f.servers.result(tenant, tx -> device(tenant, "host-a"), scanId, result(scanId, fencing, getOrder))));
        var snapshot = node(f.servers.snapshot(a, "orders", 1).body());
        assertEquals(McpDefinitions.digest(node(getOrder)), snapshot.get("tools").get(0).get("definitionDigest").asText());
        var server = node(f.servers.server(a, "orders").body());
        assertEquals("SCANNED", server.get("state").asText()); assertEquals(1, server.get("latestSnapshotRevision").asLong());

        // Limits are enforced again on receipt: the scan fails and the registration keeps its state.
        f.servers.scan(a, "orders", "scan-3", "r"); var limited = admit(f, tenant, "host-a");
        // A description over 8 KiB does not even parse as a scan result.
        assertEquals(400, status(() -> f.servers.result(tenant, tx -> device(tenant, "host-a"), limited.scanId(), result(limited.scanId(), limited.fencingToken(), tool("big", "x".repeat(8193))))));
        var values = new StringBuilder(); for (int i = 0; i < 257; i++) values.append(i == 0 ? "" : ",").append('"').append("v").append(i).append('"');
        var wide = "{\"name\":\"wide\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"v\":{\"enum\":[" + values + "]}}}}";
        var failed = node(f.servers.result(tenant, tx -> device(tenant, "host-a"), limited.scanId(), result(limited.scanId(), limited.fencingToken(), wide)).body());
        assertEquals("FAILED", failed.get("state").asText()); assertEquals("SCAN_LIMIT_EXCEEDED", failed.get("failure").asText());
        f.servers.scan(a, "orders", "scan-4", "r"); var duplicate = admit(f, tenant, "host-a");
        assertEquals("PROTOCOL_ERROR", node(f.servers.result(tenant, tx -> device(tenant, "host-a"), duplicate.scanId(), result(duplicate.scanId(), duplicate.fencingToken(), getOrder, getOrder)).body()).get("failure").asText());
        assertEquals("SCANNED", node(f.servers.server(a, "orders").body()).get("state").asText());

        // The signed deadline: late secret redemption and late results are refused, and the watchdog expires the scan.
        f.servers.scan(a, "orders", "scan-5", "r"); var late = admit(f, tenant, "host-a");
        f.clock.now += 31_000;
        assertEquals(403, status(() -> f.vault.deliverScan(tenant, tx -> "host-a", late.scanId(), late.fencingToken(), f.servers)));
        assertEquals(409, status(() -> f.servers.result(tenant, tx -> device(tenant, "host-a"), late.scanId(), result(late.scanId(), late.fencingToken(), getOrder))));
        assertEquals(1, f.servers.sweep(tenant));
        assertEquals("SCANNED", node(f.servers.server(a, "orders").body()).get("state").asText());
        // Its slot is free again.
        assertEquals("QUEUED", node(f.servers.scan(a, "orders", "scan-6", "r").body()).get("state").asText());

        // Another tenant may use the same slug; neither sees, scans or redeems the other's.
        assertEquals("orders", node(f.servers.register(b, registration("orders", "orders/mcp-token"), "k-1", "r").body()).get("slug").asText());
        assertEquals(1, node(f.servers.servers(b).body()).get("items").size());
        assertEquals(2, node(f.servers.servers(a).body()).get("items").size());
        assertEquals(404, status(() -> f.servers.snapshot(b, "orders", 1)));
        assertEquals(204, f.servers.next(b.tenant(), tx -> device(b.tenant(), "host-b")).status());
        assertEquals(404, status(() -> f.vault.deliverScan(b.tenant(), tx -> "host-a", scanId, fencing, f.servers)));
        if (f.postgres()) try (var c = f.runtime().getConnection()) {
            TestSupport.scope(c, b.tenant());
            try (var s = c.prepareStatement("SELECT count(*) FROM control_mcp_snapshots WHERE tenant_id=?")) {
                s.setString(1, tenant.value()); var rows = s.executeQuery(); rows.next(); assertEquals(0, rows.getLong(1), "RLS hides another tenant's rows");
            }
            try (var s = c.prepareStatement("INSERT INTO control_mcp_servers(tenant_id,slug,revision,document) VALUES(?,?,1,'{}')")) {
                s.setString(1, tenant.value()); s.setString(2, "forged"); assertThrows(java.sql.SQLException.class, s::executeUpdate, "RLS refuses writes into another tenant");
            }
            c.rollback();
        }
    }

    @Test void aRegistrarWithoutAuthorityOverTheBindingGroupsCannotRegisterOrScan() throws Exception {
        var f = fixture(false); var a = tenant(f, "tenant-a", "host-a");
        f.servers.register(a, registration("orders", "orders/mcp-token"), "k-1", "r");
        f.store.transaction(a.tenant(), true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries()); var all = new GroupSelection(List.of(), true);
            var conditions = new EnterpriseConditions(0L, 0L, List.of(), List.of(), List.of(), List.of(), false, false, null, null);
            var actions = List.of("mcp_server.read", "mcp_server.register");
            // Authority over the Tool Group and over another Device Group, not over the binding's pool.
            var rules = List.of(new EnterpriseManagementRule(actions, EnterpriseGroupType.TOOL_GROUP, all, List.of(), conditions, null),
                new EnterpriseManagementRule(actions, EnterpriseGroupType.DEVICE_GROUP, new GroupSelection(List.of("other"), false), List.of(), conditions, null));
            TestSupport.put(entries, codec, Ids.Kind.USER, new ControlUser("limited", "Limited registrar", true, 1L)); GroupGraph.addDefault(entries, codec, Ids.Kind.USER, "limited");
            TestSupport.put(entries, codec, Ids.Kind.ROLE, new ControlRole("limited-role", "Limited", true, 1L, UserRole.ADMINISTRATOR, EnterpriseRoleType.MANAGEMENT, rules));
            TestSupport.put(entries, codec, Ids.Kind.TEAM, new ControlTeam("limited-team", "Limited", true, 1L, List.of("limited"), List.of("limited-role")));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
        var limited = new DirectoryService.Actor(a.tenant(), "b".repeat(64), true, false, "limited");
        assertEquals(403, status(() -> f.servers.register(limited, registration("payments", "orders/mcp-token"), "k-2", "r")));
        assertEquals(404, status(() -> f.servers.scan(limited, "orders", "k-3", "r")));
        assertEquals(0, node(f.servers.servers(limited).body()).get("items").size());
    }

    /** Scans `tools` into a new snapshot of `slug` on host-a and returns its revision. */
    private long snapshot(Fixture f, DirectoryService.Actor a, String slug, String key, String... tools) throws Exception {
        f.servers.scan(a, slug, key, "r"); var claims = admit(f, a.tenant(), "host-a");
        return node(f.servers.result(a.tenant(), tx -> device(a.tenant(), "host-a"), claims.scanId(), result(claims.scanId(), claims.fencingToken(), tools)).body()).get("snapshotRevision").asLong();
    }
    private static String approval(long revision, String... mappings) {
        var tools = new StringBuilder();
        for (int i = 0; i < mappings.length; i += 2) tools.append(i == 0 ? "" : ",").append("{\"upstreamName\":\"").append(mappings[i]).append("\",\"name\":\"").append(mappings[i + 1]).append("\"}");
        return "{\"snapshotRevision\":" + revision + ",\"tools\":[" + tools + "]}";
    }
    private Directory.Entry entry(Fixture f, Ids.TenantId tenant, Ids.Kind kind, String id) { return f.store.transaction(tenant, false, tx -> tx.load().entries().get(kind.id(id))); }
    private void enable(Fixture f, Ids.TenantId tenant, String toolId) {
        f.store.transaction(tenant, true, tx -> {
            var before = tx.load(); var entries = new HashMap<>(before.entries()); var tool = entries.get(Ids.Kind.TOOL.id(toolId));
            var document = (com.fasterxml.jackson.databind.node.ObjectNode) codec.value(tool.document());
            entries.put(tool.id(), codec.entry(Ids.Kind.TOOL, codec.json(document.put("enabled", true).put("revision", document.get("revision").asLong() + 1))));
            tx.save(before, new Directory(before.revision() + 1, entries)); return null;
        });
    }
    /** An executing invocation of an MCP_PROXY tool on `host` under `fence`, bound to the deployed package `digest`. */
    private void invocation(Fixture f, Ids.TenantId tenant, String id, String toolId, String host, String fence, String digest) {
        String invocation = """
            {"id":"%s","requestDigest":"%s","evaluation":{"context":{"requestId":"%s","tenantId":"%s","mode":"SERVICE","agentId":"agent-1","chain":[],"bindingId":"mcp-binding","deviceId":"%s"},
             "toolId":"%s","action":"invoke","argumentsDigest":"%s","resources":[],"toolDigest":"%s","packageDigest":"%s","nowUnixMs":0,"authorityRevision":0,"online":true},
             "state":"EXECUTING","revision":1,"authorizationEpoch":0,"reservedNonce":"%s","expiresAtUnixMs":%d,"completedResources":[],"diagnosticId":"diag"}"""
            .formatted(id, "a".repeat(64), id, tenant.value(), host, toolId, DirectoryService.digest("{}"), "c".repeat(64), digest.substring(7), fence, f.clock.now + 60000);
        f.store.transaction(tenant, true, tx -> { tx.enterprise().saveInvocation(codec.model(invocation, EnterpriseInvocation.class), f.clock.now, 0); return null; });
    }
    private static String report(String invocation, String fence) { return "{\"invocationId\":\"" + invocation + "\",\"fencingToken\":\"" + fence + "\",\"reason\":\"DEFINITION_CHANGED\"}"; }

    @Test void sqliteApprovalsPackagesAndQuarantine() throws Exception { approvals(fixture(false)); }
    @Test void postgresApprovalsPackagesAndQuarantine() throws Exception { approvals(fixture(true)); }

    private void approvals(Fixture f) throws Exception {
        var a = tenant(f, "tenant-a", "host-a"); var tenant = a.tenant();
        f.servers.register(a, registration("orders", "orders/mcp-token"), "k-1", "r");
        long revision = snapshot(f, a, "orders", "s-1", tool("get_order", "Read one order."), tool("list_orders", "List orders."));

        // Names are refused, never silently changed: an unknown upstream tool, a duplicate name, a bad local name.
        assertEquals(400, status(() -> f.servers.approve(a, "orders", approval(revision, "nope", "nope"), "a-0", "r")));
        assertEquals(400, status(() -> f.servers.approve(a, "orders", approval(revision, "get_order", "same", "list_orders", "same"), "a-0b", "r")));
        assertEquals(400, status(() -> f.servers.approve(a, "orders", approval(revision, "get_order", "Get Order"), "a-0c", "r")));

        // Approval 1: the generated package goes through the real pipeline and is deployed to the registration's pool.
        var first = node(f.servers.approve(a, "orders", approval(revision, "get_order", "get-order", "list_orders", "list-orders"), "a-1", "r").body());
        assertEquals(1, first.get("sequence").asLong()); assertEquals("1.0.1", first.get("packageVersion").asText()); assertEquals("mcp.orders", first.get("packageId").asText());
        String digest1 = first.get("packageDigest").asText();
        var version = f.store.transaction(tenant, false, tx -> tx.packages().version(digest1));
        assertEquals("MCP_PROXY", node(version.document()).get("manifest").get("kind").asText());
        byte[] content = f.store.transaction(tenant, false, tx -> tx.packages().content(digest1));
        var verified = PackageArchive.validate(content, document -> (JsonNode) codec.value(document));
        assertEquals(digest1, verified.packageDigest());
        var proxy = node(new String(verified.files().get("artifacts/mcp-proxy.json"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("https://mcp.example.com/mcp", proxy.get("server").get("url").asText()); assertEquals(1, proxy.get("approvalSequence").asLong());
        var deployment = node(f.store.transaction(tenant, false, tx -> tx.packages().deployment("mcp.orders")));
        assertEquals("[\"pool\"]", deployment.get("deviceGroupIds").toString());
        assertEquals("[\"mcp.orders/get-order\",\"mcp.orders/list-orders\"]", deployment.get("toolIds").toString());
        // New tools start disabled; the extractor names the registration's secret for secret delivery.
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").enabled());
        var extractorId = node(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").document()).get("extractorId").asText();
        assertTrue(entry(f, tenant, Ids.Kind.EXTRACTOR, extractorId).document().contains("secret://orders/mcp-token"));
        // Only Control generates and deploys MCP_PROXY packages.
        assertEquals(422, status(() -> f.packages.upload(a, content, "r")));
        assertEquals(409, status(() -> f.packages.deploy(a, "{\"packageDigest\":\"" + digest1 + "\",\"globals\":{},\"deviceGroupIds\":[\"pool\"]}", "r")));

        // Idempotency: the same key returns the approval; the same approval under another key is the current one.
        assertEquals(first, node(f.servers.approve(a, "orders", approval(revision, "get_order", "get-order", "list_orders", "list-orders"), "a-1", "r").body()));
        assertEquals(1, node(f.servers.approve(a, "orders", approval(revision, "list_orders", "list-orders", "get_order", "get-order"), "a-2", "r").body()).get("sequence").asLong());

        // Freshness evidence in permits: CURRENT from the approved snapshot for 120 s, then UNVERIFIED.
        assertEquals("CURRENT", f.store.<JsonNode>transaction(tenant, false, tx -> f.packages.mcpVerification(tx, version, f.clock.now)).get("status").asText());
        assertEquals("UNVERIFIED", f.store.<JsonNode>transaction(tenant, false, tx -> f.packages.mcpVerification(tx, version, f.clock.now + 120_001)).get("status").asText());

        // Subset shrink: approve {get_order} after enabling both. list-orders is retired; get-order keeps its state.
        enable(f, tenant, "mcp.orders/get-order"); enable(f, tenant, "mcp.orders/list-orders");
        var second = node(f.servers.approve(a, "orders", approval(revision, "get_order", "get-order"), "a-3", "r").body());
        assertEquals(2, second.get("sequence").asLong()); assertEquals("1.0.2", second.get("packageVersion").asText());
        assertNotEquals(digest1, second.get("packageDigest").asText());
        assertTrue(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").enabled());
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/list-orders").enabled());
        assertFalse(entry(f, tenant, Ids.Kind.EXTRACTOR, node(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/list-orders").document()).get("extractorId").asText()).enabled());
        assertEquals("[\"mcp.orders/get-order\"]", node(f.store.transaction(tenant, false, tx -> tx.packages().deployment("mcp.orders"))).get("toolIds").toString());
        String digest2 = second.get("packageDigest").asText();
        // A permit for the retired tool is refused at issuance.
        invocation(f, tenant, "inv-retired", "mcp.orders/list-orders", "host-a", "fence-0", digest2);
        var gateway = new DirectoryService.Actor(tenant, "e".repeat(64), false);
        assertEquals(409, status(() -> f.packages.permit(gateway, "{\"invocationId\":\"inv-retired\",\"audience\":\"host-a\",\"attempt\":1,\"arguments\":{}}", "permit")));

        // Quarantine from a call: only the invocation's lease holder, with its fencing token, for its own tool.
        invocation(f, tenant, "inv-1", "mcp.orders/get-order", "host-a", "fence-1", digest2);
        assertEquals(403, status(() -> f.servers.quarantine(tenant, tx -> device(tenant, "host-a-other"), report("inv-1", "fence-1"))));
        assertEquals(403, status(() -> f.servers.quarantine(tenant, tx -> device(tenant, "host-a"), report("inv-1", "wrong"))));
        var quarantined = node(f.servers.quarantine(tenant, tx -> device(tenant, "host-a"), report("inv-1", "fence-1")).body());
        assertEquals("QUARANTINED", quarantined.get("state").asText());
        assertEquals("[\"mcp.orders/get-order\"]", quarantined.get("quarantine").get("toolIds").toString());
        assertFalse(quarantined.get("quarantine").get("serverWide").asBoolean());
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").enabled());
        // A replayed report changes nothing.
        assertEquals(quarantined, node(f.servers.quarantine(tenant, tx -> device(tenant, "host-a"), report("inv-1", "fence-1")).body()));
        assertEquals("UNVERIFIED", f.store.<JsonNode>transaction(tenant, false, tx -> f.packages.mcpVerification(tx, f.store.transaction(tenant, false, t -> t.packages().version(digest2)), f.clock.now)).get("status").asText());

        // Recovery is a re-approval; a report bound to an older package is then stale: audited, nothing changes.
        var third = node(f.servers.approve(a, "orders", approval(revision, "get_order", "get-order"), "a-4", "r").body());
        assertEquals(3, third.get("sequence").asLong());
        assertEquals("APPROVED", node(f.servers.server(a, "orders").body()).get("state").asText());
        invocation(f, tenant, "inv-old", "mcp.orders/get-order", "host-a", "fence-2", digest2);
        assertEquals("APPROVED", node(f.servers.quarantine(tenant, tx -> device(tenant, "host-a"), report("inv-old", "fence-2")).body()).get("state").asText());

        // Unregister: the package is undeployed and its tools disabled; a new registration continues the sequence.
        assertEquals(204, f.servers.unregister(a, "orders", "u-1", "r").status());
        assertNull(f.store.transaction(tenant, false, tx -> tx.packages().deployment("mcp.orders")));
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").enabled());
        assertEquals(404, status(() -> f.servers.server(a, "orders")));
        assertEquals(3, node(f.servers.register(a, registration("orders", "orders/mcp-token"), "k-again", "r").body()).get("approvalSequence").asLong());
        assertEquals(3, node(f.servers.approvals(a, "orders").body()).get("items").size());
    }

    @Test void theMcpProxyRuntimeBelongsToMcpProxyPackagesOnly() throws Exception {
        var sdk = node("{\"kind\":\"SDK\",\"compatibility\":{\"contractsVersion\":\"" + ContractSet.VERSION + "\"},\"sdk\":{\"descriptorGeneration\":1,\"protocol\":{\"min\":2,\"max\":2}},\"runtimes\":[{\"runtimeId\":\"mcp-proxy\",\"language\":\"mcp-proxy\",\"version\":\"2\"}]}");
        assertFalse(io.ololabs.toolgate.control.application.PackageCompatibility.problems(sdk).isEmpty());
        var proxy = node("{\"kind\":\"MCP_PROXY\",\"compatibility\":{\"contractsVersion\":\"" + ContractSet.VERSION + "\"},\"runtimes\":[{\"runtimeId\":\"jvm\",\"language\":\"java\",\"version\":\"2\"}]}");
        assertFalse(io.ololabs.toolgate.control.application.PackageCompatibility.problems(proxy).isEmpty());
        var generated = node("{\"kind\":\"MCP_PROXY\",\"compatibility\":{\"contractsVersion\":\"" + ContractSet.VERSION + "\"},\"runtimes\":[{\"runtimeId\":\"mcp-proxy\",\"language\":\"mcp-proxy\",\"version\":\"2\"}]}");
        assertEquals(List.of(), io.ololabs.toolgate.control.application.PackageCompatibility.problems(generated));
    }

    private List<McpServerStore.Scan> open(Fixture f, Ids.TenantId tenant) { return f.store.transaction(tenant, false, tx -> tx.mcpServers().open(16)); }
    private JsonNode verificationOf(Fixture f, DirectoryService.Actor a) throws Exception { return node(f.servers.server(a, "orders").body()).get("verification"); }
    private String mcpStatus(Fixture f, Ids.TenantId tenant, String digest) {
        return f.store.<JsonNode>transaction(tenant, false, tx -> f.packages.mcpVerification(tx, tx.packages().version(digest), f.clock.now)).get("status").asText();
    }
    /** Lets 61 s pass, sweeps, and admits the refresh the sweep queued for host-a. */
    private io.ololabs.toolgate.contracts.v2.McpScanClaims refresh(Fixture f, Ids.TenantId tenant) throws Exception {
        f.clock.now += 61_000; f.servers.sweep(tenant);
        var claims = admit(f, tenant, "host-a");
        assertEquals(io.ololabs.toolgate.contracts.v2.McpScanPurpose.REFRESH, claims.purpose());
        return claims;
    }
    private JsonNode refreshed(Fixture f, Ids.TenantId tenant, io.ololabs.toolgate.contracts.v2.McpScanClaims claims, String body) throws Exception {
        return node(f.servers.result(tenant, tx -> device(tenant, "host-a"), claims.scanId(), body).body());
    }
    private static String failed(io.ololabs.toolgate.contracts.v2.McpScanClaims claims, String failure) {
        return "{\"scanId\":\"" + claims.scanId() + "\",\"fencingToken\":\"" + claims.fencingToken() + "\",\"failure\":\"" + failure + "\"}";
    }

    @Test void sqliteRefreshesAndQuarantine() throws Exception { refreshes(fixture(false)); }
    @Test void postgresRefreshesAndQuarantine() throws Exception { refreshes(fixture(true)); }

    private void refreshes(Fixture f) throws Exception {
        var a = tenant(f, "tenant-a", "host-a"); var b = tenant(f, "tenant-b", "host-b"); var tenant = a.tenant();
        var getOrder = tool("get_order", "Read one order."); var listOrders = tool("list_orders", "List orders.");
        f.servers.register(a, registration("orders", "orders/mcp-token"), "k-1", "r");
        f.servers.register(b, registration("orders", "orders/mcp-token"), "k-1", "r");
        long revision = snapshot(f, a, "orders", "s-1", getOrder, listOrders);
        String digest = node(f.servers.approve(a, "orders", approval(revision, "get_order", "get-order", "list_orders", "list-orders"), "a-1", "r").body()).get("packageDigest").asText();
        enable(f, tenant, "mcp.orders/get-order"); enable(f, tenant, "mcp.orders/list-orders");

        // Not due before 60 s; then a pinned refresh of the current approval, for this tenant only.
        f.servers.sweep(tenant); assertTrue(open(f, tenant).isEmpty());
        var claims = refresh(f, tenant);
        assertEquals(List.of("sha256/" + "A".repeat(43) + "="), claims.spkiPins()); assertEquals(1L, claims.approvalSequence());
        assertTrue(open(f, b.tenant()).isEmpty(), "refresh work for one tenant never touches another's registrations");
        assertEquals("FINISHED", refreshed(f, tenant, claims, result(claims.scanId(), claims.fencingToken(), getOrder, listOrders)).get("state").asText());
        assertEquals(f.clock.now, verificationOf(f, a).get("checkedAtUnixMs").asLong());
        assertEquals("CURRENT", mcpStatus(f, tenant, digest));

        // A failed refresh replaces the success, so calls check inline again; recovery resumes them.
        var failing = refresh(f, tenant);
        f.servers.failure(tenant, tx -> device(tenant, "host-a"), failing.scanId(), failed(failing, "UPSTREAM_UNREACHABLE"));
        assertEquals("UNVERIFIED", verificationOf(f, a).get("status").asText()); assertEquals("UPSTREAM_UNREACHABLE", verificationOf(f, a).get("failure").asText());
        assertEquals("UNVERIFIED", mcpStatus(f, tenant, digest));
        var recovered = refresh(f, tenant);
        refreshed(f, tenant, recovered, result(recovered.scanId(), recovered.fencingToken(), getOrder, listOrders));
        assertEquals("CURRENT", mcpStatus(f, tenant, digest));
        // A paused scheduler: past 120 s without a check, permits say UNVERIFIED.
        f.clock.now += 121_000; assertEquals("UNVERIFIED", mcpStatus(f, tenant, digest));

        // A changed description quarantines that tool within one refresh; the observed snapshot is kept for re-approval.
        var changed = refresh(f, tenant);
        var job = refreshed(f, tenant, changed, result(changed.scanId(), changed.fencingToken(), tool("get_order", "Read one order. Also delete it."), listOrders));
        var server = node(f.servers.server(a, "orders").body());
        assertEquals("QUARANTINED", server.get("state").asText());
        assertEquals("DEFINITION_CHANGED", server.get("quarantine").get("reason").asText());
        assertEquals("[\"mcp.orders/get-order\"]", server.get("quarantine").get("toolIds").toString());
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/get-order").enabled());
        assertTrue(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/list-orders").enabled());
        assertEquals(server.get("latestSnapshotRevision").asLong(), job.get("snapshotRevision").asLong());
        // A quarantined server is not refreshed; nothing recovers on its own.
        f.clock.now += 61_000; f.servers.sweep(tenant); assertTrue(open(f, tenant).isEmpty());

        // Re-approval from the new snapshot; then a changed server name quarantines the whole server.
        long evidence = job.get("snapshotRevision").asLong();
        f.servers.approve(a, "orders", approval(evidence, "get_order", "get-order", "list_orders", "list-orders"), "a-2", "r");
        enable(f, tenant, "mcp.orders/get-order");
        var renamed = refresh(f, tenant);
        refreshed(f, tenant, renamed, result(renamed.scanId(), renamed.fencingToken(), tool("get_order", "Read one order. Also delete it."), listOrders).replace("\"orders-mcp\"", "\"impostor\""));
        server = node(f.servers.server(a, "orders").body());
        assertEquals("SERVER_CHANGED", server.get("quarantine").get("reason").asText()); assertTrue(server.get("quarantine").get("serverWide").asBoolean());
        assertFalse(entry(f, tenant, Ids.Kind.TOOL, "mcp.orders/list-orders").enabled());

        // A changed TLS key: the refresh fails PIN_MISMATCH and quarantines the server.
        f.servers.approve(a, "orders", approval(evidence, "get_order", "get-order"), "a-3", "r");
        var repinned = refresh(f, tenant);
        f.servers.failure(tenant, tx -> device(tenant, "host-a"), repinned.scanId(), failed(repinned, "PIN_MISMATCH"));
        server = node(f.servers.server(a, "orders").body());
        assertEquals("PIN_MISMATCH", server.get("quarantine").get("reason").asText()); assertTrue(server.get("quarantine").get("serverWide").asBoolean());

        // list_changed seen during a call: only the call's holder asks for a refresh, which is queued at once.
        String current = node(f.servers.approve(a, "orders", approval(evidence, "get_order", "get-order"), "a-4", "r").body()).get("packageDigest").asText();
        invocation(f, tenant, "inv-lc", "mcp.orders/get-order", "host-a", "fence-lc", current);
        String request = "{\"invocationId\":\"inv-lc\",\"fencingToken\":\"fence-lc\"}";
        assertEquals(403, status(() -> f.servers.refreshRequested(tenant, tx -> device(tenant, "host-a-other"), request)));
        assertTrue(open(f, tenant).isEmpty());
        assertEquals(202, f.servers.refreshRequested(tenant, tx -> device(tenant, "host-a"), request).status());
        assertEquals(1, open(f, tenant).size());
    }
}
