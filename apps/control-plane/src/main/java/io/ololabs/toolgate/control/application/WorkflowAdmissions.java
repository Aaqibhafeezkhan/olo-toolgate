// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.WorkflowDispatchDisposition;
import io.ololabs.toolgate.contracts.v2.WorkflowDispatchReceipt;
import io.ololabs.toolgate.contracts.v2.WorkflowDispatchRequest;
import io.ololabs.toolgate.contracts.v2.TriggerClassification;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.UUID;

/** Trigger delivery, binding resolution and durable admission share one tenant transaction. */
public final class WorkflowAdmissions {
    private final Store store;
    private final Codec codec;
    private final Clock clock;
    private final TriggerStream stream;
    private final WorkflowProfiles profiles;
    private final WorkflowRunInputs inputs;
    private final WorkflowServiceAccess access;

    public WorkflowAdmissions(Store store, Codec codec, Clock clock, TriggerStream stream, WorkflowProfiles profiles, TenantVaultKeys keys) {
        this.store = store;
        this.codec = codec;
        this.clock = clock;
        this.stream = stream;
        this.profiles = profiles;
        this.inputs = new WorkflowRunInputs(codec, keys);
        this.access = new WorkflowServiceAccess(codec);
    }

    public Store.Reply admit(DirectoryService.Actor actor, String document, String requestId) {
        actor.requireAdmin();
        Ids.valid(requestId);
        var request = codec.model(document, WorkflowDispatchRequest.class);
        return store.transaction(actor.tenant(), true, tx -> {
            new ManagementAccess(codec).access(tx.load(), actor.userId());
            var runs = tx.workflowRuns();
            var prior = runs.admission(request.subscriptionUid(), request.cursor());
            if (prior != null) {
                if (!prior.ownerId().equals(actor.userId())) throw TriggerService.notFound();
                return receipt(prior, WorkflowDispatchDisposition.DUPLICATE, 200);
            }
            var delivery = stream.dispatchEvent(tx, actor, request.subscriptionUid(), request.cursor());
            var target = profiles.dispatchTarget(tx, actor.tenant(), request.subscriptionUid(), delivery.assignmentUid());
            long now = clock.millis();
            var directory = tx.load();
            access.require(directory, target, now);
            var label = TriggerClassification.valueOf(delivery.event().path("tgclassification").asText());
            if (!TriggerLabels.covers(TriggerLabels.clearance(directory, codec, null, target.binding().serviceAgentId()), label))
                throw ManagementAccess.denied();

            String admissionId = "admission-" + UUID.randomUUID();
            String message;
            try { message = WorkflowDispatchInput.message(codec, target, delivery); }
            catch (Failure invalid) {
                if (invalid.status() != 400) throw invalid;
                return record(tx, actor, requestId, request, admissionId, null, WorkflowDispatchDisposition.REJECTED);
            }
            String runId = "run-" + UUID.randomUUID();
            var sealed = inputs.seal(tx, actor.tenant(), runId, target.profileDigest(), message);
            var binding = target.binding();
            runs.enqueue(new WorkflowRunStore.QueuedRun(runId, target.profileId(), target.profileDigest(), binding.workflowId(),
                binding.workflowVersionId(), binding.serviceAgentId(), binding.serviceAgentGroupId(), binding.remediationMode().name(),
                now, sealed.keyVersion(), sealed.ciphertext()));
            return record(tx, actor, requestId, request, admissionId, runId, WorkflowDispatchDisposition.ADMITTED);
        });
    }

    private Store.Reply record(Store.Session tx, DirectoryService.Actor actor, String requestId, WorkflowDispatchRequest request,
                               String admissionId, String runId, WorkflowDispatchDisposition disposition) {
        var admission = new WorkflowRunStore.Admission(request.subscriptionUid(), request.cursor(), actor.userId(),
            admissionId, disposition.name(), runId);
        tx.workflowRuns().insertAdmission(admission);
        tx.audit(actor.id(), "WORKFLOW_DISPATCH_" + disposition.name(), "workflow-admission:" + admissionId, 1,
            requestId, DirectoryService.digest(request.subscriptionUid() + "\n" + request.cursor()));
        return receipt(admission, disposition, 201);
    }

    private Store.Reply receipt(WorkflowRunStore.Admission admission, WorkflowDispatchDisposition disposition, int status) {
        var receipt = new WorkflowDispatchReceipt(admission.subscriptionUid(), admission.cursor(), admission.admissionId(), disposition, admission.runId());
        return new Store.Reply(status, codec.json(receipt), 0);
    }
}
