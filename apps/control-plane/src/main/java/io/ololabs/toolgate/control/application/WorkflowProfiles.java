// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.contracts.ErrorCode;
import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable staged profile revisions. Publication is separate from package provisioning and live authority. */
public final class WorkflowProfiles {
    @FunctionalInterface public interface Readiness { boolean ready(Store.Session tx, DeploymentProfile profile, String profileDigest); }
    private final Store store;
    private final Codec codec;
    private final Readiness readiness;
    private final WorkflowProfileDocuments documents;
    public WorkflowProfiles(Store store, Codec codec, Readiness readiness, TenantVaultKeys keys) {
        this.store = Objects.requireNonNull(store); this.codec = Objects.requireNonNull(codec);
        this.readiness = Objects.requireNonNull(readiness);
        this.documents = new WorkflowProfileDocuments(codec, keys);
    }
    private void require(Store.Session tx, DirectoryService.Actor actor) {
        actor.requireAdmin();
        // Until workflow-scoped management roles are registered, only live Super Admins may change this catalog.
        if (!new ManagementAccess(codec).superAdministrator(tx.load(), actor.userId())) throw ManagementAccess.denied();
    }
    private DeploymentProfile profile(String document) {
        if (document == null || document.getBytes(StandardCharsets.UTF_8).length > 262144) throw Failure.validation();
        var profile = codec.model(document, DeploymentProfile.class);
        if (profile.workflows() == null) throw Failure.validation();
        // Drafts may have unavailable external dependencies; they are never executable.
        new WorkflowConfigurations(codec).validate(codec.json(profile.workflows()), reference -> true);
        return profile;
    }
    private static void digest(String digest) {
        if (digest == null || !digest.matches("sha256:[a-f0-9]{64}")) throw Failure.validation();
    }
    private WorkflowProfileStore.Revision revision(Store.Session tx, Ids.TenantId tenant, String id, String digest) {
        Ids.valid(id); digest(digest);
        var revision = tx.workflowProfiles().revision(id, digest);
        if (revision == null) throw new Failure(ErrorCode.NOT_FOUND, 404, "Workflow profile revision unavailable");
        String plain = documents.open(tx, tenant, revision);
        var value = profile(plain);
        if (!value.profileId().equals(id) || !Canonical.digest((JsonNode)codec.value(plain)).equals(digest))
            throw Failure.unavailable();
        return revision;
    }
    private Map<String, java.util.List<String>> resources(DeploymentProfile profile) {
        var config = profile.workflows();
        var resources = new LinkedHashMap<String, java.util.List<String>>();
        resources.put("WORKFLOW_GROUP", config.workflowGroups().stream().map(WorkflowGroupDefinition::id).toList());
        resources.put("MODEL_GROUP", config.modelGroups().stream().map(WorkflowModelGroupDefinition::id).toList());
        resources.put("AGENT_DEFINITION", config.agentDefinitions().stream().map(WorkflowAgentDefinition::agentId).toList());
        resources.put("WORKFLOW", config.workflows().stream().map(WorkflowDefinition::id).toList());
        resources.put("DISPATCH", config.dispatchBindings().stream().map(WorkflowDispatchBinding::id).toList());
        resources.put("MODEL_DEPLOYMENT", config.modelDeployments().stream().map(WorkflowModelDeployment::id).toList());
        return resources;
    }
    private record Owned(String kind, String id, String definitionDigest) {}
    private java.util.List<Owned> owned(DeploymentProfile profile) {
        var owned = new java.util.ArrayList<Owned>();
        resources(profile).forEach((kind, ids) -> ids.forEach(id -> owned.add(new Owned(kind, id, null))));
        for (var workflow : profile.workflows().workflows()) {
            String key = DirectoryService.digest(codec.json(java.util.List.of(workflow.id(), workflow.versionId())));
            owned.add(new Owned("WORKFLOW_VERSION", key, Canonical.digest((JsonNode)codec.value(codec.json(workflow)))));
        }
        for (var binding : profile.workflows().dispatchBindings()) {
            owned.add(new Owned("DISPATCH_SOURCE", dispatchSourceKey(binding.subscriptionUid(), binding.sourceAssignmentUid()), null));
        }
        return owned;
    }
    private String dispatchSourceKey(String subscriptionUid, String assignmentUid) {
        return DirectoryService.digest(codec.json(java.util.List.of(subscriptionUid, assignmentUid)));
    }

    /** Configuration selected inside the admission transaction. This carries no execution authority. */
    public record DispatchTarget(String profileId, String profileDigest, WorkflowDispatchBinding binding, WorkflowDefinition workflow) {}

    public DispatchTarget dispatchTarget(Store.Session tx, Ids.TenantId tenant, String subscriptionUid, String assignmentUid) {
        Ids.valid(subscriptionUid);
        Ids.valid(assignmentUid);
        var owner = tx.workflowProfiles().owner("DISPATCH_SOURCE", dispatchSourceKey(subscriptionUid, assignmentUid));
        if (owner == null) throw ManagementAccess.denied();
        var head = tx.workflowProfiles().publication(owner.profileId());
        var profile = published(tx, tenant, owner.profileId(), head);
        var binding = profile.workflows().dispatchBindings().stream()
            .filter(candidate -> candidate.subscriptionUid().equals(subscriptionUid) && candidate.sourceAssignmentUid().equals(assignmentUid))
            .findFirst().orElseThrow(ManagementAccess::denied);
        var workflow = profile.workflows().workflows().stream()
            .filter(candidate -> candidate.id().equals(binding.workflowId()) && candidate.versionId().equals(binding.workflowVersionId()))
            .findFirst().orElseThrow(Failure::unavailable);
        return new DispatchTarget(head.profileId(), head.digest(), binding, workflow);
    }
    private WorkflowProfileStatus status(Store.Session tx, Ids.TenantId tenant, WorkflowProfileStore.Revision revision) {
        var head = tx.workflowProfiles().publication(revision.profileId());
        boolean published = head != null && head.digest().equals(revision.digest());
        boolean ready = revision.state().equals("REVIEWED") && readiness.ready(tx, profile(documents.open(tx, tenant, revision)), revision.digest());
        return new WorkflowProfileStatus(revision.profileId(), revision.digest(),
            published ? WorkflowProfileState.PUBLISHED : WorkflowProfileState.valueOf(revision.state()),
            head == null ? 0L : head.generation(), ready, revision.reviewedBy());
    }
    private Store.Reply reply(Store.Session tx, Ids.TenantId tenant, WorkflowProfileStore.Revision revision, int code) {
        var status = status(tx, tenant, revision);
        return new Store.Reply(code, codec.json(status), status.generation());
    }
    public Store.Reply upload(DirectoryService.Actor actor, String document, String requestId) {
        Ids.valid(requestId);
        // Authenticate before parsing profile data or disclosing any reference information.
        return store.transaction(actor.tenant(), true, tx -> {
            require(tx, actor);
            // Check the input format first, then validate and use the exact document we seal.
            profile(document);
            byte[] canonicalBytes;
            try {
                canonicalBytes = Canonical.bytes((JsonNode)codec.value(document));
            } catch (IllegalArgumentException invalid) {
                throw Failure.validation();
            }
            String canonical = new String(canonicalBytes, StandardCharsets.UTF_8);
            var profile = profile(canonical);
            PackageProvisioning.validateDeclarations(profile);
            String digest = Canonical.sha256(canonicalBytes);
            var old = tx.workflowProfiles().revision(profile.profileId(), digest);
            if (old != null) return reply(tx, actor.tenant(), revision(tx, actor.tenant(), profile.profileId(), digest), 200);
            for (var resource : owned(profile)) {
                var owner = tx.workflowProfiles().owner(resource.kind(), resource.id());
                if (owner != null && !owner.equals(new WorkflowProfileStore.Ownership(profile.profileId(), resource.definitionDigest()))) throw Failure.conflict();
            }
            var sealed = documents.seal(tx, actor.tenant(), profile.profileId(), digest, canonical);
            var created = new WorkflowProfileStore.Revision(profile.profileId(), digest, sealed.document(), sealed.keyVersion(), "DRAFT", null);
            tx.workflowProfiles().insert(created);
            for (var resource : owned(profile))
                tx.workflowProfiles().own(resource.kind(), resource.id(), profile.profileId(), resource.definitionDigest());
            tx.audit(actor.id(), "WORKFLOW_PROFILE_STAGE", "profile:" + profile.profileId(), 1, requestId, digest.substring(7));
            return reply(tx, actor.tenant(), created, 201);
        });
    }
    public Store.Reply get(DirectoryService.Actor actor, String id, String digest) {
        return store.transaction(actor.tenant(), false, tx -> { require(tx, actor); return reply(tx, actor.tenant(), revision(tx, actor.tenant(), id, digest), 200); });
    }
    /** Bounded metadata discovery; prompts/documents remain behind exact-revision retrieval. */
    public Store.Reply list(DirectoryService.Actor actor, String afterProfileId, String afterDigest, int limit) {
        return store.transaction(actor.tenant(), false, tx -> {
            require(tx, actor);
            if (limit < 1 || limit > 50 || (afterProfileId == null) != (afterDigest == null)) throw Failure.validation();
            if (afterProfileId != null) { Ids.valid(afterProfileId); digest(afterDigest); }
            var rows = tx.workflowProfiles().page(afterProfileId == null ? "" : afterProfileId, afterDigest == null ? "" : afterDigest, limit);
            var statuses = rows.stream().map(row -> status(tx, actor.tenant(), revision(tx, actor.tenant(), row.profileId(), row.digest()))).toList();
            return new Store.Reply(200, codec.json(statuses), 0);
        });
    }
    /** Reviewed document retrieval stays behind current management authorization; prompts are private configuration. */
    public Store.Reply document(DirectoryService.Actor actor, String id, String digest, boolean reviewed) {
        return store.transaction(actor.tenant(), false, tx -> {
            require(tx, actor); var revision = revision(tx, actor.tenant(), id, digest);
            if (reviewed && !revision.state().equals("REVIEWED")) throw Failure.conflict();
            return new Store.Reply(200, documents.open(tx, actor.tenant(), revision), 0);
        });
    }
    /** Published configuration is readable by tenant-wide agent and agent-group readers. */
    public Store.Reply listPublished(DirectoryService.Actor actor, String after, int limit) {
        if (limit < 1 || limit > 50) throw Failure.validation();
        if (after != null) Ids.valid(after);
        return store.transaction(actor.tenant(), false, tx -> {
            requirePublishedRead(tx, actor);
            var result = tx.workflowProfiles().publications(after == null ? "" : after, limit);
            return new Store.Reply(200, codec.json(result), 0);
        });
    }
    public Store.Reply publishedConfiguration(DirectoryService.Actor actor, String id, String digest) {
        return store.transaction(actor.tenant(), false, tx -> {
            requirePublishedRead(tx, actor);
            Ids.valid(id); digest(digest);
            var head = tx.workflowProfiles().publication(id);
            if (head == null || !head.digest().equals(digest))
                throw new Failure(ErrorCode.NOT_FOUND, 404, "Published workflow profile unavailable");
            var revision = revision(tx, actor.tenant(), id, digest);
            var document = ((JsonNode)codec.value(documents.open(tx, actor.tenant(), revision))).deepCopy();
            var workflows = document.path("workflows");
            // Prompts, arguments and schemas can contain arbitrary operator-supplied text.
            for (var entry : workflows.path("workflows")) {
                var workflow = (com.fasterxml.jackson.databind.node.ObjectNode)entry;
                workflow.remove(java.util.List.of("systemPrompt", "parameterSchema", "resultSchema"));
                redactContext(workflow.path("contextSources"));
            }
            for (var entry : workflows.path("agentDefinitions")) {
                var agent = (com.fasterxml.jackson.databind.node.ObjectNode)entry;
                agent.remove(java.util.List.of("systemPrompt", "outputSchema"));
                redactContext(agent.path("contextSources"));
            }
            for (var entry : workflows.path("modelDeployments"))
                ((com.fasterxml.jackson.databind.node.ObjectNode)entry).remove(java.util.List.of("secretName", "origin"));
            return new Store.Reply(200, codec.json(workflows), head.generation());
        });
    }
    private void requirePublishedRead(Store.Session tx, DirectoryService.Actor actor) {
        if (!new ManagementAccess(codec).canReadPublishedWorkflows(tx.load(), actor.userId(), System.currentTimeMillis()))
            throw ManagementAccess.denied();
    }
    private static void redactContext(JsonNode entries) {
        for (var entry : entries) ((com.fasterxml.jackson.databind.node.ObjectNode)entry).remove("arguments");
    }
    public Store.Reply review(DirectoryService.Actor actor, String id, String document, String requestId) {
        Ids.valid(requestId);
        return store.transaction(actor.tenant(), true, tx -> {
            require(tx, actor); var request = codec.model(document, WorkflowProfileReviewRequest.class);
            var revision = revision(tx, actor.tenant(), id, request.profileDigest());
            if (!revision.state().equals("REVIEWED")) {
                tx.workflowProfiles().review(id, revision.digest(), actor.userId());
                tx.audit(actor.id(), "WORKFLOW_PROFILE_REVIEW", "profile:" + id, 1, requestId, revision.digest().substring(7));
            }
            return reply(tx, actor.tenant(), revision(tx, actor.tenant(), id, revision.digest()), 200);
        });
    }
    /** Package provisioning is a separate reviewed operation; only its exact pinned READY result permits activation. */
    public Store.Reply publish(DirectoryService.Actor actor, String id, String document, String requestId) {
        Ids.valid(requestId);
        return store.transaction(actor.tenant(), true, tx -> {
            require(tx, actor); var request = codec.model(document, WorkflowProfilePublishRequest.class);
            var revision = revision(tx, actor.tenant(), id, request.profileDigest());
            if (!revision.state().equals("REVIEWED")) throw Failure.conflict();
            var head = tx.workflowProfiles().publication(id);
            long generation = head == null ? 0 : head.generation();
            if (head != null && head.digest().equals(revision.digest())) return reply(tx, actor.tenant(), revision, 200);
            if (request.expectedGeneration() != generation) throw Failure.conflict();
            var profile = profile(documents.open(tx, actor.tenant(), revision));
            if (!readiness.ready(tx, profile, revision.digest())) throw new Failure(ErrorCode.CONFLICT, 409, "Workflow profile dependencies are not ready");
            tx.workflowProfiles().publish(new WorkflowProfileStore.Publication(id, revision.digest(), generation + 1), generation);
            tx.audit(actor.id(), "WORKFLOW_PROFILE_PUBLISH", "profile:" + id, generation + 1, requestId, revision.digest().substring(7));
            return reply(tx, actor.tenant(), revision, 200);
        });
    }
    /** Internal admission boundary: recheck readiness, never treat a saved pointer as runtime authority. */
    public DeploymentProfile published(Store.Session tx, Ids.TenantId tenant, String id) {
        Ids.valid(id);
        return published(tx, tenant, id, tx.workflowProfiles().publication(id));
    }
    /** A pinned run can only use the reviewed revision while its publication and readiness are current. */
    public DeploymentProfile runnableRevision(Store.Session tx, Ids.TenantId tenant, String id, String digest) {
        Ids.valid(id);
        digest(digest);
        var head = tx.workflowProfiles().publication(id);
        if (head == null || !head.digest().equals(digest)) throw ManagementAccess.denied();
        return published(tx, tenant, id, head);
    }
    private DeploymentProfile published(Store.Session tx, Ids.TenantId tenant, String id, WorkflowProfileStore.Publication head) {
        if (head == null) throw ManagementAccess.denied();
        var revision = revision(tx, tenant, id, head.digest());
        if (!revision.state().equals("REVIEWED")) throw Failure.unavailable();
        var profile = profile(documents.open(tx, tenant, revision));
        if (!readiness.ready(tx, profile, revision.digest())) throw ManagementAccess.denied();
        return profile;
    }
}
