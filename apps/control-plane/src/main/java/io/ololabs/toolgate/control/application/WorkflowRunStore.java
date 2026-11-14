// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Durable trigger admissions and pinned queued runs in the enclosing tenant transaction. */
public interface WorkflowRunStore {
    record Admission(String subscriptionUid, String cursor, String ownerId, String admissionId, String disposition, String runId) {}

    record QueuedRun(String id, String profileId, String profileDigest, String workflowId, String workflowVersionId,
                     String serviceAgentId, String serviceAgentGroupId, String remediationMode, long createdAt,
                     long keyVersion, String document) {
        @Override public String toString() { return "QueuedRun[id=" + id + "]"; }
    }
    record Lease(String runId, String id, String ownerId, String tokenDigest, long expiresAt) {}
    record Step(String runId, String kind, String subjectId, String modelGroupId, String tokenDigest) {}

    Admission admission(String subscriptionUid, String cursor);
    void insertAdmission(Admission admission);
    void enqueue(QueuedRun run);
    QueuedRun queued(String id);
    QueuedRun running(String id);
    QueuedRun get(String id);
    String state(String id);
    QueuedRun nextQueued();
    void claim(Lease lease);
    Lease activeLease(String runId);
    Step activeStep(String runId);
    void authorizeStep(Lease lease, Step step, long now);
    void cancelQueued(String runId);
    void finish(Lease lease, String state, long now);
    void expire(long now);
}
