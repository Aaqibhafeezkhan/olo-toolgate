// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.v2.DeploymentProfile;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;

/** Current, tenant-transactional reference checks. These do not grant model or workflow execution. */
public final class WorkflowProfileDependencies implements WorkflowProfiles.Readiness {
    private final Codec codec;
    private final Clock clock;
    private final TriggerSubscriptions subscriptions;
    private final TriggerService triggers;
    private final Ids.TenantId tenant;
    public WorkflowProfileDependencies(Codec codec, Clock clock, TriggerSubscriptions subscriptions, TriggerService triggers, Ids.TenantId tenant) {
        this.codec = codec; this.clock = clock; this.subscriptions = subscriptions; this.triggers = triggers;
        this.tenant = java.util.Objects.requireNonNull(tenant);
    }
    private boolean enabled(Store.Session tx, Ids.Kind kind, String id) {
        var entry = tx.load().entries().get(kind.id(id));
        return entry != null && entry.enabled();
    }
    public boolean ready(Store.Session tx, DeploymentProfile profile, String profileDigest) {
        return blockers(tx, profile, profileDigest).isEmpty();
    }
    @Override public java.util.List<String> blockers(Store.Session tx, DeploymentProfile profile, String profileDigest) {
        var issues = new java.util.LinkedHashSet<String>();
        if (profile.workflows() == null) return java.util.List.of("No bot execution configuration is defined.");
        var head = tx.workflowProfiles().publication(profile.profileId());
        long generation = head == null ? 0L : head.generation();
        for (var workflow : profile.workflows().workflows()) {
            String raw = tx.workflowProfiles().graphBuild(profile.profileId(), profileDigest, workflow.id());
            if (raw == null) {
                issues.add("Bot " + workflow.id() + ": graph is not prepared. Select Prepare reviewed graphs.");
                continue;
            }
            var build = codec.model(raw, io.ololabs.toolgate.contracts.v2.WorkflowGraphBuildResult.class);
            boolean active = head != null && head.digest().equals(profileDigest);
            if (!build.tenantId().equals(tenant.value()) || !build.profileId().equals(profile.profileId())
                    || !build.profileDigest().equals(profileDigest) || !build.workflowId().equals(workflow.id())
                    || !(build.generation() == generation || active && build.generation() == generation - 1))
                issues.add("Bot " + workflow.id() + ": graph preparation is stale. Prepare this exact revision again.");
            if (tx.workflowRuns().compatibleWorkers(build.compilerDigest(), workflow.graph().digest(), clock.millis()) < 1)
                issues.add("Bot " + workflow.id() + ": no compatible LangGraph worker is online. Check runtime connection and service credentials.");
        }
        if (profile.readiness() != null && !profile.readiness().isEmpty())
            issues.add("Declared execution readiness checks have not been qualified.");
        if (!profile.packages().isEmpty()) {
            var packages = tx.packages().provisioning(profile.profileId());
            JsonNode document = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            if (packages == null || !packages.profileDigest().equals(profileDigest))
                issues.add("Packages have not been provisioned for this revision. Select Provision reviewed packages.");
            else {
                document = (JsonNode)codec.value(packages.document());
                if (!"READY".equals(document.path("state").asText()))
                    issues.add("Package provisioning is " + ("FAILED".equals(document.path("state").asText()) ? "failed" : "pending") + ". Check the assigned device and package status.");
            }
            for (var requested : profile.packages()) {
                if (!requested.required()) continue;
                var statuses = java.util.stream.StreamSupport.stream(document.path("packages").spliterator(), false)
                    .filter(p -> requested.packageId().equals(p.path("packageId").asText())).toList();
                JsonNode prepared = statuses.size() == 1 ? statuses.getFirst() : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
                if (requested.deviceGroupIds() != null && !requested.deviceGroupIds().isEmpty()) {
                    if (statuses.size() != 1 || !"READY".equals(prepared.path("state").asText())
                            || !requested.version().equals(prepared.path("version").asText()))
                        issues.add("Package " + requested.packageId() + " " + requested.version() + ": device installation is not confirmed READY.");
                    String blocker = new WorkflowDeviceReadiness(codec, clock).blocker(tx, profile.profileId(), requested, prepared);
                    if (blocker != null) issues.add("Package " + requested.packageId() + ": " + blocker + ".");
                }
                for (var trigger : requested.triggers() == null ? java.util.List.<io.ololabs.toolgate.contracts.v2.ProfileTrigger>of() : requested.triggers()) {
                    var devices = java.util.stream.StreamSupport.stream(document.path("packages").spliterator(), false)
                        .filter(p -> requested.packageId().equals(p.path("packageId").asText()))
                        .flatMap(p -> java.util.stream.StreamSupport.stream(p.path("deviceIds").spliterator(), false)).map(JsonNode::asText).toList();
                    if (triggers == null || !triggers.enabled() || !triggers.prepared(tx, trigger.assignment(), trigger.triggerId(), requested.version(),
                            (JsonNode)codec.value(codec.json(trigger.config())), devices))
                        issues.add("Trigger " + trigger.assignment() + ": configured settings are not confirmed ready. Check its device status and resource access.");
                }
            }
        }
        try {
            new WorkflowConfigurations(codec).validate(codec.json(profile.workflows()), reference -> {
                boolean available = switch (reference.kind()) {
                    case AGENT -> enabled(tx, Ids.Kind.AGENT, reference.id());
                    case AGENT_GROUP -> enabled(tx, Ids.Kind.AGENT_GROUP, reference.id());
                    case TOOL -> enabled(tx, Ids.Kind.TOOL, reference.id());
                    case MODEL_DEPLOYMENT -> {
                        var deployment = profile.workflows().modelDeployments().stream().filter(d -> d.id().equals(reference.id())).findFirst();
                        if (deployment.isEmpty()) yield false;
                        var d = deployment.get(); var secret = tx.enterprise().secret(d.secretName());
                        if (secret == null) issues.add("Model " + d.id() + ": missing vault secret " + d.secretName() + ". Configure the provider credential in this installation.");
                        else if (!secret.toolGroupId().equals(d.toolGroupId()) || !secret.deviceGroupId().equals(d.deviceGroupId()))
                            issues.add("Model " + d.id() + ": vault secret " + d.secretName() + " has different tool/device group scope.");
                        if (!enabled(tx, Ids.Kind.TOOL_GROUP, d.toolGroupId())) issues.add("Model " + d.id() + ": tool group " + d.toolGroupId() + " is missing or disabled.");
                        if (!enabled(tx, Ids.Kind.DEVICE_GROUP, d.deviceGroupId())) issues.add("Model " + d.id() + ": device group " + d.deviceGroupId() + " is missing or disabled.");
                        yield true; // Individual model failures were collected without exposing secret values.
                    }
                    case TRIGGER_SUBSCRIPTION -> find(tx, TriggerStore.Kind.SUBSCRIPTION, "subscription", reference.id()) != null;
                    case TRIGGER_ASSIGNMENT -> find(tx, TriggerStore.Kind.ASSIGNMENT, "assignment", reference.id()) != null;
                };
                if (!available) issues.add(reference.kind().name().replace('_', ' ') + " " + reference.id() + " is missing, disabled or unavailable in this installation.");
                return true; // Continue checking the complete reference closure; issues still block publication.
            });
            for (var workflow : profile.workflows().workflows()) for (var agent : workflow.agents())
                if (!GroupGraph.memberships(tx.load(), codec, Ids.Kind.AGENT, agent.agentId(), false).contains(agent.agentGroupId()))
                    issues.add("Agent " + agent.agentId() + " is not a member of configured agent group " + agent.agentGroupId() + ".");
            for (var binding : profile.workflows().dispatchBindings()) {
                if (subscriptions == null || triggers == null || !triggers.enabled()) {
                    issues.add("Trigger mapping " + binding.id() + ": trigger service is unavailable."); continue;
                }
                var row = find(tx, TriggerStore.Kind.SUBSCRIPTION, "subscription", binding.subscriptionUid());
                var assignmentRow = find(tx, TriggerStore.Kind.ASSIGNMENT, "assignment", binding.sourceAssignmentUid());
                if (row == null || assignmentRow == null) { issues.add("Trigger mapping " + binding.id() + ": subscription or assignment is unavailable."); continue; }
                var resource = row.path("subscription"); var assignment = assignmentRow.path("assignment");
                String name = resource.path("metadata").path("name").asText(); var sub = subscriptions.stored(tx, name);
                if (sub == null || !sub.uid().equals(binding.subscriptionUid()) || !sub.phase().equals("Active")
                        || sub.spec().path("expiresAtUnixMs").asLong() <= clock.millis()
                        || !sub.sources().contains(assignment.path("metadata").path("name").asText())
                        || !assignment.path("spec").path("desiredState").asText().equals("Running")
                        || !GroupGraph.memberships(tx.load(), codec, Ids.Kind.AGENT, binding.serviceAgentId(), false).contains(binding.serviceAgentGroupId())) {
                    issues.add("Trigger mapping " + binding.id() + ": subscription must be active, unexpired and bound to its configured running source and agent group."); continue;
                }
                var owner = new DirectoryService.Actor(tenant,
                    DirectoryService.digest("workflow-profile-listener\n" + sub.owner()), true, false, sub.owner());
                try {
                    subscriptions.requireSources(tx, owner, sub.sources());
                    if (!subscriptions.readable(tx, sub.sources(), subscriptions.clearance(tx, sub.owner())))
                        issues.add("Trigger mapping " + binding.id() + ": subscription owner lacks current source clearance.");
                } catch (Failure refused) {
                    if (refused.status() != 400 && refused.status() != 403 && refused.status() != 404) throw refused;
                    issues.add("Trigger mapping " + binding.id() + ": subscription owner lacks current source authorization.");
                }
            }
        } catch (Failure refused) {
            if (refused.status() != 400 && refused.status() != 403 && refused.status() != 404) throw refused;
            issues.add("Bot configuration contains invalid or unavailable references. Review its configured mappings.");
        }
        return java.util.List.copyOf(issues);
    }
    private JsonNode find(Store.Session tx, TriggerStore.Kind kind, String field, String uid) {
        String after = "";
        // Existing trigger subscriptions are capped at 1000; bounded scans avoid a new, competing trigger index.
        for (int page = 0; page < 11; page++) {
            var rows = tx.triggers().page(kind, "", after, 100);
            for (var row : rows) {
                var value = (JsonNode)codec.value(row.document());
                if (!value.path("removed").asBoolean() && uid.equals(value.path(field).path("metadata").path("uid").asText())) return value;
            }
            if (rows.size() < 100) return null;
            after = rows.getLast().id();
        }
        return null;
    }
}
