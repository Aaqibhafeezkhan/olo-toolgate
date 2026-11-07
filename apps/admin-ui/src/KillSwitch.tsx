// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import type {KillMode,KillOverview,KillProposal,KillScopeKind} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from './api';
import {Failure} from './Failure';
import {usePermissions} from './Permissions';
import {Table} from './Table';

const scopes:Record<KillScopeKind,string>={TENANT:'Entire tenant',PACKAGE:'Package',PACKAGE_DIGEST:'Exact package digest',
  PUBLISHER:'Publisher',TOOL:'Tool',POOL:'Execution pool',RUNTIME_IMAGE:'Runtime image digest'};
const timestamp=(value:number)=>new Date(value).toLocaleString();
export function KillSwitch({client,userId}:{client:ControlClient;userId?:string}){
  const permissions=usePermissions(client);
  const [overview,setOverview]=useState<KillOverview>();const [proposals,setProposals]=useState<readonly KillProposal[]>([]);
  const [cursor,setCursor]=useState<string>();const [history,setHistory]=useState<(string|undefined)[]>([]);
  const [selected,setSelected]=useState('');const [proposal,setProposal]=useState<KillProposal>();
  const [scope,setScope]=useState<KillScopeKind>('TENANT');const [value,setValue]=useState('');
  const [mode,setMode]=useState<KillMode>('STOP_NEW');const [reason,setReason]=useState('');
  const [recover,setRecover]=useState<string[]>([]);const [recoveryReason,setRecoveryReason]=useState('');
  const [confirmed,setConfirmed]=useState(false);const [busy,setBusy]=useState(false);
  const [error,setError]=useState<unknown>();const [refresh,setRefresh]=useState(0);
  const locked=useRef(false);const keys=useRef(new Map<string,string>());
  const selectedRef=useRef(selected);selectedRef.current=selected;
  const key=(intent:string)=>{let key=keys.current.get(intent);if(!key){key=crypto.randomUUID();keys.current.set(intent,key);}return key;};
  useEffect(()=>{
    const abort=new AbortController();setError(undefined);
    Promise.all([client.killOverview(abort.signal),client.killProposals(cursor,abort.signal)])
      .then(([state,items])=>{if(!abort.signal.aborted){setOverview(state);setProposals(items);
        setRecover(current=>current.filter(id=>state.state.kills.some(kill=>kill.eventId===id)));}})
      .catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    return ()=>abort.abort();
  },[client,cursor,refresh]);
  useEffect(()=>{
    setProposal(undefined);setConfirmed(false);if(!selected)return;
    const abort=new AbortController();
    client.killProposal(selected,abort.signal).then(next=>{if(!abort.signal.aborted)setProposal(next);})
      .catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    return ()=>abort.abort();
  },[client,selected,refresh]);
  async function change(intent:string,work:(key:string)=>Promise<KillProposal>){
    if(locked.current)return;locked.current=true;setBusy(true);setError(undefined);
    try{
      const next=await work(key(intent));keys.current.delete(intent);
      setSelected(next.id);setProposal(next);setConfirmed(false);setRefresh(value=>value+1);
    }catch(failure){
      setError(failure);
      if(failure instanceof ApiError&&failure.status===409){setProposal(undefined);setConfirmed(false);}
    }finally{locked.current=false;setBusy(false);}
  }
  async function propose(event:React.FormEvent){
    event.preventDefault();if(!permissions.can('kill.create'))return;
    const body={scope:{kind:scope,...(scope==='TENANT'?{}:{value:value.trim()})},mode,reason:reason.trim()};
    await change('kill:'+JSON.stringify(body),key=>client.proposeKill(body,key));
  }
  async function recoverKills(event:React.FormEvent){
    event.preventDefault();if(!permissions.can('kill.recover')||!recover.length)return;
    const body={supersedes:[...recover].sort(),reason:recoveryReason.trim()};
    await change('recovery:'+JSON.stringify(body),key=>client.proposeKillRecovery(body,key));
  }
  async function review(approve:boolean){
    if(!proposal||proposal.id!==selectedRef.current||!canReview||approve&&!confirmed)return;
    const body={expectedRevision:proposal.revision,approve};
    await change(proposal.id+':'+JSON.stringify(body),key=>client.reviewKill(proposal.id,body,key));
  }
  const expired=Boolean(proposal&&proposal.expiresAtUnixMs<=Date.now());
  const canReview=Boolean(proposal&&proposal.state==='PENDING'&&!expired&&userId&&userId!==proposal.requesterUserId
    &&!proposal.reviews.some(review=>review.reviewerUserId===userId)&&permissions.can('kill.approve')
    &&(proposal.kind!=='RECOVERY'||permissions.can('kill.recover')));
  return <section aria-label="Execution kill switch">
    <div className="page-heading"><div><p className="eyebrow">Execution control</p><h1>Kill switch</h1></div>
      <button disabled={busy} onClick={()=>setRefresh(value=>value+1)}>Refresh kill status</button></div>
    <p>Stop new work in a selected scope, or terminate matching running work. Normal changes require two independent reviewers, neither of them the requester.</p>
    {Boolean(error)&&<Failure error={error}/>}
    {!overview&&!error&&<p role="status">Loading kill status…</p>}
    {overview&&<>
      <h2>Active kills</h2>
      <Table><thead><tr><th>Event</th><th>Scope</th><th>Mode</th><th>Reason</th>{permissions.can('kill.recover')&&<th>Recovery</th>}</tr></thead>
        <tbody>{overview.state.kills.map(kill=><tr key={kill.eventId}>
          <td>{kill.eventId}</td><td>{scopes[kill.scope.kind]} {kill.scope.value}</td><td>{kill.mode==='STOP_NEW'?'Stop new work':'Terminate'}</td><td>{kill.reason}</td>
          {permissions.can('kill.recover')&&<td><input type="checkbox" aria-label={'Recover '+kill.eventId} checked={recover.includes(kill.eventId)} disabled={busy}
            onChange={event=>setRecover(current=>event.target.checked?[...current,kill.eventId]:current.filter(id=>id!==kill.eventId))}/></td>}
        </tr>)}</tbody></Table>
      {!overview.state.kills.length&&<p>No active kills.</p>}
      {permissions.can('kill.recover')&&<form onSubmit={event=>{void recoverKills(event);}}>
        <p>Recovery permits new work. Cancelled attempts remain cancelled.</p>
        <label>Recovery reason<input value={recoveryReason} maxLength={1024} required onChange={event=>setRecoveryReason(event.target.value)}/></label>
        <button disabled={busy||!recover.length||!recoveryReason.trim()}>Propose recovery</button></form>}
      <h2>Delivery status</h2>
      <p>Current epoch {overview.state.authorizationEpoch}. {overview.retainedEventCount} retained events, including {overview.archivedEventCount} archived. Observed {timestamp(overview.observedAtUnixMs)}.</p>
      <Table><thead><tr><th>Component</th><th>Received epoch</th><th>Delivery</th><th>Last acknowledgement</th></tr></thead>
        <tbody>{overview.components.map(component=><tr key={component.kind+':'+component.componentId}>
          <td>{component.kind} · {component.componentId}</td>
          <td>{component.authorizationEpoch===undefined?'Awaiting acknowledgement':component.authorizationEpoch+(component.authorizationEpoch<overview.state.authorizationEpoch?' · Pending update':' · Current')}</td>
          <td>{component.deliveryPath??'—'}</td><td>{component.acknowledgedAtUnixMs===undefined?'—':timestamp(component.acknowledgedAtUnixMs)}</td>
        </tr>)}</tbody></Table>
    </>}
    {permissions.can('kill.create')&&<form onSubmit={event=>{void propose(event);}}>
      <h2>Propose a kill</h2>
      <label>Kill scope<select value={scope} disabled={busy} onChange={event=>setScope(event.target.value as KillScopeKind)}>
        {Object.entries(scopes).map(([kind,label])=><option value={kind} key={kind}>{label}</option>)}</select></label>
      {scope!=='TENANT'&&<label>Scope value<input required value={value} maxLength={256} disabled={busy}
        onChange={event=>setValue(event.target.value)} pattern={scope==='PACKAGE_DIGEST'||scope==='RUNTIME_IMAGE'?'sha256:[a-f0-9]{64}':undefined}/></label>}
      <label>Kill mode<select value={mode} disabled={busy} onChange={event=>setMode(event.target.value as KillMode)}>
        <option value="STOP_NEW">Stop new work</option><option value="TERMINATE">Terminate matching work</option></select></label>
      <label>Kill reason<input required value={reason} maxLength={1024} disabled={busy} onChange={event=>setReason(event.target.value)}/></label>
      <button disabled={busy||!reason.trim()}>Create kill proposal</button>
    </form>}
    <h2>Review requests</h2>
    <Table><thead><tr><th>Request</th><th>Kind</th><th>Requester</th><th>State</th><th>Reviews</th></tr></thead>
      <tbody>{proposals.map(item=><tr key={item.id}><td><button disabled={busy} onClick={()=>setSelected(item.id)}>{item.id}</button></td>
        <td>{item.kind}</td><td>{item.requesterUserId}</td><td>{item.state}</td><td>{item.reviews.length} of 2</td></tr>)}</tbody></Table>
    {!proposals.length&&<p>No requests on this page.</p>}
    <div className="pagination"><button disabled={busy||!history.length} onClick={()=>{setCursor(history.at(-1));setHistory(history.slice(0,-1));}}>Previous kill requests</button>
      <button disabled={busy||proposals.length<50} onClick={()=>{setHistory([...history,cursor]);setCursor(proposals.at(-1)?.id);}}>Next kill requests</button></div>
    {selected&&!proposal&&!error&&<p role="status">Loading kill request…</p>}
    {proposal&&<section aria-label="Review exact kill request">
      <h2>Review exact request</h2><p><strong>{proposal.state}{expired?' · Expired':''}</strong> · Revision {proposal.revision}</p>
      <dl><dt>Requester</dt><dd>{proposal.requesterUserId}</dd><dt>Requested change</dt><dd>{proposal.kind}</dd>
        <dt>Scope</dt><dd>{proposal.scope?scopes[proposal.scope.kind]+' '+(proposal.scope.value??''):proposal.supersedes.join(', ')}</dd>
        <dt>Mode</dt><dd>{proposal.mode??'Recovery'}</dd><dt>Reason</dt><dd>{proposal.reason}</dd>
        <dt>Valid until</dt><dd>{timestamp(proposal.expiresAtUnixMs)}</dd></dl>
      <p>Reviewed by: {proposal.reviews.map(review=>review.reviewerUserId).join(', ')||'No reviewers yet'}.</p>
      {!canReview&&proposal.state==='PENDING'&&<p>This request requires a different eligible reviewer.</p>}
      {canReview&&<><label><input type="checkbox" checked={confirmed} disabled={busy} onChange={event=>setConfirmed(event.target.checked)}/>
        I reviewed this exact scope, mode and reason.</label>
        <button disabled={busy||!confirmed} onClick={()=>{void review(true);}}>Approve kill request</button>
        <button disabled={busy} onClick={()=>{void review(false);}}>Reject kill request</button></>}
    </section>}
  </section>;
}
