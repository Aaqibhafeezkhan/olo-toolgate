// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type {EnterpriseManagementRule} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {managementCatalog} from './managementPermissions.generated';
import {EnterpriseScopeEditor,GroupSelector,emptyScope,emptyConditions,groupShape,conditionsShape} from './EnterpriseScopeEditor';

const groupKinds={TEAM:'teams',AGENT_GROUP:'agentGroups',TOOL_GROUP:'toolGroups',DEVICE_GROUP:'deviceGroups'} as const;
const groupLabels={TEAM:'Teams',AGENT_GROUP:'Agent groups',TOOL_GROUP:'Tool groups',DEVICE_GROUP:'Device groups'} as const;
const known=new Set<string>(managementCatalog.resources.flatMap(r=>r.actions.map(action=>`${r.id}.${action}`)));
const label=(action:string)=>(managementCatalog.actionLabels as Record<string,string>)[action]??action;

/** Catalog metadata is shared; Control alone resolves effective authorization. */
export function ManagementRulesEditor({client,value,change,disabled,superAdmin=false}:{client:ControlClient;value:readonly EnterpriseManagementRule[];change:(value:readonly EnterpriseManagementRule[])=>void;disabled:boolean;superAdmin?:boolean}) {
 if(superAdmin)return <fieldset disabled><legend>Management permissions</legend><p>Always allowed by Super Admin. Setup-login protection and operation validation still apply.</p>{managementCatalog.resources.map(resource=><details key={resource.id}><summary>{resource.label}</summary>{resource.actions.map(action=><label className="checkbox" key={action}><input type="checkbox" checked readOnly/>{label(action)}</label>)}</details>)}</fieldset>;
 if(!Array.isArray(value)||!value.every(rule=>rule&&Array.isArray(rule.actions)&&rule.actions.every((a:string)=>known.has(a))&&rule.groupType in groupKinds&&groupShape(rule.groups)&&Array.isArray(rule.grantableScopes)&&conditionsShape(rule.conditions)))return <p role="alert">Migrate this role to the feature permission model before editing. Legacy action names are not accepted.</p>;
 const rules:readonly EnterpriseManagementRule[]=value;
 const update=(index:number,patch:Partial<EnterpriseManagementRule>)=>change(rules.map((rule,i)=>i===index?{...rule,...patch}:rule));
 function toggle(index:number,permission:string,checked:boolean){const actions=rules[index].actions;update(index,{actions:checked?[...new Set([...actions,permission])].sort():actions.filter(a=>a!==permission)});}
 return <fieldset disabled={disabled}><legend>Management permissions</legend>
  <p>Permissions apply to features throughout the console and API. Unchecked means this role grants no access; another assigned role may still grant it.</p>
  {rules.map((rule,index)=><fieldset key={index}><legend>Permission scope {index+1}</legend>
   <fieldset><legend>Group boundary</legend>{Object.keys(groupKinds).map(kind=><label className="checkbox" key={kind}><input type="checkbox" checked={rule.groupType===kind} onChange={event=>{if(event.target.checked)update(index,{groupType:kind as EnterpriseManagementRule['groupType'],groups:{ids:[],all:false},deviceScope:undefined});}}/>{groupLabels[kind as keyof typeof groupKinds]}</label>)}</fieldset>
   <GroupSelector client={client} kind={groupKinds[rule.groupType]} label={`Groups for permission scope ${index+1}`} value={rule.groups} change={groups=>update(index,{groups})}/>
   {rule.groupType==='DEVICE_GROUP'&&<fieldset><legend>Device relationship</legend>
    <label className="checkbox"><input type="checkbox" disabled={rule.actions.some(a=>!a.startsWith('device.'))} checked={rule.deviceScope==='OWNED'} onChange={e=>update(index,{deviceScope:e.target.checked?'OWNED':undefined})}/>Owned devices</label>
    <label className="checkbox"><input type="checkbox" disabled={rule.actions.some(a=>!a.startsWith('device.'))} checked={rule.deviceScope==='CURRENT'} onChange={e=>update(index,{deviceScope:e.target.checked?'CURRENT':undefined})}/>Self (this device)</label>
    {rule.actions.some(a=>!a.startsWith('device.'))&&<p>Move non-Device permissions to a separate scope before selecting a device relationship.</p>}
    <p className="hint">Self requires server-verified device context from Chrome Connect. Device relationship scopes use one placement group and Device permissions only. A code verifies ownership, not the current browser device.</p>
   </fieldset>}
   {managementCatalog.resources.filter(resource=>!rule.deviceScope||resource.id==='device').map(resource=>{
    const selected=resource.actions.filter(action=>rule.actions.includes(`${resource.id}.${action}`)).length;
    return <details key={resource.id} open={selected>0}><summary>{resource.label}{selected>0?` · ${selected} selected`:''}</summary>{resource.actions.map(action=>{const permission=`${resource.id}.${action}`;return <label className="checkbox" key={permission}><input type="checkbox" aria-label={`${resource.label}: ${label(action)} in scope ${index+1}`} checked={rule.actions.includes(permission)} onChange={e=>toggle(index,permission,e.target.checked)}/>{label(action)}</label>;})}</details>;
   })}
   <label>Valid from (Unix milliseconds)<input type="number" min={0} value={rule.conditions.notBeforeUnixMs} onChange={e=>update(index,{conditions:{...rule.conditions,notBeforeUnixMs:Number(e.target.value)}})}/></label>
   <label>Expiry (Unix milliseconds; 0 is unbounded)<input type="number" min={0} value={rule.conditions.expiresAtUnixMs} onChange={e=>update(index,{conditions:{...rule.conditions,expiresAtUnixMs:Number(e.target.value)}})}/></label>
   <p>Delegation ceilings limit runtime scopes this administrator can allocate; they do not grant tool execution.</p>
   {rule.grantableScopes.map((scope,scopeIndex)=><div key={scopeIndex}><EnterpriseScopeEditor client={client} value={scope} change={next=>update(index,{grantableScopes:rule.grantableScopes.map((s,i)=>i===scopeIndex?next:s)})}/><button type="button" onClick={()=>update(index,{grantableScopes:rule.grantableScopes.filter((_,i)=>i!==scopeIndex)})}>Remove ceiling {scopeIndex+1}</button></div>)}
   <button type="button" onClick={()=>update(index,{grantableScopes:[...rule.grantableScopes,emptyScope]})}>Add delegation ceiling</button>
   <button type="button" onClick={()=>change(value.filter((_,i)=>i!==index))}>Remove permission scope</button>
  </fieldset>)}
  <button type="button" onClick={()=>change([...value,{actions:[],groupType:'TEAM',groups:{ids:[],all:false},grantableScopes:[],conditions:emptyConditions}])}>Add permission scope</button>
 </fieldset>;
}
