// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.control.adapter.ContractCodec;
import io.ololabs.toolgate.control.adapter.LocalAnchorSink;
import io.ololabs.toolgate.control.adapter.PostgresStore;
import io.ololabs.toolgate.control.adapter.SqliteState;
import io.ololabs.toolgate.control.application.*;
import io.ololabs.toolgate.control.application.AuditAnchors.Refusal;
import io.ololabs.toolgate.control.domain.Ids.TenantId;
import io.ololabs.toolgate.control.fixtures.Tenants;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 061 signed external anchors: key lifecycle, domain/tenant binding, sink write-once and chain mismatch. */
final class AuditAnchorTest {
    @TempDir Path temp;
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static KeyPair first, second, foreign;
    private final ContractCodec codec = new ContractCodec();

    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        first = generator.generateKeyPair(); second = generator.generateKeyPair(); foreign = generator.generateKeyPair();
    }

    private static AuditAnchors.Signer signer(String keyId, KeyPair pair) {
        return new AuditAnchors.Signer() {
            public String keyId() { return keyId; }
            public byte[] sign(byte[] message) {
                try { var s = Signature.getInstance("SHA256withRSA"); s.initSign(pair.getPrivate()); s.update(message); return s.sign(); }
                catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
            }
        };
    }
    private static String entry(String keyId, KeyPair pair, String state, long activated, Long retired) {
        var key = (RSAPublicKey) pair.getPublic(); var encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keyId\":\"" + keyId + "\",\"n\":\"" + encoder.encodeToString(unsigned(key.getModulus().toByteArray())) + "\",\"e\":\""
            + encoder.encodeToString(unsigned(key.getPublicExponent().toByteArray())) + "\",\"state\":\"" + state + "\",\"activatedAtUnixMs\":" + activated
            + ",\"retiredAtUnixMs\":" + retired + "}";
    }
    private static byte[] unsigned(byte[] value) { return value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value; }
    private static AuditAnchors.Keyring ring(String... entries) {
        return AuditAnchors.keyring("{\"formatVersion\":1,\"purpose\":\"AUDIT_ANCHOR\",\"keys\":[" + String.join(",", entries) + "]}");
    }
    private static Refusal refusal(Executable action) {
        return assertThrows(AuditAnchors.Refused.class, action::run).refusal();
    }
    @FunctionalInterface private interface Executable { void run(); }

    @Test void signaturesKeyLifecycleDomainAndTenantBinding() throws Exception {
        var head = new AuditChain.Head(7, "a".repeat(64));
        var ring = ring(entry("anchor-1", first, "ACTIVE", 1000, null));
        var anchor = AuditAnchors.sign(signer("anchor-1", first), TENANT, head, 5000);
        var claims = AuditAnchors.verify(anchor, ring, TENANT);
        assertEquals(head, claims.head()); assertEquals(5000, claims.issuedAtUnixMs());
        assertEquals(anchor, AuditAnchors.fromObject(AuditAnchors.object(anchor)));

        // Altered claims, wrong tenant and a foreign signature are all refused.
        var altered = new AuditAnchors.Anchor(anchor.document().replace("\"chainIndex\":7", "\"chainIndex\":8"), anchor.signature());
        assertEquals(Refusal.BAD_SIGNATURE, refusal(() -> AuditAnchors.verify(altered, ring, TENANT)));
        assertEquals(Refusal.WRONG_TENANT, refusal(() -> AuditAnchors.verify(anchor, ring, OTHER)));
        var other = AuditAnchors.sign(signer("anchor-1", first), OTHER, head, 5000);
        assertEquals(Refusal.WRONG_TENANT, refusal(() -> AuditAnchors.verify(other, ring, TENANT)));
        assertEquals(Refusal.UNKNOWN_KEY, refusal(() -> AuditAnchors.verify(AuditAnchors.sign(signer("anchor-x", foreign), TENANT, head, 5000), ring, TENANT)));
        var impostor = AuditAnchors.sign(signer("anchor-1", foreign), TENANT, head, 5000);
        assertEquals(Refusal.BAD_SIGNATURE, refusal(() -> AuditAnchors.verify(impostor, ring, TENANT)));

        // Wrong domain: the same key and document signed without, or under another, domain prefix.
        for (var prefix : List.of("", "toolgate/fleet-release/v1\n", "toolgate/audit-anchor/v2\n")) {
            var s = Signature.getInstance("SHA256withRSA"); s.initSign(first.getPrivate()); s.update((prefix + anchor.document()).getBytes(StandardCharsets.UTF_8));
            var crossDomain = new AuditAnchors.Anchor(anchor.document(), Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign()));
            assertEquals(Refusal.BAD_SIGNATURE, refusal(() -> AuditAnchors.verify(crossDomain, ring, TENANT)));
        }
        // Non-canonical or extended documents are malformed even if otherwise meaningful.
        assertEquals(Refusal.MALFORMED, refusal(() -> AuditAnchors.parse(anchor.document().replace(",", ", "))));
        assertEquals(Refusal.MALFORMED, refusal(() -> AuditAnchors.parse(anchor.document().replace("\"purpose\":\"AUDIT_ANCHOR\"", "\"purpose\":\"FLEET\""))));
        var extended = ("{\"document\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(anchor.document())
            + ",\"signature\":\"" + anchor.signature() + "\",\"x\":1}").getBytes(StandardCharsets.UTF_8);
        assertEquals(Refusal.MALFORMED, refusal(() -> AuditAnchors.fromObject(extended)));

        // Rotation: the retired key still verifies anchors issued before retirement, never after.
        var rotated = ring(entry("anchor-1", first, "RETIRED", 1000, 6000L), entry("anchor-2", second, "ACTIVE", 6000, null));
        assertEquals(head, AuditAnchors.verify(anchor, rotated, TENANT).head());
        var late = AuditAnchors.sign(signer("anchor-1", first), TENANT, head, 6000);
        assertEquals(Refusal.KEY_NOT_VALID_AT_ISSUE, refusal(() -> AuditAnchors.verify(late, rotated, TENANT)));
        var early = AuditAnchors.sign(signer("anchor-2", second), TENANT, head, 5999);
        assertEquals(Refusal.KEY_NOT_VALID_AT_ISSUE, refusal(() -> AuditAnchors.verify(early, rotated, TENANT)));
        assertEquals(head, AuditAnchors.verify(AuditAnchors.sign(signer("anchor-2", second), TENANT, head, 6000), rotated, TENANT).head());
        assertEquals(Refusal.UNKNOWN_KEY, refusal(() -> rotated.active("anchor-1")));
        // Revocation refuses every anchor of the key, including earlier ones.
        var revoked = ring(entry("anchor-1", first, "REVOKED", 1000, 6000L), entry("anchor-2", second, "ACTIVE", 6000, null));
        assertEquals(Refusal.REVOKED_KEY, refusal(() -> AuditAnchors.verify(anchor, revoked, TENANT)));

        // Keyring hygiene: one domain, one active key, distinct moduli, strong keys.
        assertEquals(Refusal.MALFORMED, refusal(() -> AuditAnchors.keyring("{\"formatVersion\":1,\"purpose\":\"FLEET\",\"keys\":[" + entry("a", first, "ACTIVE", 0, null) + "]}")));
        assertEquals(Refusal.MALFORMED, refusal(() -> ring(entry("a", first, "ACTIVE", 0, null), entry("b", second, "ACTIVE", 0, null))));
        assertEquals(Refusal.MALFORMED, refusal(() -> ring(entry("a", first, "ACTIVE", 0, null), entry("b", first, "RETIRED", 0, 5L))));
        assertEquals(Refusal.MALFORMED, refusal(() -> ring(entry("a", first, "RETIRED", 0, null))));
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var weak = generator.generateKeyPair();
        assertEquals(Refusal.MALFORMED, refusal(() -> ring(entry("a", weak, "ACTIVE", 0, null))));
    }

    @Test void localSinkIsWriteOnce() throws Exception {
        var sink = new LocalAnchorSink(Files.createDirectory(temp.resolve("sink")));
        var name = AuditAnchors.objectName(TENANT, new AuditChain.Head(3, "b".repeat(64)));
        var content = "{\"document\":\"x\",\"signature\":\"y\"}".getBytes(StandardCharsets.UTF_8);
        assertNull(sink.read(name));
        var receipt = sink.publish(name, content);
        assertEquals("local", receipt.sink()); assertEquals(name, receipt.location());
        assertEquals(receipt, sink.publish(name, content), "identical republish is idempotent");
        assertThrows(AuditAnchorSink.Conflict.class, () -> sink.publish(name, "{}".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(content, sink.read(name));
        assertFalse(Files.isWritable(temp.resolve("sink").resolve(name)), "published objects are read-only");
        for (var invalid : List.of("../escape.json", "tenants/" + TENANT + "/audit-anchors/../../x.json", "/abs.json", "tenants/x/other.json"))
            assertThrows(Failure.class, () -> sink.publish(invalid, content));
        try (var listing = Files.walk(temp.resolve("sink"))) {
            assertEquals(1, listing.filter(Files::isRegularFile).count(), "no staged files remain");
        }
    }

    @Test void sqliteAnchorsDetectRewrittenChains() throws Exception { matrix(false); }
    @Test void postgresAnchorsDetectRewrittenChains() throws Exception { matrix(true); }

    private record Fixture(DataSource runtime, DataSource owner, boolean postgres) {}
    private Fixture fixture(boolean postgres) throws Exception {
        if (!postgres) { var path = temp.resolve(UUID.randomUUID() + ".sqlite"); return new Fixture(Tenants.database(SqliteState.open(path)), SqliteState.open(path), false); }
        var base = Objects.requireNonNull(System.getenv("CONTROL_TEST_URL"), "Required real PostgreSQL test environment missing; run tools/control/check.py");
        var password = Objects.requireNonNull(System.getenv("CONTROL_TEST_PASSWORD"));
        var name = "anchor_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(base, "control_migrator", password); var s = c.createStatement()) { s.execute("CREATE DATABASE " + name); }
        var url = base.replace("/control?", "/" + name + "?");
        org.flywaydb.core.Flyway.configure().dataSource(url, "control_migrator", password).cleanDisabled(true).load().migrate();
        var app = new org.postgresql.ds.PGSimpleDataSource(); app.setURL(url); app.setUser("control_app"); app.setPassword(password);
        var owner = new org.postgresql.ds.PGSimpleDataSource(); owner.setURL(url); owner.setUser("postgres"); owner.setPassword(password);
        return new Fixture(Tenants.database(app), owner, true);
    }

    private void matrix(boolean postgres) throws Exception {
        var f = fixture(postgres); var store = new PostgresStore(f.runtime(), codec);
        var a = Tenants.id("tenant-a"); var b = Tenants.id("tenant-b");
        var sinkRoot = Files.createDirectory(temp.resolve("sink-" + UUID.randomUUID())); var sink = new LocalAnchorSink(sinkRoot);
        var ring = ring(entry("anchor-1", first, "ACTIVE", 0, null));
        var clock = java.time.Clock.systemUTC();
        var anchoring = new AuditAnchoring(store, signer("anchor-1", first), ring, sink, clock);
        assertEquals(AuditAnchoring.Outcome.NOTHING_TO_ANCHOR, anchoring.anchor(a).outcome());
        // The anchoring service refuses a signer that is not the keyring's active key.
        assertEquals(Refusal.UNKNOWN_KEY, refusal(() -> new AuditAnchoring(store, signer("anchor-x", foreign), ring, sink, clock)));

        append(store, a, 4); append(store, b, 2);
        var firstAnchor = anchoring.anchor(a);
        assertEquals(AuditAnchoring.Outcome.ANCHORED, firstAnchor.outcome()); assertEquals(4, firstAnchor.anchor().chainIndex());
        assertEquals(AuditAnchoring.Outcome.NOTHING_TO_ANCHOR, anchoring.anchor(a).outcome());
        assertEquals(AuditAnchoring.Outcome.ANCHORED, anchoring.anchor(b).outcome());

        // Anchor records are immutable to the runtime role.
        try (var c = f.runtime().getConnection()) {
            c.setAutoCommit(false);
            if (f.postgres()) exec(c, "SELECT set_config('app.tenant_id',?,true)", a.value());
            assertThrows(SQLException.class, () -> exec(c, "UPDATE control_audit_anchors SET key_id='x' WHERE tenant_id=?", a.value()));
            c.rollback();
            if (f.postgres()) exec(c, "SELECT set_config('app.tenant_id',?,true)", a.value());
            assertThrows(SQLException.class, () -> exec(c, "DELETE FROM control_audit_anchors WHERE tenant_id=?", a.value()));
            c.rollback();
        }
        // A sink publication whose database record was lost converges on the same object.
        append(store, a, 2);
        var head = store.transaction(a, false, Store.Session::auditHead);
        var orphan = AuditAnchors.sign(signer("anchor-1", first), a.value(), head, clock.millis());
        sink.publish(AuditAnchors.objectName(a.value(), head), AuditAnchors.object(orphan));
        var recovered = anchoring.anchor(a);
        assertEquals(AuditAnchoring.Outcome.ANCHORED, recovered.outcome()); assertEquals(orphan.document(), recovered.anchor().document());
        rotateAndVerify(store, a, sink, head);

        // Independent verification uses only the sink objects, the pinned keyring and the chain.
        var published = sinkAnchors(sinkRoot, a.value());
        assertEquals(2, published.size());
        for (var anchor : published) {
            var claims = AuditAnchors.verify(anchor, ring, a.value());
            assertTrue(AuditAnchors.verifyChain(claims, chain(store, a)).valid());
            assertEquals(Refusal.WRONG_TENANT, refusal(() -> AuditAnchors.verify(anchor, ring, b.value())));
        }

        // A6 rewrites row 2 and recomputes every later digest: the chain alone verifies, the anchors do not.
        rewriteFrom(f, a, 2);
        var rewritten = chain(store, a);
        assertTrue(AuditChain.verify(a.value(), AuditChain.Head.EMPTY, rewritten, null).valid());
        for (var anchor : published) {
            var result = AuditAnchors.verifyChain(AuditAnchors.verify(anchor, ring, a.value()), rewritten);
            assertEquals(AuditChain.Defect.HEAD_MISMATCH, result.defect(), () -> "unexpected " + result);
        }
        // Control refuses to anchor a chain that no longer extends its last anchor.
        append(store, a, 1);
        var refused = anchoring.anchor(a);
        assertEquals(AuditAnchoring.Outcome.CHAIN_BROKEN, refused.outcome());
        assertEquals(2, sinkAnchors(sinkRoot, a.value()).size(), "nothing new is signed or published");

        // Revoking the anchor key: Control re-verifies the whole chain from genesis and anchors with the new key.
        long revokedAt = clock.millis();
        var revokedRing = ring(entry("anchor-1", first, "REVOKED", 0, revokedAt), entry("anchor-2", second, "ACTIVE", revokedAt, null));
        var replacement = new AuditAnchoring(store, signer("anchor-2", second), revokedRing, sink, clock);
        assertEquals(Refusal.REVOKED_KEY, refusal(() -> AuditAnchors.verify(store.transaction(b, false, Store.Session::latestAuditAnchor).anchor(), revokedRing, b.value())));
        append(store, b, 1);
        var reanchored = replacement.anchor(b);
        assertEquals(AuditAnchoring.Outcome.ANCHORED, reanchored.outcome()); assertEquals("anchor-2", reanchored.anchor().keyId()); assertEquals(3, reanchored.anchor().chainIndex());
        assertEquals(3, reanchored.chain().verifiedHead().chainIndex(), "chain verified up to the new head");

        // A rewritten database anchor record no longer verifies against the pinned keyring.
        var latest = store.transaction(b, false, Store.Session::latestAuditAnchor);
        owner(f, "UPDATE control_audit_anchors SET head_digest=?,document=? WHERE tenant_id=? AND chain_index=?",
            "c".repeat(64), latest.document().replace(latest.headDigest(), "c".repeat(64)), b.value(), latest.chainIndex());
        append(store, b, 1);
        assertEquals(Refusal.BAD_SIGNATURE, refusal(() -> replacement.anchor(b)));

    }

    /** Rotation keeps the earlier anchor verifiable and signs later heads with the new key. */
    private void rotateAndVerify(PostgresStore store, TenantId tenant, AuditAnchorSink sink, AuditChain.Head anchored) {
        long now = System.currentTimeMillis();
        var rotated = ring(entry("anchor-1", first, "RETIRED", 0, now), entry("anchor-2", second, "ACTIVE", now, null));
        var latest = store.transaction(tenant, false, Store.Session::latestAuditAnchor);
        assertEquals(anchored, AuditAnchors.verify(latest.anchor(), rotated, tenant.value()).head());
        assertEquals(Refusal.UNKNOWN_KEY, refusal(() -> new AuditAnchoring(store, signer("anchor-1", first), rotated, sink, java.time.Clock.systemUTC())));
    }

    private static List<AuditAnchors.Anchor> sinkAnchors(Path root, String tenant) throws Exception {
        try (var listing = Files.list(root.resolve("tenants").resolve(tenant).resolve("audit-anchors"))) {
            var anchors = new ArrayList<AuditAnchors.Anchor>();
            for (var path : listing.sorted().toList()) anchors.add(AuditAnchors.fromObject(Files.readAllBytes(path)));
            return anchors;
        }
    }
    private static void append(PostgresStore store, TenantId tenant, int count) {
        for (int i = 0; i < count; i++) { int n = i;
            store.transaction(tenant, true, tx -> { tx.audit("a".repeat(64), "CREATE", "anchored-" + n, n, "request-" + n, "b".repeat(64)); return null; }); }
    }
    private static List<AuditChain.Entry> chain(PostgresStore store, TenantId tenant) { return store.transaction(tenant, false, tx -> tx.auditChain(0, 10000)); }
    private static void exec(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]); s.execute(); }
    }
    /** Simulated database-owner rewrite with append-only triggers bypassed. */
    private static void owner(Fixture f, String sql, Object... values) throws SQLException {
        try (var c = f.owner().getConnection(); var s = c.createStatement()) {
            if (f.postgres()) {
                c.setAutoCommit(false);
                s.execute("ALTER TABLE control_audit DISABLE TRIGGER USER"); s.execute("ALTER TABLE control_audit_anchors DISABLE TRIGGER USER");
                exec(c, sql, values);
                s.execute("ALTER TABLE control_audit ENABLE TRIGGER USER"); s.execute("ALTER TABLE control_audit_anchors ENABLE TRIGGER USER");
                c.commit();
            } else {
                for (var trigger : List.of("control_audit_update", "control_audit_delete", "control_audit_tenant_immutable", "control_audit_chain",
                        "control_audit_anchors_update", "control_audit_anchors_delete")) s.execute("DROP TRIGGER IF EXISTS " + trigger);
                exec(c, sql, values);
            }
        }
    }
    /** Consistent suffix rewrite: edit one row and recompute every later link, as an attacker without the anchor key would. */
    private void rewriteFrom(Fixture f, TenantId tenant, long from) throws SQLException {
        var store = new PostgresStore(f.runtime(), codec); var rows = chain(store, tenant); var previous = rows.get((int) from - 2).rowDigest();
        for (var row : rows) {
            if (row.chainIndex() < from) continue;
            String target = row.chainIndex() == from ? "rewritten" : row.target();
            String digest = AuditChain.digest(tenant.value(), row.chainIndex(), previous, row.actorId(), row.operation(), target, row.revision(), row.requestId(),
                row.requestDigest(), row.evidence(), row.occurredAtMillis());
            owner(f, "UPDATE control_audit SET target=?,previous_digest=?,row_digest=? WHERE tenant_id=? AND chain_index=?", target, previous, digest, tenant.value(), row.chainIndex());
            previous = digest;
        }
    }
}
