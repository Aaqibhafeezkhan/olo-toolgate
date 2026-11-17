// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {expect,it} from 'vitest';
import type {DeploymentProfile} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from '../src/pendingContracts';
import {mergeReferences,referencedData,validateReferences} from '../src/workflowReferences';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';

const profile={profileVersion:2,profileId:'scope',packages:[],workflows:fixture.WorkflowConfiguration} as DeploymentProfile;
const agent=profile.workflows!.workflows[0].agents[0];
const snapshot={formatVersion:2,tenantId:'source',revision:3,
  users:[{id:'admin',password:'excluded'}],agents:[{id:agent.agentId,ownerUserId:'admin'},{id:'other-agent'}],
  agentGroups:[{id:agent.agentGroupId,agentIds:[agent.agentId,'other-agent'],roleIds:['actor-role']}],
  roles:[{id:'actor-role',portalRole:'BASIC'},{id:'unrelated-admin-role',portalRole:'SUPER_ADMIN'}],
  grants:[{id:'capability',sourceType:'ROLE',sourceId:'actor-role',scope:{}}],
  devices:[{id:'enrolled-device',secret:'excluded'}],deviceGroups:[],toolGroups:[],tools:[],bindings:[],policies:[],extractors:[],teams:[],delegations:[],agentDelegations:[],workloadBindings:[],
} as unknown as ConfigurationFile;

it('includes referenced actors, groups, roles and grants while excluding unrelated users and runtime identities',()=>{
  const data=referencedData(profile,snapshot);
  expect(data.agents.map(item=>item.id)).toEqual([agent.agentId]);
  expect(data.agentGroups[0].agentIds).toEqual([agent.agentId]);
  expect(data.roles.map(item=>item.id)).toEqual(['actor-role']);
  expect(data.grants.map(item=>item.id)).toEqual(['capability']);
  expect(data.requiredIdentities).toEqual([{id:'admin',teamIds:[]}]);
  expect(data.devices).toEqual([]);expect(data.users).toBeUndefined();
});
it('does not replace existing shared definitions or user accounts when preparing reference imports',()=>{
  const data=referencedData(profile,snapshot);
  const merged=mergeReferences(data,{...snapshot,tenantId:'destination',revision:9});
  expect(merged.tenantId).toBe('destination');expect(merged.revision).toBe(9);
  expect(merged.agentGroups[0].agentIds).toEqual([agent.agentId,'other-agent']);
  expect(merged.users).toEqual([]);expect(merged.identityBindings).toEqual([]);expect(merged.deviceEvidence).toEqual([]);
  expect(()=>mergeReferences({...data,roles:[{...data.roles[0],name:'conflicting'}]},snapshot)).toThrow('differs');
  expect(()=>validateReferences({...data,users:[]})).toThrow('credentials');
});
it('merges referenced tool memberships into an empty installation group without dropping existing members',()=>{
  const incoming={...referencedData(profile,snapshot),toolGroups:[{id:'tools',name:'Tools',enabled:true,revision:4,toolIds:['imported-tool']}]};
  const current={...snapshot,toolGroups:[{id:'tools',name:'Tools',enabled:true,revision:1,toolIds:['existing-tool']}]};
  expect(mergeReferences(incoming as ConfigurationFile,current as ConfigurationFile).toolGroups[0].toolIds).toEqual(['existing-tool','imported-tool']);
});

it('includes direct JSON graph tools and their groups without treating argument strings as references',()=>{
  const workflow=profile.workflows!.workflows[0];
  const changed={...profile,workflows:{...profile.workflows!,workflows:[{...workflow,graph:{...workflow.graph,packageId:'olo.json-graph',config:{nodes:[
    {id:'read',kind:'tool',toolRef:'graph.files/read',input:{toolRef:'unrelated/fake'}}]}}}]}};
  const data=referencedData(changed,{...snapshot,tools:[{id:'graph.files/read'},{id:'unrelated/fake'}],toolGroups:[{id:'graph-tools',toolIds:['graph.files/read']}]} as unknown as ConfigurationFile);
  expect(data.tools.map(item=>item.id)).toEqual(['graph.files/read']);
  expect(data.toolGroups.map(item=>item.id)).toEqual(['graph-tools']);
});
