// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type {EnterpriseManagementRule} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import type {PendingManagementRule} from './pendingContracts';
import {EnterpriseScopeEditor,GroupSelector,emptyScope,emptyConditions,groupShape,conditionsShape} from './EnterpriseScopeEditor';

const groupKinds={TEAM:'teams',AGENT_GROUP:'agentGroups',TOOL_GROUP:'toolGroups',DEVICE_GROUP:'deviceGroups'} as const;
const arrayShape=(value:unknown):boolean=>Array.isArray(value);
/** This edits the exact canonical role rules used by the server, including grantable ceilings. */
export function ManagementRulesEditor({client,value,change,disabled}:{client:ControlClient;value:readonly EnterpriseManagementRule[];change:(value:readonly EnterpriseManagementRule[])=>void;disabled:boolean}) {
 if(!arrayShape(value)||!value.every(rule=>rule&&arrayShape(rule.actions)&&rule.actions.every(a=>typeof a==='string')&&rule.groupType in groupKinds&&groupShape(rule.groups)&&arrayShape(rule.grantableScopes)&&conditionsShape(rule.conditions)))return <p role="alert">Complete the management rules in advanced JSON.</p>;
 const update=(index:number,patch:Partial<PendingManagementRule>)=>change(value.map((rule,i)=>{if(i!==index)return rule;const next:PendingManagementRule={...rule,...patch};if(next.groupType!=='DEVICE_GROUP'||!next.ownDevices){const {ownDevices:_,...rest}=next;return rest;}return next;}));
 return <fieldset disabled={disabled}><legend>Scoped management permissions</legend>{value.map((rule,index)=><fieldset key={index}><legend>Management rule {index+1}</legend>
  <label>Managed group type<select value={rule.groupType} onChange={e=>update(index,{groupType:e.target.value as EnterpriseManagementRule['groupType'],groups:{ids:[],all:false}})}>{Object.keys(groupKinds).map(kind=><option key={kind}>{kind}</option>)}</select></label>
  <GroupSelector client={client} kind={groupKinds[rule.groupType]} label={`Managed groups for rule ${index+1}`} value={rule.groups} change={groups=>update(index,{groups})}/>
  {rule.groupType==='DEVICE_GROUP'&&<><label className="checkbox"><input type="checkbox" checked={Boolean((rule as PendingManagementRule).ownDevices)} onChange={e=>update(index,{ownDevices:e.target.checked})}/> Own devices only</label>
  <p className="hint">With approve-device, the user may approve and unapprove only devices they own, including a pending enrollment they look up by its code. Approved devices are placed in the one device group above, and each user may own one such device.</p></>}
  <label>Management actions, separated by commas<input value={rule.actions.join(', ')} onChange={e=>update(index,{actions:[...new Set(e.target.value.split(',').map(s=>s.trim()).filter(Boolean))]})}/></label>
  <p>Actions include read, create, update, delete, enable, disable, grant, manage-role, approve-operation, approve-configuration, approve-device, revoke-device, deploy, build, publish and audit.</p>
  <label>Management valid from (Unix milliseconds)<input type="number" min={0} value={rule.conditions.notBeforeUnixMs} onChange={e=>update(index,{conditions:{...rule.conditions,notBeforeUnixMs:Number(e.target.value)}})}/></label>
  <label>Management expiry (Unix milliseconds; 0 is unbounded)<input type="number" min={0} value={rule.conditions.expiresAtUnixMs} onChange={e=>update(index,{conditions:{...rule.conditions,expiresAtUnixMs:Number(e.target.value)}})}/></label>
  <p>Grantable ceilings bound which complete runtime scopes this administrator may assign. A ceiling gives no runtime execution permission.</p>
  {rule.grantableScopes.map((scope,scopeIndex)=><div key={scopeIndex}><EnterpriseScopeEditor client={client} value={scope} change={next=>update(index,{grantableScopes:rule.grantableScopes.map((s,i)=>i===scopeIndex?next:s)})}/><button type="button" onClick={()=>update(index,{grantableScopes:rule.grantableScopes.filter((_,i)=>i!==scopeIndex)})}>Remove ceiling {scopeIndex+1}</button></div>)}
  <button type="button" onClick={()=>update(index,{grantableScopes:[...rule.grantableScopes,emptyScope]})}>Add grantable ceiling</button>
  <button type="button" onClick={()=>change(value.filter((_,i)=>i!==index))}>Remove management rule {index+1}</button>
 </fieldset>)}<button type="button" onClick={()=>change([...value,{actions:[],groupType:'TEAM',groups:{ids:[],all:false},grantableScopes:[],conditions:emptyConditions}])}>Add management rule</button></fieldset>;
}
