// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import io.ololabs.toolgate.control.application.Failure;
import io.ololabs.toolgate.control.application.WorkflowRunStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class JdbcWorkflowRunStore implements WorkflowRunStore {
    private static final String RUN = """
        SELECT run_id,profile_id,profile_digest,workflow_id,workflow_version_id,service_agent_id,service_agent_group_id,
            remediation_mode,created_at,key_version,document,compiler_digest,graph_digest,user_authority FROM control_workflow_runs
        WHERE tenant_id=?
        """;
    private final Connection connection;
    private final String tenant;

    JdbcWorkflowRunStore(Connection connection, String tenant) {
        this.connection = connection;
        this.tenant = tenant;
    }

    private PreparedStatement statement(String sql, String... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setString(1, tenant);
        for (int i = 0; i < values.length; i++) statement.setString(i + 2, values[i]);
        return statement;
    }

    @Override public void reportWorker(Worker worker, long now) {
        try (var expired = statement("DELETE FROM control_workflow_workers WHERE tenant_id=? AND expires_at<=?")) {
            expired.setLong(2, now); expired.executeUpdate();
            try (var count = statement("SELECT count(*) FROM control_workflow_workers WHERE tenant_id=?"); var rows = count.executeQuery()) {
                rows.next();
                if (rows.getInt(1) >= 256) {
                    try (var existing = statement("SELECT 1 FROM control_workflow_workers WHERE tenant_id=? AND instance_id=?", worker.instanceId()); var found = existing.executeQuery()) {
                        if (!found.next()) throw Failure.conflict();
                    }
                }
            }
            String inventory = "\n" + String.join("\n", worker.graphDigests()) + "\n";
            try (var update = statement("""
                    INSERT INTO control_workflow_workers(tenant_id,instance_id,owner_id,compiler_digest,graph_inventory,expires_at)
                    VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,instance_id) DO UPDATE SET
                    compiler_digest=excluded.compiler_digest,graph_inventory=excluded.graph_inventory,expires_at=excluded.expires_at
                    WHERE control_workflow_workers.owner_id=excluded.owner_id
                    """, worker.instanceId(), worker.ownerId(), worker.compilerDigest(), inventory)) {
                update.setLong(6, worker.expiresAt());
                if (update.executeUpdate() != 1) throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.FORBIDDEN, 403, "Worker identity is already owned");
            }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public int compatibleWorkers(String compilerDigest, String graphDigest, long now) {
        try (var query = statement("SELECT count(*) FROM control_workflow_workers WHERE tenant_id=? AND compiler_digest=? AND expires_at>? AND graph_inventory LIKE ?", compilerDigest)) {
            query.setLong(3, now); query.setString(4, "%\n" + graphDigest + "\n%");
            try (var rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public Admission admission(String subscriptionUid, String cursor) {
        try (var statement = statement("""
                SELECT owner_id,admission_id,disposition,run_id FROM control_workflow_admissions
                WHERE tenant_id=? AND subscription_uid=? AND cursor=?
                """, subscriptionUid, cursor); var rows = statement.executeQuery()) {
            return rows.next() ? new Admission(subscriptionUid, cursor, rows.getString(1), rows.getString(2),
                rows.getString(3), rows.getString(4)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void insertAdmission(Admission admission) {
        try (var statement = statement("""
                INSERT INTO control_workflow_admissions(tenant_id,subscription_uid,cursor,owner_id,admission_id,disposition,run_id)
                VALUES(?,?,?,?,?,?,?)
                """, admission.subscriptionUid(), admission.cursor(), admission.ownerId(), admission.admissionId(),
                admission.disposition(), admission.runId())) {
            statement.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void enqueue(QueuedRun run) {
        try (var statement = statement("""
                INSERT INTO control_workflow_runs(tenant_id,run_id,profile_id,profile_digest,workflow_id,workflow_version_id,
                    service_agent_id,service_agent_group_id,remediation_mode,state,document,created_at,key_version,compiler_digest,graph_digest,user_authority)
                VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?,?,?,?)
                """, run.id(), run.profileId(), run.profileDigest(), run.workflowId(), run.workflowVersionId(),
                run.serviceAgentId(), run.serviceAgentGroupId(), run.remediationMode(), run.document())) {
            statement.setLong(11, run.createdAt());
            statement.setLong(12, run.keyVersion());
            statement.setString(13, run.compilerDigest());
            statement.setString(14, run.graphDigest());
            statement.setString(15, run.userAuthority());
            statement.executeUpdate();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public QueuedRun queued(String id) {
        return queryRun(RUN + " AND state=? AND run_id=?", "QUEUED", id);
    }

    @Override public QueuedRun running(String id) {
        return queryRun(RUN + " AND state=? AND run_id=?", "RUNNING", id);
    }

    @Override public QueuedRun get(String id) { return queryRun(RUN + " AND run_id=?", id); }

    @Override public String state(String id) {
        try (var statement = statement("SELECT state FROM control_workflow_runs WHERE tenant_id=? AND run_id=?", id);
             var rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public QueuedRun nextQueued(String compilerDigest, java.util.List<String> graphDigests) {
        if (graphDigests.isEmpty()) return null;
        if (graphDigests.size() > 128) throw Failure.validation();
        var values = new java.util.ArrayList<String>(); values.add(compilerDigest); values.addAll(graphDigests);
        return queryRun(RUN + " AND state='QUEUED' AND compiler_digest=? AND graph_digest IN ("
            + String.join(",", java.util.Collections.nCopies(graphDigests.size(), "?")) + ") ORDER BY created_at,run_id LIMIT 1", values.toArray(String[]::new));
    }

    private QueuedRun queryRun(String sql, String... values) {
        try (var statement = statement(sql, values); var rows = statement.executeQuery()) {
            return rows.next() ? new QueuedRun(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getLong(9), rows.getLong(10), rows.getString(11), rows.getString(12), rows.getString(13), rows.getString(14)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void claim(Lease lease) {
        try (var statement = connection.prepareStatement("""
                UPDATE control_workflow_runs SET state='RUNNING',lease_id=?,lease_owner=?,lease_token_digest=?,lease_expires_at=?
                WHERE tenant_id=? AND run_id=? AND state='QUEUED'
                """)) {
            statement.setString(1, lease.id()); statement.setString(2, lease.ownerId()); statement.setString(3, lease.tokenDigest());
            statement.setLong(4, lease.expiresAt()); statement.setString(5, tenant); statement.setString(6, lease.runId());
            if (statement.executeUpdate() != 1) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public Lease activeLease(String runId) {
        try (var statement = statement("""
                SELECT lease_id,lease_owner,lease_token_digest,lease_expires_at FROM control_workflow_runs
                WHERE tenant_id=? AND run_id=? AND state='RUNNING'
                """, runId); var rows = statement.executeQuery()) {
            return rows.next() ? new Lease(runId, rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public Step activeStep(String runId) {
        try (var statement = statement("""
                SELECT step_kind,step_subject,step_model_group,step_token_digest FROM control_workflow_runs
                WHERE tenant_id=? AND run_id=? AND state='RUNNING' AND step_token_digest IS NOT NULL
                """, runId); var rows = statement.executeQuery()) {
            return rows.next() ? new Step(runId, rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)) : null;
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void authorizeStep(Lease lease, Step step, long now) {
        try (var statement = connection.prepareStatement("""
                UPDATE control_workflow_runs SET step_kind=?,step_subject=?,step_model_group=?,step_token_digest=?
                WHERE tenant_id=? AND run_id=? AND state='RUNNING' AND lease_id=? AND lease_owner=? AND lease_expires_at>?
                """)) {
            statement.setString(1, step.kind()); statement.setString(2, step.subjectId()); statement.setString(3, step.modelGroupId());
            statement.setString(4, step.tokenDigest()); statement.setString(5, tenant); statement.setString(6, lease.runId());
            statement.setString(7, lease.id()); statement.setString(8, lease.ownerId()); statement.setLong(9, now);
            if (statement.executeUpdate() != 1) throw Failure.conflict();
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void cancelQueued(String runId) {
        try (var statement = statement("""
                UPDATE control_workflow_runs SET state='CANCELLED' WHERE tenant_id=? AND run_id=? AND state='QUEUED'
                """, runId)) {
            if (statement.executeUpdate() != 1) throw Failure.conflict();
            terminal(runId,"CANCELLED",System.currentTimeMillis());
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void finish(Lease lease, String state, long now) {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "AWAITING_APPROVAL").contains(state)) throw Failure.validation();
        try (var statement = connection.prepareStatement("""
                UPDATE control_workflow_runs SET state=?,lease_id=NULL,lease_owner=NULL,lease_token_digest=NULL,lease_expires_at=NULL,
                    step_kind=NULL,step_subject=NULL,step_model_group=NULL,step_token_digest=NULL
                WHERE tenant_id=? AND run_id=? AND state='RUNNING' AND lease_id=? AND lease_owner=? AND lease_token_digest=? AND lease_expires_at>?
                """)) {
            statement.setString(1, state); statement.setString(2, tenant); statement.setString(3, lease.runId());
            statement.setString(4, lease.id()); statement.setString(5, lease.ownerId()); statement.setString(6, lease.tokenDigest());
            statement.setLong(7, now);
            if (statement.executeUpdate() != 1) throw Failure.conflict();
            terminal(lease.runId(),state,now);
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }

    @Override public void expire(long now) {
        var expired=new java.util.ArrayList<String>();
        try(var query=statement("SELECT run_id FROM control_workflow_runs WHERE tenant_id=? AND state='RUNNING' AND lease_expires_at<=? ORDER BY run_id LIMIT 256")){
            query.setLong(2,now);try(var rows=query.executeQuery()){while(rows.next())expired.add(rows.getString(1));}
        }catch(SQLException failure){throw Failure.unavailable();}
        for(String id:expired)expireOne(id,now);
    }
    private void expireOne(String id,long now) {
        try (var statement = statement("""
                UPDATE control_workflow_runs SET state='EXPIRED',lease_id=NULL,lease_owner=NULL,lease_token_digest=NULL,lease_expires_at=NULL,
                    step_kind=NULL,step_subject=NULL,step_model_group=NULL,step_token_digest=NULL
                WHERE tenant_id=? AND state='RUNNING' AND lease_expires_at<=? AND run_id=?
                """)) {
            statement.setLong(2, now);
            statement.setString(3,id);
            if(statement.executeUpdate()==1)terminal(id,"EXPIRED",now);
        } catch (SQLException failure) { throw Failure.unavailable(); }
    }
    private void terminal(String run,String state,long now) {
        String document=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("kind",state)
            .put("resultPath","/api/control/v1/workflow-runs/"+run).toString();
        new JdbcApplicationEventStore(connection,tenant).append(io.ololabs.toolgate.contracts.v2.ApplicationEventSource.WORKFLOW,run,
            "state:"+state,io.ololabs.toolgate.contracts.v2.ApplicationEventKind.RESULT,now,document);
    }
}
