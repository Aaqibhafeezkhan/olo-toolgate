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
        if (profile.workflows() == null) return false;
        // Tool execution readiness cases cannot be assumed from package installation alone.
        if (profile.readiness() != null && !profile.readiness().isEmpty()) return false;
        if (!profile.packages().isEmpty()) {
            var packages = tx.packages().provisioning(profile.profileId());
            if (packages == null || !packages.profileDigest().equals(profileDigest)
                    || !"READY".equals(((JsonNode)codec.value(packages.document())).path("state").asText())) return false;
        }
        try {
            new WorkflowConfigurations(codec).validate(codec.json(profile.workflows()), reference -> switch (reference.kind()) {
                case AGENT -> enabled(tx, Ids.Kind.AGENT, reference.id());
                case AGENT_GROUP -> enabled(tx, Ids.Kind.AGENT_GROUP, reference.id());
                case TOOL -> enabled(tx, Ids.Kind.TOOL, reference.id());
                case MODEL_DEPLOYMENT -> profile.workflows().modelDeployments().stream().filter(d -> d.id().equals(reference.id())).anyMatch(d -> {
                    var secret = tx.enterprise().secret(d.secretName());
                    return secret != null && secret.toolGroupId().equals(d.toolGroupId()) && secret.deviceGroupId().equals(d.deviceGroupId())
                        && enabled(tx, Ids.Kind.TOOL_GROUP, d.toolGroupId()) && enabled(tx, Ids.Kind.DEVICE_GROUP, d.deviceGroupId());
                });
                case TRIGGER_SUBSCRIPTION -> find(tx, TriggerStore.Kind.SUBSCRIPTION, "subscription", reference.id()) != null;
                case TRIGGER_ASSIGNMENT -> find(tx, TriggerStore.Kind.ASSIGNMENT, "assignment", reference.id()) != null;
            });
            for (var workflow : profile.workflows().workflows()) for (var agent : workflow.agents())
                if (!GroupGraph.memberships(tx.load(), codec, Ids.Kind.AGENT, agent.agentId(), false).contains(agent.agentGroupId())) return false;
            for (var binding : profile.workflows().dispatchBindings()) {
                if (subscriptions == null || triggers == null || !triggers.enabled()) return false;
                var row = find(tx, TriggerStore.Kind.SUBSCRIPTION, "subscription", binding.subscriptionUid());
                var resource = row.path("subscription");
                var assignment = find(tx, TriggerStore.Kind.ASSIGNMENT, "assignment", binding.sourceAssignmentUid()).path("assignment");
                String name = resource.path("metadata").path("name").asText();
                var sub = subscriptions.stored(tx, name);
                String assignmentName = assignment.path("metadata").path("name").asText();
                if (sub == null || !sub.uid().equals(binding.subscriptionUid()) || !sub.phase().equals("Active")
                        || sub.spec().path("expiresAtUnixMs").asLong() <= clock.millis()
                        || !sub.sources().contains(assignmentName) || !assignment.path("spec").path("desiredState").asText().equals("Running")
                        || !GroupGraph.memberships(tx.load(), codec, Ids.Kind.AGENT, binding.serviceAgentId(), false).contains(binding.serviceAgentGroupId())) return false;
                var owner = new DirectoryService.Actor(tenant,
                    DirectoryService.digest("workflow-profile-listener\n" + sub.owner()), true, false, sub.owner());
                // Source clearance and current subscription grants are checked without rehydrating redacted data.
                subscriptions.requireSources(tx, owner, sub.sources());
                if (!subscriptions.readable(tx, sub.sources(), subscriptions.clearance(tx, sub.owner()))) return false;
            }
            return true;
        } catch (Failure refused) {
            if (refused.status() == 400 || refused.status() == 403 || refused.status() == 404) return false;
            throw refused;
        }
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
