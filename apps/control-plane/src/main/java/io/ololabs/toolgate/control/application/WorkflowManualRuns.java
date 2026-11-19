// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Explicit operator admission to the published configuration, without fabricating a trigger delivery. */
public final class WorkflowManualRuns {
    private final Store store;private final Codec codec;private final Clock clock;
    private final WorkflowProfiles profiles;private final WorkflowRunInputs inputs;
    public WorkflowManualRuns(Store store,Codec codec,Clock clock,WorkflowProfiles profiles,TenantVaultKeys keys){
        this.store=store;this.codec=codec;this.clock=clock;this.profiles=profiles;this.inputs=new WorkflowRunInputs(codec,keys);
    }
    public Store.Reply start(DirectoryService.Actor actor,String profileId,String workflowId,String document,String key,String requestId){
        Ids.valid(profileId);Ids.valid(workflowId);Ids.valid(key);Ids.valid(requestId);
        var request=codec.model(document,WorkflowRunStartRequest.class);
        String digest=Canonical.digest((com.fasterxml.jackson.databind.JsonNode)codec.value(codec.json(List.of(profileId,workflowId,request)))).substring(7);
        return store.transaction(actor.tenant(),true,tx->{
            actor.requireAdmin();
            if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))throw ManagementAccess.denied();
            var replay=tx.replay(actor.id(),"manual-run:"+DirectoryService.digest(key),digest);if(replay!=null)return replay;
            var head=tx.workflowProfiles().publication(profileId);
            if(head==null||!head.digest().equals(request.profileDigest())||head.generation()!=request.expectedGeneration())throw Failure.conflict();
            var profile=profiles.published(tx,actor.tenant(),profileId);
            var workflow=profile.workflows().workflows().stream().filter(w->w.id().equals(workflowId)).findFirst().orElseThrow(ManagementAccess::denied);
            var entry=workflow.agents().stream().filter(a->a.agentId().equals(request.entryAgentId())&&a.entryEligible()).findFirst().orElseThrow(ManagementAccess::denied);
            new WorkflowServiceAccess(codec).require(tx.load(),entry.agentId(),entry.agentGroupId(),workflow,clock.millis());
            var routes=profile.workflows().dispatchBindings().stream().filter(b->b.workflowId().equals(workflowId)&&b.workflowVersionId().equals(workflow.versionId())
                &&b.serviceAgentId().equals(entry.agentId())&&b.serviceAgentGroupId().equals(entry.agentGroupId()))
                .filter(b->request.dispatchBindingId()==null||b.id().equals(request.dispatchBindingId())).toList();
            if(request.dispatchBindingId()!=null&&routes.isEmpty())throw ManagementAccess.denied();
            var modes=routes.stream().map(WorkflowDispatchBinding::remediationMode).distinct().toList();
            if(modes.size()>1)throw Failure.conflict();
            var mode=modes.isEmpty()?WorkflowRemediationMode.PREPARE:modes.getFirst();
            String build=tx.workflowProfiles().graphBuild(profileId,head.digest(),workflowId);
            if(build==null)throw ManagementAccess.denied();
            String compiler=codec.model(build,WorkflowGraphBuildResult.class).compilerDigest();
            String run="run-"+UUID.randomUUID();
            var sealed=inputs.seal(tx,actor.tenant(),run,head.digest(),request.message());
            tx.workflowRuns().enqueue(new WorkflowRunStore.QueuedRun(run,profileId,head.digest(),workflowId,workflow.versionId(),entry.agentId(),entry.agentGroupId(),mode.name(),
                clock.millis(),sealed.keyVersion(),sealed.ciphertext(),compiler,workflow.graph().digest()));
            WorkflowProgress.control(tx,codec,actor.id(),run,"QUEUED",requestId,entry.agentId());
            var reply=new Store.Reply(201,codec.json(new WorkflowRunStartReceipt(run,profileId,head.digest(),workflowId,"QUEUED")),0);
            tx.remember(actor.id(),"manual-run:"+DirectoryService.digest(key),digest,reply);return reply;
        });
    }
}
