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
        return new Fixture(postgres, runtime, store, new TenantRegistry(platform, codec, keys), new VaultService(store, codec, keys),
            new ServerPackages(store, codec, null, clock), new McpServers(store, codec, signer, clock), clock, signer);
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
}
