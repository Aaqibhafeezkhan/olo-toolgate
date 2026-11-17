// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import type {DeploymentProfile,TriggerAssignment,TriggerSubscription} from '@olo-labs/toolgate-contracts/v2';
import type {ConfigurationFile} from '../src/pendingContracts';
import {ControlClient} from '../src/api';
import {WorkflowManagement,WorkflowWorkspaceProvider} from '../src/WorkflowManagement';
import {exportWorkflow} from '../src/workflowBundle';
import {mockWorkflowPackages} from './workflowPackageFixture';
import {inspectPackage} from '../src/workflowPackages';
import {WorkflowTransfer} from '../src/WorkflowTransfer';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';

afterEach(()=>{cleanup();vi.restoreAllMocks();});
const profile={profileVersion:2,profileId:'support',packages:[],workflows:fixture.WorkflowConfiguration} as DeploymentProfile;
const binding=profile.workflows!.dispatchBindings[0];
const assignments=[{...fixture.TriggerAssignment,metadata:{...fixture.TriggerAssignment.metadata,uid:binding.sourceAssignmentUid,name:'watch'}}] as TriggerAssignment[];
const subscriptions=[{...fixture.TriggerSubscription,metadata:{...fixture.TriggerSubscription.metadata,uid:binding.subscriptionUid,name:'events'}}] as TriggerSubscription[];
const snapshot={formatVersion:2,tenantId:'tenant-a',revision:1,agents:[],agentGroups:[],roles:[],grants:[],tools:[],toolGroups:[],deviceGroups:[],bindings:[],policies:[],extractors:[],teams:[],devices:[],delegations:[],agentDelegations:[],workloadBindings:[]} as ConfigurationFile;

it('replaces and rereads the staged workflow, then refreshes publication without any stack or credential operation',async()=>{
  let current=profile;
  let status={profileId:'support',profileDigest:'sha256:'+'a'.repeat(64),state:'PUBLISHED',generation:3,dependenciesReady:true};
  const requests: string[]=[];
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
    const path=String(url);requests.push(path);
    let value:unknown;
    if(path.includes('/triggers/assignments'))value={items:assignments};
    else if(path.includes('/triggers/subscriptions'))value={items:subscriptions};
    else if(path.endsWith('/document'))value=current;
    else if(path.endsWith('/review')){status={...status,state:'REVIEWED'};value=status;}
    else if(path.endsWith('/publish')){status={...status,state:'PUBLISHED',generation:4};value=status;}
    else if(options?.method==='POST'){current=JSON.parse(options.body as string) as DeploymentProfile;status={...status,profileDigest:'sha256:'+'b'.repeat(64),state:'DRAFT'};value=status;}
    else if(path.includes('/revisions/'))value=status;
    else value=[status];
    return new Response(JSON.stringify(value),{status:200});
  });
  const client=new ControlClient('existing-token',vi.fn(),transport);
  const embedded=await Promise.all((await mockWorkflowPackages(profile,assignments)).map(inspectPackage));
  const upload=vi.spyOn(client,'uploadServerPackage').mockImplementation(async file=>{
    const item=embedded.find(item=>file.name===item.metadata.packageId+'.olotool')!;
    expect(current).toBe(profile);
    return {packageId:item.metadata.packageId,version:item.metadata.version,packageDigest:item.metadata.packageDigest,state:'VERIFIED',descriptorSetDigest:item.manifest.descriptorSetDigest,revision:1,uploadedAtUnixMs:0};
  });
  render(<WorkflowWorkspaceProvider><WorkflowManagement client={client} page="workflows"/></WorkflowWorkspaceProvider>);
  fireEvent.click(await screen.findByRole('button',{name:'Open support aaaaaaaaaaaa'}));
  await screen.findByText(/PUBLISHED; generation 3/);
  const workflow=profile.workflows!.workflows[0];
  const modified={...profile,workflows:{...profile.workflows!,workflows:[{...workflow,systemPrompt:'Imported replacement prompt'}]}};
  const bytes=await exportWorkflow(modified,workflow.id,assignments,subscriptions,snapshot,await mockWorkflowPackages(modified,assignments));
  const file=new File([new Uint8Array(bytes)],'workflow.olo');
  Object.defineProperty(file,'arrayBuffer',{value:async()=>new Uint8Array(bytes).buffer});
  fireEvent.change(screen.getByLabelText('Workflow .olo file'),{target:{files:[file]}});
  fireEvent.click(screen.getByRole('button',{name:'Import workflow .olo'}));
  await screen.findByText(/Imported workflow and refreshed/);
  expect(upload).toHaveBeenCalledTimes(embedded.length);
  expect(current.workflows!.workflows[0].systemPrompt).toBe('Imported replacement prompt');
  expect(current.workflows!.workflows[0].versionId).not.toBe(workflow.versionId);
  fireEvent.click(screen.getByRole('button',{name:'Edit '+workflow.id+' JSON'}));
  expect((screen.getByLabelText('Workflows JSON') as HTMLTextAreaElement).value).toContain('Imported replacement prompt');
  fireEvent.click(screen.getByRole('button',{name:'Close editor'}));
  fireEvent.click(screen.getByRole('button',{name:'Review exact revision'}));
  await screen.findByText(/REVIEWED; generation 3/);
  fireEvent.click(screen.getByRole('button',{name:'Publish reviewed revision'}));
  await screen.findByText(/Published workflow configuration. The editor has refreshed/);
  expect(requests.filter(path=>path.endsWith('/document')).length).toBeGreaterThanOrEqual(3);
  expect(requests.every(path=>path.startsWith('/api/control/v1/'))).toBe(true);
  expect(transport.mock.calls.filter(([,options])=>options?.method==='POST').map(([url])=>String(url))).toEqual([
    '/api/control/v1/workflow-profiles', '/api/control/v1/workflow-profiles/support/review', '/api/control/v1/workflow-profiles/support/publish']);
});

it('does not stage or replace a workflow when an embedded package upload fails verification',async()=>{
  const client=new ControlClient('existing-token',vi.fn());
  vi.spyOn(client,'triggerAssignments').mockResolvedValue({items:assignments});
  vi.spyOn(client,'triggerSubscriptions').mockResolvedValue({items:subscriptions});
  vi.spyOn(client,'uploadServerPackage').mockRejectedValue(Error('Package verification refused'));
  const replace=vi.fn();let error:unknown;
  render(<WorkflowTransfer client={client} profile={profile} busy={false} replace={replace} run={async action=>{try{await action();}catch(value){error=value;}}}/>);
  const bytes=await exportWorkflow(profile,profile.workflows!.workflows[0].id,assignments,subscriptions,snapshot,await mockWorkflowPackages(profile,assignments));
  const file=new File([new Uint8Array(bytes)],'workflow.olo');
  Object.defineProperty(file,'arrayBuffer',{value:async()=>new Uint8Array(bytes).buffer});
  fireEvent.change(screen.getByLabelText('Workflow .olo file'),{target:{files:[file]}});
  fireEvent.click(screen.getByRole('button',{name:'Import workflow .olo'}));
  await waitFor(()=>expect(error).toBeInstanceOf(Error));
  expect(replace).not.toHaveBeenCalled();
  expect(screen.queryByLabelText('Imported workflow dependencies')).toBeNull();
});
