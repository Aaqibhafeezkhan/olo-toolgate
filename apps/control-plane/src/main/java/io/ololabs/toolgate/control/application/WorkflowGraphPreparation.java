// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import java.util.ArrayList;
import java.util.List;

/** Compile outside database transactions; record a complete exact-generation result atomically. */
public final class WorkflowGraphPreparation {
    @FunctionalInterface public interface Builder { WorkflowGraphBuildResult build(WorkflowGraphBuildRequest request); }
    private final Codec codec;
    private final WorkflowProfiles profiles;
    private final Builder builder;
    public WorkflowGraphPreparation(Codec codec, WorkflowProfiles profiles, Builder builder) {
        this.codec = java.util.Objects.requireNonNull(codec);
        this.profiles = java.util.Objects.requireNonNull(profiles); this.builder = java.util.Objects.requireNonNull(builder);
    }
    public Store.Reply prepare(DirectoryService.Actor actor, String id, String document, String requestId) {
        io.ololabs.toolgate.control.domain.Ids.valid(requestId);
        // Document retrieval verifies current management authority before any external request.
        var request = codec.model(document, WorkflowProfilePublishRequest.class);
        var desired = codec.model(profiles.document(actor, id, request.profileDigest(), true).body(), DeploymentProfile.class);
        var status = codec.model(profiles.get(actor, id, request.profileDigest()).body(), WorkflowProfileStatus.class);
        if (!status.generation().equals(request.expectedGeneration())) throw Failure.conflict();
        var receipts = new ArrayList<WorkflowGraphBuildResult>();
        for (var workflow : desired.workflows().workflows()) {
            var build = new WorkflowGraphBuildRequest(actor.tenant().value(), id, request.profileDigest(), workflow.id(), request.expectedGeneration());
            var receipt = codec.model(codec.json(builder.build(build)), WorkflowGraphBuildResult.class);
            if (!receipt.tenantId().equals(build.tenantId()) || !receipt.profileId().equals(id)
                    || !receipt.profileDigest().equals(build.profileDigest()) || !receipt.workflowId().equals(build.workflowId())
                    || !receipt.generation().equals(build.expectedGeneration())) throw Failure.unavailable();
            receipts.add(receipt);
        }
        // Reauthorize and compare-and-set after the remote calls. No partial preparation is recorded.
        return profiles.recordGraphBuilds(actor, id, request.profileDigest(), request.expectedGeneration(), List.copyOf(receipts), requestId);
    }
}
