// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState,type FormEvent} from 'react';
import type {BuilderDefinition,BuilderDraft,BuilderTestRecord,LocalRuntimeKind,FleetSelfTest,FleetPackageRelease,LocalToolRegistration,InstalledAuthorizationProfile} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {EditorDialog} from './EditorDialog';
import {ActionButton} from './Table';

const initialCode="def tool(arguments):\n    return {'text': arguments['text']}\n";
const initialSchema=JSON.stringify({type:'object',additionalProperties:false,properties:{text:{type:'string',maxLength:256}},required:['text']},null,2);
const initialExamples=JSON.stringify([{arguments:{text:'hello'},expectedOutput:{text:'hello'}}],null,2);
const entries:Record<string,string>={PYTHON:'/opt/tool/tool.py',NODE:'/opt/tool/tool.mjs',POWERSHELL:'/opt/tool/tool.ps1',SHELL:'/opt/tool/tool.sh',NATIVE:'/opt/tool/run',JAVA_JAR:'/opt/tool/tool.jar',DOTNET:'/opt/tool/tool.dll'};
function download(value:unknown,name:string){const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)+'\n'],{type:'application/json'}));const anchor=document.createElement('a');anchor.href=url;anchor.download=name;anchor.click();URL.revokeObjectURL(url);}
async function sha256(code:string){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(code)))].map(byte=>byte.toString(16).padStart(2,'0')).join('');}
function canonical(value:unknown):unknown {if(Array.isArray(value))return value.map(canonical);if(value!==null&&typeof value==='object')return Object.fromEntries(Object.entries(value).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([key,item])=>[key,canonical(item)]));return value;}

/** All authority, validation, scanning and version immutability belong to Control and the client. */
export function Builder({client}:{client:ControlClient}){
 const [drafts,setDrafts]=useState<readonly BuilderDraft[]>([]),[tests,setTests]=useState<readonly BuilderTestRecord[]>([]),[selected,setSelected]=useState<BuilderDraft>();
 const [editorOpen,setEditorOpen]=useState(false);
 const [error,setError]=useState<unknown>(),[busy,setBusy]=useState(false),[loading,setLoading]=useState(true),[refresh,setRefresh]=useState(0),[message,setMessage]=useState('');
 const [id,setId]=useState<string>(()=>crypto.randomUUID()),[packageId,setPackageId]=useState('custom-echo'),[toolId,setToolId]=useState('custom.echo'),[version,setVersion]=useState('1.0.0'),[name,setName]=useState('Text echo');
 const [kind,setKind]=useState<LocalRuntimeKind>('PYTHON'),[image,setImage]=useState(''),[runtimeVersion,setRuntimeVersion]=useState(''),[code,setCode]=useState(initialCode);
 const [description,setDescription]=useState('Returns a bounded text value.'),[useWhen,setUseWhen]=useState('Return text without accessing files or network.'),[doNotUseWhen,setDoNotUseWhen]=useState('Reading files or accessing external services.');
 const [input,setInput]=useState(initialSchema),[output,setOutput]=useState(initialSchema),[examples,setExamples]=useState(initialExamples),[credentials,setCredentials]=useState(''),[device,setDevice]=useState(''),[release,setRelease]=useState(''),[percentage,setPercentage]=useState('10');
 const [resource,setResource]=useState('runtime/custom.echo'),[permission,setPermission]=useState('COMPUTE');
 useEffect(()=>{const abort=new AbortController();setLoading(true);setError(undefined);
  async function load(){const d:BuilderDraft[]=[];const t:BuilderTestRecord[]=[];let cursor:string|undefined;
   for(let page=0;page<8;page++){const result=await client.builderDrafts(cursor,abort.signal);d.push(...result.items);cursor=result.nextCursor;if(!cursor)break;}
   cursor=undefined;for(let page=0;page<8;page++){const result=await client.builderTests(cursor,abort.signal);t.push(...result.items);cursor=result.nextCursor;if(!cursor)break;}
   if(!abort.signal.aborted){setDrafts(d);setTests(t);setLoading(false);}}
  void load().catch(e=>{if(!abort.signal.aborted){setError(e);setLoading(false);}});return ()=>abort.abort();
 },[client,refresh]);
 async function action(work:()=>Promise<void>){if(busy)return;setBusy(true);setError(undefined);setMessage('');try{await work();setRefresh(v=>v+1);}catch(e){setError(e);}finally{setBusy(false);}}
 function create(){setSelected(undefined);setId(crypto.randomUUID());setPackageId('custom-echo');setToolId('custom.echo');setVersion('1.0.0');setName('Text echo');setKind('PYTHON');setImage('');setRuntimeVersion('');setCode(initialCode);setDescription('Returns a bounded text value.');setUseWhen('Return text without accessing files or network.');setDoNotUseWhen('Reading files or accessing external services.');setInput(initialSchema);setOutput(initialSchema);setExamples(initialExamples);setCredentials('');setResource('runtime/custom.echo');setPermission('COMPUTE');setDevice('');setRelease('');setPercentage('10');setError(undefined);setMessage('');setEditorOpen(true);}
 function open(draft:BuilderDraft){setEditorOpen(true);setError(undefined);setMessage('');setDevice('');setRelease('');const d=draft.definition;setSelected(draft);setId(draft.id);setPackageId(d.packageId);setToolId(d.tool.toolId);setVersion(d.version);setName(d.name);setKind(d.runtime.kind);setImage(d.runtime.image);setRuntimeVersion(d.runtime.version);setCode(d.tool.source?.code??'');setDescription(d.description);setUseWhen(d.useWhen);setDoNotUseWhen(d.doNotUseWhen);setInput(JSON.stringify(d.tool.inputSchema,null,2));setOutput(JSON.stringify(d.tool.outputSchema,null,2));setExamples(JSON.stringify(d.examples.map(({arguments:args,expectedOutput})=>({arguments:args,expectedOutput})),null,2));setCredentials(d.credentialRequirements.join(','));setResource(d.resource.locator);setPermission(d.permissions[0]??'COMPUTE');}
 async function save(event:FormEvent){event.preventDefault();void action(async()=>{const source=code?{code,sha256:await sha256(code)}:undefined;const runtimeId=`runtime-${packageId}`;
  const runtime={id:runtimeId,kind,image,version:runtimeVersion};
  const registration={toolId,action:'execute',runtimeId,entryPoint:entries[kind],inputSchema:JSON.parse(input),outputSchema:JSON.parse(output),limits:{timeoutMs:10000,memoryMiB:512,maxInputBytes:8192,maxOutputBytes:8192},...(source?{source}:{})};
  const packageDigest=await sha256(JSON.stringify(canonical({registration,runtime})));
  const extractorId=`extract-${(await sha256(toolId)).slice(0,32)}`;
  const authorizationProfile:InstalledAuthorizationProfile={tool:{id:toolId,name,enabled:true,revision:1,version,packageDigest,extractorId,definition:{id:toolId,name,description,actions:[{name:'execute',resourceKinds:['CUSTOM']}],inputSchema:registration.inputSchema,outputSchema:registration.outputSchema}},extractor:{id:extractorId,name:`Resources for ${name}`.slice(0,256),enabled:true,revision:1,extractorKind:'FIELDS',version,fields:[],fixedResources:[{kind:'CUSTOM',locator:resource}],maxResources:1}};
  const tool:LocalToolRegistration={...registration,authorizationProfile};
  const definition:BuilderDefinition={packageId,version,name,description,useWhen,doNotUseWhen,runtime,tool,platforms:['LINUX'],architectures:['x86_64'],examples:(JSON.parse(examples) as Omit<FleetSelfTest,'toolId'>[]).map(e=>({...e,toolId})),permissions:[permission as BuilderDefinition['permissions'][number]],resource:{kind:'CUSTOM',locator:resource},credentialRequirements:credentials.split(',').map(s=>s.trim()).filter(Boolean)};
  const draft=await client.saveDraft({id,expectedRevision:selected?.revision??0,definition},crypto.randomUUID());setSelected(draft);setMessage('Draft saved and scanned.');});}
 function clone(){setSelected(undefined);setId(crypto.randomUUID());setVersion('');setMessage('Choose a new immutable version before saving.');}
 const sealed=selected?.sealed??false;
 return <><p className="eyebrow">Local capabilities</p><div className="page-heading"><h1>Custom tool builder</h1><button className="primary" disabled={busy} onClick={create}>Add tool</button></div><p className="intro">Write a tool, test it on a designated client, and prepare an immutable organization package. The package pins its code and resource definition. Register the exact Tool and extractor, assign its Tool Group and execution binding, and review group grants before runtime use.</p>
 {error!==undefined&&!editorOpen&&<Failure error={error} retry={()=>setRefresh(v=>v+1)}/>}
 {loading?<p role="status">Loading drafts and tests…</p>:<section><h2>Saved drafts</h2>{drafts.length?<ul>{drafts.map(d=><li key={d.id}>{d.definition.name} {d.definition.version}{d.sealed?' — sealed':''} <ActionButton icon="edit" label={`Edit draft ${d.id}`} disabled={busy} onClick={()=>open(d)}/></li>)}</ul>:<p>No drafts yet. Add a tool to get started.</p>}</section>}
 {editorOpen&&<EditorDialog title={selected?'Edit tool draft':'Add tool'} close={()=>setEditorOpen(false)} busy={busy}>{error!==undefined&&<Failure error={error} retry={()=>setRefresh(v=>v+1)}/>}<p role="status" aria-live="polite">{message}</p><form onSubmit={save}><fieldset disabled={busy||sealed}><legend>Tool definition</legend>
 <label htmlFor="builder-package">Package ID</label><input id="builder-package" required maxLength={128} value={packageId} onChange={e=>setPackageId(e.target.value)}/>
 <label htmlFor="builder-tool">Tool ID</label><input id="builder-tool" required maxLength={128} value={toolId} onChange={e=>{setToolId(e.target.value);setResource(`runtime/${e.target.value}`);}}/>
 <label htmlFor="builder-version">Version</label><input id="builder-version" required maxLength={128} value={version} onChange={e=>setVersion(e.target.value)}/>
 <label htmlFor="builder-name">Name</label><input id="builder-name" required maxLength={256} value={name} onChange={e=>setName(e.target.value)}/>
 <label htmlFor="builder-runtime">Runtime</label><select id="builder-runtime" value={kind} onChange={e=>{setKind(e.target.value as LocalRuntimeKind);setCode('');}}>{Object.keys(entries).map(k=><option key={k}>{k}</option>)}</select>
 <label htmlFor="builder-image">Approved digest-pinned runtime image</label><input id="builder-image" required maxLength={512} value={image} onChange={e=>setImage(e.target.value)}/>
 <label htmlFor="builder-runtime-version">Exact runtime version</label><input id="builder-runtime-version" required maxLength={128} value={runtimeVersion} onChange={e=>setRuntimeVersion(e.target.value)}/>
 <label htmlFor="builder-code">Code editor</label><textarea id="builder-code" rows={12} spellCheck={false} maxLength={8192} value={code} onChange={e=>setCode(e.target.value)}/><p>Python, Node and PowerShell define a tool function returning an object. Shell handles the JSON invocation. Leave code empty for an approved image-contained artifact.</p>
 <label htmlFor="builder-description">AI description</label><textarea id="builder-description" required maxLength={4096} value={description} onChange={e=>setDescription(e.target.value)}/>
 <label htmlFor="builder-use">Use when</label><textarea id="builder-use" required maxLength={2048} value={useWhen} onChange={e=>setUseWhen(e.target.value)}/>
 <label htmlFor="builder-avoid">Do not use when</label><textarea id="builder-avoid" required maxLength={2048} value={doNotUseWhen} onChange={e=>setDoNotUseWhen(e.target.value)}/>
 <label htmlFor="builder-input">Input JSON schema</label><textarea id="builder-input" required maxLength={8192} value={input} onChange={e=>setInput(e.target.value)}/>
 <label htmlFor="builder-output">Output JSON schema</label><textarea id="builder-output" required maxLength={8192} value={output} onChange={e=>setOutput(e.target.value)}/>
 <label htmlFor="builder-examples">Examples: arguments and expectedOutput JSON array</label><textarea id="builder-examples" required maxLength={16384} value={examples} onChange={e=>setExamples(e.target.value)}/>
 <label htmlFor="builder-permission">Permission</label><select id="builder-permission" value={permission} onChange={e=>setPermission(e.target.value)}><option>COMPUTE</option><option disabled>FILE_READ</option><option disabled>FILE_WRITE</option><option disabled>NETWORK</option><option disabled>CREDENTIALS</option></select><p>Tests and deployment have no host files, network, child processes or injected credentials.</p>
 <label htmlFor="builder-resource">CUSTOM resource mapping</label><input id="builder-resource" required value={resource} onChange={e=>setResource(e.target.value)}/>
 <label htmlFor="builder-credentials">Credential requirements: secret:// references, comma separated</label><input id="builder-credentials" maxLength={4096} value={credentials} onChange={e=>setCredentials(e.target.value)}/><p>Unbound credential requirements prevent testing and sealing. Never paste secret values.</p>
 <button>Save and scan draft</button></fieldset></form>
 {selected&&<section className="guidance"><h2>Test, package and deploy</h2><p>Revision {selected.revision}. {sealed?'Version is immutable. Clone to create a new version.':'Every example must pass on a designated client before sealing.'}</p><button disabled={busy} onClick={clone}>Clone to new version</button>
 <label htmlFor="builder-device">Designated enrolled client IDs, comma separated</label><input id="builder-device" value={device} onChange={e=>setDevice(e.target.value)}/>
 <button disabled={busy||!device} onClick={()=>void action(async()=>{for(let index=0;index<selected.definition.examples.length;index++)await client.testDraft({id:crypto.randomUUID(),draftId:selected.id,expectedRevision:selected.revision,deviceId:device.split(',')[0].trim(),exampleIndex:index},crypto.randomUUID());setMessage('Tests queued for the designated client.');})}>Test on designated client</button><button disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Refresh tests</button>
 <ul aria-label="Test results">{tests.filter(t=>t.draftId===selected.id).map(t=><li key={t.id}>Example {t.exampleIndex+1}: {t.state}{t.error?` (${t.error})`:''}</li>)}</ul>
 <button disabled={busy||sealed} onClick={()=>void action(async()=>{const draft=await client.sealDraft(selected.id,selected.revision,crypto.randomUUID());setSelected(draft);download(draft.packageDocument,`${draft.definition.packageId}-${draft.definition.version}.json`);setMessage('Organization package sealed. Sign and mirror its descriptor with the independent release authority.');})}>Create organization package</button>
 <button disabled={busy||!sealed} onClick={()=>void action(async()=>{download(await client.publicationDraft(selected.id),`${selected.definition.packageId}-publication.json`);setMessage('Public preparation downloaded. Review it before later publication.');})}>Prepare publication</button>
 <label htmlFor="builder-release">Signed release envelope JSON</label><textarea id="builder-release" maxLength={65536} value={release} onChange={e=>setRelease(e.target.value)}/><button disabled={busy||!sealed||!release} onClick={()=>void action(async()=>{await client.releaseDraft(selected.id,JSON.parse(release) as FleetPackageRelease,crypto.randomUUID());setMessage('Exact sealed package release verified and published.');})}>Publish organization release</button>
 <label htmlFor="builder-percent">Canary percentage</label><input id="builder-percent" type="number" min={1} max={100} value={percentage} onChange={e=>setPercentage(e.target.value)}/><button disabled={busy||!sealed||!device} onClick={()=>void action(async()=>{await client.deployDraft(selected.id,{id:crypto.randomUUID(),packageId:selected.definition.packageId,version:selected.definition.version,deviceIds:device.split(',').map(s=>s.trim()).filter(Boolean),desiredPresence:true,percentage:Number(percentage)},crypto.randomUUID());setMessage('Deployment created. Observe desired/reported state in Packages.');})}>Deploy package</button>
 </section>}</EditorDialog>}</>;
}
