// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {createContext,useContext,useEffect,useState,type ReactNode,type Dispatch,type SetStateAction} from 'react';
import type {DeploymentProfile,WorkflowConfiguration,WorkflowDefinition,WorkflowProfileStatus,TriggerDefinitionRecord,TriggerAssignment,TriggerSubscription} from '@olo-labs/toolgate-contracts/v2';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {EditorDialog} from './EditorDialog';
import {ProfileFields,initialField,profileSchema,type FieldOptions} from './ProfileFields';
import {Table} from './Table';
import {TriggerAssignments} from './TriggerAssignments';

export const workflowPages={triggers:'Triggers',workflows:'Workflows',workflowGroups:'Workflow groups','workflow-models':'Models','workflow-dispatch':'Trigger mappings','workflow-profiles':'Profiles','agent-configuration':'Models & prompts','agent-workflow-mappings':'Workflow mappings'} as const;
export type WorkflowPage=keyof typeof workflowPages;
export const isWorkflowPage=(route:string):route is WorkflowPage=>Object.hasOwn(workflowPages,route);
type Workspace={profile?:DeploymentProfile;status?:WorkflowProfileStatus;base?:DeploymentProfile;baseStatus?:WorkflowProfileStatus;dirty:boolean;epoch:number};
const WorkspaceContext=createContext<{workspace:Workspace;setWorkspace:Dispatch<SetStateAction<Workspace>>;busy:boolean;setBusy:Dispatch<SetStateAction<boolean>>}|undefined>(undefined);
/** In-memory working profile survives page navigation, and is destroyed on disconnect. */
export function WorkflowWorkspaceProvider({children}:{children:ReactNode}){
  const [workspace,setWorkspace]=useState<Workspace>({dirty:false,epoch:0});
  const [busy,setBusy]=useState(false);
  return <WorkspaceContext.Provider value={{workspace,setWorkspace,busy,setBusy}}>{children}</WorkspaceContext.Provider>;
}
function useWorkspace(){const value=useContext(WorkspaceContext);if(!value)throw Error('Workflow workspace unavailable');return value;}
const schema=(name:string)=>({$ref:`${name==='ProfilePackage'?'profile':'workflow'}.schema.json#/$defs/${name}`});
type Item=Record<string,unknown>;
function describeItem(item:Item){
  if('versionId' in item)return `Version ${item.versionId} · Workflow group ${item.workflowGroupId}`;
  if('entryEligible' in item)return `Agent group ${item.agentGroupId} · Entry ${item.entryEligible?'eligible':'ineligible'} · Delegation ${item.delegationAllowed?'allowed':'disabled'}`;
  return String(item.displayName??item.modelGroupId??item.workflowId??(item.deploymentIds as string[]|undefined)?.join(', ')??'');
}

function Collection({title,definition,items,change,idField='id',disabled=false,defaults,options}:{title:string;definition:string;items:readonly unknown[];change:(items:Item[])=>void;idField?:string;disabled?:boolean;defaults?:Item;options?:FieldOptions}){
  const [editing,setEditing]=useState<{index?:number;value:Item}>();
  return <section className="guidance"><div className="page-heading"><h2>{title}</h2><button disabled={disabled} onClick={()=>setEditing({value:{...initialField(schema(definition)) as Item,...defaults}})}>Add {title.toLowerCase()}</button></div>
    {!items.length?<p>No {title.toLowerCase()} configured in this profile.</p>:<div className="table-wrap"><Table><caption>{title} in the working profile</caption><thead><tr><th>Identifier</th><th>Configuration</th><th>Actions</th></tr></thead><tbody>{items.map((value,index)=>{const item=value as Item;return <tr key={index}><th scope="row">{String(item[idField]??index+1)}</th><td>{describeItem(item)}</td><td><button disabled={disabled} onClick={()=>setEditing({index,value:item})}>Edit {String(item[idField]??index+1)}</button><button disabled={disabled} onClick={()=>change(items.filter((_,i)=>i!==index) as Item[])}>Remove {String(item[idField]??index+1)}</button></td></tr>;})}</tbody></Table></div>}
    {editing&&<EditorDialog title={`Configure ${title.toLowerCase()}`} close={()=>setEditing(undefined)} busy={disabled}><form className="workflow-profile-editor" onSubmit={e=>{e.preventDefault();change(editing.index===undefined?[...items,editing.value] as Item[]:items.map((item,index)=>index===editing.index?editing.value:item) as Item[]);setEditing(undefined);}}><fieldset disabled={disabled}><ProfileFields input={schema(definition)} label={`${title} properties`} value={editing.value} options={options} change={value=>setEditing({...editing,value:value as Item})}/><button className="primary">Apply to profile draft</button><p>This updates the working profile. Stage, review and publish the profile to activate configuration.</p></fieldset></form></EditorDialog>}
  </section>;
}

/** Dedicated entity pages share one complete reviewed profile, rather than inventing directory fields. */
export function WorkflowManagement({client,page}:{client:ControlClient;page:WorkflowPage}){
  const {workspace:w,setWorkspace,busy,setBusy}=useWorkspace();
  const [catalog,setCatalog]=useState<readonly WorkflowProfileStatus[]>(),[hasMore,setHasMore]=useState(false),[attempt,setAttempt]=useState(0),[error,setError]=useState<unknown>(),[notice,setNotice]=useState('');
  useEffect(()=>{const abort=new AbortController();setError(undefined);client.workflowProfiles(undefined,abort.signal).then(items=>{if(!abort.signal.aborted){setCatalog(items);setHasMore(items.length===50);}}).catch(e=>{if(!abort.signal.aborted)setError(e);});return()=>abort.abort();},[client,attempt]);
  async function run(action:()=>Promise<void>){if(busy)return;setBusy(true);setError(undefined);setNotice('');try{await action();}catch(e){setError(e);setWorkspace(current=>({...current,status:undefined}));}finally{setBusy(false);}}
  const edit=(profile:DeploymentProfile)=>setWorkspace({...w,profile,status:undefined,dirty:true});
  const config=w.profile?.workflows;
  function editConfig(key:keyof WorkflowConfiguration,value:unknown){if(w.profile&&config)edit({...w.profile,workflows:{...config,[key]:value}});}
  function editWorkflows(entries:readonly WorkflowDefinition[]){
    if(!w.profile||!config)return;
    const versions=new Map<string,{from:string;to:string}>();
    const workflows=entries.map(entry=>{
      const previous=config.workflows.find(item=>item.id===entry.id);
      const stored=w.base?.workflows?.workflows.find(item=>item.id===entry.id&&item.versionId===entry.versionId);
      const changed=stored&&JSON.stringify(stored)!==JSON.stringify(entry)?{...entry,versionId:`rev-${crypto.randomUUID()}`}:entry;
      if(previous&&previous.versionId!==changed.versionId)versions.set(entry.id,{from:previous.versionId,to:changed.versionId});
      return changed;
    });
    const dispatchBindings=config.dispatchBindings.map(binding=>{const version=versions.get(binding.workflowId);return version?.from===binding.workflowVersionId?{...binding,workflowVersionId:version.to}:binding;});
    edit({...w.profile,workflows:{...config,workflows,dispatchBindings}});
  }
  async function load(entry:WorkflowProfileStatus){const [profile,status]=await Promise.all([client.workflowProfileDocument(entry.profileId,entry.profileDigest),client.workflowProfileStatus(entry.profileId,entry.profileDigest)]);setWorkspace({profile,base:profile,status,baseStatus:status,dirty:false,epoch:w.epoch+1});}
  async function stage(){const status=await client.stageWorkflowProfile(w.profile!);setWorkspace({...w,status,baseStatus:status,base:w.profile,dirty:false});setAttempt(a=>a+1);setNotice('Staged exact profile revision. Review before provisioning or publication.');}
  function create(){const profile={...initialField(profileSchema) as DeploymentProfile,workflows:initialField(schema('WorkflowConfiguration')) as WorkflowConfiguration};setWorkspace({profile,dirty:true,epoch:w.epoch+1});}
  async function openFile(file:File){
    if(file.size>262144)throw new Error('Deployment profile exceeds the Control size limit.');
    const value=JSON.parse(await file.text()) as DeploymentProfile;
    if(value?.profileVersion!==2||typeof value.profileId!=='string'||!Array.isArray(value.packages))throw new Error('Choose a version 2 deployment profile.');
    const defaults=initialField(schema('WorkflowConfiguration')) as WorkflowConfiguration;
    if(value.workflows&&Object.keys(defaults).some(key=>!Array.isArray(value.workflows?.[key as keyof WorkflowConfiguration])))throw new Error('The workflow configuration must contain every collection.');
    const profile={...value,workflows:value.workflows??defaults};
    const status=await client.stageWorkflowProfile(profile);
    setWorkspace({profile,base:profile,status,baseStatus:status,dirty:false,epoch:w.epoch+1});
    setAttempt(a=>a+1);
    setNotice('Imported and staged for review.');
  }
  function saveFile(){
    const url=URL.createObjectURL(new Blob([JSON.stringify(w.profile,null,2)+'\n'],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download='deployment-profile.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  const agentFromHash=()=>new URLSearchParams(location.hash.split('?')[1]).get('agentId');
  const [selectedAgent,setSelectedAgent]=useState(agentFromHash);
  useEffect(()=>{const update=()=>setSelectedAgent(agentFromHash());window.addEventListener('hashchange',update);return()=>window.removeEventListener('hashchange',update);},[]);
  const modelGroups=config?.modelGroups??[];
  const modelOptions:FieldOptions=({label})=>label==='modelGroupId'?{choices:modelGroups.map(group=>({value:group.id,label:group.id}))}:undefined;
  const [assignments,setAssignments]=useState<readonly TriggerAssignment[]>([]);
  const [subscriptions,setSubscriptions]=useState<readonly TriggerSubscription[]>([]);
  useEffect(()=>{
    if(page!=='workflow-dispatch')return;
    const abort=new AbortController();
    void client.triggerAssignments(abort.signal).then(result=>{if(!abort.signal.aborted)setAssignments(result.items??[]);}).catch(()=>{if(!abort.signal.aborted)setAssignments([]);});
    void client.triggerSubscriptions(abort.signal).then(result=>{if(!abort.signal.aborted)setSubscriptions(result.items??[]);}).catch(()=>{if(!abort.signal.aborted)setSubscriptions([]);});
    return()=>abort.abort();
  },[client,page]);
  const dispatchOptions:FieldOptions=({label,value})=>label==='workflowId'?{
    choices:(config?.workflows??[]).map(workflow=>({value:workflow.id,label:workflow.id})),
    updates:(id)=>({workflowVersionId:config?.workflows.find(workflow=>workflow.id===id)?.versionId??''}),
  }:label==='workflowVersionId'&&config?.workflows.some(workflow=>workflow.id===value.workflowId)?{
    choices:config.workflows.filter(workflow=>workflow.id===value.workflowId).map(workflow=>({value:workflow.versionId,label:workflow.versionId})),
  }:label==='subscriptionUid'?{suggestions:subscriptions.map(item=>({value:item.metadata.uid,label:item.metadata.name}))}
  :label==='sourceAssignmentUid'?{suggestions:assignments.map(item=>({value:item.metadata.uid,label:item.metadata.name}))}:undefined;
  const models=config&&<><Collection title="Model deployments" definition="WorkflowModelDeployment" items={config.modelDeployments} change={value=>editConfig('modelDeployments',value)} disabled={busy}/><Collection title="Model groups" definition="WorkflowModelGroupDefinition" items={config.modelGroups} change={value=>editConfig('modelGroups',value)} disabled={busy}/><p>secretName refers to a configured vault secret. Enter its reference, never a provider key.</p></>;
  const agentItems=config?.agentDefinitions??[];
  const visibleAgents=selectedAgent?agentItems.filter(agent=>agent.agentId===selectedAgent):agentItems;
  return <><div className="page-heading"><div><p className="eyebrow">Workflow configuration</p><h1>{workflowPages[page]}</h1></div><button disabled={busy} onClick={()=>setAttempt(a=>a+1)}>Refresh profiles</button></div>
    <p>Configure the selected profile across these pages, then stage, review and publish its exact revision. Current Super Admin authority is required. Runtime grants remain separate.</p>
    {error!==undefined&&<Failure error={error}/>}<p role="status">{notice}</p>
    {page==='triggers'&&<TriggerAssignments client={client}/>}
    <section className="guidance"><h2>Working profile</h2>
      <button disabled={busy||w.dirty} onClick={create}>New profile</button>
      {page==='workflow-profiles'&&<><label>Import and stage deployment profile JSON<input type="file" accept=".json,application/json" disabled={busy||w.dirty} onChange={event=>{const file=event.currentTarget.files?.[0];event.currentTarget.value='';if(file)void run(()=>openFile(file));}}/></label>{w.profile&&<button disabled={busy} onClick={saveFile}>Download working profile JSON</button>}</>}
      {w.profile&&<><p><strong>{w.profile.profileId||'Unnamed profile'}</strong> — {w.dirty?'Unsaved profile draft':w.status?.state??'Working copy'}</p><label>Profile identifier<input required disabled={busy||Boolean(w.base)} value={w.profile.profileId} onChange={e=>edit({...w.profile!,profileId:e.target.value})}/></label>
        {w.dirty&&<button disabled={busy} onClick={()=>setWorkspace(w.base?{profile:w.base,base:w.base,status:w.baseStatus,baseStatus:w.baseStatus,dirty:false,epoch:w.epoch+1}:{dirty:false,epoch:w.epoch+1})}>Discard profile edits</button>}
        <button className="primary" disabled={busy||!w.profile.profileId} form={page==='workflow-profiles'?'workflow-profile-fields':undefined} onClick={()=>{if(page!=='workflow-profiles')void run(stage);}}>Stage profile revision</button>
      </>}
      {w.dirty&&<p>Unsaved edits are shared across the workflow and agent configuration pages. Stage or discard them before opening another revision. Disconnecting discards the working copy.</p>}
      {!catalog?<p>Loading profile revisions…</p>:!catalog.length?<p>No workflow profiles staged. Create a profile to configure its groups, models, agents and workflows.</p>:<><div className="table-wrap"><Table><caption>Available profile revisions</caption><thead><tr><th>Profile</th><th>Revision</th><th>State</th><th>Action</th></tr></thead><tbody>{catalog.map(entry=><tr key={`${entry.profileId}:${entry.profileDigest}`}><th scope="row">{entry.profileId}</th><td><code title={entry.profileDigest}>{entry.profileDigest.slice(7,19)}</code></td><td>{entry.state}</td><td><button disabled={busy||w.dirty} onClick={()=>void run(()=>load(entry))}>Open {entry.profileId} {entry.profileDigest.slice(7,19)}</button></td></tr>)}</tbody></Table></div>{hasMore&&<button disabled={busy} onClick={()=>void run(async()=>{const last=catalog.at(-1)!;const next=await client.workflowProfiles(last);setCatalog([...catalog,...next]);setHasMore(next.length===50);if(!next.length)setNotice('No more profile revisions.');})}>More profile revisions</button>}</>}
    </section>
    {w.profile&&<section className="guidance"><h2>Profile lifecycle</h2><p>Stage a new immutable revision to save profile changes. The Workflows and Workflow mappings pages assign a new version when editing a staged workflow and update its matching trigger mappings in the draft.</p>
      {w.status?<><p>{w.status.state}; generation {w.status.generation}; dependencies {w.status.dependenciesReady?'ready':'not ready'}.</p><p className="workflow-profile-editor">Revision: {w.status.profileDigest}</p>
        <button disabled={busy} onClick={()=>void run(async()=>setWorkspace({...w,status:await client.workflowProfileStatus(w.status!.profileId,w.status!.profileDigest)}))}>Refresh lifecycle status</button>
        <button disabled={busy||w.status.state!=='DRAFT'} onClick={()=>void run(async()=>setWorkspace({...w,status:await client.reviewWorkflowProfile(w.status!.profileId,w.status!.profileDigest)}))}>Review exact revision</button>
        <button disabled={busy||w.status.state==='DRAFT'} onClick={()=>void run(async()=>{const result=await client.provisionWorkflowProfile(w.status!.profileId,w.status!.profileDigest);setWorkspace({...w,status:await client.workflowProfileStatus(w.status!.profileId,w.status!.profileDigest)});setNotice(`Package provisioning: ${result.state}`);})}>Provision reviewed packages</button>
        <button disabled={busy||w.status.state!=='REVIEWED'||!w.status.dependenciesReady} onClick={()=>void run(async()=>setWorkspace({...w,status:await client.publishWorkflowProfile(w.status!.profileId,w.status!.profileDigest,w.status!.generation)}))}>Publish reviewed revision</button>
      </>:<p>Stage the working profile before review or publication.</p>}
    </section>}
    {w.profile&&page==='workflow-profiles'&&<section className="guidance"><h2>Profile properties</h2><form id="workflow-profile-fields" className="workflow-profile-editor" onSubmit={e=>{e.preventDefault();void run(stage);}}><fieldset disabled={busy}><ProfileFields key={w.epoch} value={w.profile} change={value=>edit(value as DeploymentProfile)}/></fieldset></form></section>}
    {w.profile&&page==='triggers'&&<TriggerPackages client={client} busy={busy} packages={w.profile.packages} change={value=>edit({...w.profile!,packages:value as unknown as DeploymentProfile['packages']})}/>}
    {w.profile&&config&&<>
      {page==='workflowGroups'&&<><Collection title="Workflow groups" definition="WorkflowGroupDefinition" items={config.workflowGroups} change={value=>editConfig('workflowGroups',value)} disabled={busy}/><p>Assign workflows to groups using each workflow’s workflowGroupId on the Workflows page.</p></>}
      {page==='workflows'&&<Collection title="Workflows" definition="WorkflowDefinition" items={config.workflows} change={value=>editWorkflows(value as unknown as WorkflowDefinition[])} disabled={busy}/>}
      {page==='workflow-models'&&models}
      {page==='workflow-dispatch'&&<><p>Choose a workflow to pin its version. Choose a subscription and assignment, or enter their UIDs. Set a JSON pointer to the message in the event data.</p><Collection title="Trigger mappings" definition="WorkflowDispatchBinding" options={dispatchOptions} items={config.dispatchBindings} change={value=>editConfig('dispatchBindings',value)} disabled={busy}/></>}
      {page==='agent-configuration'&&<>{selectedAgent&&<p>Agent: {selectedAgent}. <a href="#agent-configuration">Show all profile agents</a></p>}<Collection title="Agent prompts and context" definition="WorkflowAgentDefinition" options={modelOptions} idField="agentId" items={visibleAgents} defaults={selectedAgent?{agentId:selectedAgent}:undefined} change={value=>editConfig('agentDefinitions',selectedAgent?[...agentItems.filter(agent=>agent.agentId!==selectedAgent),...value]:value)} disabled={busy}/><p>Each definition assigns a model group, system prompt, context sources and output schema. The agent must also exist in the Agents directory.</p><details className="guidance"><summary>Configure model deployments and groups</summary>{models}</details></>}
      {page==='agent-workflow-mappings'&&<><h2>Agent membership and delegation in workflows</h2>{selectedAgent&&<p>Agent: {selectedAgent}. <a href="#agent-workflow-mappings">Show all workflow mappings</a></p>}{config.workflows.map((workflow,index)=><Collection key={`${workflow.id}:${index}`} title={`Agents in ${workflow.id||'unnamed workflow'}`} definition="WorkflowAgentBinding" idField="agentId" items={selectedAgent?workflow.agents.filter(agent=>agent.agentId===selectedAgent):workflow.agents} defaults={selectedAgent?{agentId:selectedAgent}:undefined} disabled={busy} change={value=>{
        const agents=(selectedAgent?[...workflow.agents.filter(agent=>agent.agentId!==selectedAgent),...value]:value) as unknown as WorkflowDefinition['agents'];
        editWorkflows(config.workflows.map((entry,i)=>i===index?{...entry,agents}:entry));
      }}/>)}<p>Mappings configure the actor group, entry eligibility and delegation eligibility. Actor group membership, tool grants and runtime delegation authority are managed separately in Agents and Agent Group tool mapping.</p><a href="#mapping">Open Agent Group tool mapping</a></>}
    </>}
  </>;
}

function TriggerPackages({client,busy,packages,change}:{client:ControlClient;busy:boolean;packages:DeploymentProfile['packages'];change:(value:Item[])=>void}){
  const [definitions,setDefinitions]=useState<readonly TriggerDefinitionRecord[]>([]),[next,setNext]=useState<string>(),[error,setError]=useState<unknown>(),[loading,setLoading]=useState(true);
  useEffect(()=>{const abort=new AbortController();client.triggerDefinitions(undefined,abort.signal).then(page=>{if(!abort.signal.aborted){setDefinitions(page.items);setNext(page.nextPageToken);}}).catch(e=>{if(!abort.signal.aborted)setError(e);}).finally(()=>{if(!abort.signal.aborted)setLoading(false);});return()=>abort.abort();},[client]);
  const options:FieldOptions=({label,value,root})=>{
    const exports=definitions.filter(entry=>entry.packageId===root.packageId&&entry.packageVersion===root.version&&(!root.packageDigest||entry.packageDigest===root.packageDigest));
    if(label==='triggerId'&&exports.length)return {choices:exports.map(entry=>({value:entry.triggerId,label:entry.triggerId}))};
    if(label==='config'){
      const entry=exports.find(entry=>entry.triggerId===value.triggerId);
      if(entry)return {signedSchema:entry.descriptor.configSchema};
    }
    return undefined;
  };
  return <><p>Configure package exports and their device groups here. Provision the reviewed package to apply its trigger assignments. <a href="#workflow-dispatch">Map trigger events to workflows</a>.</p>
    {error!==undefined&&<Failure error={error}/>}
    <Collection title="Trigger packages" definition="ProfilePackage" idField="packageId" items={packages} change={change} options={options} disabled={busy}/>
    <section className="guidance"><h2>Installed trigger exports</h2>{loading?<p>Loading trigger exports...</p>:!definitions.length?<p>No installed trigger exports found. Add the package and provision it to load its signed schema.</p>:<ul>{definitions.map(entry=><li key={entry.triggerId+entry.packageDigest}>{entry.triggerId} ({entry.packageVersion})</li>)}</ul>}
    {next&&<button disabled={loading} onClick={()=>{setLoading(true);setError(undefined);void client.triggerDefinitions(next).then(page=>{setDefinitions(current=>[...current,...page.items]);setNext(page.nextPageToken);}).catch(setError).finally(()=>setLoading(false));}}>More trigger exports</button>}</section></>;
}
