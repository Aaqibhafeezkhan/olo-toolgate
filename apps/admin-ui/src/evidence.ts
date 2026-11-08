// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {EnterpriseEffectFencing,EnterpriseEvidenceObservation,EnterpriseExecutionCoverage,EnterpriseOutcomeGrade} from '@olo-labs/toolgate-contracts';

/** ADR 060 wording. Grades present recorded evidence; they never grant or imply execution authority. */
export const GRADES:Record<EnterpriseOutcomeGrade,{label:string;meaning:string}>={
 TARGET_CONFIRMED:{label:'Target-confirmed',meaning:'The target reported the outcome, or a reconciler lookup at the target found it.'},
 BROKER_OBSERVED:{label:'Broker-observed',meaning:'The broker saw the target respond on a route reviewed as committing before it answers. Trusted only while the host is intact.'},
 HUMAN_RECONCILED:{label:'Human-reconciled',meaning:'An independent reviewer decided the outcome from recorded evidence.'},
 CONTROL_PROVEN:{label:'Control record',meaning:'Control\'s own records: no execution authority was used, or authority was released unsent.'},
 EXECUTOR_REPORTED:{label:'Executor-reported',meaning:'Only the executor\'s own report supports this outcome.'},
 UNKNOWN:{label:'Unknown',meaning:'Nothing outcome-bearing is recorded. The outcome is unresolved.'},
};
export const COVERAGE:Record<EnterpriseExecutionCoverage,{label:string;meaning:string}>={
 NO_AUTHORITY_CONSUMED:{label:'No authority used',meaning:'No attempt consumed execution authority.'},
 MEDIATED_ONLY:{label:'Brokered effects only',meaning:'Every attempt that ran was confined to brokered effects, so the effect list below is complete.'},
 UNMEDIATED_POSSIBLE:{label:'Unmediated effects possible',meaning:'An attempt could act outside the broker. Effects it may have caused directly are unknown.'},
};
export const FENCING:Record<EnterpriseEffectFencing,string>={
 FENCED:'Fenced target: it consumes single-use authority at Control',
 UNFENCED:'Unfenced target: it cannot check authority with Control',
};
export const OBSERVATIONS:Record<EnterpriseEvidenceObservation,string>={
 AUTHORIZATION_CONSUMED:'target accepted authority (not an outcome)',
 BROKER_RESPONSE:'broker saw a response',
 TARGET_OUTCOME:'target reported the outcome',
 RECONCILER_LOOKUP:'reconciler lookup at the target',
};
export const gradeLabel=(grade:EnterpriseOutcomeGrade)=>GRADES[grade]?.label??grade;
