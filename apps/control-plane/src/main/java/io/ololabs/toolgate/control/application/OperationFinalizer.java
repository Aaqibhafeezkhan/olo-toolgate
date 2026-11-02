// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import java.util.*;

/** Called only inside a tenant writer transaction; observations and their audit commit together. */
public final class OperationFinalizer {
    private final Codec codec;
    public OperationFinalizer(Codec codec){this.codec=Objects.requireNonNull(codec);}

    public EnterpriseOperationOutcome refresh(Store.Session tx,String operation,long now){
        var store=tx.enterprise();var invocation=store.invocation(operation);
        if(invocation==null)throw Failure.conflict();
        var preparation=store.prepareFinalization(operation,now);
        var facts=store.finalizationFacts(operation,now);
        var derived=OperationFinalizationPolicy.derive(facts);
        var previous=store.latestOperationOutcome(operation);
        var state=EnterpriseOperationState.valueOf(derived.status().name());
        if(previous!=null){
            var before=previous.value();
            if(before.state()==EnterpriseOperationState.RECONCILIATION_REQUIRED)
                state=EnterpriseOperationState.RECONCILIATION_REQUIRED;
            else if(before.finalized()&&before.state()!=state){
                boolean recovery=before.state()==EnterpriseOperationState.FAILED&&state==EnterpriseOperationState.RUNNING
                        &&store.recoveries(operation,64).stream().anyMatch(r->
                        r.toRevision()>previous.invocationRevision()&&r.toRevision()<=invocation.revision());
                if(!recovery)state=EnterpriseOperationState.RECONCILIATION_REQUIRED;
            }
        }
        boolean complete=facts.present()&&!facts.legacy()&&facts.completeJournal()
                &&facts.attempts().size()<=64&&facts.effects().size()<=4096
                &&facts.effects().stream().mapToInt(e->e.generations().size()).sum()<=4096;
        boolean finalized=Set.of(EnterpriseOperationState.SUCCEEDED,EnterpriseOperationState.PARTIAL,
                EnterpriseOperationState.FAILED).contains(state);
        var grade=!finalized?EnterpriseOutcomeGrade.REPORTED
                :store.hasAttemptReviews(operation)?EnterpriseOutcomeGrade.REVIEWER_CONFIRMED
                :derived.deduplicationAssumed()?EnterpriseOutcomeGrade.TARGET_DEDUPLICATION_ASSUMED
                :facts.effects().stream().flatMap(e->e.generations().stream()).anyMatch(g->
                    g.witnessed()&&Set.of("COMPLETED","FAILED").contains(g.state()))?EnterpriseOutcomeGrade.TARGET_CONFIRMED
                :state==EnterpriseOperationState.FAILED?EnterpriseOutcomeGrade.CONTROL_PROVEN
                :EnterpriseOutcomeGrade.REPORTED;
        // Exclude wall time and this projection's own revision/audit to keep unchanged reads stable.
        var source=new LinkedHashMap<String,Object>();
        source.put("format","toolgate-operation-outcome-v1");
        source.put("operationId",operation);source.put("requestDigest",invocation.requestDigest());
        source.put("invocationRevision",invocation.revision());source.put("facts",facts);
        source.put("evidenceHead",store.operationEvidenceHead(operation));
        source.put("derived",derived);source.put("state",state);source.put("grade",grade);
        String digest=DirectoryService.digest(codec.json(source));
        if(previous!=null&&previous.sourceDigest().equals(digest))return previous.value();
        var outcome=new EnterpriseOperationOutcome(operation,state,previous==null?1L:previous.value().revision()+1,
                grade,complete,(long)facts.attempts().size(),(long)facts.effects().size(),
                (long)derived.unresolvedEffects(),(long)derived.unsettledAttempts(),finalized,false,
                derived.deduplicationAssumed(),now);
        var saved=store.recordOperationOutcome(outcome,digest,invocation.revision());
        if(preparation.closed()>0||preparation.settled()>0)
            tx.audit("control","ATTEMPTS_SETTLED","invocation:"+operation,invocation.revision(),
                    invocation.evaluation().context().requestId(),preparation.digest());
        tx.audit("control","OPERATION_OUTCOME_DERIVED","invocation:"+operation,saved.value().revision(),
                invocation.evaluation().context().requestId(),digest);
        return saved.value();
    }
}
