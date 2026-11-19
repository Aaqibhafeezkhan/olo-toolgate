// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {DeploymentProfile} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from './pendingContracts';
import {configuredToolIds} from './workflowTools';

const kinds=['agents','agentGroups','roles','grants','tools','toolGroups','deviceGroups','bindings','policies','extractors'] as const;
const emptyKinds=['teams','devices','delegations','agentDelegations','workloadBindings'] as const;
type Row=Record<string,unknown>&{id:string};
const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value)) as T;
function stable(value:unknown):unknown{return Array.isArray(value)?value.map(stable):value&&typeof value==='object'?Object.fromEntries(Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([key,item])=>[key,stable(item)])):value;}
const equal=(left:Row,right:Row)=>JSON.stringify(stable({...left,revision:0}))===JSON.stringify(stable({...right,revision:0}));
const names:Record<string,string>={agentId:'agents',agentIds:'agents',agentGroupId:'agentGroups',agentGroupIds:'agentGroups',toolId:'tools',toolIds:'tools',toolGroupId:'toolGroups',toolGroupIds:'toolGroups',deviceGroupId:'deviceGroups',deviceGroupIds:'deviceGroups',roleId:'roles',roleIds:'roles',extractorId:'extractors',bindingId:'bindings',bindingIds:'bindings',policyId:'policies'};

/** Configuration only. Human accounts, credentials, enrolled devices and workload identities are never copied. */
export function referencedData(profile:DeploymentProfile,snapshot:ConfigurationFile):ConfigurationFile{
  const source=snapshot as unknown as Record<string,Row[]>;
  const wanted=new Map<string,Set<string>>(kinds.map(key=>[key,new Set<string>()]));
  function visit(value:unknown){if(!value||typeof value!=='object')return;for(const [key,item] of Object.entries(value)){
    if(['config','arguments','globals','parameterSchema','resultSchema','outputSchema','graph','systemPrompt'].includes(key))continue;
    const kind=names[key];if(kind){for(const id of Array.isArray(item)?item:[item])if(typeof id==='string')wanted.get(kind)?.add(id);}
    if(['agentGroups','toolGroups','deviceGroups'].includes(key)&&item&&typeof item==='object'&&'ids' in item)for(const id of (item as {ids:unknown[]}).ids??[])if(typeof id==='string')wanted.get(key)?.add(id);
    if(Array.isArray(item))item.forEach(visit);else visit(item);
  }}
  visit(profile.workflows?.workflows.map(row=>({agents:row.agents,contextSources:row.contextSources,deviceGroupId:row.deployment?.source.deviceGroupId})));
  visit(profile.workflows?.agentDefinitions.map(row=>({agentId:row.agentId,contextSources:row.contextSources})));
  visit(profile.workflows?.modelDeployments.map(row=>({toolGroupId:row.toolGroupId,deviceGroupId:row.deviceGroupId})));
  visit(profile.packages.map(row=>({tools:row.tools,deviceGroupIds:row.deviceGroupIds,bindingIds:row.bindingIds})));
  for(const toolId of configuredToolIds(profile))wanted.get('tools')!.add(toolId);
  let changed=true;const selected=new Map<string,Map<string,Row>>(kinds.map(key=>[key,new Map()]));
  while(changed){changed=false;for(const key of kinds)for(const original of source[key]??[]){
    const sourceKind=original.sourceType==='AGENT'?'agents':original.sourceType==='ROLE'?'roles':original.sourceType==='AGENT_GROUP'?'agentGroups':undefined;
    if(sourceKind&&wanted.get(sourceKind)?.has(String(original.sourceId)))wanted.get(key)!.add(original.id);
    const selectors=original.agentGroups as {ids?:string[]}|undefined;
    if(key==='toolGroups'&&(original.toolIds as string[]??[]).some(id=>wanted.get('tools')!.has(id)))wanted.get(key)!.add(original.id);
    if(key==='bindings'&&wanted.get('toolGroups')!.has(String(original.toolGroupId)))wanted.get(key)!.add(original.id);
    if((key==='bindings'||key==='policies')&&selectors?.ids?.some(id=>wanted.get('agentGroups')!.has(id)))wanted.get(key)!.add(original.id);
    if(!wanted.get(key)!.has(original.id)||selected.get(key)!.has(original.id))continue;
    const row=clone(original);
    if(key==='agentGroups')row.agentIds=(row.agentIds as string[]).filter(id=>wanted.get('agents')!.has(id));
    if(key==='deviceGroups')row.deviceIds=[];
    selected.get(key)!.set(row.id,row);visit(row);changed=true;
  }}
  const result:Record<string,unknown>={formatVersion:2,tenantId:snapshot.tenantId,revision:0};
  for(const key of kinds)result[key]=[...selected.get(key)!.values()];
  for(const key of emptyKinds)result[key]=[];
  const owners=new Set((result.agents as Row[]).map(row=>row.ownerUserId).filter((id):id is string=>typeof id==='string'));
  result.requiredIdentities=[...owners].map(id=>({id,teamIds:[]}));
  return result as ConfigurationFile;
}

export function validateReferences(value:unknown):ConfigurationFile{
  if(!value||typeof value!=='object')throw Error('Invalid referenced configuration.');
  const object=value as Record<string,unknown>,allowed=new Set(['formatVersion','tenantId','revision','requiredIdentities',...kinds,...emptyKinds]);
  if(object.formatVersion!==2||typeof object.tenantId!=='string'||Object.keys(object).some(key=>!allowed.has(key))||[...kinds,...emptyKinds].some(key=>!Array.isArray(object[key]))||emptyKinds.some(key=>(object[key] as unknown[]).length))throw Error('Workflow references cannot contain user accounts, credentials or runtime state.');
  if((object.deviceGroups as Row[]).some(row=>!Array.isArray(row.deviceIds)||row.deviceIds.length))throw Error('Bot references cannot allocate device group membership.');
  return value as ConfigurationFile;
}

/** Existing definitions are preserved; additions go through the ordinary reviewed MERGE operation. */
export function mergeReferences(incoming:ConfigurationFile,current:ConfigurationFile):ConfigurationFile{
  validateReferences(incoming);
  const source=incoming as unknown as Record<string,Row[]>,existing=current as unknown as Record<string,Row[]>;
  const next=clone(incoming) as unknown as Record<string,unknown>;
  next.tenantId=current.tenantId;next.revision=current.revision;
  // The current ControlSnapshot request requires these collections. MERGE with
  // empty arrays neither imports human accounts nor changes runtime identities.
  next.users=[];next.identityBindings=[];next.deviceEvidence=[];
  for(const key of kinds)next[key]=(source[key]??[]).map(row=>{
    const old=existing[key]?.find(item=>item.id===row.id);
    if(!old)return row;
    const comparable=clone(row),prior=clone(old);
    if(key==='tools'&&row.version===old.version&&JSON.stringify(stable(row.definition))===JSON.stringify(stable(old.definition)))return old;
    if(key==='bindings')comparable.allowedPackageDigests=prior.allowedPackageDigests;
    if(key==='agentGroups'){comparable.agentIds=prior.agentIds=[];}
    if(key==='toolGroups'){comparable.toolIds=prior.toolIds=[];}
    if(key==='deviceGroups'){comparable.deviceIds=prior.deviceIds=[];}
    if(['agentGroups','toolGroups','deviceGroups'].includes(key))comparable.name=prior.name;
    if(!equal(comparable,prior))throw Error(`Existing referenced ${key} ${row.id} differs. Resolve the conflict without replacing shared data.`);
    if(key==='agentGroups')return {...old,agentIds:[...new Set([...(old.agentIds as string[]),...(row.agentIds as string[])])]};
    if(key==='toolGroups')return {...old,toolIds:[...new Set([...(old.toolIds as string[]),...(row.toolIds as string[])])]};
    return old;
  });
  return next as ConfigurationFile;
}
