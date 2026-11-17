// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {expect,it} from 'vitest';
import {unzipSync,zipSync,strToU8} from 'fflate';
import type {DeploymentProfile,TriggerAssignment,TriggerSubscription} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from '../src/pendingContracts';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';
import {applyWorkflow,exportWorkflow as packWorkflow,importWorkflow,removeWorkflow,workflowScope,workflowConnections,workflowAssignmentMatches} from '../src/workflowBundle';
import {mockWorkflowPackages} from './workflowPackageFixture';

type Mutable<T>=T extends Uint8Array?Uint8Array:T extends readonly (infer E)[]?Mutable<E>[]:T extends object?{-readonly [K in keyof T]:Mutable<T[K]>}:T;
const mutable=<T,>(value:T):Mutable<T>=>structuredClone(value) as Mutable<T>;
const original={profileVersion:2,profileId:'test-profile',packages:[],workflows:mutable(fixture.WorkflowConfiguration)} as DeploymentProfile;
const first=original.workflows!.workflows[0];
const binding=original.workflows!.dispatchBindings[0];
const assignments=[{...fixture.TriggerAssignment,metadata:{...fixture.TriggerAssignment.metadata,uid:binding.sourceAssignmentUid,name:'source'}}] as TriggerAssignment[];
const subscriptions=[{...fixture.TriggerSubscription,metadata:{...fixture.TriggerSubscription.metadata,uid:binding.subscriptionUid,name:'events'}}] as TriggerSubscription[];

const snapshot={formatVersion:2,tenantId:'tenant-a',revision:1,agents:[],agentGroups:[],roles:[],grants:[],tools:[],toolGroups:[],deviceGroups:[],bindings:[],policies:[],extractors:[],teams:[],devices:[],delegations:[],agentDelegations:[],workloadBindings:[]} as ConfigurationFile;
const exportWorkflow=async(profile:DeploymentProfile,id:string,a:TriggerAssignment[],s:TriggerSubscription[])=>packWorkflow(profile,id,a,s,snapshot,await mockWorkflowPackages(profile,a));

it('exports profile group placement without copying the source device and matches a different destination device',()=>{
  const profile=mutable(original);
  profile.packages.push({packageId:'examples.hotfolder-watch',version:'1.0.0',source:'PREBUILT_CORE',required:true,deviceGroupIds:['finance-desktops'],triggers:[{assignment:'source',triggerId:assignments[0].spec.trigger,config:{folder:'inbox'}}]});
  const connection=workflowConnections(profile,assignments,subscriptions)[0];
  expect(connection.assignmentSpec.target).toEqual({executor:'CLIENT',deviceGroups:['finance-desktops'],requiredDeviceGroupSets:[['finance-desktops']]});
  expect(JSON.stringify(connection.assignmentSpec)).not.toContain('device-42');
  const destination=mutable(assignments[0]);destination.spec.target={executor:'CLIENT',devices:['new-device'],requiredDeviceGroupSets:[['finance-desktops']]};
  expect(workflowAssignmentMatches(profile,destination,connection.assignmentSpec)).toBe(true);
  destination.spec.config={folder:'different'};
  expect(workflowAssignmentMatches(profile,destination,connection.assignmentSpec)).toBe(false);
});

it('exports one compressed workflow with its dependency closure and no README, host settings or credentials',async()=>{
  const profile=mutable(original);profile.workflows!.workflows.push({...mutable(first),id:'other'});
  const bytes=await exportWorkflow(profile,first.id,assignments,subscriptions);
  const files=unzipSync(bytes);
  expect(Object.keys(files).filter(name=>!name.startsWith('packages/')).sort()).toEqual(['manifest.json','referenced-data.json','workflow.json']);
  const tools=Object.keys(files).filter(name=>name.startsWith('packages/'));
  expect(tools).toHaveLength(2);
  expect(tools.every(name=>name.endsWith('.olotool'))).toBe(true);
  const bundle=await importWorkflow('working.olo',bytes);
  expect(bundle.profile.workflows!.workflows.map(item=>item.id)).toEqual([first.id]);
  expect(bundle.profile.workflows!.agentDefinitions).toEqual(original.workflows!.agentDefinitions);
  expect(bundle.profile.workflows!.modelDeployments).toEqual(original.workflows!.modelDeployments);
  expect(bundle.connections[0]).toMatchObject({id:binding.id,assignment:'source',subscription:'events'});
  expect(bundle.connections[0].assignmentSpec).toEqual(assignments[0].spec);
});
it('replaces one workflow, repins its version and resolves trigger names in the destination',async()=>{
  const bundle=mutable(await importWorkflow('working.olo',await exportWorkflow(original,first.id,assignments,subscriptions)));
  bundle.profile.workflows!.workflows[0].systemPrompt='Imported configuration';
  const next=applyWorkflow(original,bundle,first.id,[{metadata:{uid:'destination-source',name:'source'}}] as TriggerAssignment[],[{metadata:{uid:'destination-events',name:'events'}}] as TriggerSubscription[]);
  expect(next.workflows!.workflows[0].systemPrompt).toBe('Imported configuration');
  expect(next.workflows!.workflows[0].versionId).not.toBe(first.versionId);
  expect(next.workflows!.dispatchBindings[0]).toMatchObject({subscriptionUid:'destination-events',sourceAssignmentUid:'destination-source',workflowVersionId:next.workflows!.workflows[0].versionId});
  expect(original.workflows!.workflows[0]).toEqual(first);
});
it('preserves shared definitions and removes only the deleted workflow and its exclusive definitions',()=>{
  const profile=mutable(original);
  const agent=mutable(profile.workflows!.agentDefinitions[0]);agent.agentId='exclusive-agent';
  const second={...mutable(first),id:'second',agents:[{...first.agents[0],agentId:agent.agentId}],agentStages:undefined};
  profile.workflows!.workflows.push(second);profile.workflows!.agentDefinitions.push(agent);
  const next=removeWorkflow(profile,'second');
  expect(next.workflows!.workflows).toEqual([first]);
  expect(next.workflows!.agentDefinitions).toEqual(original.workflows!.agentDefinitions);
  expect(next.workflows!.modelGroups).toEqual(profile.workflows!.modelGroups);
  const empty=removeWorkflow(next,first.id);
  expect(empty.workflows).toEqual({workflows:[],dispatchBindings:[],agentDefinitions:[],modelDeployments:[],modelGroups:[],workflowGroups:[]});
});
it('refuses an implicit change to a definition used by another workflow',async()=>{
  const profile=mutable(original);profile.workflows!.workflows.push({...mutable(first),id:'other'});
  const bundle=mutable(await importWorkflow('working.olo',await exportWorkflow(original,first.id,assignments,subscriptions)));
  bundle.profile.workflows!.agentDefinitions[0].systemPrompt='Changed shared agent';
  expect(()=>applyWorkflow(profile,bundle,first.id,assignments,subscriptions)).toThrow('Shared agentDefinitions');
});
it('rejects tampering, unsupported stack archives, traversal, wrong extensions and oversized bundles',async()=>{
  const bytes=await exportWorkflow(original,first.id,assignments,subscriptions),files=unzipSync(bytes);
  files['workflow.json']=strToU8('{}');
  await expect(importWorkflow('working.olo',zipSync(files))).rejects.toThrow('verification');
  await expect(importWorkflow('working.olo',zipSync({'../workflow.json':strToU8('{}')}))).rejects.toThrow();
  await expect(importWorkflow('working.olo',zipSync({'images.tar':new Uint8Array(1)}))).rejects.toThrow();
  await expect(importWorkflow('working.zip',bytes)).rejects.toThrow();
  await expect(importWorkflow('working.olo',new Uint8Array(3145729))).rejects.toThrow();
});
it('requires exact embedded packages and refuses incomplete inventories and the prior profile format',async()=>{
  await expect(packWorkflow(original,first.id,assignments,subscriptions,snapshot)).rejects.toThrow('missing');
  const files=unzipSync(await exportWorkflow(original,first.id,assignments,subscriptions));
  const manifest=JSON.parse(new TextDecoder().decode(files['manifest.json'])) as {version:number;packages:{path:string}[]};
  delete files[manifest.packages[0].path];
  await expect(importWorkflow('missing.olo',zipSync(files))).rejects.toThrow('Missing package');
  manifest.version=1;files['manifest.json']=strToU8(JSON.stringify(manifest));
  await expect(importWorkflow('prior.olo',zipSync(files))).rejects.toThrow('verification');
});
it('excludes unrelated models, agents and workflow groups',()=>{
  const profile=mutable(original);
  profile.workflows!.agentDefinitions.push({...profile.workflows!.agentDefinitions[0],agentId:'unused'});
  profile.workflows!.modelDeployments.push({...profile.workflows!.modelDeployments[0],id:'unused'});
  profile.workflows!.workflowGroups.push({id:'unused',displayName:'Unused'});
  expect(workflowScope(profile,first.id)).toEqual(original);
});

it('exports graph-only tool packages and retains their shared references when another workflow is removed',async()=>{
  const profile=mutable(original);
  const graph=profile.workflows!.workflows[0].graph;
  graph.packageId='olo.json-graph';
  graph.config={schemaVersion:1,nodes:[{id:'read',kind:'tool',agentId:first.agents[0].agentId,toolRef:'graph.files/read',input:{toolRef:'unrelated.package/fake'}}],
    edges:[{from:['START'],to:'read'},{from:['read'],to:'END'}],output:{$path:'/values/read'},outputSchema:{}};
  profile.packages.push({packageId:'graph.files',source:'MARKETPLACE',version:'1.0.0',required:true});
  profile.workflows!.workflows.push({...mutable(profile.workflows!.workflows[0]),id:'shared-graph'});
  expect(workflowScope(profile,first.id).packages.map(item=>item.packageId)).toContain('graph.files');
  const bundle=await importWorkflow('graph.olo',await exportWorkflow(profile,first.id,assignments,subscriptions));
  expect(bundle.packages.map(item=>item.metadata.packageId)).toContain('graph.files');
  expect(bundle.packages.map(item=>item.metadata.packageId)).not.toContain('unrelated.package');
  expect(removeWorkflow(profile,first.id).packages.map(item=>item.packageId)).toContain('graph.files');
  const archives=await mockWorkflowPackages(profile,assignments);
  const withoutGraph=[];for(const archive of archives){const content=unzipSync(archive);if(JSON.parse(new TextDecoder().decode(content['toolgate-package.json'])).id!=='graph.files')withoutGraph.push(archive);}
  await expect(packWorkflow(profile,first.id,assignments,subscriptions,snapshot,withoutGraph)).rejects.toThrow('missing');
});

it('rejects former nested tool filenames and tool archives as workflow filenames',async()=>{
  const bytes=await exportWorkflow(original,first.id,assignments,subscriptions);
  await expect(importWorkflow('tool.olotool',bytes)).rejects.toThrow();
  for(const extension of ['.olo','.tgpkg']){
    const files=unzipSync(bytes);
    const manifest=JSON.parse(new TextDecoder().decode(files['manifest.json']));
    const previous=manifest.packages[0].path;
    const obsolete=previous.replace(/\.olotool$/,extension);
    files[obsolete]=files[previous];delete files[previous];
    manifest.packages[0].path=obsolete;files['manifest.json']=strToU8(JSON.stringify(manifest));
    await expect(importWorkflow('workflow.olo',zipSync(files))).rejects.toThrow();
  }
});
