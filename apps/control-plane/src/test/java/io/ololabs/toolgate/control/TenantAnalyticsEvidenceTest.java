// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.application.TenantAnalytics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** ADR 060 analytics: every grade and coverage value is reported, and a bounded summary says when it is partial. */
class TenantAnalyticsEvidenceTest {
    static EnterpriseOperationOutcome outcome(String id,EnterpriseOutcomeGrade grade,EnterpriseExecutionCoverage coverage,long fenced,long unfenced){
        return new EnterpriseOperationOutcome(id,EnterpriseOperationState.SUCCEEDED,1L,grade,coverage,true,1L,fenced+unfenced,
            fenced,unfenced,0L,0L,true,false,false,1L);
    }
    @Test void countsGradesCoverageAndFencingWithExplicitZeros(){
        var evidence=TenantAnalytics.evidence(List.of(
            outcome("a",EnterpriseOutcomeGrade.TARGET_CONFIRMED,EnterpriseExecutionCoverage.MEDIATED_ONLY,2,0),
            outcome("b",EnterpriseOutcomeGrade.EXECUTOR_REPORTED,EnterpriseExecutionCoverage.UNMEDIATED_POSSIBLE,0,3),
            outcome("c",EnterpriseOutcomeGrade.EXECUTOR_REPORTED,EnterpriseExecutionCoverage.UNMEDIATED_POSSIBLE,1,1)));
        assertEquals(EnterpriseOutcomeGrade.values().length,evidence.grades().size());
        assertEquals(1L,evidence.grades().get("TARGET_CONFIRMED"));assertEquals(2L,evidence.grades().get("EXECUTOR_REPORTED"));
        assertEquals(0L,evidence.grades().get("BROKER_OBSERVED"));
        assertEquals(2L,evidence.coverage().get("UNMEDIATED_POSSIBLE"));assertEquals(0L,evidence.coverage().get("NO_AUTHORITY_CONSUMED"));
        assertEquals(3L,evidence.fencedEffects());assertEquals(4L,evidence.unfencedEffects());
        assertEquals(3L,evidence.operations());assertFalse(evidence.truncated());
    }
    @Test void aSummaryBeyondTheBoundIsMarkedTruncatedAndCountsOnlyTheBound(){
        var outcomes=new ArrayList<EnterpriseOperationOutcome>();
        for(int n=0;n<=TenantAnalytics.EVIDENCE_LIMIT;n++)
            outcomes.add(outcome("op-"+n,EnterpriseOutcomeGrade.UNKNOWN,EnterpriseExecutionCoverage.NO_AUTHORITY_CONSUMED,0,0));
        var evidence=TenantAnalytics.evidence(outcomes);
        assertTrue(evidence.truncated());assertEquals(TenantAnalytics.EVIDENCE_LIMIT,evidence.operations());
    }
}
