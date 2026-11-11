// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.WorkflowProfileStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Uses the enclosing Store transaction for tenant fencing, audit and commit/rollback. */
final class JdbcWorkflowProfileStore implements WorkflowProfileStore {
    private final Connection connection;
    private final String tenant;
    JdbcWorkflowProfileStore(Connection connection, String tenant) { this.connection = connection; this.tenant = tenant; }
    private PreparedStatement statement(String sql, String... values) throws SQLException {
        var statement = connection.prepareStatement(sql); statement.setString(1, tenant);
        for (int i = 0; i < values.length; i++) statement.setString(i + 2, values[i]);
        return statement;
    }
    public Revision revision(String profileId, String digest) {
        try (var statement = statement("SELECT document,key_version,state,reviewed_by FROM control_workflow_profile_revisions WHERE tenant_id=? AND profile_id=? AND digest=?", profileId, digest);
             var rows = statement.executeQuery()) {
            return rows.next() ? new Revision(profileId, digest, rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void insert(Revision revision) {
        try (var statement = statement("INSERT INTO control_workflow_profile_revisions(tenant_id,profile_id,digest,document,key_version,state,reviewed_by) VALUES(?,?,?,?,?,'DRAFT',NULL)",
                revision.profileId(), revision.digest(), revision.document())) {
            if (!revision.state().equals("DRAFT") || revision.reviewedBy() != null) throw Failure.validation();
            statement.setLong(5, revision.keyVersion());
            statement.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public java.util.List<Revision> page(String afterProfileId, String afterDigest, int limit) {
        if (limit < 1 || limit > 50) throw Failure.validation();
        try (var statement = statement("SELECT profile_id,digest,document,key_version,state,reviewed_by FROM control_workflow_profile_revisions WHERE tenant_id=? AND (profile_id>? OR (profile_id=? AND digest>?)) ORDER BY profile_id,digest LIMIT ?",
                afterProfileId, afterProfileId, afterDigest)) {
            statement.setInt(5, limit);
            try (var rows = statement.executeQuery()) {
                var result = new java.util.ArrayList<Revision>();
                while (rows.next()) result.add(new Revision(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4), rows.getString(5), rows.getString(6)));
                return java.util.List.copyOf(result);
            }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void review(String profileId, String digest, String reviewer) {
        try (var statement = connection.prepareStatement("UPDATE control_workflow_profile_revisions SET state='REVIEWED',reviewed_by=? WHERE tenant_id=? AND profile_id=? AND digest=? AND state='DRAFT'")) {
            statement.setString(1, reviewer); statement.setString(2, tenant); statement.setString(3, profileId); statement.setString(4, digest);
            if (statement.executeUpdate() != 1) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public Publication publication(String profileId) {
        try (var statement = statement("SELECT digest,generation FROM control_workflow_profile_heads WHERE tenant_id=? AND profile_id=?", profileId);
             var rows = statement.executeQuery()) {
            return rows.next() ? new Publication(profileId, rows.getString(1), rows.getLong(2)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void publish(Publication publication, long expectedGeneration) {
        if (publication.generation() != expectedGeneration + 1) throw Failure.conflict();
        String sql = expectedGeneration == 0
            ? "INSERT INTO control_workflow_profile_heads(digest,generation,tenant_id,profile_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING"
            : "UPDATE control_workflow_profile_heads SET digest=?,generation=? WHERE tenant_id=? AND profile_id=? AND generation=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, publication.digest()); statement.setLong(2, publication.generation());
            statement.setString(3, tenant); statement.setString(4, publication.profileId());
            if (expectedGeneration != 0) statement.setLong(5, expectedGeneration);
            if (statement.executeUpdate() != 1) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public Ownership owner(String kind, String id) {
        try (var statement = statement("SELECT profile_id,definition_digest FROM control_workflow_profile_owners WHERE tenant_id=? AND resource_kind=? AND resource_id=?", kind, id);
             var rows = statement.executeQuery()) {
            return rows.next() ? new Ownership(rows.getString(1), rows.getString(2)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    public void own(String kind, String id, String profileId, String definitionDigest) {
        try (var statement = statement("INSERT INTO control_workflow_profile_owners(tenant_id,resource_kind,resource_id,profile_id,definition_digest) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING", kind, id, profileId, definitionDigest)) {
            statement.executeUpdate();
            if (!new Ownership(profileId, definitionDigest).equals(owner(kind, id))) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
}
