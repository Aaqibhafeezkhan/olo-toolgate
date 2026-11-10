// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.WorkflowConfiguration;
import io.ololabs.toolgate.contracts.v2.WorkflowDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;

/** Read-only workflow configuration validation. This never publishes, provisions or grants execution. */
public final class WorkflowConfigurations {
    private final Codec codec;
    public WorkflowConfigurations(Codec codec) { this.codec = java.util.Objects.requireNonNull(codec); }

    /** External references must be checked against the caller's tenant and readable directory, never globally. */
    public enum ResourceKind { AGENT, AGENT_GROUP, MODEL_DEPLOYMENT, TOOL, TRIGGER_SUBSCRIPTION, TRIGGER_ASSIGNMENT }
    public record Reference(ResourceKind kind, String id) {}
    private record DispatchSource(String subscriptionUid, String assignmentUid) {}
    /** The caller must provide a current tenant-scoped check; false means unavailable without an existence leak. */
    @FunctionalInterface public interface Dependencies { boolean available(Reference reference); }

    /** Validate structure and full local reference closure before consulting external dependencies. */
    public WorkflowConfiguration validate(String document, Dependencies dependencies) {
        java.util.Objects.requireNonNull(dependencies);
        var config = codec.model(document, WorkflowConfiguration.class);
        var groups = index(config.workflowGroups(), g -> g.id(), "workflow group");
        var models = index(config.modelGroups(), g -> g.id(), "model group");
        var deployments = index(config.modelDeployments(), d -> d.id(), "model deployment");
        var agents = index(config.agentDefinitions(), a -> a.agentId(), "agent");
        var workflows = index(config.workflows(), w -> w.id(), "workflow");
        index(config.dispatchBindings(), b -> b.id(), "dispatch binding");
        var external = new LinkedHashSet<Reference>();
        for (var deployment : config.modelDeployments()) {
            try {
                EndpointService.origin(deployment.origin());
                int port = java.net.URI.create(deployment.origin()).getPort();
                if (port == 0 || port > 65535) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw invalid("A model requires a fixed HTTPS origin"); }
            external.add(new Reference(ResourceKind.MODEL_DEPLOYMENT, deployment.id()));
        }
        for (var model : config.modelGroups()) {
            unique(model.deploymentIds(), "model deployment");
            model.deploymentIds().forEach(id -> require(deployments, id, "model deployment"));
            model.deploymentIds().forEach(id -> external.add(new Reference(ResourceKind.MODEL_DEPLOYMENT, id)));
        }
        for (var agent : config.agentDefinitions()) {
            require(models, agent.modelGroupId(), "agent model group");
            external.add(new Reference(ResourceKind.AGENT, agent.agentId()));
            agent.contextSources().forEach(c -> external.add(new Reference(ResourceKind.TOOL, c.toolId())));
        }
        for (var workflow : config.workflows()) {
            require(groups, workflow.workflowGroupId(), "workflow group");
            require(models, workflow.modelGroupId(), "workflow model group");
            index(workflow.agents(), a -> a.agentId(), "workflow agent");
            boolean entry = false;
            for (var binding : workflow.agents()) {
                require(agents, binding.agentId(), "agent definition");
                external.add(new Reference(ResourceKind.AGENT_GROUP, binding.agentGroupId()));
                entry |= binding.entryEligible();
            }
            if (!entry) throw invalid("A workflow requires an entry-eligible agent");
            workflow.contextSources().forEach(c -> external.add(new Reference(ResourceKind.TOOL, c.toolId())));
            if (workflow.limits().maxHandoffs() > workflow.limits().maxSteps())
                throw invalid("maxHandoffs cannot exceed maxSteps");
        }
        var dispatchSources = new HashSet<DispatchSource>();
        for (var binding : config.dispatchBindings()) {
            WorkflowDefinition workflow = require(workflows, binding.workflowId(), "dispatch workflow");
            if (!workflow.versionId().equals(binding.workflowVersionId())) throw invalid("Dispatch workflow version is not declared");
            // One default per subscription/source pair; names never substitute for immutable resource UIDs.
            if (!dispatchSources.add(new DispatchSource(binding.subscriptionUid(), binding.sourceAssignmentUid())))
                throw invalid("A subscription source has more than one default workflow");
            external.add(new Reference(ResourceKind.AGENT, binding.serviceAgentId()));
            external.add(new Reference(ResourceKind.AGENT_GROUP, binding.serviceAgentGroupId()));
            external.add(new Reference(ResourceKind.TRIGGER_SUBSCRIPTION, binding.subscriptionUid()));
            external.add(new Reference(ResourceKind.TRIGGER_ASSIGNMENT, binding.sourceAssignmentUid()));
        }
        for (var reference : external) if (!dependencies.available(reference))
            throw invalid("A required workflow dependency is unavailable");
        return config;
    }

    private static <T> Map<String,T> index(java.util.List<T> values, Function<T,String> key, String kind) {
        var result = new HashMap<String,T>();
        for (T value : values) if (result.putIfAbsent(key.apply(value), value) != null) throw invalid("Duplicate " + kind);
        return result;
    }
    private static void unique(java.util.List<String> values, String kind) {
        if (new HashSet<>(values).size() != values.size()) throw invalid("Duplicate " + kind);
    }
    private static <T> T require(Map<String,T> values, String id, String kind) {
        T value = values.get(id);
        if (value == null) throw invalid("An undeclared " + kind + " is referenced");
        return value;
    }
    private static Failure invalid(String message) { return new Failure(ErrorCode.VALIDATION, 400, message); }
}
