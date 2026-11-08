// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useState} from 'react';
import type {EnterpriseExecutionCoverage,EnterpriseOperationOutcome,EnterpriseOutcomeGrade} from '@olo-labs/toolgate-contracts';
import {ControlClient,type TenantAnalyticsOverview} from './api';
import {COVERAGE,GRADES} from './evidence';
import {Failure} from './Failure';

const EXPORT_PAGES=50;

/** ADR 060: Control-derived grades, kept apart from Gateway-recorded call states. */
export function EvidenceSummary({client,evidence}:{client:ControlClient;evidence:TenantAnalyticsOverview['evidence']}){
 const [busy,setBusy]=useState(false),[error,setError]=useState<unknown>(),[note,setNote]=useState<string>();
 const download=async()=>{
  setBusy(true);setError(undefined);setNote(undefined);
  try{
   const items:EnterpriseOperationOutcome[]=[];let after:string|undefined;let pages=0;let exportedAt=0;
   do{const page=await client.operationEvidence(after);items.push(...page.items);after=page.next??undefined;exportedAt=page.exportedAtUnixMs;pages++;}
   while(after&&pages<EXPORT_PAGES);
   const body=JSON.stringify({format:'toolgate-operation-evidence-v1',exportedAtUnixMs:exportedAt,complete:!after,items},null,2);
   const url=URL.createObjectURL(new Blob([body],{type:'application/json'}));
   const link=document.createElement('a');link.href=url;link.download='operation-evidence.json';link.click();URL.revokeObjectURL(url);
   setNote(after?`Exported the first ${items.length} operations; the export stopped at ${EXPORT_PAGES} pages and is marked incomplete.`:`Exported ${items.length} operations.`);
  }catch(failure){setError(failure);}finally{setBusy(false);}
 };
 const grades=Object.keys(GRADES) as EnterpriseOutcomeGrade[];const coverage=Object.keys(COVERAGE) as EnterpriseExecutionCoverage[];
 return <section aria-labelledby="evidence-heading"><h2 id="evidence-heading">Outcome evidence</h2>
  <p>From Control's operation records, by the weakest evidence each outcome depends on. These are separate from the call outcomes above, which are states recorded at the Gateway and carry no evidence grade.</p>
  <p className="hint">{evidence.operations} operations last derived in this range. Effects: {evidence.fencedEffects} fenced, {evidence.unfencedEffects} unfenced.</p>
  {evidence.truncated&&<p role="status">More operations exist in this range than are summarized. Use the export for the complete record.</p>}
  <table><caption>Evidence grade</caption><thead><tr><th>Grade</th><th>Operations</th><th>Meaning</th></tr></thead>
   <tbody>{grades.map(grade=><tr key={grade}><td>{GRADES[grade].label}</td><td>{evidence.grades[grade]??0}</td><td>{GRADES[grade].meaning}</td></tr>)}</tbody></table>
  <table><caption>Effect coverage</caption><thead><tr><th>Coverage</th><th>Operations</th><th>Meaning</th></tr></thead>
   <tbody>{coverage.map(value=><tr key={value}><td>{COVERAGE[value].label}</td><td>{evidence.coverage[value]??0}</td><td>{COVERAGE[value].meaning}</td></tr>)}</tbody></table>
  <button type="button" disabled={busy} onClick={()=>void download()}>{busy?'Exporting…':'Download evidence export'}</button>
  {note&&<p role="status">{note}</p>}
  {Boolean(error)&&<Failure error={error} retry={()=>void download()}/>}
 </section>;
}
