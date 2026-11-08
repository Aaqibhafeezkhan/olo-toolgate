// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.EnterpriseEvidenceObservation;
import io.ololabs.toolgate.contracts.EnterpriseExecutionCoverage;
import io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade;
import io.ololabs.toolgate.control.application.OperationFinalizationPolicy.Attempt;
import io.ololabs.toolgate.control.application.OutcomeGrading;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.ololabs.toolgate.contracts.EnterpriseEvidenceObservation.*;
import static io.ololabs.toolgate.contracts.EnterpriseExecutionCoverage.*;
import static io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 060 presentation grading: honest, never stronger than the weakest source an outcome depends on. */
class OutcomeGradingTest {
    static Attempt attempt(String id,boolean consumed){return new Attempt(id,"SUCCEEDED",consumed,true,true,null);}

    @Test void coverageDistinguishesNoAuthorityMediatedAndPossiblyUnmediatedExecution(){
        Set<String> qualified=Set.of("contained");
        assertEquals(NO_AUTHORITY_CONSUMED,OutcomeGrading.coverage(List.of(),qualified::contains));
        assertEquals(NO_AUTHORITY_CONSUMED,OutcomeGrading.coverage(List.of(attempt("plain",false)),qualified::contains));
        assertEquals(MEDIATED_ONLY,OutcomeGrading.coverage(List.of(attempt("contained",true),attempt("plain",false)),qualified::contains));
        // One unconfined consumed attempt makes effects outside the journal unknown for the whole operation.
        assertEquals(UNMEDIATED_POSSIBLE,OutcomeGrading.coverage(List.of(attempt("contained",true),attempt("plain",true)),qualified::contains));
    }

    @Test void unresolvedOutcomesAreUnknownWhateverWasObserved(){
        for(var coverage:EnterpriseExecutionCoverage.values())
            for(boolean reviewed:new boolean[]{false,true})
                assertEquals(UNKNOWN,OutcomeGrading.operation(false,reviewed,coverage,3,true));
    }

    @Test void finalizedOperationsAreGradedByTheWeakestSourceTheyDependOn(){
        assertEquals(HUMAN_RECONCILED,OutcomeGrading.operation(true,true,UNMEDIATED_POSSIBLE,0,false));
        assertEquals(CONTROL_PROVEN,OutcomeGrading.operation(true,false,NO_AUTHORITY_CONSUMED,0,false));
        assertEquals(TARGET_CONFIRMED,OutcomeGrading.operation(true,false,MEDIATED_ONLY,2,true));
        // Every mediated effect released unsent: Control's own record, not a target witness.
        assertEquals(CONTROL_PROVEN,OutcomeGrading.operation(true,false,MEDIATED_ONLY,2,false));
        // Contained success with no mediated effect rests on the executor's report alone.
        assertEquals(EXECUTOR_REPORTED,OutcomeGrading.operation(true,false,MEDIATED_ONLY,0,false));
    }

    @Test void oneWitnessOrConsumedFailureNoLongerOverclaims(){
        // Previously any witnessed generation graded the whole operation TARGET_CONFIRMED.
        assertEquals(EXECUTOR_REPORTED,OutcomeGrading.operation(true,false,UNMEDIATED_POSSIBLE,4,true));
        // Previously every FAILED operation was CONTROL_PROVEN, even after consumed authority.
        assertEquals(EXECUTOR_REPORTED,OutcomeGrading.operation(true,false,UNMEDIATED_POSSIBLE,0,false));
    }

    @Test void effectGradesUseOnlyOutcomeBearingObservations(){
        Set<EnterpriseEvidenceObservation> none=EnumSet.noneOf(EnterpriseEvidenceObservation.class);
        assertEquals(TARGET_CONFIRMED,OutcomeGrading.effect("COMPLETED",true,EnumSet.of(AUTHORIZATION_CONSUMED,TARGET_OUTCOME),false));
        assertEquals(TARGET_CONFIRMED,OutcomeGrading.effect("AMBIGUOUS",false,EnumSet.of(RECONCILER_LOOKUP),false));
        assertEquals(CONTROL_PROVEN,OutcomeGrading.effect("RELEASED",false,none,false));
        assertEquals(BROKER_OBSERVED,OutcomeGrading.effect("SENDING",false,EnumSet.of(BROKER_RESPONSE),true));
        // A broker response on a route not reviewed as authoritative is an observation only.
        assertEquals(UNKNOWN,OutcomeGrading.effect("SENDING",false,EnumSet.of(BROKER_RESPONSE),false));
        // Consumption proves the target accepted authority, not that it applied the effect.
        assertEquals(UNKNOWN,OutcomeGrading.effect("CONSUMED",false,EnumSet.of(AUTHORIZATION_CONSUMED),true));
        assertEquals(UNKNOWN,OutcomeGrading.effect("AUTHORIZED",false,none,true));
    }
}
