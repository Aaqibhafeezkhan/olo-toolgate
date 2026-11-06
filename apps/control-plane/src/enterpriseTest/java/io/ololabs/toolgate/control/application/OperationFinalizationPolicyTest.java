// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.*;
import org.junit.jupiter.api.Test;
import static io.ololabs.toolgate.control.application.OperationFinalizationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original-plan transition cases; these are policy tests, not SQL/caller or security-release acceptance. */
final class OperationFinalizationPolicyTest {
    private static Manifest sealed(String... ids){return new Manifest(true,Set.of(ids));}
    private static Attempt attempt(String id,String state,boolean settled,String... effects){
        return new Attempt(id,state,true,true,settled,sealed(effects));
    }
    private static Generation generation(String state){
        return new Generation(state,Set.of("COMPLETED","FAILED").contains(state),
                state.equals("COMPLETED")?"receipt":null,false,true);
    }
    private static Effect effect(String id,String state){return new Effect(id,List.of(generation(state)),null,false);}
    private static Facts facts(List<Attempt> attempts,Effect... effects){
        return new Facts(true,false,true,false,false,attempts,List.of(effects),null);
    }
    private static Status status(Facts facts){return derive(facts).status();}
    @Test void runningWorkDoesNotBecomeUnknownOrSucceededWhenItsFirstWriteCompletes(){
        var f=facts(List.of(attempt("a","EXECUTING",false,"first")),effect("first","COMPLETED"));
        assertEquals(Status.RUNNING,status(f));
        assertEquals(Status.SUCCEEDED,status(facts(List.of(attempt("a","SUCCEEDED",false,"first")),effect("first","COMPLETED"))));
    }
    @Test void aLateFailedPredecessorCannotOverwriteANewRunningOrSuccessfulAttempt(){
        var old=attempt("old","FAILED",false,"released");
        assertEquals(Status.RUNNING,status(facts(List.of(old,attempt("new","EXECUTING",false,"write")),
                effect("released","RELEASED"),effect("write","COMPLETED"))));
        assertEquals(Status.SUCCEEDED,status(facts(List.of(old,attempt("new","SUCCEEDED",false,"write")),
                effect("released","RELEASED"),effect("write","COMPLETED"))));
    }
    @Test void everyOldAttemptAndGenerationMustBeResolvedBeforeNewSuccessCounts(){
        var attempts=List.of(attempt("old","OUTCOME_UNKNOWN",true,"a"),attempt("new","SUCCEEDED",false,"b"));
        var unknown=derive(facts(attempts,effect("a","AMBIGUOUS"),effect("b","COMPLETED")));
        assertEquals(Status.OUTCOME_UNKNOWN,unknown.status());assertEquals(1,unknown.unresolvedEffects());
        assertEquals(Status.SUCCEEDED,status(facts(attempts,effect("a","FAILED"),effect("b","COMPLETED"))));
        assertEquals(Status.RECONCILIATION_REQUIRED,status(facts(attempts,effect("a","COMPLETED"),effect("b","COMPLETED"))));
        var equivalent=new Effect("a",List.of(generation("AMBIGUOUS")),"b",true);
        assertEquals(Status.SUCCEEDED,status(facts(attempts,equivalent,effect("b","COMPLETED"))));
    }
    @Test void equivalentEvidenceNeedsIndependentWitnessAndClosedAuthority(){
        var attempts=List.of(attempt("old","OUTCOME_UNKNOWN",true,"a"),attempt("new","SUCCEEDED",false,"b"));
        for(var a:List.of(new Effect("a",List.of(generation("AMBIGUOUS")),"b",false),
                new Effect("a",List.of(new Generation("AMBIGUOUS",false,null,false,false)),"b",true))){
            assertEquals(Status.OUTCOME_UNKNOWN,status(facts(attempts,a,effect("b","COMPLETED"))));
        }
        var missing=new Effect("a",List.of(generation("AMBIGUOUS")),"missing",true);
        assertEquals(Status.OUTCOME_UNKNOWN,status(facts(attempts,missing,effect("b","COMPLETED"))));
        var a=new Effect("a",List.of(generation("AMBIGUOUS")),"b",true);
        var b=new Effect("b",List.of(generation("AMBIGUOUS")),"a",true);
        assertEquals(Status.OUTCOME_UNKNOWN,status(facts(List.of(attempt("old","FAILED",false,"a","b")),a,b)));
    }
    @Test void equivalenceCannotHideContradictoryWitnessedBusinessReceipts(){
        var a=new Effect("a",List.of(new Generation("COMPLETED",true,"receipt-a",false,true)),"b",true);
        var b=new Effect("b",List.of(new Generation("COMPLETED",true,"receipt-b",false,true)),null,false);
        assertEquals(Status.RECONCILIATION_REQUIRED,status(facts(
                List.of(attempt("new","SUCCEEDED",false,"b")),a,b)));
    }
    @Test void theFullBoundedEquivalenceGraphDoesNotDependOnRecursiveStackDepth(){
        var effects=new ArrayList<Effect>();
        for(int i=0;i<4095;i++)effects.add(new Effect("e-"+i,List.of(generation("AMBIGUOUS")),"e-"+(i+1),true));
        effects.add(effect("e-4095","COMPLETED"));
        var f=new Facts(true,false,true,false,false,List.of(attempt("done","SUCCEEDED",false,"e-0")),effects,null);
        assertEquals(Status.SUCCEEDED,status(f));
    }
    @Test void reportedSuccessDoesNotProveUnmediatedOrUnsealedExecution(){
        for(var a:List.of(new Attempt("a","SUCCEEDED",true,false,false,sealed()),
                new Attempt("a","SUCCEEDED",true,true,false,new Manifest(false,Set.of())))){
            assertEquals(Status.OUTCOME_UNKNOWN,status(facts(List.of(a))));
        }
    }
    @Test void readOnlySuccessAndCrashDifferEvenWithAnEmptyManifest(){
        assertEquals(Status.SUCCEEDED,status(facts(List.of(attempt("read","SUCCEEDED",false)))));
        assertEquals(Status.FAILED,status(facts(List.of(attempt("read","FAILED",false)))));
        var crashed=facts(List.of(attempt("read","FAILED",false)));
        assertEquals(Status.FAILED,status(new Facts(true,false,true,false,false,crashed.attempts(),List.of(),sealed())));
        assertEquals(Status.FAILED,status(facts(List.of(new Attempt("unconsumed","EXPIRED",false,false,false,null)))));
    }
    @Test void anUnknownAttemptNeedsAnExplicitSettlementDespiteResolvedEffects(){
        var f=facts(List.of(attempt("lost","OUTCOME_UNKNOWN",false,"a")),effect("a","FAILED"));
        assertEquals(Status.OUTCOME_UNKNOWN,status(f));assertEquals(1,derive(f).unsettledAttempts());
        assertEquals(Status.FAILED,status(facts(List.of(attempt("lost","OUTCOME_UNKNOWN",true,"a")),effect("a","FAILED"))));
    }
    @Test void sealedFixedPlanCanSucceedAfterACrashButDynamicIntentCannot(){
        var attempts=List.of(attempt("lost","OUTCOME_UNKNOWN",true,"first","second"));
        var dynamic=facts(attempts,effect("first","COMPLETED"),effect("second","COMPLETED"));
        assertEquals(Status.PARTIAL,status(dynamic));
        assertEquals(Status.SUCCEEDED,status(new Facts(true,false,true,false,false,attempts,dynamic.effects(),sealed("first","second"))));
        assertEquals(Status.PARTIAL,status(new Facts(true,false,true,false,false,attempts,dynamic.effects(),sealed("first","second","never-issued"))));
    }
    @Test void successNeedsEveryManifestEffectAndEveryUnionEffect(){
        var attempts=List.of(attempt("a","SUCCEEDED",false,"first","second"));
        assertEquals(Status.PARTIAL,status(facts(attempts,effect("first","COMPLETED"),effect("second","FAILED"))));
        assertEquals(Status.FAILED,status(facts(attempts,effect("first","FAILED"),effect("second","RELEASED"))));
        assertEquals(Status.OUTCOME_UNKNOWN,status(facts(attempts,effect("first","COMPLETED"),effect("second","CONSUMED"))));
    }
    @Test void oldAndNewSuccessForTheSameWitnessedReceiptMerge(){
        var g=generation("COMPLETED");
        var e=new Effect("same",List.of(g,g),null,false);
        var f=facts(List.of(attempt("old","SUCCEEDED",false,"same"),attempt("new","SUCCEEDED",false,"same")),e);
        assertEquals(Status.SUCCEEDED,status(f));assertFalse(derive(f).deduplicationAssumed());
    }
    @Test void differentWitnessedReceiptsAndMissingUnapprovedReceiptsRequireReconciliation(){
        var attempts=List.of(attempt("a","SUCCEEDED",false,"same"));
        var first=new Generation("COMPLETED",true,"one",false,true);
        var second=new Generation("COMPLETED",true,"two",false,true);
        assertEquals(Status.RECONCILIATION_REQUIRED,status(facts(attempts,new Effect("same",List.of(first,second),null,false))));
        var noReceipt=new Generation("COMPLETED",true,null,false,true);
        assertEquals(Status.RECONCILIATION_REQUIRED,status(facts(attempts,new Effect("same",List.of(noReceipt,noReceipt),null,false))));
    }
    @Test void independentlyApprovedReceiptlessDeduplicationRemainsExplicitlyAssumed(){
        var g=new Generation("COMPLETED",true,null,true,true);
        var f=facts(List.of(attempt("a","SUCCEEDED",false,"same")),new Effect("same",List.of(g,g),null,false));
        assertEquals(Status.SUCCEEDED,status(f));assertTrue(derive(f).deduplicationAssumed());
    }
    @Test void aKnownConflictPrecedesRunningWork(){
        var attempts=List.of(attempt("a","EXECUTING",false,"same"));
        assertEquals(Status.RECONCILIATION_REQUIRED,status(facts(attempts,effect("same","CONFLICT"))));
        assertEquals(Status.RECONCILIATION_REQUIRED,status(new Facts(true,false,true,true,true,attempts,List.of(),null)));
    }
    @Test void missingLegacyTruncatedOrMalformedHistoryCannotProveSuccess(){
        var a=List.of(attempt("a","SUCCEEDED",false));
        for(var f:List.of(new Facts(false,false,true,false,false,a,List.of(),null),
                new Facts(true,true,true,false,false,a,List.of(),null),
                new Facts(true,false,false,false,false,a,List.of(),null),
                facts(List.of(new Attempt("a","SUCCEEDED",false,true,false,sealed()))),
                facts(List.of(new Attempt("a","INVALID",true,true,false,sealed()))),
                facts(List.of(a.getFirst(),a.getFirst())),
                facts(a,effect("same","COMPLETED"),effect("same","COMPLETED")))){
            assertEquals(Status.OUTCOME_UNKNOWN,status(f));
        }
        assertEquals(Status.RUNNING,status(new Facts(true,true,false,true,false,a,List.of(),null)));
    }
    @Test void clippedHistoryPreservesKnownActiveWorkWithoutProvingFinality(){
        var running=attempt("active","EXECUTING",false);
        var terminal=attempt("stopped","FAILED",true);
        var tooManyAttempts=new ArrayList<Attempt>();
        for(int n=0;n<65;n++)tooManyAttempts.add(attempt("attempt-"+n,"FAILED",true));
        tooManyAttempts.set(64,running);
        var effects=new ArrayList<Effect>();
        for(int n=0;n<4097;n++)effects.add(effect("effect-"+n,"RELEASED"));
        var oversized=new Effect("many-generations",Collections.nCopies(4097,generation("RELEASED")),null,false);
        for(var clipped:List.of(
                facts(tooManyAttempts),
                facts(List.of(running),effects.toArray(Effect[]::new)),
                facts(List.of(running),oversized),
                new Facts(true,false,false,true,false,List.of(terminal),List.of(oversized),null))){
            assertEquals(Status.RUNNING,status(clipped));
        }
        assertEquals(Status.OUTCOME_UNKNOWN,status(facts(List.of(terminal),oversized)));
        assertEquals(Status.RECONCILIATION_REQUIRED,status(
                new Facts(true,false,false,true,true,List.of(running),List.of(oversized),null)));
    }

}
