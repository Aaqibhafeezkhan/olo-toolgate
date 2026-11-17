// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
/** Small package test doubles for workflow transfer tests; Control uploads are mocked. */
import {strToU8,zipSync} from 'fflate';
import type {DeploymentProfile,TriggerAssignment} from '@olo-labs/toolgate-contracts/v2';
import {sha256} from '../src/workflowPackages';

export async function mockWorkflowPackages(profile:DeploymentProfile,assignments:readonly TriggerAssignment[]){
  const groups=new Map<string,{tools:unknown[];triggers:unknown[];version:string}>();
  for(const item of profile.workflows!.agentDefinitions.flatMap(item=>item.contextSources)){
    const id=item.toolId.split('/')[0],group=groups.get(id)??{tools:[],triggers:[],version:'1.0.0'};
    group.tools.push({descriptor:{toolId:item.toolId},toolDescriptorDigest:'sha256:'+'a'.repeat(64)});groups.set(id,group);
  }
  for(const item of assignments){const id=item.spec.trigger.split('/')[0],group=groups.get(id)??{tools:[],triggers:[],version:item.spec.packageVersion};group.triggers.push({descriptor:{triggerId:item.spec.trigger}});groups.set(id,group);}
  const result=[];
  for(const [id,group] of groups){const raw=strToU8(JSON.stringify({schemaVersion:2,id,version:group.version,tools:group.tools,triggers:group.triggers,descriptorSetDigest:'sha256:'+'a'.repeat(64)}));result.push(zipSync({'toolgate-package.json':raw,'CHECKSUMS.sha256':strToU8((await sha256(raw)).slice(7)+'  toolgate-package.json\n')},{level:6}));}
  return result;
}
