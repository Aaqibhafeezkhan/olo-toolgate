// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import type {DeploymentProfile,TriggerAssignment,TriggerSubscription} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ConfigurationPending,ControlClient} from './api';
import type {ConfigurationFile,ImportIdentityOptions} from './pendingContracts';
import {mergeReferences,referencedData} from './workflowReferences';
import {applyWorkflow,exportWorkflow,importWorkflow,workflowConnections,workflowScope,workflowAssignmentMatches,type WorkflowBundle} from './workflowBundle';
import {inspectPackage,MAX_BUNDLE_BYTES,requiredPackages} from './workflowPackages';

async function triggers(client:ControlClient){
  const assignments:TriggerAssignment[]=[];let token:string|undefined;
  do{const page=await client.triggerAssignments(token);assignments.push(...page.items);token=page.nextPageToken;if(assignments.length>1000)throw Error('Trigger catalog exceeds the transfer limit.');}while(token);
  const subscriptions:readonly TriggerSubscription[]=(await client.triggerSubscriptions()).items;
  return {assignments,subscriptions};
}

/** File transfer only uses existing Control profile operations. It never calls the Docker operator. */
export function WorkflowTransfer({client,profile,allowNew=false,busy,run,replace}:{client:ControlClient;profile?:DeploymentProfile;allowNew?:boolean;busy:boolean;run:(action:()=>Promise<void>)=>Promise<void>;replace:(profile:DeploymentProfile)=>Promise<void>}){
  const [selected,setSelected]=useState(''),[target,setTarget]=useState(''),[file,setFile]=useState<File>();
  const [imported,setImported]=useState<{bundle:WorkflowBundle;targetId:string}>(),[referencePreview,setReferencePreview]=useState<{snapshot:ConfigurationFile;options:ImportIdentityOptions;changes:number}>(),[referenceChange,setReferenceChange]=useState(''),[ownerMappings,setOwnerMappings]=useState<Record<string,string>>({});
  const workflows=profile?.workflows?.workflows??[];
  useEffect(()=>{if(imported&&profile&&!profile.workflows?.workflows.some(item=>item.id===imported.targetId)){setImported(undefined);setReferencePreview(undefined);setReferenceChange('');}},[profile,imported]);
  const exportId=workflows.some(item=>item.id===selected)?selected:workflows[0]?.id??'';
  return <section className="guidance" aria-label="Bot bundles"><h2>Bot import / export (.olo)</h2>
    <p>Transfer one bot, its referenced configuration and exact tool/trigger .olotool packages. Import verifies and stores its embedded packages through Control before staging the bot. Deployment, graph preparation, model services and vault secrets still require destination readiness. Containers and login credentials stay unchanged.</p>
    {['127.0.0.1','localhost','[::1]'].includes(location.hostname)&&<p><a href="http://127.0.0.1:48190/operator" target="_blank" rel="noopener">Factory reset / deployment operator</a></p>}
    <fieldset disabled={busy}><legend>Export a bot</legend><label>Bot to export<select value={exportId} onChange={event=>setSelected(event.target.value)}>{workflows.map(item=><option key={item.id} value={item.id}>{item.id}</option>)}</select></label>
      <button disabled={!exportId} onClick={()=>void run(async()=>{const [sources,snapshot,catalog]=await Promise.all([triggers(client),client.exportConfiguration(),client.serverPackages()]);const selected=workflowScope(profile!,exportId);const needed=requiredPackages(selected,referencedData(selected,snapshot),workflowConnections(selected,sources.assignments,sources.subscriptions),catalog.items);const archives=[];let total=0;for(const item of needed){const archive=await client.exportServerPackage(item.packageDigest);if((total+=archive.length)>MAX_BUNDLE_BYTES)throw new ApiError(413,'WORKFLOW_BUNDLE_LIMIT');if((await inspectPackage(archive)).metadata.packageDigest!==item.packageDigest)throw new ApiError(400,'PACKAGE_DIGEST');archives.push(archive);}const bytes=await exportWorkflow(profile!,exportId,sources.assignments,sources.subscriptions,snapshot,archives);const url=URL.createObjectURL(new Blob([new Uint8Array(bytes).buffer],{type:'application/zip'}));const link=document.createElement('a');link.href=url;link.download=exportId+'.olo';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);})}>Export bot .olo</button>
    </fieldset>
    <fieldset disabled={busy}><legend>Import or replace a bot</legend><label>Bot .olo file<input type="file" accept=".olo" onChange={event=>{setFile(event.currentTarget.files?.[0]);event.currentTarget.value='';}}/></label>{file&&<p>Selected: {file.name}</p>}
      <label>Import destination<select value={target} onChange={event=>setTarget(event.target.value)}><option value="">Add, or replace the matching bot ID</option>{workflows.map(item=><option key={item.id} value={item.id}>Replace {item.id}</option>)}</select></label>
      <button disabled={!file||!profile&&!allowNew} onClick={()=>void run(async()=>{
        if(!file||file.size>MAX_BUNDLE_BYTES)throw new ApiError(413,'WORKFLOW_BUNDLE_LIMIT');
        let bundle:WorkflowBundle;try{bundle=await importWorkflow(file.name,new Uint8Array(await file.arrayBuffer()));}catch{throw new ApiError(400,'INVALID_WORKFLOW_BUNDLE');}const sources=await triggers(client);
        const base=profile??{profileVersion:2,profileId:bundle.profile.profileId,packages:[],workflows:{workflowGroups:[],modelGroups:[],modelDeployments:[],agentDefinitions:[],workflows:[],dispatchBindings:[]}};
        const candidate=applyWorkflow(base,bundle,target||undefined,sources.assignments,sources.subscriptions,true);
        for(const item of bundle.packages){
          const stored=await client.uploadServerPackage(new File([new Uint8Array(item.bytes).buffer],item.metadata.packageId+'.olotool'));
          if(stored.packageDigest!==item.metadata.packageDigest||stored.packageId!==item.metadata.packageId||stored.version!==item.metadata.version||stored.state!=='VERIFIED')throw new ApiError(400,'PACKAGE_DIGEST');
        }
        await replace(candidate);
        setImported({bundle,targetId:target||bundle.profile.workflows!.workflows[0].id});setFile(undefined);setReferencePreview(undefined);setReferenceChange('');setOwnerMappings({});
      })}>Import bot .olo</button>
      <p>Replacement refreshes the editor after Control stages the new revision. Review and publish it to activate it. Removing a bot prunes its unshared bot definitions; other bots and shared definitions remain.</p>
    </fieldset>
    {imported&&<section aria-label="Imported bot dependencies"><h3>Referenced configuration</h3>
      <p>{imported.bundle.packages.length} embedded tool/trigger packages verified and stored through Control. This does not deploy or authorize execution.</p>
      <p>The bot is staged. Existing references stay in place. Review missing directory configuration and restore its trigger connections before publication. Provider secrets, approved devices and exact runtime graph packages must be configured on this system.</p>
      {(imported.bundle.references.requiredIdentities??[]).map(owner=><label key={owner.id}>Existing user for owner {owner.id}<input value={ownerMappings[owner.id]??owner.id} onChange={event=>{setOwnerMappings({...ownerMappings,[owner.id]:event.target.value});setReferencePreview(undefined);}}/></label>)}
      <button disabled={busy} onClick={()=>void run(async()=>{const current=await client.exportConfiguration();const snapshot=mergeReferences(imported.bundle.references,current);const options:ImportIdentityOptions={removeUnlistedMemberships:false,identityResolution:Object.fromEntries((snapshot.requiredIdentities??[]).map(item=>{const mapped=ownerMappings[item.id]??item.id;return [item.id,{action:mapped===item.id?'EXISTING':'MAP',...(mapped===item.id?{}:{targetUserId:mapped}),teamIds:[]}];}))};const result=await client.previewConfiguration(snapshot,'MERGE',options);setReferencePreview({snapshot,options,changes:result.changes.length});})}>Preview referenced data</button>
      {referencePreview&&<><p>{referencePreview.changes} referenced configuration changes. Existing accounts and credentials are excluded.</p>{referencePreview.changes>0&&<button disabled={busy} onClick={()=>void run(async()=>{try{await client.importConfiguration(referencePreview.snapshot,'MERGE',crypto.randomUUID(),referencePreview.options);setReferencePreview(undefined);}catch(error){if(error instanceof ConfigurationPending){setReferenceChange(error.changeId);setReferencePreview(undefined);}else throw error;}})}>Prepare referenced data for review</button>}</>}
      {referenceChange&&<p>Review and apply the configuration change: <a href={`#config-reviews?id=${encodeURIComponent(referenceChange)}`}>{referenceChange}</a>. Then return here and refresh lifecycle status.</p>}
      <button disabled={busy} onClick={()=>void run(async()=>{
        let sources=await triggers(client);
        for(const connection of imported.bundle.connections){
          const existing=sources.assignments.find(item=>item.metadata.name===connection.assignment);
          const managed=imported.bundle.profile.packages.some(item=>item.triggers?.some(trigger=>trigger.assignment===connection.assignment));
          if(managed&&!existing)throw Error('Review this bot and provision its packages first so Control can select the destination device. Then restore trigger connections.');
          if(existing&&!workflowAssignmentMatches(imported.bundle.profile,existing,connection.assignmentSpec))throw Error('An existing trigger assignment differs; resolve it before restoration.');
          if(!existing){await client.createTriggerAssignment({name:connection.assignment,spec:connection.assignmentSpec},crypto.randomUUID());sources=await triggers(client);}
          const subscription=sources.subscriptions.find(item=>item.metadata.name===connection.subscription);
          if(subscription&&(subscription.spec.filter!==connection.subscriptionSpec.filter||!connection.subscriptionSpec.sources.assignments.every(name=>subscription.spec.sources.assignments.includes(name))))throw Error('An existing subscription differs; shared subscriptions are not overwritten.');
          if(!subscription){const spec={...connection.subscriptionSpec,expiresAtUnixMs:Date.now()+30*86400000};await client.createTriggerSubscription({name:connection.subscription,spec},crypto.randomUUID());sources=await triggers(client);}
        }
        if(!profile)throw Error('Open the staged bot first.');await replace(applyWorkflow(profile,imported.bundle,imported.targetId,sources.assignments,sources.subscriptions));
      })}>Restore trigger connections and stage mappings</button>
    </section>}
  </section>;
}
