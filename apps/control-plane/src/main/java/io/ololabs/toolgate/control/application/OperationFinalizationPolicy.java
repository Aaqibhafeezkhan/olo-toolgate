// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.*;

/** Pure revision-10 derivation. Facts come only from a complete authoritative Control journal. */
public final class OperationFinalizationPolicy {
    public enum Status { RUNNING, OUTCOME_UNKNOWN, RECONCILIATION_REQUIRED, SUCCEEDED, PARTIAL, FAILED }
    public record Manifest(boolean sealed,Set<String> effects) {
        public Manifest { effects=Collections.unmodifiableSet(new TreeSet<>(effects)); }
    }
    public record Attempt(String id,String state,boolean consumed,boolean completeEffectScope,
                          boolean settled,Manifest manifest) {}
    /** A business receipt identifies the actual target effect; a response-body digest does not. */
    public record Generation(String state,boolean witnessed,String businessReceipt,
                             boolean approvedTargetDeduplication,boolean authorityClosed) {}
    /** Equivalence is independent witnessed reconciliation, never a worker's self-reported alias. */
    public record Effect(String id,List<Generation> generations,String equivalentTo,boolean witnessedEquivalence) {
        public Effect { generations=List.copyOf(generations); }
    }
    public record Facts(boolean present,boolean legacy,boolean completeJournal,boolean queued,
                        boolean witnessedConflict,List<Attempt> attempts,List<Effect> effects,Manifest fixedPlan) {
        public Facts { attempts=List.copyOf(attempts);effects=List.copyOf(effects); }
    }
    public record Result(Status status,int unresolvedEffects,int unsettledAttempts,boolean deduplicationAssumed) {}
    private enum Resolution { COMPLETED, NOT_APPLIED, UNRESOLVED, CONFLICT }
    private record Resolved(Resolution resolution,String canonical,boolean assumed) {}
    private static final Set<String> ACTIVE=Set.of("PENDING_APPROVAL","QUEUED","RESERVED","DISPATCHED","EXECUTING","RUNNING");
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED","EXPIRED","OUTCOME_UNKNOWN");
    private OperationFinalizationPolicy() {}
    private static Result result(Status status,int effects,int attempts,boolean assumed){
        return new Result(status,effects,attempts,assumed);
    }
    private static boolean active(Attempt a){return a.state()!=null&&ACTIVE.contains(a.state());}
    private static boolean terminal(Attempt a){return a.state()!=null&&TERMINAL.contains(a.state());}
    private static Result boundedHistory(Facts facts){
        boolean running=facts.queued()||facts.attempts().stream().anyMatch(OperationFinalizationPolicy::active);
        return result(running?Status.RUNNING:Status.OUTCOME_UNKNOWN,0,0,false);
    }

    public static Result derive(Facts facts){
        if(facts==null||!facts.present())return result(Status.OUTCOME_UNKNOWN,0,0,false);
        if(facts.witnessedConflict())return result(Status.RECONCILIATION_REQUIRED,0,0,false);
        if(facts.attempts().size()>64||facts.effects().size()>4096)
            return boundedHistory(facts);
        var effects=new LinkedHashMap<String,Effect>();
        int generations=0;
        for(var e:facts.effects()){
            if(e.id()==null||e.id().isBlank()||effects.putIfAbsent(e.id(),e)!=null)
                return result(Status.OUTCOME_UNKNOWN,0,0,false);
            generations+=e.generations().size();
        }
        if(generations>4096)return boundedHistory(facts);
        var resolved=new HashMap<String,Resolved>();
        for(var e:effects.values()){
            var value=resolve(e,effects,resolved);
            if(value.resolution()==Resolution.CONFLICT)
                return result(Status.RECONCILIATION_REQUIRED,0,0,false);
        }
        var ids=new HashSet<String>();
        boolean valid=!facts.legacy()&&facts.completeJournal();
        int unsettled=0;
        for(var a:facts.attempts()){
            if(a.id()==null||a.id().isBlank()||!ids.add(a.id())||!active(a)&&!terminal(a))valid=false;
            if(a.consumed()&&(!a.completeEffectScope()||a.manifest()==null||!a.manifest().sealed())
                    ||"OUTCOME_UNKNOWN".equals(a.state())&&!a.settled())unsettled++;
            // A success without consumption is not a trustworthy journal projection.
            if(!a.consumed()&&"SUCCEEDED".equals(a.state()))valid=false;
        }
        int unresolved=(int)resolved.values().stream().filter(e->e.resolution()==Resolution.UNRESOLVED).count();
        boolean assumed=resolved.values().stream().anyMatch(Resolved::assumed);
        var candidates=new ArrayList<Manifest>();
        if(facts.fixedPlan()!=null&&facts.fixedPlan().sealed()&&!facts.fixedPlan().effects().isEmpty()
                &&complete(facts.fixedPlan(),resolved))candidates.add(facts.fixedPlan());
        for(var a:facts.attempts())if(a.consumed()&&a.completeEffectScope()
                &&"SUCCEEDED".equals(a.state())&&a.manifest()!=null&&a.manifest().sealed()
                &&complete(a.manifest(),resolved))candidates.add(a.manifest());
        boolean supportedSuccess=candidates.stream().anyMatch(m->coversCompleted(m,resolved));
        // An extra real effect takes priority over ordinary active/unknown state.
        if(valid&&!candidates.isEmpty()&&!supportedSuccess)
            return result(Status.RECONCILIATION_REQUIRED,unresolved,unsettled,assumed);
        if(facts.queued()||facts.attempts().stream().anyMatch(OperationFinalizationPolicy::active))
            return result(Status.RUNNING,unresolved,unsettled,assumed);
        if(!valid||unresolved>0||unsettled>0)
            return result(Status.OUTCOME_UNKNOWN,unresolved,unsettled,assumed);
        if(supportedSuccess)return result(Status.SUCCEEDED,0,0,assumed);
        boolean completed=resolved.values().stream().anyMatch(e->e.resolution()==Resolution.COMPLETED);
        return result(completed?Status.PARTIAL:Status.FAILED,0,0,assumed);
    }
    private static boolean complete(Manifest manifest,Map<String,Resolved> resolved){
        return manifest.effects().stream().allMatch(id->resolved.containsKey(id)
                &&resolved.get(id).resolution()==Resolution.COMPLETED);
    }
    private static boolean coversCompleted(Manifest manifest,Map<String,Resolved> resolved){
        var expected=new HashSet<String>();
        for(String id:manifest.effects())expected.add(resolved.get(id).canonical());
        return resolved.values().stream().filter(e->e.resolution()==Resolution.COMPLETED)
                .allMatch(e->expected.contains(e.canonical()));
    }
    private static boolean equivalent(Effect e){
        return e.equivalentTo()!=null&&e.witnessedEquivalence()&&!e.generations().isEmpty()
                &&e.generations().stream().allMatch(Generation::authorityClosed);
    }
    private static Set<String> receipts(Effect e){
        var result=new HashSet<String>();
        for(var g:e.generations())if("COMPLETED".equals(g.state())&&g.witnessed()&&g.authorityClosed()
                &&g.businessReceipt()!=null&&!g.businessReceipt().isBlank())result.add(g.businessReceipt());
        return result;
    }
    private static Resolved resolve(Effect effect,Map<String,Effect> effects,Map<String,Resolved> resolved){
        var cached=resolved.get(effect.id());if(cached!=null)return cached;
        // Iterative traversal handles the complete bounded graph without consuming the JVM stack.
        var path=new ArrayList<Effect>();var seen=new HashSet<String>();
        var cursor=effect;Resolved value;
        while(true){
            var known=resolved.get(cursor.id());
            if(known!=null){value=known;break;}
            if(!seen.add(cursor.id())){value=new Resolved(Resolution.UNRESOLVED,cursor.id(),false);break;}
            path.add(cursor);var local=own(cursor);
            if(local.resolution()==Resolution.CONFLICT||!equivalent(cursor)){value=local;break;}
            var target=effects.get(cursor.equivalentTo());
            if(target==null){value=new Resolved(Resolution.UNRESOLVED,cursor.id(),false);break;}
            cursor=target;
        }
        for(int index=path.size()-1;index>=0;index--){
            var item=path.get(index);var local=own(item);
            if(local.resolution()==Resolution.CONFLICT||!equivalent(item))value=local;
            else if(value.resolution()==Resolution.COMPLETED){
                var receipts=receipts(item);var target=effects.get(value.canonical());
                if(target!=null)receipts.addAll(receipts(target));
                value=receipts.size()>1?new Resolved(Resolution.CONFLICT,item.id(),false)
                        :new Resolved(Resolution.COMPLETED,value.canonical(),value.assumed()||local.assumed());
            }else if(value.resolution()!=Resolution.CONFLICT)value=new Resolved(Resolution.UNRESOLVED,item.id(),false);
            resolved.put(item.id(),value);
        }
        return resolved.get(effect.id());
    }
    private static Resolved own(Effect effect){
        if(effect.generations().isEmpty())return new Resolved(Resolution.UNRESOLVED,effect.id(),false);
        boolean unresolved=false,completed=false,missingReceipt=false,allApproved=true;
        int completions=0;var receipts=new HashSet<String>();
        for(var g:effect.generations()){
            if("CONFLICT".equals(g.state()))return new Resolved(Resolution.CONFLICT,effect.id(),false);
            switch(g.state()==null?"UNKNOWN":g.state()){
                case "RELEASED" -> {if(!g.authorityClosed())unresolved=true;}
                case "FAILED" -> {if(!g.witnessed()||!g.authorityClosed())unresolved=true;}
                case "COMPLETED" -> {
                    if(!g.witnessed()||!g.authorityClosed()){unresolved=true;continue;}
                    completed=true;completions++;
                    if(g.businessReceipt()==null||g.businessReceipt().isBlank())missingReceipt=true;
                    else receipts.add(g.businessReceipt());
                    allApproved&=g.approvedTargetDeduplication();
                }
                default -> unresolved=true;
            }
        }
        if(receipts.size()>1||completions>1&&missingReceipt&&!allApproved)
            return new Resolved(Resolution.CONFLICT,effect.id(),false);
        return new Resolved(unresolved?Resolution.UNRESOLVED:completed?Resolution.COMPLETED:Resolution.NOT_APPLIED,
                effect.id(),completions>1&&missingReceipt&&allApproved);
    }
}
