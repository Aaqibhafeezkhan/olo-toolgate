// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table,ActionButton,StatusToggle} from './Table';
import {useEffect,useRef,useState} from 'react';
import type {GroupMembership,BundlePublishRequest,ControlAgentGroup,ControlAccessGrant,EnterpriseAuthorityStatus} from '@olo-labs/toolgate-contracts';
import {ControlClient,ApiError} from './api';
import {Failure} from './Failure';
import {ScopePicker} from './ScopePicker';
import {useRecordActions} from './RecordActions';
import {EnterpriseScopeEditor,emptyScope} from './EnterpriseScopeEditor';
import {EffectiveAccess} from './EffectiveAccess';
import {EditorDialog} from './EditorDialog';

export function PublishAccess({client}:{client:ControlClient}) {
  const [busy,setBusy]=useState(false),[error,setError]=useState<unknown>(),[notice,setNotice]=useState('');
  const pending=useRef<{body:BundlePublishRequest;key:string}>(undefined);
  const [status,setStatus]=useState<EnterpriseAuthorityStatus>();
  async function refresh(){try{setStatus(await client.authorityStatus());}catch(failure){setError(failure);}}
  useEffect(()=>{const abort=new AbortController();client.authorityStatus(abort.signal).then(value=>{if(!abort.signal.aborted)setStatus(value);}).catch(failure=>{if(!abort.signal.aborted)setError(failure);});return()=>abort.abort();},[client]);
  async function publish(){if(busy)return;setBusy(true);setError(undefined);setNotice('');try{
    if(!pending.current){const snapshot=await client.exportConfig();let sequence=0;try{const bundle=await client.currentBundle();const payload=JSON.parse(atob(bundle.jws.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));sequence=payload.sequence;if(!Number.isSafeInteger(sequence)||sequence<1)throw new ApiError(502,'INVALID_RESPONSE');}catch(failure){if(!(failure instanceof ApiError)||failure.status!==404)throw failure;}
      pending.current={body:{directoryRevision:snapshot.revision,expectedSequence:sequence,lifetimeMs:3600000,graceMs:0},key:crypto.randomUUID()};}
    await client.publishAccess(pending.current.body,pending.current.key);pending.current=undefined;setNotice('Snapshot published. Adoption is confirmed by each connected client acknowledgement.');await refresh();
  }catch(failure){if(failure instanceof ApiError&&failure.status===409)pending.current=undefined;setError(failure);}finally{setBusy(false);}}
  return <section className="guidance"><h2>Publish current access snapshot</h2><p>Saved changes are evaluated by current online authority. Published snapshots support discovery and distribution. Adoption requires a separate acknowledgement. Gateways use live authority for each request.</p>{status&&<><dl><dt>Saved</dt><dd>revision {status.directoryRevision}, epoch {status.authorizationEpoch}</dd><dt>Published</dt><dd>{status.publishedRevision===undefined?'No signed snapshot':`revision ${status.publishedRevision}, sequence ${status.snapshotSequence}`}</dd></dl>{status.adoptions?.length?<Table><caption>Device authority acknowledgements</caption><thead><tr><th>Device</th><th>Adopted revision / epoch</th><th>Observed</th><th>Current authority</th></tr></thead><tbody>{status.adoptions.map(a=><tr key={a.deviceId}><td>{a.deviceId}</td><td>{a.directoryRevision} / {a.authorizationEpoch}</td><td>{new Date(a.observedAtUnixMs).toISOString()}</td><td>{a.directoryRevision===status.directoryRevision&&a.authorizationEpoch===status.authorizationEpoch?'Current acknowledgement':'Behind current authority'}</td></tr>)}</tbody></Table>:<p>No device authority acknowledgements.</p>}</>}{error!==undefined&&<Failure error={error}/>}<button disabled={busy} onClick={()=>void publish()}>{busy?'Publishing…':'Publish access changes'}</button><button disabled={busy} onClick={()=>void refresh()}>Refresh activation status</button>{notice&&<p role="status">{notice}</p>}</section>;
}

type Entity='users'|'agents'|'tools'|'devices';
const groups={users:'teams',agents:'agentGroups',tools:'toolGroups',devices:'deviceGroups'} as const;
const labels={users:'Teams',agents:'Agent groups',tools:'Primary tool group',devices:'Device groups'} as const;

/** Atomic membership edits are the only access-related controls on individual records. */
export function GroupMembershipEditor({client,entity,id,close,saved}:{client:ControlClient;entity:Entity;id:string;close?:()=>void;saved:()=>void}) {
  const [current,setCurrent]=useState<GroupMembership>(),[ids,setIds]=useState<readonly string[]>([]),[error,setError]=useState<unknown>(),[busy,setBusy]=useState(false),[attempt,setAttempt]=useState(0),[reviewed,setReviewed]=useState(false);
  const pending=useRef<{body:string;key:string}>(undefined);
  useEffect(()=>{const abort=new AbortController();setError(undefined);setCurrent(undefined);client.memberships(entity,id,abort.signal).then(value=>{if(!abort.signal.aborted){if(!Array.isArray(value.groupIds)||!value.groupIds.every(v=>typeof v==='string')||!Number.isSafeInteger(value.revision))throw new ApiError(502,'INVALID_RESPONSE');setCurrent(value);setIds(value.groupIds);setReviewed(false);pending.current=undefined;}}).catch(failure=>{if(!abort.signal.aborted)setError(failure);});return()=>abort.abort();},[client,entity,id,attempt]);
  const added=ids.filter(v=>!current?.groupIds.includes(v)),removed=current?.groupIds.filter(v=>!ids.includes(v))??[];
  async function save(){if(!current||busy||!ids.length||!reviewed)return;setBusy(true);setError(undefined);const body={...current,groupIds:[...ids].sort()},encoded=JSON.stringify(body);if(pending.current?.body!==encoded)pending.current={body:encoded,key:crypto.randomUUID()};try{await client.saveMemberships(entity,body,pending.current.key);saved();close?.();}catch(failure){setError(failure);}finally{setBusy(false);}}
  const editor=<section className="detail" aria-label="Group membership">{!close&&<h2>{labels[entity]} for {id}</h2>}
    {error!==undefined&&<Failure error={error}/>} {!current&&!error&&<p role="status">Loading membership…</p>}
    {current&&<><ScopePicker client={client} kind={groups[entity]} label={labels[entity]} value={ids} onChange={value=>{setIds(value);setReviewed(false);}} single={entity==='tools'} disabled={busy}/>
      <p className="hint">{entity==='tools'?'Every Tool has exactly one primary Tool Group.':'Every record must remain in at least one group.'} Membership supplies no implicit grant.</p>
      <dl><dt>Groups added</dt><dd>{added.join(', ')||'None'}</dd><dt>Groups removed</dt><dd>{removed.join(', ')||'None'}</dd><dt>Evaluated directory revision</dt><dd>{current.revision}</dd></dl>
      <p>Review the selected groups' Roles, grants and bindings. Moving a member changes its inherited access and revokes removed paths.</p>
      <label className="checkbox"><input type="checkbox" checked={reviewed} disabled={busy} onChange={e=>setReviewed(e.target.checked)}/>I reviewed the membership changes</label>
      <button disabled={busy||!ids.length||entity==='tools'&&ids.length!==1||!reviewed} onClick={()=>void save()}>Save group membership</button></>}
    <button disabled={busy} onClick={()=>setAttempt(attempt+1)}>Reload membership</button>{current&&<EffectiveAccess client={client} entity={entity} id={id}/>}</section>;
  return close?<EditorDialog title={`${labels[entity]} for ${id}`} close={close} busy={busy} closeLabel="Close membership">{editor}</EditorDialog>:editor;
}

export function DeviceMembership({client,id,close,saved}:{client:ControlClient;id:string;close:()=>void;saved:()=>void}) {return <GroupMembershipEditor client={client} entity="devices" id={id} close={close} saved={saved}/>;}

/** The mapping window authors ordinary canonical Agent Group capabilities. */
export function AgentToolMapping({client}:{client:ControlClient}) {
  const [groups,setGroups]=useState<ControlAgentGroup[]>(),[selected,setSelected]=useState(''),[grants,setGrants]=useState<ControlAccessGrant[]>([]),[draft,setDraft]=useState<ControlAccessGrant>(),[error,setError]=useState<unknown>(),[busy,setBusy]=useState(false),[attempt,setAttempt]=useState(0),[notice,setNotice]=useState('');
  const pending=useRef<{body:string;key:string}>(undefined);const [editing,setEditing]=useState(false);
  const recordActions=useRecordActions(client,'grants',()=>setAttempt(value=>value+1));
  useEffect(()=>{const abort=new AbortController();setError(undefined);Promise.all([client.all('agentGroups',abort.signal),client.all('grants',abort.signal)]).then(([g,r])=>{if(!abort.signal.aborted){setGroups(g);setGrants(r);}}).catch(failure=>{if(!abort.signal.aborted)setError(failure);});return()=>abort.abort();},[client,attempt]);
  function create(){setEditing(false);setDraft({id:'',name:'',enabled:false,revision:1,sourceType:'AGENT_GROUP',sourceId:selected,purpose:'CAPABILITY',scope:emptyScope});setNotice('');pending.current=undefined;}
  async function save(){if(!draft||busy)return;setBusy(true);setError(undefined);const encoded=JSON.stringify(draft);if(pending.current?.body!==encoded)pending.current={body:encoded,key:crypto.randomUUID()};try{await client.saveRecord('grants',draft,editing,pending.current.key);setDraft(undefined);pending.current=undefined;setNotice('Agent Group capability saved. Current authorization checks the full Team, delegation, capability and execution binding path.');setAttempt(attempt+1);}catch(failure){setError(failure);}finally{setBusy(false);}}
  return <><div className="page-heading"><h1>Agent Group tool mapping</h1><button disabled={busy} onClick={()=>setAttempt(attempt+1)}>Refresh mappings</button></div><p>Map Agent Groups to Tool Groups through complete capabilities. Agents inherit these mappings through membership. Delegated calls also require a human grant and a delegation from the granting Team.</p>
    {error!==undefined&&!draft&&<Failure error={error}/>} {!groups&&!error&&<p role="status">Loading Agent Groups…</p>}
    {groups&&<><label>Agent Group<select value={selected} disabled={busy} onChange={e=>{setSelected(e.target.value);setDraft(undefined);}}><option value="">Select an Agent Group</option>{groups.map(g=><option key={g.id} value={g.id}>{g.name} ({g.id})</option>)}</select></label>
      {selected&&<><button disabled={busy} onClick={create}>Add capability mapping</button><div className="table-wrap"><Table disabled={busy||recordActions.busy} bulkActions={keys=>{const records=grants.filter(g=>g.sourceType==='AGENT_GROUP'&&g.sourceId===selected&&g.purpose==='CAPABILITY'&&keys.includes(g.id));return <><button disabled={busy||recordActions.busy||!records.some(g=>!g.enabled)} onClick={()=>void recordActions.run(records,'enable')}>Enable selected</button><button disabled={busy||recordActions.busy||!records.some(g=>g.enabled)} onClick={()=>void recordActions.run(records,'disable')}>Disable selected</button><button disabled={busy||recordActions.busy||!records.length} onClick={()=>recordActions.requestDelete(records)}>Delete selected</button></>;}}><caption>Capability mappings</caption><thead><tr><th>Name</th><th>Status</th><th>Revision</th><th>Actions</th></tr></thead><tbody>{grants.filter(g=>g.sourceType==='AGENT_GROUP'&&g.sourceId===selected&&g.purpose==='CAPABILITY').map(g=><tr key={g.id}><th scope="row">{g.name}</th><td data-sort-value={g.enabled?'Enabled':'Disabled'}><StatusToggle enabled={g.enabled} label={g.id} disabled={busy||recordActions.busy} onClick={()=>void recordActions.run([g],g.enabled?'disable':'enable')}/></td><td data-sort-value={g.revision}>{g.revision}</td><td><ActionButton icon="edit" label={`Edit ${g.id}`} disabled={busy||recordActions.busy} onClick={()=>{setEditing(true);setDraft(g);pending.current=undefined;}}/><ActionButton icon="delete" label={`Delete ${g.id}`} disabled={busy||recordActions.busy} onClick={()=>recordActions.requestDelete([g])}/></td></tr>)}</tbody></Table></div>{recordActions.feedback}</>}
      {draft&&<EditorDialog title={editing?'Edit capability mapping':'Add capability mapping'} close={()=>setDraft(undefined)} busy={busy}>{error!==undefined&&<Failure error={error}/>}<form className="user-form" onSubmit={e=>{e.preventDefault();void save();}}><label>Grant identifier<input required maxLength={128} disabled={busy||editing} value={draft.id} onChange={e=>setDraft({...draft,id:e.target.value})}/></label><label>Mapping name<input required value={draft.name} onChange={e=>setDraft({...draft,name:e.target.value})}/></label><label className="checkbox"><input type="checkbox" checked={draft.enabled} onChange={e=>setDraft({...draft,enabled:e.target.checked})}/>Enabled capability</label><EnterpriseScopeEditor client={client} value={draft.scope} change={scope=>setDraft({...draft,scope})} disabled={busy}/><button className="primary" disabled={busy}>Save capability mapping</button><button type="button" disabled={busy} onClick={()=>setDraft(undefined)}>Cancel mapping</button></form></EditorDialog>}
    </>}{notice&&<p role="status">{notice}</p>}<PublishAccess client={client}/></>;
}
