// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState,type FormEvent} from 'react';
import type {SdkBuilderGroup,SdkBuilderDraft,SdkBuilderBuild} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {EditorDialog} from './EditorDialog';
import {usePermissions} from './Permissions';

declare const __APP_VERSION__:string;
function example(sdkVersion=__APP_VERSION__){return JSON.stringify({schemaVersion:1,sdkVersion,id:'custom.hello-world',name:'Hello World',publisher:'custom',version:'1.0.0',tools:[{
 name:'hello',kind:'SERVER',title:'Hello World',description:'Greet someone',inputs:{text:{type:'STRING',description:'Who to greet',default:'World',maxLength:128}},
 output:{message:{concat:[{literal:'Hello '},{input:'text'}]}},events:[{level:'info',message:'Producing greeting'},{fraction:1,message:'Greeting complete'}]
}]},null,2);}
const failureMessages:Record<string,string>={BUILD_FAILED:'The SDK rejected this build. Check the project and examples.',AUTHORITY_REVOKED:'Build permission was revoked.',EXPIRED:'The build expired. Queue a new build.',VERSION_CONFLICT:'This package version already has different contents. Increase the version and build again.'};
function saveBlob(blob:Blob,name:string){const url=URL.createObjectURL(blob),anchor=document.createElement('a');anchor.href=url;anchor.download=name;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}

/** Forms, editor and downloads use one canonical JSON SDK project. Runtime tests happen after governed installation. */
export function Builder({client}:{client:ControlClient}){
 const {can}=usePermissions(client),canBuild=can('tool_builder.build');
 const [drafts,setDrafts]=useState<readonly SdkBuilderDraft[]>([]),[builds,setBuilds]=useState<readonly SdkBuilderBuild[]>([]),[groups,setGroups]=useState<readonly SdkBuilderGroup[]>([]);
 const [sdkVersion,setSdkVersion]=useState(__APP_VERSION__),[configured,setConfigured]=useState(false);
 const [selected,setSelected]=useState<SdkBuilderDraft>(),[source,setSource]=useState(()=>example()),[group,setGroup]=useState('default-tools');
 const [id,setId]=useState<string>(()=>crypto.randomUUID()),[open,setOpen]=useState(false),[busy,setBusy]=useState(false),[loading,setLoading]=useState(true);
 const [error,setError]=useState<unknown>(),[message,setMessage]=useState(''),[validation,setValidation]=useState(''),[refresh,setRefresh]=useState(0);
 const [draftCursor,setDraftCursor]=useState<string>(),[buildCursor,setBuildCursor]=useState<string>();
 useEffect(()=>{const abort=new AbortController();void Promise.all([client.builderDrafts(undefined,abort.signal),client.builderBuilds(undefined,abort.signal),client.sdkBuilderStatus(abort.signal)]).then(([projects,jobs,status])=>{
  setDrafts(projects.items);setBuilds(jobs.items);setGroups(status.groups);setConfigured(status.configured);setSdkVersion(status.sdkVersion);setDraftCursor(projects.nextCursor);setBuildCursor(jobs.nextCursor);
 }).catch(failure=>{if(!abort.signal.aborted)setError(failure);}).finally(()=>{if(!abort.signal.aborted)setLoading(false);});return()=>abort.abort();},[client,refresh]);
 const pending=builds.some(build=>build.state==='QUEUED'||build.state==='RUNNING');
 useEffect(()=>{if(!pending)return;const abort=new AbortController();let active=false;const timer=setInterval(()=>{if(active)return;active=true;void client.builderBuilds(undefined,abort.signal).then(page=>setBuilds(current=>{
  const latest=new Map(current.map(build=>[build.id,build]));for(const build of page.items)latest.set(build.id,build);return [...latest.values()];
 })).catch(failure=>{if(!abort.signal.aborted)setError(failure);}).finally(()=>{active=false;});},2000);return()=>{clearInterval(timer);abort.abort();};},[client,pending]);
 async function action(run:()=>Promise<void>){if(busy)return;setBusy(true);setError(undefined);setValidation('');try{await run();}catch(failure){setError(failure);}finally{setBusy(false);}}
 function create(){setSelected(undefined);setId(crypto.randomUUID());setSource(example(sdkVersion));setGroup(groups.find(item=>item.canBuild)?.id??'default-tools');setMessage('');setValidation('');setError(undefined);setOpen(true);}
 function edit(draft:SdkBuilderDraft){setSelected(draft);setId(draft.id);setSource(draft.source);setGroup(draft.toolGroupId);setMessage('');setValidation('');setError(undefined);setOpen(true);}
 function save(event:FormEvent){event.preventDefault();try{JSON.parse(source);}catch{setValidation('Enter a valid JSON SDK project.');return;}void action(async()=>{
  const draft=await client.saveSdkDraft({id,expectedRevision:selected?.revision??0,toolGroupId:group,source},crypto.randomUUID());setSelected(draft);setSource(draft.source);setRefresh(value=>value+1);setMessage(`Project saved at revision ${draft.revision}.`);
 });}
 const dirty=!selected||selected.source!==source||selected.toolGroupId!==group;
 const mayAuthor=canBuild&&Boolean(groups.find(item=>item.id===group)?.canBuild);
 return <><p className="eyebrow">SDK packages</p><div className="page-heading"><h1>Tool Builder</h1><button className="primary" disabled={busy||!canBuild||!groups.some(item=>item.canBuild)} onClick={create}>Add project</button></div>
 <p className="intro">Create a JSON SDK project and build a standard tool package. Download the package to install or publish, and the source project to edit, commit and rebuild with the same SDK.</p>
 {!canBuild&&<p role="status">View only. Your role does not grant building or editing projects.</p>}
 {!loading&&!configured&&<p role="status">Package building is not configured. An administrator can connect the SDK build worker. Saved projects remain available.</p>}
 {error!==undefined&&!open&&<Failure error={error} retry={()=>setRefresh(value=>value+1)}/>}
 {loading?<p role="status">Loading projects and builds…</p>:<section><h2>Projects</h2>{drafts.length?<ul>{drafts.map(draft=><li key={draft.id}>{draft.id} · revision {draft.revision} <button disabled={busy} onClick={()=>edit(draft)}>Open project {draft.id}</button></li>)}</ul>:<p>No SDK projects yet. Add a project to start with Hello World.</p>}
 {draftCursor&&<button disabled={busy} onClick={()=>void action(async()=>{const page=await client.builderDrafts(draftCursor);setDrafts(current=>[...current,...page.items]);setDraftCursor(page.nextCursor);})}>More projects</button>}</section>}
 <section><h2>Package builds</h2><p>Builds compile and check the saved project. After installation, use the tool’s Test action to run it on an authorized host or device and see its responses.</p>
 {builds.length?<ul aria-label="Package builds">{builds.map(build=><li key={build.id}><strong>{build.draftId}</strong> · revision {build.draftRevision} · {build.state==='RUNNING'?'Compiling and checking':build.state}
 {build.error&&<p role="status">{failureMessages[build.error]}</p>}
 {build.state==='READY'&&<><button disabled={busy} aria-label={`Download package ${build.id}`} onClick={()=>void action(async()=>saveBlob(await client.downloadSdkBuild(build.id,false),'tool-package.tgpkg'))}>Download package</button> <button disabled={busy} aria-label={`Download source ${build.id}`} onClick={()=>void action(async()=>saveBlob(await client.downloadSdkBuild(build.id,true),'tool-source.zip'))}>Download source</button></>}
 </li>)}</ul>:<p>No package builds yet.</p>}
 {buildCursor&&<button disabled={busy} onClick={()=>void action(async()=>{const page=await client.builderBuilds(buildCursor);setBuilds(current=>[...current,...page.items]);setBuildCursor(page.nextCursor);})}>More builds</button>}</section>
 {open&&<EditorDialog title={selected?'SDK project':'New SDK project'} close={()=>setOpen(false)} busy={busy}>
 {error!==undefined&&<Failure error={error}/>}<p role="status" aria-live="polite">{message}</p>{validation&&<p role="alert">{validation}</p>}
 <form onSubmit={save}><label htmlFor="sdk-builder-group">Tool Group</label><select id="sdk-builder-group" value={group} disabled={busy||!canBuild} onChange={event=>setGroup(event.target.value)}>{groups.map(item=><option key={item.id} value={item.id}>{item.name}{item.canBuild?'':' (view only)'}</option>)}</select>
 <label htmlFor="sdk-builder-source">JSON SDK project</label><textarea id="sdk-builder-source" rows={22} maxLength={32768} value={source} readOnly={!mayAuthor} disabled={busy} onChange={event=>setSource(event.target.value)} spellCheck={false}/>
 <p>Choose CLIENT for device-only tools or SERVER for host and supported device execution. The current JSON SDK supports bounded string inputs and concatenation. Defaults and examples are checked during the build. Project versions are immutable after a successful build.</p>
 <button disabled={busy||!mayAuthor}>Save project</button></form>
 {selected&&<><p>Saved revision {selected.revision}. {dirty?'Save changes before building.':'Ready to build this saved revision.'}</p><button disabled={busy||dirty||!mayAuthor||!configured} onClick={()=>void action(async()=>{
  const job=await client.buildSdkDraft({id:crypto.randomUUID(),draftId:selected.id,expectedRevision:selected.revision},crypto.randomUUID());setBuilds(current=>[job,...current]);setMessage('Build queued. The verified package and source downloads will appear below Package builds.');
 })}>Build package</button></>}
 </EditorDialog>}</>;
}
