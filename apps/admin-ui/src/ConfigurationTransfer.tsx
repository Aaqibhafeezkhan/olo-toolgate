// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table} from './Table';
import {useRef,useState} from 'react';
import type {ControlSnapshot} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';

const collections=['users','teams','agents','tools','policies','devices','roles','deviceGroups','agentGroups','toolGroups','grants','delegations','agentDelegations','bindings','extractors','workloadBindings','identityBindings','deviceEvidence'] as const;
export function completeSnapshot(value:unknown):ControlSnapshot {
  if(!value||typeof value!=='object')throw new Error('Invalid configuration');
  const snapshot=value as ControlSnapshot;
  if(snapshot.formatVersion!==2||typeof snapshot.tenantId!=='string'||!Number.isSafeInteger(snapshot.revision)||collections.some(key=>!Array.isArray(snapshot[key])))throw new Error('Incomplete configuration');
  return snapshot;
}

/** Complete configuration round trips use the same optimistic revision and reviewed import as the API. */
export function ConfigurationTransfer({client}:{client:ControlClient}){
  const [files,setFiles]=useState<File[]>([]);const [mode,setMode]=useState<'REPLACE'|'MERGE'>('REPLACE');
  const [busy,setBusy]=useState(false);const [error,setError]=useState<unknown>();const [notice,setNotice]=useState('');
  const [preview,setPreview]=useState<{snapshot:ControlSnapshot;changes:readonly {kind:string;id:string;operation:string}[]}>();
  const [confirmed,setConfirmed]=useState(false);const locked=useRef(false);
  const invalidate=()=>{setPreview(undefined);setConfirmed(false);setNotice('');setError(undefined);};
  async function work(action:()=>Promise<void>){if(locked.current)return;locked.current=true;setBusy(true);setError(undefined);setNotice('');try{await action();}catch(failure){setError(failure);}finally{locked.current=false;setBusy(false);}}
  async function exportAll(){await work(async()=>{
    const snapshot=completeSnapshot(await client.exportConfig());
    const url=URL.createObjectURL(new Blob([JSON.stringify(snapshot,null,2)+'\n'],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download=`toolgate-${snapshot.tenantId}-configuration-r${snapshot.revision}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
    setNotice('Complete access configuration exported. Keep the tenant, revision, and all collections when editing. Credentials and private keys are held separately.');
  });}
  async function previewImport(){await work(async()=>{
    if(!files.length||files.reduce((size,file)=>size+file.size,0)>2*1024*1024){setNotice('Choose JSON configuration files totaling no more than 2 MiB.');return;}
    let values:unknown[];
    try{values=await Promise.all(files.map(async file=>JSON.parse(await file.text()) as unknown));}
    catch{setNotice('The selected files must contain valid JSON.');return;}
    let snapshot:ControlSnapshot;
    if(values.length===1&&values[0]&&typeof values[0]==='object'&&'formatVersion' in values[0]){
      try{snapshot=completeSnapshot(values[0]);}catch{setNotice('The snapshot must contain every access configuration collection. Export a complete snapshot first.');return;}
    }else{
      if(mode!=='MERGE'){setNotice('Choose Merge when importing the files from an initial configuration bundle.');return;}
      snapshot={...await client.exportConfig()};const seen=new Set<string>();
      for(const value of values){
        if(!value||typeof value!=='object'){setNotice('Invalid initial configuration file.');return;}
        if('bundleId' in value)continue;
        for(const [key,rows] of Object.entries(value)){
          if(!collections.includes(key as typeof collections[number])||!Array.isArray(rows)||seen.has(key)){setNotice('Use distinct configuration collection files or one complete exported snapshot.');return;}
          seen.add(key);const current=snapshot[key as typeof collections[number]];
          const merged=new Map(current.map(row=>[row.id,row]));
          for(const row of rows){
            if(!row||typeof row.id!=='string'){setNotice('Every configuration record needs an identifier.');return;}
            const existing=merged.get(row.id);
            // Initial preset files change permission definitions; retain established entity memberships.
            const membership=existing&&(['userIds','agentIds','deviceIds','toolIds'] as const).find(field=>field in existing);
            merged.set(row.id,{...row,...(membership?{[membership]:(existing as unknown as Record<string,unknown>)[membership]}:{})});
          }
          Object.assign(snapshot,{[key]:[...merged.values()]});
        }
      }
    }
    const result=await client.previewConfiguration(snapshot,mode);setPreview({snapshot,changes:result.changes});setConfirmed(false);
  });}
  return <section className="guidance"><h2>Import and export configuration</h2>
    <p>Export the complete access configuration, edit its JSON, and import it back. Replace includes removals; Merge retains records absent from the files. A full snapshot includes all memberships. Initial bundle files preserve existing memberships.</p>
    <button disabled={busy} onClick={()=>void exportAll()}>Export complete configuration</button>
    <label>Configuration JSON files<input type="file" accept=".json,application/json" multiple disabled={busy} onChange={event=>{invalidate();setFiles(Array.from(event.target.files??[]));}}/></label>
    <label>Import mode<select value={mode} disabled={busy} onChange={event=>{invalidate();setMode(event.target.value as 'MERGE'|'REPLACE');}}><option value="REPLACE">Replace complete configuration</option><option value="MERGE">Merge configuration records</option></select></label>
    <button disabled={busy||!files.length} onClick={()=>void previewImport()}>Preview import</button>
    {preview&&<><p>Proposed changes: {preview.changes.length}</p><div className="table-wrap"><Table><thead><tr><th>Kind</th><th>Identifier</th><th>Change</th></tr></thead><tbody>{preview.changes.map(change=><tr key={change.kind+':'+change.id}><td>{change.kind}</td><td>{change.id}</td><td>{change.operation}</td></tr>)}</tbody></Table></div>
      <label><input type="checkbox" disabled={busy} checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/> I reviewed all additions, updates, removals, and group mappings.</label>
      <button disabled={busy||!confirmed||!preview.changes.length} onClick={()=>void work(async()=>{await client.importConfiguration(preview.snapshot,mode,crypto.randomUUID());})}>Create import for independent review</button></>}
    {notice&&<p role="status">{notice}</p>}{error!==undefined&&<Failure error={error}/>}</section>;
}
