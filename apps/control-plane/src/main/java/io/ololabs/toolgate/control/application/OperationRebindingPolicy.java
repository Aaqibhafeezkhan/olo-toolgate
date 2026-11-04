// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import java.util.List;
import java.util.Set;

/** Internal proof calculation. Facts must come from Control's complete journal in its writer transaction. */
public final class OperationRebindingPolicy {
    public enum Proof { NEVER_CONSUMED, QUALIFIED_BROKER_NO_WRITE, TARGET_DEDUPLICATION }
    public record Attempt(boolean executionPurpose, boolean journalBound, Long consumedAt,
                   long expiresAt, boolean qualifiedBrokerOnly, List<String> generations) {
        public Attempt { generations=List.copyOf(generations); }
    }
    public record Facts(boolean present, boolean legacy, boolean completeJournal, boolean cancelled, boolean rejected,
                 String invocationState, String operationState, long originalDeadline, List<Attempt> attempts) {
        public Facts { attempts=List.copyOf(attempts); }
    }
    private OperationRebindingPolicy() {}

    /** Candidate only: live reviewed per-effect target contracts must be checked separately. */
    public static boolean targetCandidate(Facts facts,long now){
        if(facts==null||facts.attempts().stream().flatMap(a->a.generations().stream())
            .noneMatch("AMBIGUOUS"::equals))return false;
        var attempts=facts.attempts().stream().map(a->new Attempt(a.executionPurpose(),a.journalBound(),a.consumedAt(),
            a.expiresAt(),a.qualifiedBrokerOnly(),a.generations().stream().map(g->g.equals("AMBIGUOUS")?"RELEASED":g).toList())).toList();
        return proof(new Facts(facts.present(),facts.legacy(),facts.completeJournal(),facts.cancelled(),facts.rejected(),
            facts.invocationState(),facts.operationState(),facts.originalDeadline(),attempts),now)==Proof.QUALIFIED_BROKER_NO_WRITE;
    }
    public static Proof proof(Facts facts,long now) {
        if(now<0||facts.attempts().size()>=64||!facts.present()||facts.legacy()||!facts.completeJournal()||facts.cancelled()||facts.rejected()
                ||facts.originalDeadline()<=now||facts.invocationState()==null||facts.operationState()==null
                ||!Set.of("FAILED","OUTCOME_UNKNOWN").contains(facts.operationState())
                ||!Set.of("FAILED","EXPIRED","OUTCOME_UNKNOWN").contains(facts.invocationState()))return null;
        boolean consumed=false;
        for(var attempt:facts.attempts()) {
            if(attempt.expiresAt()<=0||!attempt.executionPurpose()||!attempt.journalBound())return null;
            if(attempt.consumedAt()==null) {
                if(attempt.expiresAt()>now||!attempt.generations().isEmpty())return null;
            } else {
                if(attempt.consumedAt()<0||attempt.consumedAt()>now||attempt.consumedAt()>=attempt.expiresAt()
                        ||!attempt.qualifiedBrokerOnly()
                        ||attempt.generations().stream().anyMatch(state->!"RELEASED".equals(state)))return null;
                consumed=true;
            }
        }
        // FAILED is Control's positive aggregate for a complete unconsumed journal.
        // OUTCOME_UNKNOWN without positively qualified consumption is never an empty-history shortcut.
        if(!consumed&&!"FAILED".equals(facts.operationState()))return null;
        return consumed?Proof.QUALIFIED_BROKER_NO_WRITE:Proof.NEVER_CONSUMED;
    }
}
