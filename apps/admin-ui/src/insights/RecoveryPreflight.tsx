// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useState,type FormEvent} from 'react';
import type {RecoveryPreflightPage,ToolRecoveryReadiness} from '@olo-labs/toolgate-contracts/v2';
import {InsightsError,type InsightsClient} from './api';

const reason=(value:ToolRecoveryReadiness['reason'])=>({
  ELIGIBLE:'Reviewed contract and qualified executor configured',
  DISABLED:'Tool is disabled',
  MISSING_PROFILE:'No current deployed service profile',
  PROFILE_CHANGED:'Deployment and tool no longer match',
  NO_WRITE_ROUTES:'No supported write routes',
  UNREVIEWED_ROUTE:'Write route needs explicit effect review',
  MISSING_GUARANTEE:'Target deduplication guarantee is missing',
  REVIEW_EXPIRED:'Target guarantee review expired',
  INDEPENDENT_REVIEW_REQUIRED:'Current independent guarantee approval is required',
  UNSUPPORTED_CREDENTIAL:'Credential scope or adapter is unsupported',
  UNQUALIFIED_EXECUTOR:'No matching qualified executor is configured',
}[value]);
const method=(value:ToolRecoveryReadiness['effectiveMethod'])=>value==='DOWNSTREAM_IDEMPOTENCY'?'Guarded target-deduplicated retry':value==='STATUS_LOOKUP'?'Read-only status lookup':'Manual reconciliation';

/** Current capability inventory, independent of telemetry mode and never execution authority. */
export function RecoveryPreflight({api}:{api:InsightsClient}){
  const [tenant,setTenant]=useState(''),[filter,setFilter]=useState(''),[page,setPage]=useState<RecoveryPreflightPage>();
  const [loading,setLoading]=useState(false),[error,setError]=useState<string>();
  async function load(selected:string,cursor?:string){
    setLoading(true);setError(undefined);
    try{setPage(await api.recovery({tenant:selected||undefined,cursor,limit:50}));setFilter(selected);}
    catch(error){setPage(undefined);setError(error instanceof InsightsError?error.message:'Recovery preflight could not be loaded.');}
    finally{setLoading(false);}
  }
  function submit(event:FormEvent){event.preventDefault();void load(tenant.trim());}
  return <section aria-labelledby="recovery-preflight-title">
    <h2 id="recovery-preflight-title">Recovery preflight</h2>
    <p>This fresh build uses current schemas and contracts. Older databases and artifacts require a fresh installation.</p>
    <p>Inspect tools declaring downstream idempotency before release. Each retry still needs complete qualified attempt history, current policy and consent, and its original target window.</p>
    <form onSubmit={submit}>
      <label>Tenant UUID (leave empty for all tenants)<input value={tenant} onChange={event=>setTenant(event.target.value)} maxLength={36}/></label>
      <button type="submit" disabled={loading}>{page?'Refresh preflight':'Run preflight'}</button>
    </form>
    {loading&&<p role="status">Checking recovery readiness…</p>}
    {error&&<p className="notice error" role="alert">{error}</p>}
    {page&&<><p>Observed {new Date(page.observedAtUnixMs).toISOString()}. Effect status and whole-operation completion are recorded separately.</p>
      {page.items.length===0?<p>No affected tools on this page.</p>:<div className="table-scroll"><table>
        <caption>Current recovery configuration</caption><thead><tr><th>Tenant</th><th>Tool</th><th>Recovery method</th><th>Required work</th><th>Write routes</th></tr></thead>
        <tbody>{page.items.map(tool=><tr key={tool.tenantId+'/'+tool.toolId}>
          <td>{tool.tenantId}</td><td>{tool.name}<br/><code>{tool.toolId}</code></td>
          <td>{method(tool.effectiveMethod)}</td><td>{reason(tool.reason)}</td>
          <td>{tool.routes.length===0?'No supported route facts':<ul>{tool.routes.map(route=><li key={route.routeId}>
            <code>{route.routeId}</code>: {reason(route.reason)}; {route.lookupAvailable?'lookup available':'lookup unavailable'}
            {route.reviewExpiresAtUnixMs!==undefined&&<>; review expires {new Date(route.reviewExpiresAtUnixMs).toISOString()}</>}
          </li>)}</ul>}</td>
        </tr>)}</tbody></table></div>}
      {page.nextCursor&&<button type="button" disabled={loading} onClick={()=>void load(filter,page.nextCursor)}>Next preflight page</button>}
    </>}
  </section>;
}
