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
        boolean assumed=derived.deduplicationAssumed()||reliedOnTargetRecovery(store,facts,operation);
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
        // ADR 059: presentation only. Derived state, resolution and settlement are unchanged.
        var coverage=OutcomeGrading.coverage(facts.attempts(),attempt->store.recoveryScope(attempt)!=null);
        var fencing=store.effectFencing(operation);
        boolean witnessed=facts.effects().stream().flatMap(e->e.generations().stream()).anyMatch(g->
                g.witnessed()&&Set.of("COMPLETED","FAILED").contains(g.state()));
        var grade=OutcomeGrading.operation(finalized,finalized&&store.hasAttemptReviews(operation),coverage,
                facts.effects().size(),witnessed);
        // Exclude wall time and this projection's own revision/audit to keep unchanged reads stable.
        var source=new LinkedHashMap<String,Object>();
        source.put("format","toolgate-operation-outcome-v1");
        source.put("operationId",operation);source.put("requestDigest",invocation.requestDigest());
        source.put("invocationRevision",invocation.revision());source.put("facts",facts);
        source.put("evidenceHead",store.operationEvidenceHead(operation));
        source.put("derived",derived);source.put("state",state);source.put("grade",grade);source.put("coverage",coverage);source.put("fencing",fencing);source.put("deduplicationAssumed",assumed);
        String digest=DirectoryService.digest(codec.json(source));
        if(previous!=null&&previous.sourceDigest().equals(digest))return previous.value();
        var outcome=new EnterpriseOperationOutcome(operation,state,previous==null?1L:previous.value().revision()+1,
                grade,coverage,complete,(long)facts.attempts().size(),(long)facts.effects().size(),fencing.fenced(),fencing.unfenced(),
                (long)derived.unresolvedEffects(),(long)derived.unsettledAttempts(),finalized,false,
                assumed,now);
        var saved=store.recordOperationOutcome(outcome,digest,invocation.revision());
        if(preparation.closed()>0||preparation.settled()>0)
            tx.audit("control","ATTEMPTS_SETTLED","invocation:"+operation,invocation.revision(),
                    invocation.evaluation().context().requestId(),preparation.digest());
        tx.audit("control","OPERATION_OUTCOME_DERIVED","invocation:"+operation,saved.value().revision(),
                invocation.evaluation().context().requestId(),digest);
        return saved.value();
    }
    private boolean reliedOnTargetRecovery(EnterpriseStore store,OperationFinalizationPolicy.Facts facts,String operation){
        var effects=new HashMap<String,OperationFinalizationPolicy.Effect>();
        for(var effect:facts.effects())effects.put(effect.id(),effect);
        for(var recovery:store.recoveries(operation,64)){
            if(!"TARGET_DEDUPLICATION".equals(recovery.proof()))continue;
            var plan=((com.fasterxml.jackson.databind.JsonNode)codec.value(recovery.document())).path("targetPlan");
            if(!plan.isArray()||plan.isEmpty()||plan.size()>256)throw Failure.conflict();
            for(var item:plan){
                var effect=effects.get(item.path("effectId").asText());long generation=item.path("generation").asLong();
                if(effect==null||generation<1||generation>4096)throw Failure.conflict();
                // Complete ordered SQL histories start at generation one. A positively
                // released replacement did not rely on target deduplication for a send.
                for(long n=generation;n<effect.generations().size();n++)
                    if(!effect.generations().get((int)n).state().equals("RELEASED"))return true;
            }
        }
        return false;
    }

}
