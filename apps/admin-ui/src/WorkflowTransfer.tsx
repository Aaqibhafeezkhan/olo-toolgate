// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useState} from 'react';
import type {DeploymentProfile,WorkflowProfileStatus} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from './api';
import {referencedData} from './workflowReferences';
import {exportWorkflow,importWorkflow,workflowConnections,workflowScope,connectionNames,type WorkflowBundle} from './workflowBundle';
import {inspectPackage,MAX_BUNDLE_BYTES,requiredPackages} from './workflowPackages';
import {BotImportError,prepareBotImport,triggerCatalog,type ImportStep} from './botImport';

/** File transfer only uses existing Control profile operations. It never calls the Docker operator. */
export function WorkflowTransfer({client,profile,allowNew=false,busy,run,stored}:{client:ControlClient;profile?:DeploymentProfile;allowNew?:boolean;busy:boolean;run:(action:()=>Promise<void>)=>Promise<void>;stored:(profile:DeploymentProfile,status:WorkflowProfileStatus)=>Promise<void>}){
  const [selected,setSelected]=useState(''),[target,setTarget]=useState(''),[file,setFile]=useState<File>();
  const [bundle,setBundle]=useState<WorkflowBundle>(),[owners,setOwners]=useState<Record<string,string>>({});
  const [progress,setProgress]=useState<ImportStep>(),[prepared,setPrepared]=useState<WorkflowProfileStatus>();
  const [pending,setPending]=useState(false);
  const workflows=profile?.workflows?.workflows??[];
  const exportId=workflows.some(item=>item.id===selected)?selected:workflows[0]?.id??'';
  return <section className="guidance" aria-label="Bot bundles"><h2>Bot import / export (.olo)</h2>
    <p>Transfer one bot, its referenced configuration and exact tool/trigger .olotool packages. Import prepares its referenced data, embedded packages, trigger connections and graph together. Model services, vault secrets and approved device access belong to the destination installation. Containers and login credentials stay unchanged.</p>
    {['127.0.0.1','localhost','[::1]'].includes(location.hostname)&&<p><a href="http://127.0.0.1:48190/operator" target="_blank" rel="noopener">Factory reset / deployment operator</a></p>}
    <fieldset disabled={busy}><legend>Export a bot</legend><label>Bot to export<select value={exportId} onChange={event=>setSelected(event.target.value)}>{workflows.map(item=><option key={item.id} value={item.id}>{item.id}</option>)}</select></label>
      <button disabled={!exportId} onClick={()=>void run(async()=>{const [sources,snapshot,catalog]=await Promise.all([triggerCatalog(client),client.exportConfiguration(),client.serverPackages()]);const selected=workflowScope(profile!,exportId,connectionNames(profile!,sources.assignments));const needed=requiredPackages(selected,referencedData(selected,snapshot),workflowConnections(selected,sources.assignments,sources.subscriptions),catalog.items);const archives=[];let total=0;for(const item of needed){const archive=await client.exportServerPackage(item.packageDigest);if((total+=archive.length)>MAX_BUNDLE_BYTES)throw new ApiError(413,'WORKFLOW_BUNDLE_LIMIT');if((await inspectPackage(archive)).metadata.packageDigest!==item.packageDigest)throw new ApiError(400,'PACKAGE_DIGEST');archives.push(archive);}const bytes=await exportWorkflow(profile!,exportId,sources.assignments,sources.subscriptions,snapshot,archives);const url=URL.createObjectURL(new Blob([new Uint8Array(bytes).buffer],{type:'application/zip'}));const link=document.createElement('a');link.href=url;link.download=exportId+'.olo';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);})}>Export bot .olo</button>
    </fieldset>
    <fieldset disabled={busy}><legend>Import or replace a bot</legend><label>Bot .olo file<input type="file" accept=".olo" onChange={event=>{setFile(event.currentTarget.files?.[0]);event.currentTarget.value='';}}/></label>{file&&<p>Selected: {file.name}</p>}
      <label>Import destination<select value={target} onChange={event=>setTarget(event.target.value)}><option value="">Add, or replace the matching bot ID</option>{workflows.map(item=><option key={item.id} value={item.id}>Replace {item.id}</option>)}</select></label>
      <button disabled={!file||!profile&&!allowNew} onClick={()=>void run(async()=>{
        setPrepared(undefined);setProgress(undefined);
        if(!file||file.size>MAX_BUNDLE_BYTES)throw new BotImportError('bundle');
        let incoming:WorkflowBundle;
        try{incoming=await importWorkflow(file.name,new Uint8Array(await file.arrayBuffer()));}catch{throw new BotImportError('bundle');}
        setBundle(incoming);
        const base=profile??{profileVersion:2,profileId:incoming.profile.profileId,packages:[],workflows:{workflowGroups:[],modelGroups:[],modelDeployments:[],agentDefinitions:[],workflows:[],dispatchBindings:[]}};
        const result=await prepareBotImport(client,incoming,base,target||undefined,{owners,progress:setProgress,stored});
        setPrepared(result.status);setPending(result.pending);if(!result.pending)setFile(undefined);setProgress(undefined);
      })}>{pending?'Resume bot preparation':'Import bot .olo'}</button>
      <p>Import verifies embedded packages, merges referenced configuration, creates destination service bindings, provisions packages, restores trigger connections and prepares the graph. Review the resulting revision and publish when ready. Existing users, credentials, device membership and device file contents are preserved.</p>
      {progress&&busy&&<p role="status">Preparing bot: {progress}…</p>}
      {bundle&&(bundle.references.requiredIdentities??[]).map(owner=><label key={owner.id}>Existing user for bot owner {owner.id}<input value={owners[owner.id]??owner.id} onChange={event=>setOwners({...owners,[owner.id]:event.target.value})}/></label>)}
      {prepared&&<p role="status">{pending?'Bot imported with its configuration and packages. Device/package readiness is pending. Enable a device in the declared groups, then resume preparation.':prepared.dependenciesReady?'Bot imported with its configuration and packages. Ready for publication.':'Bot imported and staged. Complete the destination model secret, device and runtime readiness before publishing.'}</p>}
    </fieldset>
  </section>;
}
