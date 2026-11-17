// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {strToU8,unzipSync,zipSync} from 'fflate';
import type {DeploymentProfile,WorkflowConfiguration,WorkflowDefinition,TriggerAssignment,TriggerSubscription,WorkflowBundleManifest,WorkflowBundleConnection} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from './pendingContracts';
import {referencedData,validateReferences} from './workflowReferences';
import {inspectPackage,MAX_BUNDLE_BYTES,MAX_PACKAGE_BYTES,sha256,verifyPackageCoverage,type EmbeddedPackage} from './workflowPackages';
import {graphToolIds} from './workflowTools';

const MAX_PROFILE=262144,MAX_REFERENCES=2097152;
const collections=['workflowGroups','modelGroups','modelDeployments','agentDefinitions','workflows','dispatchBindings'] as const;
type Connection=WorkflowBundleConnection;
export type WorkflowBundle={profile:DeploymentProfile;connections:readonly Connection[];references:ConfigurationFile;packages:readonly EmbeddedPackage[]};
type Mutable<T>=T extends readonly (infer E)[]?Mutable<E>[]:T extends object?{-readonly [K in keyof T]:Mutable<T[K]>}:T;
const copy=<T,>(value:T):Mutable<T>=>JSON.parse(JSON.stringify(value)) as Mutable<T>;
function fail(message='Choose a valid workflow .olo bundle.'):never{throw Error(message);}
const identity=(value:unknown):value is string=>typeof value==='string'&&/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value);
const object=(value:unknown):value is Record<string,unknown>=>Boolean(value)&&typeof value==='object'&&!Array.isArray(value);
function canonical(value:unknown):unknown{return Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([key,item])=>[key,canonical(item)])):value;}
const same=(a:unknown,b:unknown)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
function configuration(profile:DeploymentProfile):WorkflowConfiguration{
  if(!object(profile)||profile.profileVersion!==2||!identity(profile.profileId)||!Array.isArray(profile.packages)||!profile.workflows||collections.some(key=>!Array.isArray(profile.workflows![key])))fail();
  for(const key of collections){const ids=new Set();for(const item of profile.workflows[key]){const id=key==='agentDefinitions'?('agentId' in item?item.agentId:undefined):('id' in item?item.id:undefined);if(!identity(id)||ids.has(id))fail();ids.add(id);}}
  return profile.workflows;
}
/** Select one workflow and its local dependency closure. Deployment hints remain inert configuration. */
export function workflowScope(profile:DeploymentProfile,id:string):DeploymentProfile{
  const source=configuration(profile),workflow=source.workflows.find(item=>item.id===id);
  if(!workflow)fail('Select an existing workflow.');
  const agents=new Set(workflow.agents.map(item=>item.agentId));
  const agentDefinitions=source.agentDefinitions.filter(item=>agents.has(item.agentId));
  if(agentDefinitions.length!==agents.size)fail('Workflow agent definitions are incomplete.');
  const groups=new Set([workflow.modelGroupId,...agentDefinitions.map(item=>item.modelGroupId)]);
  const modelGroups=source.modelGroups.filter(item=>groups.has(item.id));
  if(modelGroups.length!==groups.size)fail('Workflow model groups are incomplete.');
  const deployments=new Set(modelGroups.flatMap(item=>item.deploymentIds));
  const modelDeployments=source.modelDeployments.filter(item=>deployments.has(item.id));
  if(modelDeployments.length!==deployments.size)fail('Workflow model deployments are incomplete.');
  const workflowGroups=source.workflowGroups.filter(item=>item.id===workflow.workflowGroupId);
  if(workflowGroups.length!==1)fail('Workflow group is unavailable.');
  const packageIds=new Set([...(workflow.contextSources??[]),...agentDefinitions.flatMap(item=>item.contextSources)].map(item=>item.toolId.split('/')[0]));
  for(const toolId of graphToolIds(workflow))packageIds.add(toolId.split('/')[0]);
  if(workflow.deployment)packageIds.add(workflow.deployment.source.packageId);
  const definition=copy(workflow);
  return copy({profileVersion:2,profileId:profile.profileId,packages:profile.packages.filter(item=>packageIds.has(item.packageId)),
    workflows:{workflowGroups,modelGroups,modelDeployments,agentDefinitions,workflows:[definition],dispatchBindings:source.dispatchBindings.filter(item=>item.workflowId===id)}});
}

/** Prune only the removed workflow's dependency closure, keeping shared and unrelated draft data. */
export function removeWorkflow(profile:DeploymentProfile,id:string):DeploymentProfile{
  const config=configuration(profile),removed=workflowScope(profile,id),others=config.workflows.filter(item=>item.id!==id);
  const scopes=others.map(item=>workflowScope(profile,item.id));
  const next=copy(profile),result=next.workflows!;
  for(const key of ['agentDefinitions','workflowGroups','modelGroups','modelDeployments'] as const){
    const field=(item:unknown)=>key==='agentDefinitions'?(item as {agentId:string}).agentId:(item as {id:string}).id;
    const candidates=new Set(removed.workflows![key].map(field)),shared=new Set(scopes.flatMap(scope=>scope.workflows![key].map(field)));
    if(key==='modelGroups')for(const agent of result.agentDefinitions)shared.add(agent.modelGroupId);
    if(key==='modelDeployments')for(const group of result.modelGroups)for(const model of group.deploymentIds)shared.add(model);
    (result as unknown as Record<string,unknown>)[key]=config[key].filter(item=>!candidates.has(field(item))||shared.has(field(item)));
  }
  result.workflows=copy(others);
  result.dispatchBindings=copy(config.dispatchBindings.filter(item=>item.workflowId!==id));
  const candidates=new Set(removed.packages.map(item=>item.packageId)),shared=new Set(scopes.flatMap(scope=>scope.packages.map(item=>item.packageId)));
  next.packages=copy(profile.packages.filter(item=>!candidates.has(item.packageId)||shared.has(item.packageId)));
  return next;
}

/** Profile placement is portable; the selected physical device belongs only to the destination installation. */
export function portableAssignmentSpec(profile:DeploymentProfile,assignment:TriggerAssignment){
  const spec=copy(assignment.spec);
  const owner=profile.packages.find(item=>item.triggers?.some(trigger=>trigger.assignment===assignment.metadata.name));
  if(owner?.deviceGroupIds?.length){
    const local=profile.packages.filter(item=>item.deviceGroupIds?.length),required=local.filter(item=>item.required);
    const sets=[...(required.length?required:local),owner].map(item=>[...item.deviceGroupIds!].sort());
    spec.target={executor:'CLIENT',deviceGroups:[...owner.deviceGroupIds],requiredDeviceGroupSets:sets.filter((set,index)=>sets.findIndex(other=>same(other,set))===index)};
  }
  return spec;
}
export function workflowAssignmentMatches(profile:DeploymentProfile,assignment:TriggerAssignment,spec:Connection['assignmentSpec']){
  return same(portableAssignmentSpec(profile,assignment),spec);
}
export function workflowConnections(selected:DeploymentProfile,assignments:readonly TriggerAssignment[],subscriptions:readonly TriggerSubscription[]):Connection[]{
  return selected.workflows!.dispatchBindings.map(binding=>{
    const subscription=subscriptions.find(item=>item.metadata.uid===binding.subscriptionUid);
    const assignment=assignments.find(item=>item.metadata.uid===binding.sourceAssignmentUid);
    if(!subscription||!assignment)fail('Resolve the workflow trigger mappings before export.');
    const {owner:_,...spec}=subscription.spec;
    const names=selected.workflows!.dispatchBindings.filter(item=>item.subscriptionUid===binding.subscriptionUid).map(item=>assignments.find(entry=>entry.metadata.uid===item.sourceAssignmentUid)?.metadata.name).filter((item):item is string=>Boolean(item));
    return {id:binding.id,subscription:subscription.metadata.name,assignment:assignment.metadata.name,assignmentSpec:portableAssignmentSpec(selected,assignment),subscriptionSpec:{...copy(spec),sources:{assignments:[...new Set(names)]}}};
  });
}

export async function exportWorkflow(profile:DeploymentProfile,id:string,assignments:readonly TriggerAssignment[],subscriptions:readonly TriggerSubscription[],snapshot:ConfigurationFile,archives:readonly Uint8Array[]=[]):Promise<Uint8Array>{
  const selected=copy(workflowScope(profile,id));
  const connections=workflowConnections(selected,assignments,subscriptions);
  const assignmentNames=new Set(connections.map(item=>item.assignment));
  for(const entry of selected.packages)if(entry.triggers)entry.triggers=entry.triggers.filter(item=>assignmentNames.has(item.assignment));
  if(archives.reduce((total,item)=>total+item.length,0)>MAX_BUNDLE_BYTES)fail('Embedded packages exceed 128 MiB.');
  const packages:EmbeddedPackage[]=[];for(const archive of archives)packages.push(await inspectPackage(archive));
  for(const pin of selected.packages){
    const matching=packages.filter(item=>item.metadata.packageId===pin.packageId&&item.metadata.version===pin.version&&(!pin.packageDigest||pin.packageDigest===item.metadata.packageDigest));
    if(matching.length!==1)fail('Profile package is missing its exact embedded archive: '+pin.packageId);
    pin.source='BUNDLE';pin.packageDigest=matching[0].metadata.packageDigest;
  }
  const bytes=strToU8(JSON.stringify(selected));
  if(bytes.length>MAX_PROFILE)fail('Workflow configuration exceeds the Control size limit.');
  const referenced=referencedData(selected,snapshot),references=strToU8(JSON.stringify(referenced));
  if(references.length>MAX_REFERENCES)fail('Referenced configuration exceeds the size limit.');
  verifyPackageCoverage(selected,referenced,connections,packages);
  const manifest:WorkflowBundleManifest={format:'olo-workflow',version:2,workflowId:id,sha256:await sha256(bytes),referencesSha256:await sha256(references),connections,packages:packages.map(item=>item.metadata)};
  const files={'manifest.json':strToU8(JSON.stringify(manifest)),'workflow.json':bytes,'referenced-data.json':references,...Object.fromEntries(packages.map(item=>[item.metadata.path,item.bytes]))};
  const result=zipSync(files,{level:6});if(result.length>MAX_BUNDLE_BYTES)fail('Workflow bundle exceeds 128 MiB.');return result;
}

export async function importWorkflow(name:string,bytes:Uint8Array):Promise<WorkflowBundle>{
  if(!name.endsWith('.olo')||bytes.length>MAX_BUNDLE_BYTES||bytes.length<22)fail();
  const seen=new Set<string>();let expanded=0;
  const files=unzipSync(bytes,{filter:entry=>{
    const artifact=/^packages\/[a-f0-9]{64}\.olotool$/.test(entry.name);
    const limit=artifact?MAX_PACKAGE_BYTES:entry.name==='referenced-data.json'?MAX_REFERENCES:entry.name==='workflow.json'?MAX_PROFILE:262144;
    if(!artifact&&!['manifest.json','workflow.json','referenced-data.json'].includes(entry.name)||seen.has(entry.name)||seen.size>=35||entry.compression!==8||entry.originalSize>limit||(expanded+=entry.originalSize)>256*1024*1024)fail('Unsupported or oversized workflow archive.');
    seen.add(entry.name);return true;
  }});
  if(!files['manifest.json']||!files['workflow.json']||!files['referenced-data.json'])fail();
  const decode=(value:Uint8Array)=>JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(value)) as unknown;
  const manifest=decode(files['manifest.json']);
  if(!object(manifest)||Object.keys(manifest).some(key=>!['build','connections','format','packages','referencesSha256','sha256','version','workflowId'].includes(key))||manifest.format!=='olo-workflow'||manifest.version!==2||!identity(manifest.workflowId)||!Array.isArray(manifest.connections)||manifest.connections.length>256||!Array.isArray(manifest.packages)||manifest.packages.length>32||manifest.sha256!==await sha256(files['workflow.json'])||manifest.referencesSha256!==await sha256(files['referenced-data.json']))fail('Workflow bundle verification failed.');
  const profile=decode(files['workflow.json']) as DeploymentProfile;
  const config=configuration(profile);
  if(Object.keys(profile).some(key=>!['profileVersion','profileId','packages','workflows'].includes(key))||config.workflows.length!==1||config.workflows[0].id!==manifest.workflowId)fail();
  const expected=workflowScope(profile,manifest.workflowId);
  if(!same(expected,profile))fail('Workflow bundle contains unrelated configuration.');
  const connections=manifest.connections as Connection[];
  if(connections.length!==config.dispatchBindings.length||new Set(connections.map(item=>item.id)).size!==connections.length||connections.some(item=>!object(item)||Object.keys(item).sort().join(',')!=='assignment,assignmentSpec,id,subscription,subscriptionSpec'||!identity(item.id)||!identity(item.assignment)||!identity(item.subscription)||!object(item.assignmentSpec)||!object(item.subscriptionSpec)||!config.dispatchBindings.some(binding=>binding.id===item.id)))fail();
  const packages:EmbeddedPackage[]=[];
  for(const item of manifest.packages){
    if(!object(item)||typeof item.path!=='string'||!/^packages\/[a-f0-9]{64}\.olotool$/.test(item.path)||!files[item.path])fail('Missing package archive.');
    const entry=await inspectPackage(files[item.path]);if(!same(entry.metadata,item)||packages.some(prior=>prior.metadata.path===item.path))fail('Package inventory verification failed.');packages.push(entry);
  }
  if(seen.size!==3+packages.length)fail('Unlisted package archive.');
  const references=validateReferences(decode(files['referenced-data.json']));verifyPackageCoverage(profile,references,connections,packages);
  if(profile.packages.some(pin=>pin.source!=='BUNDLE'||!packages.some(item=>item.metadata.packageId===pin.packageId&&item.metadata.version===pin.version&&item.metadata.packageDigest===pin.packageDigest)))fail('Profile package must use its exact embedded archive.');
  return {profile,connections,references,packages};
}

/** Replace only the selected workflow. Shared definitions cannot change other workflows implicitly. */
export function applyWorkflow(target:DeploymentProfile,bundle:WorkflowBundle,targetId:string|undefined,assignments:readonly TriggerAssignment[],subscriptions:readonly TriggerSubscription[],allowUnresolved=false):DeploymentProfile{
  const current=configuration(target),incoming=configuration(bundle.profile),source=incoming.workflows[0];
  const id=targetId||source.id,previous=current.workflows.find(item=>item.id===id);
  if(targetId&&!previous)fail('The replacement workflow is no longer present.');
  const others=current.workflows.filter(item=>item.id!==id);
  const next=copy(previous?removeWorkflow(target,id):target),config=next.workflows!;
  for(const key of ['workflowGroups','modelGroups','modelDeployments','agentDefinitions'] as const){
    const field=key==='agentDefinitions'?'agentId':'id';
    const existing=config[key] as unknown as Record<string,unknown>[];
    const additions=incoming[key] as unknown as Record<string,unknown>[];
    for(const item of additions){const old=existing.find(value=>value[field]===item[field]);if(old&&!same(old,item))fail(`Shared ${key} definition ${String(item[field])} differs. Use separate identifiers before importing.`);}
    (config as unknown as Record<string,unknown>)[key]=[...existing.filter(item=>!additions.some(value=>value[field]===item[field])),...copy(additions)];
  }
  const versionId=`rev-${crypto.randomUUID()}`;
  const workflow:WorkflowDefinition={...copy(source),id,versionId};
  config.workflows=copy([...others,workflow]);
  const mapped=incoming.dispatchBindings.flatMap(binding=>{
    const connection=bundle.connections.find(item=>item.id===binding.id)!;
    const subscription=subscriptions.find(item=>item.metadata.name===connection.subscription);
    const assignment=assignments.find(item=>item.metadata.name===connection.assignment);
    if(!subscription||!assignment){if(allowUnresolved)return [];fail(`Configure subscription ${connection.subscription} and assignment ${connection.assignment} before importing.`);}
    return [{...copy(binding),workflowId:id,workflowVersionId:versionId,subscriptionUid:subscription.metadata.uid,sourceAssignmentUid:assignment.metadata.uid}];
  });
  if(mapped.some(binding=>current.dispatchBindings.some(old=>old.workflowId!==id&&(old.id===binding.id||old.subscriptionUid===binding.subscriptionUid&&old.sourceAssignmentUid===binding.sourceAssignmentUid))))fail('An imported trigger mapping conflicts with another workflow.');
  config.dispatchBindings=copy([...current.dispatchBindings.filter(item=>item.workflowId!==id),...mapped]);
  for(const item of bundle.profile.packages){
    const old=next.packages.find(value=>value.packageId===item.packageId);
    if(!old){next.packages.push(copy(item));continue;}
    const shared=['hostIds','deviceGroupIds','bindingIds','tools','triggers','required'];
    const base=(value:unknown)=>Object.fromEntries(Object.entries(value as Record<string,unknown>).filter(([key])=>!shared.includes(key)));
    if(!same(base(old),base(item)))fail('A shared package pin or global configuration differs; review it separately.');
    old.required=old.required||item.required;
    for(const key of ['hostIds','deviceGroupIds','bindingIds'] as const)if(item[key])old[key]=[...new Set([...(old[key]??[]),...item[key]])];
    for(const [key,field] of [['tools','toolId'],['triggers','assignment']] as const){
      const entries=(old[key]??[]) as unknown as Record<string,unknown>[];
      for(const entry of item[key]??[]){const existing=entries.find(value=>value[field]===(entry as unknown as Record<string,unknown>)[field]);if(existing&&!same(existing,entry))fail('A shared package export or trigger declaration differs.');if(!existing)entries.push(copy(entry) as unknown as Record<string,unknown>);}
      if(item[key])(old as unknown as Record<string,unknown>)[key]=entries;
    }
  }
  if(strToU8(JSON.stringify(next)).length>MAX_PROFILE)fail('Combined profile exceeds the Control size limit.');
  return next;
}
