// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.RunEventStore;
import java.sql.*;
import java.util.*;

/** Run-event rows inside the caller's tenant transaction. The upsert of the stream row takes its row lock (PostgreSQL
 *  READ COMMITTED) or the database write lock (SQLite IMMEDIATE), so concurrent batches receive disjoint cursors. */
final class JdbcRunEventStore implements RunEventStore {
    private final Connection connection; private final String tenant;
    JdbcRunEventStore(Connection connection, String tenant) { this.connection = connection; this.tenant = tenant; }
    private PreparedStatement query(String sql, Object... values) throws SQLException {
        var s = connection.prepareStatement(SqliteState.sql(connection, sql)); s.setString(1, tenant);
        for (int i = 0; i < values.length; i++) s.setObject(i + 2, values[i]); return s;
    }
    private static Stream stream(String id, ResultSet rows) throws SQLException {
        long purged = rows.getLong(7); boolean none = rows.wasNull();
        return new Stream(id, rows.getLong(1), rows.getInt(2), rows.getLong(3), rows.getLong(4), rows.getLong(5), rows.getLong(6), none ? null : purged);
    }
    private static final String COLUMNS = "last_cursor,event_count,byte_count,dropped_frames,refused_frames,retain_until,purged_at";

    public Stream stream(String invocationId) {
        try (var s = query("SELECT " + COLUMNS + " FROM control_invocation_event_streams WHERE tenant_id=? AND invocation_id=?", invocationId); var rows = s.executeQuery()) {
            return rows.next() ? stream(invocationId, rows) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public Stream append(String invocationId, List<Event> events, long bytes, long droppedFrames, long refusedFrames, long retainUntil) {
        int n = events.size();
        try {
            Stream after;
            try (var s = query("INSERT INTO control_invocation_event_streams(tenant_id,invocation_id," + COLUMNS.replace(",purged_at", "") + ") VALUES(?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(tenant_id,invocation_id) DO UPDATE SET last_cursor=control_invocation_event_streams.last_cursor+excluded.last_cursor,"
                + "event_count=control_invocation_event_streams.event_count+excluded.event_count,byte_count=control_invocation_event_streams.byte_count+excluded.byte_count,"
                + "dropped_frames=control_invocation_event_streams.dropped_frames+excluded.dropped_frames,refused_frames=control_invocation_event_streams.refused_frames+excluded.refused_frames,"
                + "retain_until=GREATEST(control_invocation_event_streams.retain_until,excluded.retain_until) RETURNING " + COLUMNS,
                invocationId, (long) n, n, bytes, droppedFrames, refusedFrames, retainUntil); var rows = s.executeQuery()) {
                if (!rows.next()) throw Failure.conflict();
                after = stream(invocationId, rows);
            }
            if (after.purgedAt() != null && n > 0) throw Failure.conflict();
            long cursor = after.lastCursor() - n;
            for (var e : events) {
                cursor++;
                try (var s = query("INSERT INTO control_invocation_events(tenant_id,invocation_id,event_cursor,source,kind,attempt,fencing_token,sequence,recorded_at,document) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    invocationId, cursor, e.source(), e.kind(), e.attempt(), e.fencingToken(), e.sequence(), e.recordedAt(), e.document())) {
                    if (s.executeUpdate() != 1) throw Failure.conflict();
                }
            }
            return after;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public List<Integer> sequences(String invocationId, String fencingToken) {
        try (var s = query("SELECT sequence FROM control_invocation_events WHERE tenant_id=? AND invocation_id=? AND fencing_token=? AND sequence IS NOT NULL ORDER BY sequence", invocationId, fencingToken);
             var rows = s.executeQuery()) {
            var values = new ArrayList<Integer>(); while (rows.next()) values.add(rows.getInt(1)); return List.copyOf(values);
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public Integer attempt(String invocationId, String fencingToken) {
        try (var s = query("SELECT max(attempt) FROM control_invocation_events WHERE tenant_id=? AND invocation_id=? AND fencing_token=?", invocationId, fencingToken); var rows = s.executeQuery()) {
            if (!rows.next()) return null; int attempt = rows.getInt(1); return rows.wasNull() ? null : attempt;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public List<Event> page(String invocationId, long after, int limit) {
        try (var s = query("SELECT event_cursor,source,kind,attempt,fencing_token,sequence,recorded_at,document FROM control_invocation_events WHERE tenant_id=? AND invocation_id=? AND event_cursor>? ORDER BY event_cursor LIMIT ?",
            invocationId, after, limit); var rows = s.executeQuery()) {
            var values = new ArrayList<Event>();
            while (rows.next()) {
                int attempt = rows.getInt(4); boolean noAttempt = rows.wasNull(); int sequence = rows.getInt(6); boolean noSequence = rows.wasNull();
                values.add(new Event(rows.getLong(1), rows.getString(2), rows.getString(3), noAttempt ? null : attempt, rows.getString(5), noSequence ? null : sequence, rows.getLong(7), rows.getString(8)));
            }
            return List.copyOf(values);
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    public int purge(long now, int limit) {
        try {
            var expired = new ArrayList<String>();
            try (var s = query("SELECT invocation_id FROM control_invocation_event_streams WHERE tenant_id=? AND purged_at IS NULL AND retain_until<? ORDER BY retain_until LIMIT ?", now, limit); var rows = s.executeQuery()) {
                while (rows.next()) expired.add(rows.getString(1));
            }
            for (var id : expired) {
                try (var s = query("DELETE FROM control_invocation_events WHERE tenant_id=? AND invocation_id=?", id)) { s.executeUpdate(); }
                try (var s = connection.prepareStatement("UPDATE control_invocation_event_streams SET purged_at=? WHERE tenant_id=? AND invocation_id=? AND purged_at IS NULL")) {
                    s.setLong(1, now); s.setString(2, tenant); s.setString(3, id); s.executeUpdate();
                }
            }
            return expired.size();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
}
