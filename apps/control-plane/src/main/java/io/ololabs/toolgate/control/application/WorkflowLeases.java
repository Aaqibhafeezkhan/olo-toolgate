// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.WorkflowAgentDefinition;
import io.ololabs.toolgate.contracts.v2.WorkflowAgentBinding;
import io.ololabs.toolgate.contracts.v2.WorkflowContextSource;
import io.ololabs.toolgate.contracts.v2.WorkflowExecutionLimits;
import io.ololabs.toolgate.contracts.v2.WorkflowConfiguration;
import io.ololabs.toolgate.contracts.v2.WorkflowDefinition;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Single-owner execution leases for tenant workload principals. */
public final class WorkflowLeases {
    private final Store store;
    private final Codec codec;
    private final Clock clock;
    private final WorkflowProfiles profiles;
    private final WorkflowRunInputs inputs;
    private final WorkflowServiceAccess access;
    private final WorkflowRunResults results;
    private final SecureRandom random = new SecureRandom();

    public WorkflowLeases(Store store, Codec codec, Clock clock, WorkflowProfiles profiles, TenantVaultKeys keys) {
        this.store = store;
        this.codec = codec;
        this.clock = clock;
        this.profiles = profiles;
        this.inputs = new WorkflowRunInputs(codec, keys);
        this.access = new WorkflowServiceAccess(codec);
        this.results = new WorkflowRunResults(codec, keys);
    }

    private record AgentSnapshot(String agentId, String modelGroupId, String systemPrompt,
                                 List<WorkflowContextSource> contextSources, boolean entryEligible, boolean delegationAllowed) {}

    private record Snapshot(String runId, String leaseId, String workflowId, String workflowVersionId, String profileDigest,
                            String leaseToken, String message, String supervisorPrompt, String supervisorModelGroupId,
                            WorkflowExecutionLimits limits, List<AgentSnapshot> agents, List<WorkflowContextSource> contextSources,
                            String remediationMode, long expiresAtUnixMs,
                            List<io.ololabs.toolgate.contracts.v2.WorkflowAgentStage> agentStages,
                            io.ololabs.toolgate.contracts.v2.WorkflowGraphSelection graph) {
        @Override public String toString() { return "Snapshot[runId=" + runId + "]"; }
    }

    public Store.Reply claim(DirectoryService.Actor worker, String requestId) {
        Ids.valid(requestId);
        return store.transaction(worker.tenant(), true, tx -> {
            var runs = tx.workflowRuns();
            long now = clock.millis();
            runs.expire(now);
            var run = runs.nextQueued();
            if (run == null) return new Store.Reply(204, null, 0);
            WorkflowConfiguration config;
            WorkflowDefinition workflow;
            try {
                config = profiles.runnableRevision(tx, worker.tenant(), run.profileId(), run.profileDigest()).workflows();
                workflow = config.workflows().stream().filter(candidate -> candidate.id().equals(run.workflowId())
                    && candidate.versionId().equals(run.workflowVersionId())).findFirst().orElseThrow(Failure::unavailable);
                access.require(tx.load(), run.serviceAgentId(), run.serviceAgentGroupId(), workflow, now);
            } catch (Failure denied) {
                if (denied.status() != 403) throw denied;
                runs.cancelQueued(run.id());
                tx.audit(worker.id(), "WORKFLOW_RUN_CANCEL", "workflow-run:" + run.id(), 1, requestId, run.profileDigest().substring(7));
                return new Store.Reply(204, null, 0);
            }
            var definitions = config.agentDefinitions().stream().collect(Collectors.toMap(WorkflowAgentDefinition::agentId, Function.identity()));
            var agents = workflow.agents().stream().map(binding -> snapshot(binding, definitions.get(binding.agentId()))).toList();
            var message = inputs.open(tx, worker.tenant(), run);
            String token = token();
            long expires = clock.millis() + (workflow.limits().maxDurationSeconds() + 10) * 1000L;
            var lease = new WorkflowRunStore.Lease(run.id(), "lease-" + UUID.randomUUID(), worker.id(), DirectoryService.digest(token), expires);
            var snapshot = new Snapshot(run.id(), lease.id(), run.workflowId(), run.workflowVersionId(), run.profileDigest(),
                token, message, workflow.systemPrompt(), workflow.modelGroupId(), workflow.limits(), agents, workflow.contextSources(),
                run.remediationMode(), expires, workflow.agentStages() == null ? List.of() : workflow.agentStages(), workflow.graph());
            runs.claim(lease);
            tx.audit(worker.id(), "WORKFLOW_RUN_CLAIM", "workflow-run:" + run.id(), 1, requestId, run.profileDigest().substring(7));
            return new Store.Reply(200, codec.json(snapshot), 0);
        });
    }

    public Store.Reply finish(DirectoryService.Actor worker, String runId, String leaseId, String leaseToken, String state,
                              com.fasterxml.jackson.databind.JsonNode result, String code, String requestId) {
        Ids.valid(runId);
        Ids.valid(leaseId);
        Ids.valid(requestId);
        if (leaseToken == null || !leaseToken.matches("[A-Za-z0-9_-]{43}")) throw ManagementAccess.denied();
        if (result == null || code != null && !code.matches("[A-Z][A-Z0-9_]{0,63}")
                || "SUCCEEDED".equals(state) && code != null) throw Failure.validation();
        return store.transaction(worker.tenant(), true, tx -> {
            var runs = tx.workflowRuns();
            long now = clock.millis();
            var lease = requireLease(runs, worker, runId, leaseId, leaseToken, now);
            if ("SUCCEEDED".equals(state) && runs.activeStep(runId) == null) throw ManagementAccess.denied();
            var run = runs.running(runId);
            if (run == null) throw ManagementAccess.denied();
            if ("SUCCEEDED".equals(state)) {
                var config = profiles.runnableRevision(tx, worker.tenant(), run.profileId(), run.profileDigest()).workflows();
                var workflow = config.workflows().stream().filter(item -> item.id().equals(run.workflowId())
                    && item.versionId().equals(run.workflowVersionId())).findFirst().orElseThrow(Failure::unavailable);
                access.require(tx.load(), run.serviceAgentId(), run.serviceAgentGroupId(), workflow, now);
                if (tx.kills().effective().stream().anyMatch(kill -> "TENANT".equals(kill.scope()))) throw ManagementAccess.denied();
                if (TriggerConfigs.validate((com.fasterxml.jackson.databind.JsonNode) codec.value(codec.json(workflow.resultSchema())), result) != null)
                    throw Failure.validation();
            }
            results.save(tx, worker.tenant(), run, state, result, code);
            runs.finish(lease, state, now);
            tx.audit(worker.id(), "WORKFLOW_RUN_FINISH", "workflow-run:" + runId, 2, requestId, DirectoryService.digest(state));
            return new Store.Reply(200, codec.json(Map.of("runId", runId, "leaseId", leaseId, "state", state)), 0);
        });
    }

    public Store.Reply result(DirectoryService.Actor actor, String runId) {
        Ids.valid(runId);
        return store.transaction(actor.tenant(), false, tx -> {
            actor.requireAdmin();
            if (!new ManagementAccess(codec).superAdministrator(tx.load(), actor.userId())) throw ManagementAccess.denied();
            var run = tx.workflowRuns().get(runId);
            if (run == null) throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.NOT_FOUND, 404, "Workflow run unavailable");
            String state = tx.workflowRuns().state(runId);
            var response = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            response.put("runId", run.id()).put("workflowId", run.workflowId()).put("workflowVersionId", run.workflowVersionId())
                .put("profileDigest", run.profileDigest()).put("state", state);
            response.put("message", inputs.open(tx, actor.tenant(), run));
            response.set("outcome", results.open(tx, actor.tenant(), run, state));
            return new Store.Reply(200, codec.json(response), 0);
        });
    }

    /** Issue a step token for the service agent itself or its workflow supervisor. */
    public Store.Reply authorizeStep(DirectoryService.Actor worker, String runId, String leaseId, String leaseToken,
                                    String kind, String subjectId, String modelGroupId) {
        return store.transaction(worker.tenant(), true, tx -> {
            var runs = tx.workflowRuns();
            long now = clock.millis();
            var lease = requireLease(runs, worker, runId, leaseId, leaseToken, now);
            var run = runs.running(runId);
            if (run == null) throw ManagementAccess.denied();
            var config = profiles.runnableRevision(tx, worker.tenant(), run.profileId(), run.profileDigest()).workflows();
            var workflow = config.workflows().stream().filter(candidate -> candidate.id().equals(run.workflowId())
                && candidate.versionId().equals(run.workflowVersionId())).findFirst().orElseThrow(Failure::unavailable);
            if (tx.kills().effective().stream().anyMatch(kill -> "TENANT".equals(kill.scope()))) throw ManagementAccess.denied();
            access.require(tx.load(), run.serviceAgentId(), run.serviceAgentGroupId(), workflow, now);

            String allowedModelGroup = switch (kind) {
                case "WORKFLOW" -> {
                    if (!workflow.id().equals(subjectId)) throw ManagementAccess.denied();
                    yield workflow.modelGroupId();
                }
                case "AGENT" -> {
                    var binding = access.agent(tx.load(), workflow, subjectId, now);
                    var definition = config.agentDefinitions().stream().filter(agent -> agent.agentId().equals(subjectId)).findFirst()
                        .orElseThrow(Failure::unavailable);
                    access.requireGrant(tx.load(), binding.agentGroupId(), "model.infer", "model-group:" + definition.modelGroupId(), now);
                    yield definition.modelGroupId();
                }
                default -> throw Failure.validation();
            };
            if (!allowedModelGroup.equals(modelGroupId)) throw ManagementAccess.denied();
            access.requireGrant(tx.load(), run.serviceAgentGroupId(), "model.infer", "model-group:" + allowedModelGroup, now);
            String stepToken = token();
            runs.authorizeStep(lease, new WorkflowRunStore.Step(runId, kind, subjectId, modelGroupId,
                DirectoryService.digest(stepToken)), now);
            return new Store.Reply(200, codec.json(Map.of("runId", runId, "leaseId", leaseId, "subjectKind", kind,
                "subjectId", subjectId, "modelGroupId", modelGroupId, "token", stepToken)), 0);
        });
    }

    static WorkflowRunStore.Lease requireLease(WorkflowRunStore runs, DirectoryService.Actor worker, String runId,
                                               String leaseId, String leaseToken, long now) {
        var lease = runs.activeLease(runId);
        if (lease == null || lease.expiresAt() <= now || !lease.id().equals(leaseId) || !lease.ownerId().equals(worker.id())
                || leaseToken == null || !leaseToken.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(lease.tokenDigest().getBytes(StandardCharsets.US_ASCII),
                    DirectoryService.digest(leaseToken).getBytes(StandardCharsets.US_ASCII))) throw ManagementAccess.denied();
        return lease;
    }

    static WorkflowRunStore.Step requireStep(WorkflowRunStore runs, String runId, String stepToken, long now) {
        var lease = runs.activeLease(runId);
        var step = runs.activeStep(runId);
        if (lease == null || lease.expiresAt() <= now || step == null || stepToken == null || !stepToken.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(step.tokenDigest().getBytes(StandardCharsets.US_ASCII),
                    DirectoryService.digest(stepToken).getBytes(StandardCharsets.US_ASCII))) throw ManagementAccess.denied();
        return step;
    }

    private static AgentSnapshot snapshot(WorkflowAgentBinding binding, WorkflowAgentDefinition definition) {
        if (definition == null) throw Failure.unavailable();
        return new AgentSnapshot(definition.agentId(), definition.modelGroupId(), definition.systemPrompt(), definition.contextSources(),
            binding.entryEligible(), binding.delegationAllowed());
    }

    private String token() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
