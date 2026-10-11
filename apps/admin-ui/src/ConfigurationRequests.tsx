// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import type {EnterpriseConfigurationChange,EnterpriseConfigurationAction} from '@olo-labs/toolgate-contracts';
import {ControlClient,ApiError} from './api';
import {Failure} from './Failure';

export function ConfigurationRequests({client}:{client:ControlClient}){
  const [requestedId,setRequestedId]=useState(()=>new URLSearchParams(window.location.hash.split('?')[1]).get('id'));
  const requestedIdRef=useRef(requestedId);
  useEffect(()=>{const update=()=>{const id=new URLSearchParams(window.location.hash.split('?')[1]).get('id');if(id===requestedIdRef.current)return;requestedIdRef.current=id;setRequestedId(id);setSelected(undefined);setConfirmed(false);setError(undefined);pending.current=undefined;};window.addEventListener('hashchange',update);return()=>window.removeEventListener('hashchange',update);},[]);
  const [items,setItems]=useState<readonly EnterpriseConfigurationChange[]>();const [selected,setSelected]=useState<EnterpriseConfigurationChange>();const [error,setError]=useState<unknown>();const [busy,setBusy]=useState(false);const [confirmed,setConfirmed]=useState(false);const [attempt,setAttempt]=useState(0);const [after,setAfter]=useState<string>();const [history,setHistory]=useState<(string|undefined)[]>([]);
  const locked=useRef(false);const pending=useRef<{body:string;key:string}|undefined>(undefined);const alive=useRef(true);
  useEffect(()=>{const abort=new AbortController();alive.current=true;setError(undefined);const id=requestedId;
    async function load(){try{const page=await client.configurationChanges(after,abort.signal);if(!abort.signal.aborted)setItems(page.items);if(id){const c=await client.configurationChange(id,abort.signal);if(!abort.signal.aborted)setSelected(c);}}catch(f){if(!abort.signal.aborted)setError(f);}}void load();return()=>{alive.current=false;abort.abort();};
  },[client,attempt,after,requestedId]);
  async function open(c:EnterpriseConfigurationChange){setConfirmed(false);setError(undefined);try{setSelected(await client.configurationChange(c.id));}catch(f){setError(f);}}
  async function transition(action:EnterpriseConfigurationAction){if(!selected||!confirmed||locked.current)return;const body={expectedRevision:selected.revision,action};const encoded=JSON.stringify(body);if(pending.current?.body!==encoded)pending.current={body:encoded,key:crypto.randomUUID()};locked.current=true;setBusy(true);setError(undefined);
    try{const result=await client.transitionConfiguration(selected.id,body,pending.current.key);if(alive.current){setSelected(result);setConfirmed(false);pending.current=undefined;setAttempt(v=>v+1);}}
    catch(f){if(alive.current){setError(f);if(f instanceof ApiError&&f.status===409){pending.current=undefined;setConfirmed(false);}}}finally{locked.current=false;if(alive.current)setBusy(false);}}
  const expired=selected&&selected.expiresAtUnixMs<=Date.now();
  return <><div className="page-heading"><h1>Configuration reviews</h1><button disabled={busy} onClick={()=>setAttempt(v=>v+1)}>Refresh changes</button></div>
    <p className="intro">Review the impact of each draft before submitting it. Independent reviewers need authority over the same source and destination groups and permission to grant the proposed scopes. Application checks the requester, every reviewer and the current revision again.</p>
    {Boolean(error)&&<Failure error={error}/>}{!items&&!error&&<p role="status">Loading configuration changes…</p>}
    {items&&<><div className="table-wrap"><table><caption className="sr-only">Reviewed configuration changes</caption><thead><tr><th>Change</th><th>Requester</th><th>Operation</th><th>State</th><th>Reviews</th></tr></thead><tbody>{items.map(c=><tr key={c.id}><th scope="row"><button className="record-link" onClick={()=>void open(c)}>{c.id}</button></th><td>{c.requesterUserId}</td><td>{c.command.operation} {c.command.kind} {c.command.entityId}</td><td>{c.state}</td><td>{c.reviews.filter(r=>r.decision==='APPROVE').length} / {c.requiredReviews}</td></tr>)}</tbody></table></div>{items.length===0&&<p>No configuration changes in your current scope.</p>}<div className="pagination"><button disabled={busy||!history.length} onClick={()=>{setAfter(history.at(-1));setHistory(history.slice(0,-1));}}>Previous changes</button><button disabled={busy||items.length<100} onClick={()=>{setHistory([...history,after]);setAfter(items.at(-1)?.id);}}>Next changes</button></div></>}
    {selected&&<section className="detail" aria-label="Configuration impact"><div className="page-heading"><h2>Review configuration impact</h2><button disabled={busy} onClick={()=>{setSelected(undefined);setConfirmed(false);}}>Close change</button></div><p><strong>{selected.state}</strong> · Change revision {selected.revision} · Directory revision {selected.directoryRevision} · Authorization epoch {selected.authorizationEpoch}</p>
      <dl className="policy-details"><dt>Requester</dt><dd>{selected.requesterUserId}</dd><dt>Exact change digest</dt><dd><code>{selected.requestDigest}</code></dd><dt>Expires</dt><dd>{new Date(selected.expiresAtUnixMs).toLocaleString()}</dd><dt>Affected groups</dt><dd><ul>{selected.affectedGroups.map(g=><li key={g}>{g}</li>)}</ul></dd><dt>Affected individuals</dt><dd><ul>{selected.affectedIndividuals.map(i=><li key={i}>{i}</li>)}</ul></dd></dl>
      <p className="hint">Affected individuals include members of every changed or referenced group. This is a conservative impact set; membership alone supplies no permission.</p>
      <div className="table-wrap"><table><caption>Exact record changes</caption><thead><tr><th>Record</th><th>Operation</th><th>Previous digest</th><th>Proposed digest</th></tr></thead><tbody>{selected.impact.map(i=><tr key={`${i.kind}:${i.entityId}`}><th scope="row">{i.kind}: {i.entityId}</th><td>{i.operation}</td><td><code>{i.beforeDigest}</code></td><td><code>{i.afterDigest}</code></td></tr>)}</tbody></table></div>
      <details><summary>Exact proposed configuration</summary><pre>{selected.command.document||'Delete the named record.'}</pre></details><h3>Independent reviews</h3><ul>{selected.reviews.map(r=><li key={r.reviewerUserId}>{r.decision} by {r.reviewerUserId} · {new Date(r.reviewedAtUnixMs).toLocaleString()}</li>)}</ul>
      {expired&&<p role="status">This change expired. Create a new draft against the current revision.</p>}
      {!['APPLIED','CANCELLED','DENIED','REVOKED'].includes(selected.state)&&<fieldset disabled={busy}><legend>Confirm the exact change</legend><label className="checkbox"><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/>I reviewed the affected groups, inherited access, complete configuration and digests.</label>
        {!expired&&selected.state==='DRAFT'&&<button className="primary" disabled={!confirmed} onClick={()=>void transition('SUBMIT')}>Submit draft for independent review</button>}
        {!expired&&selected.state==='PENDING'&&<><button className="primary" disabled={!confirmed} onClick={()=>void transition('APPROVE')}>Approve exact configuration</button><button disabled={!confirmed} onClick={()=>void transition('DENY')}>Deny change</button></>}
        {!expired&&selected.state==='APPROVED'&&<button className="primary" disabled={!confirmed} onClick={()=>void transition('APPLY')}>Apply reviewed change</button>}
        <button disabled={!confirmed} onClick={()=>void transition('CANCEL')}>Cancel change</button>{selected.state==='APPROVED'&&<button className="danger" disabled={!confirmed} onClick={()=>void transition('REVOKE')}>Revoke review</button>}
      </fieldset>}<p className="hint">Requesters can submit and apply their own drafts but cannot approve them. Any concurrent graph edit makes the draft stale. Applying a change advances the authorization epoch and immediately invalidates previous permits.</p>
    </section>}
  </>;
}
