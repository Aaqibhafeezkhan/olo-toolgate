// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useRef,useState,type FormEvent} from 'react';
import type {DirectoryKind,DirectoryRecords} from './operations.generated';
import type {EnterpriseScope,GroupSelection,EnterpriseManagementRule} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {ScopePicker} from './ScopePicker';
import {EnterpriseScopeEditor,GroupSelector,emptyScope} from './EnterpriseScopeEditor';
import {GroupMembershipEditor} from './AccessMapping';
import {ManagementRulesEditor} from './ManagementRulesEditor';
import {EditorDialog} from './EditorDialog';

type Kind=Exclude<DirectoryKind,'users'>;
export const entityTitles:Record<Kind,string>={teams:'team',roles:'role',tools:'tool',policies:'policy',agents:'agent',devices:'device',deviceGroups:'device group',agentGroups:'agent group',toolGroups:'tool group',grants:'grant',delegations:'delegation',agentDelegations:'agent delegation',bindings:'execution binding',extractors:'resource extractor',workloadBindings:'workload binding',identityBindings:'identity binding',deviceEvidence:'device evidence'};
const defaults:Record<Kind,Record<string,unknown>>={
  teams:{userIds:[],roleIds:[]},agentGroups:{agentIds:[],roleIds:[]},toolGroups:{toolIds:[]},deviceGroups:{deviceIds:[]},agents:{ownerUserId:''},devices:{ownerUserId:''},
  tools:{definition:{id:'',name:'',description:'',actions:[{name:'invoke',resourceKinds:['CUSTOM']}],inputSchema:{type:'object'},outputSchema:{type:'object'}},extractorId:'',version:'1.0.0',packageDigest:''},
  roles:{portalRole:'BASIC',roleType:'HUMAN',managementRules:[]},
  grants:{sourceType:'TEAM',sourceId:'',purpose:'HUMAN',scope:emptyScope},
  policies:{decision:'BLOCK',scope:emptyScope,teams:{ids:[],all:false},agentGroups:{ids:[],all:false},approverTeams:{ids:[],all:false}},
  delegations:{teamId:'',agentGroupId:'',scope:emptyScope},agentDelegations:{fromAgentGroupId:'',toAgentGroupId:'',maximumDepth:1,scope:emptyScope},
  bindings:{toolGroupId:'',deviceGroupId:'',actions:[],allowedPackageDigests:[],requireOnline:true,ownerDependency:false},
  extractors:{extractorKind:'FIELDS',version:'1.0.0',fields:[],fixedResources:[],maxResources:32},
  workloadBindings:{agentId:'',mode:'SERVICE',issuer:'',subject:'',audience:'',credentialSha256:'',credentialEpoch:1,expiresAtUnixMs:0},
  identityBindings:{userId:'',issuer:'',subject:'',sessionEpoch:1,firstSeenUnixMs:0,lastAttemptUnixMs:0,attemptCount:1,registrationReason:'REVIEWED_IDENTITY_BINDING',sessionsValidAfterUnixMs:0},
  deviceEvidence:{deviceId:'',posture:[],region:'',verifiedNetworkAddress:'',verifiedAtUnixMs:0,expiresAtUnixMs:0},
};
// Form shape guards keep incomplete advanced drafts editable; Control owns semantic validation.
function formShape(template:unknown,value:unknown):boolean {
  if(Array.isArray(template))return Array.isArray(value)&&value.every(item=>item!==null);
  if(template!==null&&typeof template==='object')return value!==null&&typeof value==='object'&&!Array.isArray(value)&&Object.entries(template).every(([key,item])=>formShape(item,(value as Record<string,unknown>)[key]));
  return typeof template===typeof value;
}
const extra=(record:DirectoryRecords[Kind])=>Object.fromEntries(Object.entries(record).filter(([k])=>!['id','name','enabled','revision'].includes(k)));
/** All permission edits target group records, typed Roles, grants or bindings. */
export function DirectoryEditor({client,kind,record,close,saved}:{client:ControlClient;kind:Kind;record?:DirectoryRecords[Kind];close:()=>void;saved:()=>void}) {
  const [current,setCurrent]=useState(record);const [id,setId]=useState(record?.id??''),[name,setName]=useState(record?.name??''),[enabled,setEnabled]=useState(record?.enabled??false);
  const [config,setConfig]=useState(JSON.stringify(record?extra(record):defaults[kind],null,2));
  const [error,setError]=useState<unknown>(),[busy,setBusy]=useState(false),[confirm,setConfirm]=useState(false);
  const pending=useRef<{body:string;key:string}>(undefined);
  let draft:Record<string,unknown>|undefined;try{const value:unknown=JSON.parse(config);if(formShape(defaults[kind],value))draft=value as Record<string,unknown>;}catch{/* Advanced editing can contain an incomplete draft. */}
  const update=(field:string,value:unknown)=>{if(draft)setConfig(JSON.stringify({...draft,[field]:value},null,2));};
  const picker=(field:string,target:DirectoryKind,label:string,multiple=false)=><ScopePicker key={field} client={client} kind={target} label={label} single={!multiple} value={multiple?(draft?.[field] as readonly string[]??[]):draft?.[field]?[String(draft[field])]:[]} onChange={ids=>update(field,multiple?ids:ids[0]??'')} disabled={busy||!draft}/>;
  const mutationKey=(body:string)=>{if(pending.current?.body!==body)pending.current={body,key:crypto.randomUUID()};return pending.current.key;};
  async function save(event:FormEvent){event.preventDefault();if(busy)return;setBusy(true);setError(undefined);try{
    const fields:Record<string,unknown>=JSON.parse(config);if(!fields||typeof fields!=='object'||Array.isArray(fields))throw new Error('Configuration must be an object');
    if(kind==='tools')fields.definition={...(fields.definition as Record<string,unknown>),id,name};
    const body={...fields,id,name,enabled,revision:current?.revision??1} as DirectoryRecords[Kind];await client.saveRecord(kind,body,!!current,mutationKey(JSON.stringify(body)));saved();
  }catch(failure){setError(failure);}finally{setBusy(false);}}
  async function reload(){if(!current)return;setBusy(true);setError(undefined);try{const value=await client.record(kind,current.id);setCurrent(value);setName(value.name);setEnabled(value.enabled);setConfig(JSON.stringify(extra(value),null,2));pending.current=undefined;}catch(failure){setError(failure);}finally{setBusy(false);}}
  async function remove(){if(!current||busy)return;setBusy(true);setError(undefined);try{await client.deleteRecord(kind,current,mutationKey(`delete:${current.id}:${current.revision}`));saved();}catch(failure){setError(failure);}finally{setBusy(false);}}
  const title=entityTitles[kind];const protectedDefault=['team-default','default-agents','default-tools','default-devices'].includes(id);
  return <EditorDialog title={current?`Edit ${title}`:`Add ${title}`} close={close} busy={busy}><section aria-label={`${title} editor`}>
    {error!==undefined&&<Failure error={error}/>}
    <form className="user-form" onSubmit={save}>
      <label>Identifier<input required maxLength={128} disabled={busy||!!current} value={id} onChange={e=>setId(e.target.value)}/></label>
      <label>Display name<input required maxLength={128} disabled={busy} value={name} onChange={e=>setName(e.target.value)}/></label>
      <label className="checkbox"><input type="checkbox" checked={enabled} disabled={busy||protectedDefault} onChange={e=>setEnabled(e.target.checked)}/>Enabled</label>
      {(kind==='agents'||kind==='devices')&&picker('ownerUserId','users','Administrative owner')}
      {(kind==='agents'||kind==='agentGroups')&&draft&&<><label>Route slug<input maxLength={63} pattern="[a-z0-9][a-z0-9-]{0,62}" disabled={busy} value={typeof draft.slug==='string'?draft.slug:''} onChange={e=>update('slug',e.target.value||undefined)} aria-describedby="slug-help"/></label>
        <p id="slug-help" className="hint">Optional. Lowercase letters, numbers and hyphens, starting with a letter or number. Names this {kind==='agents'?'agent':'group'} in its MCP route ({kind==='agents'?'/mcp/agent/':'/mcp/agent-group/'}slug) and must be unique among {kind==='agents'?'agents':'agent groups'}.</p></>}
      {kind==='teams'&&picker('userIds','users','Member users',true)}{kind==='agentGroups'&&picker('agentIds','agents','Member agents',true)}{kind==='toolGroups'&&picker('toolIds','tools','Member tools',true)}{kind==='deviceGroups'&&picker('deviceIds','devices','Member devices',true)}
      {(kind==='teams'||kind==='agentGroups')&&<>{picker('roleIds','roles','Group roles',true)}<p className="hint">Teams accept Human and Management Roles. Agent Groups accept Actor/Service Roles.</p></>}
      {kind==='roles'&&draft&&<><label>Role type<select disabled={busy} value={String(draft.roleType)} onChange={e=>setConfig(JSON.stringify({...draft,roleType:e.target.value,portalRole:'BASIC',managementRules:[]},null,2))}><option value="HUMAN">Human runtime role</option><option value="ACTOR_SERVICE">Actor / Service runtime role</option><option value="MANAGEMENT">Management role</option></select></label>
        {draft.roleType==='MANAGEMENT'&&<><label>Portal classification<select value={String(draft.portalRole)} onChange={e=>update('portalRole',e.target.value)}><option value="BASIC">No portal administration</option><option value="ADMINISTRATOR">Scoped administrator</option><option value="SUPER_ADMIN">Recovery administrator</option></select></label><ManagementRulesEditor client={client} value={draft.managementRules as EnterpriseManagementRule[]} change={rules=>update('managementRules',rules)} disabled={busy}/><p className="hint">Classification alone grants no management operation.</p></>}
        <p className="hint">Runtime grants are separate records referencing a Human or Actor/Service Role. Individuals cannot receive Roles directly.</p></>}
      {kind==='grants'&&draft&&<><label>Grant source<select value={String(draft.sourceType)} onChange={e=>setConfig(JSON.stringify({...draft,sourceType:e.target.value,sourceId:'',purpose:e.target.value==='TEAM'?'HUMAN':'CAPABILITY'},null,2))}><option value="TEAM">Team</option><option value="AGENT_GROUP">Agent Group</option><option value="ROLE">Typed Role</option></select></label>
        {picker('sourceId',draft.sourceType==='TEAM'?'teams':draft.sourceType==='AGENT_GROUP'?'agentGroups':'roles','Grant source')}
        <label>Purpose<select value={String(draft.purpose)} onChange={e=>update('purpose',e.target.value)}>{(draft.sourceType==='TEAM'?['HUMAN','SECRET']:draft.sourceType==='AGENT_GROUP'?['CAPABILITY','SERVICE','SECRET']:['HUMAN','CAPABILITY','SERVICE','SECRET']).map(p=><option key={p}>{p}</option>)}</select></label></>}
      {kind==='delegations'&&<>{picker('teamId','teams','Delegating Team')}{picker('agentGroupId','agentGroups','Authorized Agent Group')}</>}
      {kind==='agentDelegations'&&<>{picker('fromAgentGroupId','agentGroups','Delegating Agent Group')}{picker('toAgentGroupId','agentGroups','Receiving Agent Group')}<label>Maximum chain depth<input type="number" min={1} max={8} value={Number(draft?.maximumDepth??1)} onChange={e=>update('maximumDepth',Number(e.target.value))}/></label></>}
      {draft&&'scope' in draft&&<EnterpriseScopeEditor client={client} value={draft.scope as EnterpriseScope} change={value=>update('scope',value)} disabled={busy}/>}
      {kind==='policies'&&draft&&<><label>Decision<select value={String(draft.decision)} onChange={e=>update('decision',e.target.value)}><option value="BLOCK">Block</option><option value="ASK">Ask for approval</option><option value="ALLOW">Allow guardrail (requires grants)</option></select></label>{(['teams','agentGroups','approverTeams'] as const).map(f=><GroupSelector key={f} client={client} kind={f==='agentGroups'?'agentGroups':'teams'} label={f==='teams'?'Human Teams':f==='agentGroups'?'Actor Groups':'Eligible approver Teams'} value={draft[f] as GroupSelection} change={v=>update(f,v)} disabled={busy}/>)}</>}
      {kind==='bindings'&&<>{picker('toolGroupId','toolGroups','Bound Tool Group')}{picker('deviceGroupId','deviceGroups','Bound Device Group')}<label>Actions<input value={(draft?.actions as string[]??[]).join(', ')} onChange={e=>update('actions',e.target.value.split(',').map(v=>v.trim()).filter(Boolean))}/></label><label>Allowed package digests<input value={(draft?.allowedPackageDigests as string[]??[]).join(', ')} onChange={e=>update('allowedPackageDigests',e.target.value.split(',').map(v=>v.trim()).filter(Boolean))}/></label><label className="checkbox"><input type="checkbox" checked={Boolean(draft?.requireOnline)} onChange={e=>update('requireOnline',e.target.checked)}/>Require online authority</label><label className="checkbox"><input type="checkbox" checked={Boolean(draft?.ownerDependency)} onChange={e=>update('ownerDependency',e.target.checked)}/>Require an enabled administrative owner</label></>}
      {kind==='tools'&&<>{picker('extractorId','extractors','Reviewed resource extractor')}<label>Tool version<input required value={String(draft?.version??'')} onChange={e=>update('version',e.target.value)}/></label><label>Package SHA-256<input required pattern="[a-f0-9]{64}" value={String(draft?.packageDigest??'')} onChange={e=>update('packageDigest',e.target.value)}/></label></>}
      {kind==='workloadBindings'&&picker('agentId','agents','Authenticated Agent')}{kind==='identityBindings'&&picker('userId','users','Verified User')}{kind==='deviceEvidence'&&picker('deviceId','devices','Attested Device')}
      <details><summary>Advanced canonical configuration</summary><label>Configuration (JSON)<textarea rows={18} maxLength={131072} value={config} disabled={busy} onChange={e=>setConfig(e.target.value)}/></label><p className="hint">This is the same record edited above. Control validates references, role types, ceilings and mandatory group membership.</p></details>
      {!draft&&<p role="alert">Complete the JSON object before using guided fields.</p>}<div className="actions"><button className="primary" disabled={busy||!draft}>{busy?'Saving…':`Save ${title}`}</button>{current&&<button type="button" disabled={busy} onClick={()=>void reload()}>Reload current record</button>}</div>
    </form>
    {current&&(kind==='agents'||kind==='tools'||kind==='devices')&&<GroupMembershipEditor client={client} entity={kind} id={current.id} saved={saved}/>}
    {current&&!protectedDefault&&<div className="delete-area">{confirm?<><p>Delete this record? References, required memberships and recovery access must remain valid. Its identifier cannot be reused.</p><button className="danger" disabled={busy} onClick={()=>void remove()}>Confirm delete</button><button disabled={busy} onClick={()=>setConfirm(false)}>Cancel delete</button></>:<button className="danger" disabled={busy} onClick={()=>setConfirm(true)}>Delete {title}</button>}</div>}
  </section></EditorDialog>;
}
