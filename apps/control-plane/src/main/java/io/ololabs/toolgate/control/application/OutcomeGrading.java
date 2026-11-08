// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.EnterpriseEvidenceObservation;
import io.ololabs.toolgate.contracts.EnterpriseExecutionCoverage;
import io.ololabs.toolgate.contracts.EnterpriseOutcomeGrade;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

/** Pure ADR 060 presentation grading. It never changes derived state, resolution or settlement. */
public final class OutcomeGrading {
    private OutcomeGrading() {}

    /** Whether the effect journal can describe everything consumed authority could do. */
    public static EnterpriseExecutionCoverage coverage(Collection<OperationFinalizationPolicy.Attempt> attempts,
                                                       Predicate<String> brokerOnly) {
        boolean consumed = false;
        for (var attempt : attempts) {
            if (!attempt.consumed()) continue;
            consumed = true;
            if (!brokerOnly.test(attempt.id())) return EnterpriseExecutionCoverage.UNMEDIATED_POSSIBLE;
        }
        return consumed ? EnterpriseExecutionCoverage.MEDIATED_ONLY : EnterpriseExecutionCoverage.NO_AUTHORITY_CONSUMED;
    }

    /** The weakest source a finalized outcome depends on; unresolved outcomes are UNKNOWN. */
    public static EnterpriseOutcomeGrade operation(boolean finalized, boolean reviewed,
                                                   EnterpriseExecutionCoverage coverage, int effects,
                                                   boolean targetWitnessed) {
        if (!finalized) return EnterpriseOutcomeGrade.UNKNOWN;
        if (reviewed) return EnterpriseOutcomeGrade.HUMAN_RECONCILED;
        if (coverage == EnterpriseExecutionCoverage.NO_AUTHORITY_CONSUMED) return EnterpriseOutcomeGrade.CONTROL_PROVEN;
        // Finalization resolves every effect only by a target witness or a closed unsent release.
        if (coverage == EnterpriseExecutionCoverage.MEDIATED_ONLY && effects > 0)
            return targetWitnessed ? EnterpriseOutcomeGrade.TARGET_CONFIRMED : EnterpriseOutcomeGrade.CONTROL_PROVEN;
        return EnterpriseOutcomeGrade.EXECUTOR_REPORTED;
    }

    /** The strongest outcome-bearing observation behind one effect generation. */
    public static EnterpriseOutcomeGrade effect(String state, boolean targetWitnessed,
                                                Set<EnterpriseEvidenceObservation> observations,
                                                boolean authoritativeResponse) {
        if (targetWitnessed || observations.contains(EnterpriseEvidenceObservation.RECONCILER_LOOKUP))
            return EnterpriseOutcomeGrade.TARGET_CONFIRMED;
        if ("RELEASED".equals(state)) return EnterpriseOutcomeGrade.CONTROL_PROVEN;
        if (authoritativeResponse && observations.contains(EnterpriseEvidenceObservation.BROKER_RESPONSE))
            return EnterpriseOutcomeGrade.BROKER_OBSERVED;
        return EnterpriseOutcomeGrade.UNKNOWN;
    }
}
