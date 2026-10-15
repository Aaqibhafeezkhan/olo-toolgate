// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The local business-key reservation store of standalone mode (execution.md §4, platform-requirements.md §11.7), in SQLite. One row per
 * {@code (toolId, namespace, namespaceIdentity, keyHash)}: the first caller wins and every repeat is answered from that
 * row. A row is kept with its result for the retention period, then tombstoned, and a tombstone answers
 * {@code EXPIRED_KEY} from then on. One runtime process owns a state file at a time.
 */
final class Reservations implements AutoCloseable {
    enum Kind { NEW, EXISTING, CONFLICT, IN_USE, EXPIRED }

    /** States a row moves through; a row in {@link #REBINDABLE} may be taken by a new invocation of the same caller. */
    static final String RUNNING = "RUNNING";
    static final String AWAITING_INPUT = "AWAITING_INPUT";
    static final String NOT_STARTED = "NOT_STARTED";

    /** The answer to a reservation; {@code state}, {@code taskId} and {@code result} describe the existing invocation. */
    record Outcome(Kind kind, String invocationId, String state, String taskId, ObjectNode result) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection db;
    private final LongSupplier clock;
    private final long retentionMs;
    private final byte[] tenantKey;

    private Reservations(Connection db, LongSupplier clock, long retentionMs, byte[] tenantKey) {
        this.db = db;
        this.clock = clock;
        this.retentionMs = retentionMs;
        this.tenantKey = tenantKey;
    }

    static Reservations open(Path file, long retentionMs, LongSupplier clock) throws SQLException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw new SQLException("cannot create " + file.getParent() + ": " + e.getMessage(), e);
        }
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS toolgate_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS toolgate_business_keys ("
                    + "tool_id TEXT NOT NULL, namespace TEXT NOT NULL, namespace_identity TEXT NOT NULL, key_hash TEXT NOT NULL,"
                    + " fingerprint TEXT NOT NULL, caller TEXT NOT NULL, invocation_id TEXT NOT NULL, task_id TEXT, state TEXT NOT NULL,"
                    + " result TEXT, created_at INTEGER NOT NULL, retain_until INTEGER NOT NULL, tombstoned_at INTEGER,"
                    + " PRIMARY KEY (tool_id, namespace, namespace_identity, key_hash))");
            // Work that was running when an earlier process stopped can no longer report; its effect is unknown.
            s.execute("UPDATE toolgate_business_keys SET state = 'OUTCOME_UNKNOWN' WHERE state = 'RUNNING'");
        } catch (SQLException e) {
            db.close();
            throw e;
        }
        return new Reservations(db, clock, retentionMs, tenantKey(db));
    }

    /** The per-store key that hashes namespace identities before they go upstream, created once. */
    private static byte[] tenantKey(Connection db) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT value FROM toolgate_meta WHERE name = 'tenant_key'"); ResultSet r = q.executeQuery()) {
            if (r.next()) return Base64.getDecoder().decode(r.getString(1));
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (PreparedStatement i = db.prepareStatement("INSERT OR IGNORE INTO toolgate_meta (name, value) VALUES ('tenant_key', ?)")) {
            i.setString(1, Base64.getEncoder().encodeToString(key));
            i.executeUpdate();
        }
        return tenantKey(db);
    }

    /**
     * Reserves {@code key} for a new invocation or answers from the existing row, in the order of
     * invocation-state-v2.md §4.3: tombstone, other caller, other fingerprint, rebind, existing.
     */
    synchronized Outcome reserve(String toolId, String namespace, String namespaceIdentity, String key, String fingerprint, String caller,
            String invocationId) throws SQLException {
        long now = clock.getAsLong();
        String keyHash = sha256(key);
        db.setAutoCommit(false);
        try {
            try (PreparedStatement t = db.prepareStatement("UPDATE toolgate_business_keys SET result = NULL, tombstoned_at = ?"
                    + " WHERE retain_until <= ? AND tombstoned_at IS NULL")) {
                t.setLong(1, now);
                t.setLong(2, now);
                t.executeUpdate();
            }
            Outcome outcome;
            try (PreparedStatement q = db.prepareStatement("SELECT fingerprint, caller, invocation_id, task_id, state, result, tombstoned_at"
                    + " FROM toolgate_business_keys WHERE tool_id = ? AND namespace = ? AND namespace_identity = ? AND key_hash = ?")) {
                q.setString(1, toolId);
                q.setString(2, namespace);
                q.setString(3, namespaceIdentity);
                q.setString(4, keyHash);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) {
                        insert(toolId, namespace, namespaceIdentity, keyHash, fingerprint, caller, invocationId, now);
                        outcome = new Outcome(Kind.NEW, invocationId, RUNNING, null, null);
                    } else if (r.getObject("tombstoned_at") != null) {
                        outcome = new Outcome(Kind.EXPIRED, null, null, null, null);
                    } else if (!r.getString("caller").equals(caller)) {
                        outcome = new Outcome(Kind.IN_USE, null, null, null, null);
                    } else if (!r.getString("fingerprint").equals(fingerprint)) {
                        outcome = new Outcome(Kind.CONFLICT, null, null, null, null);
                    } else if (r.getString("state").equals(NOT_STARTED) || r.getString("state").equals(AWAITING_INPUT)) {
                        // Nothing ran to completion (or the round asked for input): the key goes to the new invocation.
                        rebind(toolId, namespace, namespaceIdentity, keyHash, invocationId);
                        outcome = new Outcome(Kind.NEW, invocationId, RUNNING, null, null);
                    } else {
                        String result = r.getString("result");
                        outcome = new Outcome(Kind.EXISTING, r.getString("invocation_id"), r.getString("state"), r.getString("task_id"),
                                result == null ? null : (ObjectNode) JSON.readTree(result));
                    }
                }
            }
            db.commit();
            return outcome;
        } catch (SQLException | IOException | RuntimeException e) {
            db.rollback();
            throw e instanceof SQLException s ? s : new SQLException(e);
        } finally {
            db.setAutoCommit(true);
        }
    }

    private void insert(String toolId, String namespace, String namespaceIdentity, String keyHash, String fingerprint, String caller,
            String invocationId, long now) throws SQLException {
        try (PreparedStatement i = db.prepareStatement("INSERT INTO toolgate_business_keys (tool_id, namespace, namespace_identity, key_hash,"
                + " fingerprint, caller, invocation_id, state, created_at, retain_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            i.setString(1, toolId);
            i.setString(2, namespace);
            i.setString(3, namespaceIdentity);
            i.setString(4, keyHash);
            i.setString(5, fingerprint);
            i.setString(6, caller);
            i.setString(7, invocationId);
            i.setString(8, RUNNING);
            i.setLong(9, now);
            i.setLong(10, now + retentionMs);
            i.executeUpdate();
        }
    }

    private void rebind(String toolId, String namespace, String namespaceIdentity, String keyHash, String invocationId) throws SQLException {
        try (PreparedStatement u = db.prepareStatement("UPDATE toolgate_business_keys SET invocation_id = ?, task_id = NULL, state = ?, result = NULL"
                + " WHERE tool_id = ? AND namespace = ? AND namespace_identity = ? AND key_hash = ?")) {
            u.setString(1, invocationId);
            u.setString(2, RUNNING);
            u.setString(3, toolId);
            u.setString(4, namespace);
            u.setString(5, namespaceIdentity);
            u.setString(6, keyHash);
            u.executeUpdate();
        }
    }

    /** Records the task that runs the reserved invocation. */
    synchronized void attachTask(String invocationId, String taskId) throws SQLException {
        try (PreparedStatement u = db.prepareStatement("UPDATE toolgate_business_keys SET task_id = ? WHERE invocation_id = ?")) {
            u.setString(1, taskId);
            u.setString(2, invocationId);
            u.executeUpdate();
        }
    }

    /** Records how the reserved invocation ended, with its result when there is one. */
    synchronized void finish(String invocationId, String state, ObjectNode result) throws SQLException {
        try (PreparedStatement u = db.prepareStatement("UPDATE toolgate_business_keys SET state = ?, result = ?"
                + " WHERE invocation_id = ? AND tombstoned_at IS NULL")) {
            u.setString(1, state);
            u.setString(2, result == null ? null : result.toString());
            u.setString(3, invocationId);
            u.executeUpdate();
        }
    }

    /** A short keyed hash of a namespace identity, stable for this store, so the upstream cannot link identities elsewhere. */
    String identityHash(String identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tenantKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(identity.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            // closing on shutdown
        }
    }
}
