// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Recovery requires positive Control facts for every predecessor, never executor assertions. */
final class OperationRebindingPolicyTest {
    private static final long NOW=10000, DEADLINE=20000;
    private static OperationRebindingPolicy.Attempt unconsumed() {
        return new OperationRebindingPolicy.Attempt(true,true,null,NOW,false,List.of());
    }
    private static OperationRebindingPolicy.Attempt consumed(boolean qualified,List<String> generations) {
        return new OperationRebindingPolicy.Attempt(true,true,1000L,2000,qualified,generations);
    }
    private static OperationRebindingPolicy.Facts facts(String aggregate,OperationRebindingPolicy.Attempt... attempts) {
        return new OperationRebindingPolicy.Facts(true,false,true,false,false,"FAILED",aggregate,DEADLINE,List.of(attempts));
    }
    @Test void completeExpiredUnconsumedJournalIsPositiveControlProof() {
        assertEquals(OperationRebindingPolicy.Proof.NEVER_CONSUMED,
                OperationRebindingPolicy.proof(facts("FAILED",unconsumed()),NOW));
        assertEquals(OperationRebindingPolicy.Proof.NEVER_CONSUMED,
                OperationRebindingPolicy.proof(facts("FAILED"),NOW));
        var live=new OperationRebindingPolicy.Attempt(true,true,null,NOW+1,false,List.of());
        assertNull(OperationRebindingPolicy.proof(facts("FAILED",live),NOW));
    }
    @Test void consumedNotStartedOrEmptySpoolCannotReplaceQualification() {
        assertNull(OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",consumed(false,List.of())),NOW));
        assertNull(OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",consumed(false,List.of("RELEASED"))),NOW));
    }
    @Test void onlyPositiveCompleteMediationMakesAnEmptyEffectSetUseful() {
        for(var generations:List.of(List.<String>of(),List.of("RELEASED"),List.of("RELEASED","RELEASED"))) {
            assertEquals(OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,
                    OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",consumed(true,generations)),NOW));
        }
    }
    @Test void everyPotentialOrCompletedWriteFencesTheOperation() {
        for(var state:List.of("AUTHORIZED","SENDING","CONSUMED","COMPLETED","FAILED","AMBIGUOUS","CONFLICT","UNKNOWN")) {
            assertNull(OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",
                    consumed(true,List.of("RELEASED",state))),NOW),state);
        }
    }
    @Test void aSafeLatestAttemptCannotHideAnUnsafePredecessor() {
        assertNull(OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",
                consumed(false,List.of()),consumed(true,List.of("RELEASED"))),NOW));
        assertNull(OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",
                consumed(true,List.of("SENDING")),unconsumed()),NOW));
        assertEquals(OperationRebindingPolicy.Proof.QUALIFIED_BROKER_NO_WRITE,
                OperationRebindingPolicy.proof(facts("OUTCOME_UNKNOWN",
                        consumed(true,List.of("RELEASED")),unconsumed()),NOW));
    }
    @Test void missingOrLegacyNonceBindingsAreNotEvidenceOfNoExecution() {
        for(var attempt:List.of(
                new OperationRebindingPolicy.Attempt(false,true,null,NOW,false,List.of()),
                new OperationRebindingPolicy.Attempt(true,false,null,NOW,false,List.of()))) {
            assertNull(OperationRebindingPolicy.proof(facts("FAILED",attempt),NOW));
        }
        for(var aggregate:List.of("RUNNING","OUTCOME_UNKNOWN","SUCCEEDED","INVALID")) {
            assertNull(OperationRebindingPolicy.proof(facts(aggregate),NOW),aggregate);
        }
    }
    @Test void cancellationFinalOutcomesLegacyAndElapsedDeadlineCannotReopen() {
        var attempts=List.of(unconsumed());
        var unsafe=List.of(
                new OperationRebindingPolicy.Facts(false,false,true,false,false,"FAILED","FAILED",DEADLINE,attempts),
                new OperationRebindingPolicy.Facts(true,true,true,false,false,"FAILED","FAILED",DEADLINE,attempts),
                new OperationRebindingPolicy.Facts(true,false,false,false,false,"FAILED","FAILED",DEADLINE,attempts),
                new OperationRebindingPolicy.Facts(true,false,true,true,false,"FAILED","FAILED",DEADLINE,attempts),
                new OperationRebindingPolicy.Facts(true,false,true,false,true,"FAILED","FAILED",DEADLINE,attempts),
                new OperationRebindingPolicy.Facts(true,false,true,false,false,"FAILED","FAILED",NOW,attempts));
        for(var value:unsafe)assertNull(OperationRebindingPolicy.proof(value,NOW));
        for(var state:List.of("QUEUED","RESERVED","DISPATCHED","EXECUTING","SUCCEEDED","PARTIAL","CANCELLED","REJECTED")) {
            var value=new OperationRebindingPolicy.Facts(true,false,true,false,false,state,"FAILED",DEADLINE,attempts);
            assertNull(OperationRebindingPolicy.proof(value,NOW),state);
        }
    }
    @Test void exhaustedAttemptCapacityCannotReopen() {
        var exhausted=new OperationRebindingPolicy.Facts(true,false,true,false,false,"FAILED","FAILED",DEADLINE,
                java.util.Collections.nCopies(64,unconsumed()));
        assertNull(OperationRebindingPolicy.proof(exhausted,NOW));
    }
    @Test void malformedTimesOrStateCannotManufacturePositiveEvidence() {
        for(var attempt:List.of(
                new OperationRebindingPolicy.Attempt(true,true,null,0,false,List.of()),
                new OperationRebindingPolicy.Attempt(true,true,-1L,2000,true,List.of()),
                new OperationRebindingPolicy.Attempt(true,true,2000L,2000,true,List.of()),
                new OperationRebindingPolicy.Attempt(true,true,NOW+1,NOW+2,true,List.of()))) {
            assertNull(OperationRebindingPolicy.proof(facts("FAILED",attempt),NOW));
        }
        assertNull(OperationRebindingPolicy.proof(facts("FAILED",unconsumed()),-1));
        var invalid=new OperationRebindingPolicy.Facts(true,false,true,false,false,null,"FAILED",DEADLINE,List.of());
        assertNull(OperationRebindingPolicy.proof(invalid,NOW));
    }
}
