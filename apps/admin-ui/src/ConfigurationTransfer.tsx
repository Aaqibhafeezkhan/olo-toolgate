// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table} from './Table';
import {useRef,useState} from 'react';
import {ApiError,ControlClient} from './api';
import {Failure} from './Failure';
import {UNRESOLVED_IDENTITIES,type ConfigurationFile,type IdentityAction,type ImportIdentityOptions,type RequiredIdentity} from './pendingContracts';

const collections=['users','teams','agents','tools','policies','devices','roles','deviceGroups','agentGroups','toolGroups','grants','delegations','agentDelegations','bindings','extractors','workloadBindings','identityBindings','deviceEvidence'] as const;
/** Kinds export leaves out: users and runtime records. Replace never deletes them, so a file may omit them. */
const optionalCollections:readonly string[]=['users','identityBindings','deviceEvidence'];
const identifier=(value:unknown):value is string=>typeof value==='string'&&/^[a-zA-Z0-9][a-zA-Z0-9._:@-]{0,127}$/.test(value);
const requiredIdentity=(value:unknown):value is RequiredIdentity=>Boolean(value)&&typeof value==='object'&&identifier((value as RequiredIdentity).id)
  &&Array.isArray((value as RequiredIdentity).teamIds)&&(value as RequiredIdentity).teamIds.every(identifier);
export function completeSnapshot(value:unknown):ConfigurationFile {
  if(!value||typeof value!=='object')throw new Error('Invalid configuration');
  const snapshot=value as ConfigurationFile&Record<string,unknown>;
  if(snapshot.formatVersion!==2||typeof snapshot.tenantId!=='string'||!Number.isSafeInteger(snapshot.revision)
    ||collections.some(key=>optionalCollections.includes(key)?snapshot[key]!==undefined&&!Array.isArray(snapshot[key]):!Array.isArray(snapshot[key]))
    ||(snapshot.requiredIdentities!==undefined&&(!Array.isArray(snapshot.requiredIdentities)||!snapshot.requiredIdentities.every(requiredIdentity)))
    ||(snapshot.settings!==undefined&&(!snapshot.settings||typeof snapshot.settings!=='object')))throw new Error('Incomplete configuration');
  return snapshot;
}

type Choice={action:IdentityAction|'';target:string;teams:readonly string[]};
const actionLabels:Record<IdentityAction,string>={EXISTING:'Existing user',MAP:'Map to …',PLACEHOLDER:'Placeholder'};
/** Only resolved ids are sent; Control refuses the import and lists the rest. */
function identityOptions(identities:readonly RequiredIdentity[],choices:Readonly<Record<string,Choice>>,removeUnlistedMemberships:boolean):ImportIdentityOptions|undefined{
  if(!identities.length)return undefined;
  const identityResolution=Object.fromEntries(identities.flatMap(({id})=>{const choice=choices[id];
    if(!choice?.action||(choice.action==='MAP'&&!identifier(choice.target)))return [];
    return [[id,{action:choice.action,...(choice.action==='MAP'?{targetUserId:choice.target}:{}),teamIds:[...choice.teams]}]];}));
  return {identityResolution,removeUnlistedMemberships};
}

/** Complete configuration round trips use the same optimistic revision and reviewed import as the API. */
export function ConfigurationTransfer({client}:{client:ControlClient}){
  const [files,setFiles]=useState<File[]>([]);const [mode,setMode]=useState<'REPLACE'|'MERGE'>('MERGE');
  const [busy,setBusy]=useState(false);const [error,setError]=useState<unknown>();const [notice,setNotice]=useState('');
  const [loaded,setLoaded]=useState<ConfigurationFile>();const [existing,setExisting]=useState<readonly string[]>();
  const [choices,setChoices]=useState<Record<string,Choice>>({});const [removeUnlisted,setRemoveUnlisted]=useState(false);
  const [unresolved,setUnresolved]=useState<readonly string[]>([]);
  const [preview,setPreview]=useState<{snapshot:ConfigurationFile;identities?:ImportIdentityOptions;changes:readonly {kind:string;id:string;operation:string}[]}>();
  const [confirmed,setConfirmed]=useState(false);const locked=useRef(false);const selection=useRef(0);
  const invalidate=()=>{setPreview(undefined);setConfirmed(false);setNotice('');setError(undefined);setUnresolved([]);};
  const identities=loaded?.requiredIdentities??[];
  async function work(action:()=>Promise<void>){if(locked.current)return;locked.current=true;setBusy(true);setError(undefined);setNotice('');try{await action();}catch(failure){
    if(failure instanceof ApiError&&failure.status===409&&failure.code===UNRESOLVED_IDENTITIES){setUnresolved(failure.ids);setPreview(undefined);setConfirmed(false);}else setError(failure);}finally{locked.current=false;setBusy(false);}}
  async function exportAll(){await work(async()=>{
    const snapshot=completeSnapshot(await client.exportConfiguration());
    const url=URL.createObjectURL(new Blob([JSON.stringify(snapshot,null,2)+'\n'],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download=`toolgate-${snapshot.tenantId}-configuration-r${snapshot.revision}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
    setNotice('Configuration exported, including server settings. Users are not exported: the file lists the user IDs it references and their teams, which you resolve when importing. Credentials and private keys are never exported.');
  });}
  /** A single complete file is read at once so its required identities can be resolved before preview. */
  async function choose(selected:File[]){
    invalidate();setFiles(selected);setLoaded(undefined);setChoices({});setRemoveUnlisted(false);const current=++selection.current;
    if(selected.length!==1||selected[0].size>2*1024*1024)return;
    let value:unknown;try{value=JSON.parse(await selected[0].text());}catch{return;}
    if(!value||typeof value!=='object'||!('formatVersion' in value))return;
    let snapshot:ConfigurationFile;try{snapshot=completeSnapshot(value);}catch{return;}
    if(current!==selection.current)return;
    setLoaded(snapshot);const required=snapshot.requiredIdentities??[];if(!required.length)return;
    let users:readonly string[]|undefined;try{users=(await client.all('users')).map(user=>user.id);}catch{users=undefined;}
    if(current!==selection.current)return;
    setExisting(users);
    setChoices(Object.fromEntries(required.map(entry=>[entry.id,{action:users?.includes(entry.id)?'EXISTING':'',target:'',teams:[...entry.teamIds]}])));
  }
  const updateChoice=(id:string,change:Partial<Choice>)=>{setChoices(previous=>({...previous,[id]:{...previous[id],...change}}));setPreview(undefined);setConfirmed(false);setUnresolved(previous=>previous.filter(value=>value!==id));};
  async function previewImport(){await work(async()=>{
    if(!files.length||files.reduce((size,file)=>size+file.size,0)>2*1024*1024){setNotice('Choose JSON configuration files totaling no more than 2 MiB.');return;}
    let values:unknown[];
    try{values=await Promise.all(files.map(async file=>JSON.parse(await file.text()) as unknown));}
    catch{setNotice('The selected files must contain valid JSON.');return;}
    let snapshot:ConfigurationFile;
    if(values.length===1&&values[0]&&typeof values[0]==='object'&&'formatVersion' in values[0]){
      try{snapshot=completeSnapshot(values[0]);}catch{setNotice('The file must contain every exported configuration collection. Export a complete configuration first.');return;}
    }else{
      if(mode!=='MERGE'){setNotice('Choose Merge when importing the files from an initial configuration bundle.');return;}
      const base:Record<string,unknown>={...await client.exportConfiguration()};delete base.requiredIdentities;delete base.settings;const seen=new Set<string>();
      for(const value of values){
        if(!value||typeof value!=='object'){setNotice('Invalid initial configuration file.');return;}
        if('bundleId' in value)continue;
        for(const [key,rows] of Object.entries(value)){
          if(!collections.includes(key as typeof collections[number])||!Array.isArray(rows)||seen.has(key)){setNotice('Use distinct configuration collection files or one complete exported file.');return;}
          seen.add(key);const current=(base[key]??[]) as readonly {id:string}[];
          const merged=new Map<string,unknown>(current.map(row=>[row.id,row]));
          for(const row of rows){
            if(!row||typeof row.id!=='string'){setNotice('Every configuration record needs an identifier.');return;}
            const existingRow=merged.get(row.id) as Record<string,unknown>|undefined;
            // Initial preset files change permission definitions; retain established entity memberships.
            const membership=existingRow&&(['userIds','agentIds','deviceIds','toolIds'] as const).find(field=>field in existingRow);
            merged.set(row.id,{...row,...(membership?{[membership]:existingRow[membership]}:{})});
          }
          base[key]=[...merged.values()];
        }
      }
      snapshot=base as unknown as ConfigurationFile;
    }
    const options=identityOptions(snapshot.requiredIdentities??[],choices,removeUnlisted);
    const result=await client.previewConfiguration(snapshot,mode,options);setPreview({snapshot,identities:options,changes:result.changes});setConfirmed(false);setUnresolved([]);
  });}
  const pendingChoices=identities.filter(({id})=>{const choice=choices[id];return !choice?.action||(choice.action==='MAP'&&!identifier(choice.target));}).length;
  return <section className="guidance"><h2>Import and export configuration</h2>
    <p>Export the configuration, edit its JSON, and import it back here or on another server. The file holds server settings and every non-secret definition. Users, identity bindings, device evidence and secrets are never exported.</p>
    <button disabled={busy} onClick={()=>void exportAll()}>Export complete configuration</button>
    <label>Configuration JSON files<input type="file" accept=".json,application/json" multiple disabled={busy} onChange={event=>void choose(Array.from(event.target.files??[]))}/></label>
    <fieldset disabled={busy}><legend>Import mode</legend>
      <label className="checkbox"><input type="radio" name="import-mode" value="MERGE" checked={mode==='MERGE'} onChange={()=>{invalidate();setMode('MERGE');}}/> Merge: keep existing records and update those with the same ID</label>
      <label className="checkbox"><input type="radio" name="import-mode" value="REPLACE" checked={mode==='REPLACE'} onChange={()=>{invalidate();setMode('REPLACE');}}/> Replace: also delete records of the exported kinds that the file does not list</label>
      <p className="hint">Replace never deletes users, team memberships, identity bindings, device evidence, the administrator and reviewer accounts or the default groups. Control refuses a Replace that would leave a kept record pointing at a deleted one.</p>
    </fieldset>
    {loaded?.settings&&<p className="notice" role="note">This file includes server settings. Importing it applies them, so only a Super Admin can import it.</p>}
    {identities.length>0&&<fieldset disabled={busy} aria-describedby="required-identities-help"><legend>Required identities</legend>
      <p id="required-identities-help" className="hint">Records in this file refer to these user IDs. Choose how each one is resolved on this server: an existing user with the same ID, another user it maps to, or a disabled placeholder with no credentials. The user is added to the ticked teams; teams they already belong to are kept. Delegated agent bindings for mapped or placeholder users arrive disabled and need reauthorization.</p>
      {existing===undefined&&<p className="hint">The user list could not be loaded; Control still checks each choice.</p>}
      <div className="table-wrap"><Table><caption className="sr-only">Required identities</caption><thead><tr><th scope="col">User ID</th><th scope="col">Resolution</th><th scope="col">Teams in the backup</th></tr></thead><tbody>{identities.map(entry=>{const choice=choices[entry.id]??{action:'',target:'',teams:[]};const missing=unresolved.includes(entry.id);
        return <tr key={entry.id}><th scope="row"><code>{entry.id}</code>{missing&&<> <span className="badge warning">Unresolved</span></>}{existing&&!existing.includes(entry.id)&&<small>Not on this server</small>}</th>
          <td><select aria-label={`Resolution for ${entry.id}`} value={choice.action} onChange={event=>updateChoice(entry.id,{action:event.target.value as Choice['action']})}><option value="">Choose…</option>{(Object.keys(actionLabels) as IdentityAction[]).map(action=><option key={action} value={action} disabled={action==='EXISTING'&&existing!==undefined&&!existing.includes(entry.id)}>{actionLabels[action]}</option>)}</select>
            {choice.action==='MAP'&&<><label htmlFor={`map-${entry.id}`} className="sr-only">Map {entry.id} to user</label><input id={`map-${entry.id}`} list="import-users" placeholder="Existing user ID" maxLength={128} value={choice.target} onChange={event=>updateChoice(entry.id,{target:event.target.value.trim()})}/></>}</td>
          <td data-sort-value={entry.teamIds.join(' ')}>{entry.teamIds.length?entry.teamIds.map(team=><label className="checkbox" key={team}><input type="checkbox" checked={choice.teams.includes(team)} onChange={event=>updateChoice(entry.id,{teams:event.target.checked?[...choice.teams,team]:choice.teams.filter(value=>value!==team)})}/> Add to {team}</label>):'No teams'}</td></tr>;})}</tbody></Table></div>
      {existing&&<datalist id="import-users">{existing.map(id=><option key={id} value={id}/>)}</datalist>}
      <label className="checkbox"><input type="checkbox" checked={removeUnlisted} onChange={event=>{setRemoveUnlisted(event.target.checked);setPreview(undefined);setConfirmed(false);}}/> Also remove from teams not in the backup</label>
      <p className="hint">Off by default. When on, each resolved user also leaves every team on this server that the backup does not list for them.</p>
      {pendingChoices>0&&<p role="status">{pendingChoices===1?'1 identity still needs a choice.':`${pendingChoices} identities still need a choice.`}</p>}
    </fieldset>}
    {unresolved.length>0&&<div className="notice error" role="alert"><strong>Import refused: every required identity must be resolved first.</strong><span> Unresolved: {unresolved.join(', ')}.</span></div>}
    <button disabled={busy||!files.length} onClick={()=>void previewImport()}>Preview import</button>
    {preview&&<><p>Proposed changes: {preview.changes.length}</p><div className="table-wrap"><Table><thead><tr><th>Kind</th><th>Identifier</th><th>Change</th></tr></thead><tbody>{preview.changes.map(change=><tr key={change.kind+':'+change.id}><td>{change.kind}</td><td>{change.id}</td><td>{change.operation}</td></tr>)}</tbody></Table></div>
      {preview.identities?.removeUnlistedMemberships&&<p className="hint">Resolved users are removed from teams the backup does not list for them.</p>}
      <label><input type="checkbox" disabled={busy} checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/> I reviewed all additions, updates, removals, and group mappings.</label>
      <button disabled={busy||!confirmed||!preview.changes.length} onClick={()=>void work(async()=>{await client.importConfiguration(preview.snapshot,mode,crypto.randomUUID(),preview.identities);setPreview(undefined);setConfirmed(false);setNotice('Configuration imported.');})}>Import configuration</button></>}
    {notice&&<p role="status">{notice}</p>}{error!==undefined&&<Failure error={error}/>}</section>;
}
