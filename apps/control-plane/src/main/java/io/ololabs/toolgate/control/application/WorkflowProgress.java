// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.v2.*;
import io.ololabs.toolgate.control.domain.Ids;
import java.time.Clock;
import java.util.Map;

/** Structural progress in the existing immutable tenant audit ledger; never a target receipt. */
public final class WorkflowProgress {
    private final Store store; private final Codec codec; private final Clock clock;
    public WorkflowProgress(Store store,Codec codec,Clock clock){this.store=store;this.codec=codec;this.clock=clock;}
    public static void control(Store.Session tx,Codec codec,String actor,String run,String kind,String request,String subject){
        var evidence=new java.util.LinkedHashMap<String,Object>();evidence.put("kind",kind);
        if(subject!=null)evidence.put("subjectId",subject);
        String json=codec.json(evidence);
        tx.audit(actor,"WORKFLOW_PROGRESS_CONTROL","workflow-run:"+run,1,request,DirectoryService.digest(json),json);
    }
    public Store.Reply read(DirectoryService.Actor actor,String run,long after){
        Ids.valid(run);if(after<0||after>9007199254740991L)throw Failure.validation();
        return store.transaction(actor.tenant(),false,tx->{
            actor.requireAdmin();
            if(!new ManagementAccess(codec).superAdministrator(tx.load(),actor.userId()))throw ManagementAccess.denied();
            if(tx.workflowRuns().get(run)==null)throw new Failure(io.ololabs.toolgate.contracts.ErrorCode.NOT_FOUND,404,"Workflow run unavailable");
            var events=tx.workflowProgress(run,after,200);
            return new Store.Reply(200,codec.json(new WorkflowRunProgressPage(events,events.isEmpty()?after:events.getLast().sequence())),0);
        });
    }
    public Store.Reply report(DirectoryService.Actor worker,String run,String document,String requestId){
        Ids.valid(run);Ids.valid(requestId);
        var event=codec.model(document,WorkflowRunProgressRequest.class);
        return store.transaction(worker.tenant(),true,tx->{
            WorkflowLeases.requireLease(tx.workflowRuns(),worker,run,event.leaseId(),event.leaseToken(),clock.millis());
            if(tx.workflowRuns().running(run)==null)throw ManagementAccess.denied();
            String key="workflow-progress:"+DirectoryService.digest(run+"\n"+event.eventId());
            String digest=Canonical.digest((com.fasterxml.jackson.databind.JsonNode)codec.value(document)).substring(7);
            var prior=tx.replay(worker.id(),key,digest);if(prior!=null)return prior;
            // Bounded per-run observability. Exceeding capacity never permits an unrecorded side effect.
            if(tx.workflowProgress(run,0,4097).size()>=4096)throw Failure.unavailable();
            var evidence=new java.util.LinkedHashMap<String,Object>();evidence.put("kind",event.kind().name());
            if(event.nodeId()!=null)evidence.put("nodeId",event.nodeId());
            if(event.subjectId()!=null)evidence.put("subjectId",event.subjectId());
            if(event.toolRef()!=null)evidence.put("toolRef",event.toolRef());
            String json=codec.json(evidence);
            tx.audit(worker.id(),"WORKFLOW_PROGRESS_RUNTIME","workflow-run:"+run,1,requestId,DirectoryService.digest(json),json);
            var reply=new Store.Reply(204,"",0);tx.remember(worker.id(),key,digest,reply);return reply;
        });
    }
}
