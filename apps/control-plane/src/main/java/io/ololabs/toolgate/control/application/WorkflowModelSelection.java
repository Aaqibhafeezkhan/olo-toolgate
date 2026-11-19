// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.WorkflowModelDeployment;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;

/** Control-owned model selection. Configuration and current authority must both permit the deployment. */
public final class WorkflowModelSelection {
    private final WorkflowProfiles profiles;
    private final WorkflowServiceAccess access;
    private final Clock clock;

    public WorkflowModelSelection(WorkflowProfiles profiles, Codec codec, Clock clock) {
        this.profiles = profiles;
        this.access = new WorkflowServiceAccess(codec);
        this.clock = clock;
    }

    public record Candidate(WorkflowModelDeployment deployment, int candidateIndex, int candidateCount,
                            java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> resultSchema) {}

    public Candidate resolve(Store.Session tx, Ids.TenantId tenant, String runId, String stepToken, int candidateIndex) {
        var runtime = context(tx, tenant, runId, stepToken);
        var config = runtime.config();
        var workflowModels = config.modelGroups().stream().filter(group -> group.id().equals(runtime.workflow().modelGroupId()))
            .findFirst().orElseThrow(Failure::unavailable).deploymentIds();
        var stepModels = config.modelGroups().stream().filter(group -> group.id().equals(runtime.step().modelGroupId()))
            .findFirst().orElseThrow(Failure::unavailable).deploymentIds();
        var candidates = stepModels.stream().filter(workflowModels::contains).toList();
        if (candidateIndex < 0 || candidateIndex >= candidates.size()) throw ManagementAccess.denied();
        var model = candidates.get(candidateIndex);
        var deployment = config.modelDeployments().stream().filter(item -> item.id().equals(model))
            .findFirst().orElseThrow(Failure::unavailable);
        var resultSchema = "AGENT".equals(runtime.step().kind())
            ? config.agentDefinitions().stream().filter(agent -> agent.agentId().equals(runtime.step().subjectId()))
                .findFirst().orElseThrow(Failure::unavailable).outputSchema()
            : runtime.workflow().resultSchema();
        return new Candidate(deployment, candidateIndex, candidates.size(), resultSchema);
    }

    record Runtime(WorkflowRunStore.QueuedRun run, WorkflowRunStore.Step step,
                   io.ololabs.toolgate.contracts.v2.WorkflowConfiguration config,
                   io.ololabs.toolgate.contracts.v2.WorkflowDefinition workflow) {}

    Runtime context(Store.Session tx, Ids.TenantId tenant, String runId, String stepToken) {
        long now = clock.millis();
        var runs = tx.workflowRuns();
        var step = WorkflowLeases.requireStep(runs, runId, stepToken, now);
        if (tx.kills().effective().stream().anyMatch(kill -> "TENANT".equals(kill.scope()))) throw ManagementAccess.denied();
        var run = runs.running(runId);
        if (run == null) throw ManagementAccess.denied();
        var config = profiles.admittedRevision(tx, tenant, run).workflows();
        var workflow = config.workflows().stream().filter(item -> item.id().equals(run.workflowId())
            && item.versionId().equals(run.workflowVersionId())).findFirst().orElseThrow(Failure::unavailable);
        var directory = tx.load();
        access.require(directory, run.serviceAgentId(), run.serviceAgentGroupId(), workflow, now);
        access.requireGrant(directory, run.serviceAgentGroupId(), "model.infer", "model-group:" + step.modelGroupId(), now);
        if ("AGENT".equals(step.kind())) {
            var binding = access.agent(directory, workflow, step.subjectId(), now);
            var definition = config.agentDefinitions().stream().filter(agent -> agent.agentId().equals(step.subjectId()))
                .findFirst().orElseThrow(Failure::unavailable);
            if (!definition.modelGroupId().equals(step.modelGroupId())) throw ManagementAccess.denied();
            access.requireGrant(directory, binding.agentGroupId(), "model.infer", "model-group:" + step.modelGroupId(), now);
        } else if (!"WORKFLOW".equals(step.kind()) || !workflow.id().equals(step.subjectId())
                || !workflow.modelGroupId().equals(step.modelGroupId())) throw ManagementAccess.denied();
        return new Runtime(run, step, config, workflow);
    }
}
