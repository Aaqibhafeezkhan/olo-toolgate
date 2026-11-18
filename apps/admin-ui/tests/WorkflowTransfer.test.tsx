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
import {BotImportError} from '../src/botImport';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';

afterEach(()=>{cleanup();vi.restoreAllMocks();});
const profile={profileVersion:2,profileId:'support',packages:[],workflows:fixture.WorkflowConfiguration} as DeploymentProfile;
const binding=profile.workflows!.dispatchBindings[0];
const assignments=[{...fixture.TriggerAssignment,metadata:{...fixture.TriggerAssignment.metadata,uid:binding.sourceAssignmentUid,name:'watch'}}] as TriggerAssignment[];
const subscriptions=[{...fixture.TriggerSubscription,spec:{...fixture.TriggerSubscription.spec,sources:{assignments:['watch']},expiresAtUnixMs:Date.now()+86400000},metadata:{...fixture.TriggerSubscription.metadata,uid:binding.subscriptionUid,name:'events'}}] as TriggerSubscription[];
const snapshot={formatVersion:2,tenantId:'tenant-a',revision:1,agents:[],agentGroups:[],roles:[],grants:[],tools:[],toolGroups:[],deviceGroups:[],bindings:[],policies:[],extractors:[],teams:[],devices:[],delegations:[],agentDelegations:[],workloadBindings:[]} as ConfigurationFile;

async function choose(){
  const archives=await mockWorkflowPackages(profile,assignments);
  const bytes=await exportWorkflow(profile,profile.workflows!.workflows[0].id,assignments,subscriptions,snapshot,archives);
  const file=new File([new Uint8Array(bytes)],'bot.olo');
  Object.defineProperty(file,'arrayBuffer',{value:async()=>new Uint8Array(bytes).buffer});
  fireEvent.change(screen.getByLabelText('Bot .olo file'),{target:{files:[file]}});
  fireEvent.click(screen.getByRole('button',{name:'Import bot .olo'}));
}
function serviceBindings(){return profile.workflows!.agentDefinitions.map(agent=>({id:agent.agentId,agentId:agent.agentId,mode:'SERVICE',enabled:true,expiresAtUnixMs:Date.now()+86400000}));}
async function uploads(client:ControlClient){
  const embedded=await Promise.all((await mockWorkflowPackages(profile,assignments)).map(inspectPackage));
  vi.spyOn(client,'uploadServerPackage').mockImplementation(async file=>{const item=embedded.find(item=>file instanceof File&&file.name===item.metadata.packageId+'.olotool')!;return {...item.metadata,state:'VERIFIED',descriptorSetDigest:item.manifest.descriptorSetDigest,revision:1,uploadedAtUnixMs:0};});
}

it('prepares the complete bundle before publication and refreshes the editor without stack or credential operations',async()=>{
  let current=profile,status={profileId:'support',profileDigest:'sha256:'+'a'.repeat(64),state:'PUBLISHED',generation:3,dependenciesReady:true};
  const requests:{path:string;body?:Record<string,unknown>}[]=[];
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
    const path=String(url),body=options?.body?JSON.parse(options.body as string):undefined;
    requests.push({path,body});let value:unknown;
    if(path.includes('/triggers/assignments'))value={items:assignments};
    else if(path.includes('/triggers/subscriptions'))value={items:subscriptions};
    else if(path.endsWith('/config/export'))value=snapshot;
    else if(path.endsWith('/config/import'))value=body.dryRun?{changes:[{kind:'AGENT',id:'example',operation:'ADD'}]}:{};
    else if(path.includes('/workload-bindings'))value={items:serviceBindings()};
    else if(path.endsWith('/document'))value=current;
    else if(path.endsWith('/review')){status={...status,state:'REVIEWED'};value=status;}
    else if(path.endsWith('/provision'))value={state:'READY',packages:[]};
    else if(path.endsWith('/graphs:prepare')){status={...status,dependenciesReady:true};value=[{state:'READY'}];}
    else if(path.endsWith('/publish')){status={...status,state:'PUBLISHED',generation:4};value=status;}
    else if(options?.method==='POST'){current=body as DeploymentProfile;status={...status,profileDigest:'sha256:'+'b'.repeat(64),state:'DRAFT',dependenciesReady:false};value=status;}
    else if(path.includes('/revisions/'))value=status;
    else value=[status];
    return new Response(JSON.stringify(value),{status:200});
  });
  const client=new ControlClient('existing-token',vi.fn(),transport);await uploads(client);
  render(<WorkflowWorkspaceProvider><WorkflowManagement client={client} page="workflows"/></WorkflowWorkspaceProvider>);
  await screen.findByRole('button',{name:'Open support aaaaaaaaaaaa'});await choose();
  await screen.findByText('Bot imported with its configuration and packages. Ready for publication.');
  const writes=requests.filter(row=>row.body);
  expect(writes.findIndex(row=>row.path.endsWith('/config/import')&&row.body?.dryRun===false)).toBeLessThan(writes.findIndex(row=>row.path.endsWith('/workflow-profiles')));
  expect(writes.filter(row=>row.path.endsWith('/workflow-profiles'))[0].body!.workflows).toMatchObject({dispatchBindings:[]});
  expect(requests.some(row=>row.path.endsWith('/publish'))).toBe(false);
  expect(requests.some(row=>/enabled|memberships|password|reset|operator/.test(row.path))).toBe(false);
  fireEvent.click(screen.getByRole('button',{name:'Publish reviewed revision'}));await screen.findByText(/Published bot configuration/);
  expect(requests.filter(row=>row.path.endsWith('/document')).length).toBeGreaterThanOrEqual(3);
});

it('stops before configuration writes or staging when package verification fails',async()=>{
  const client=new ControlClient('token',vi.fn());
  vi.spyOn(client,'triggerAssignments').mockResolvedValue({items:assignments});vi.spyOn(client,'triggerSubscriptions').mockResolvedValue({items:subscriptions});
  vi.spyOn(client,'exportConfiguration').mockResolvedValue(snapshot);vi.spyOn(client,'previewConfiguration').mockResolvedValue({changes:[]});
  vi.spyOn(client,'uploadServerPackage').mockRejectedValue(Error('private reflected provider payload'));
  const configure=vi.spyOn(client,'importConfiguration'),stored=vi.fn();let error:unknown;
  render(<WorkflowTransfer client={client} profile={profile} busy={false} stored={stored} run={async action=>{try{await action();}catch(value){error=value;}}}/>);
  await choose();await waitFor(()=>expect(error).toBeInstanceOf(BotImportError));
  expect((error as BotImportError).step).toBe('packages');expect(String(error)).not.toContain('private reflected');
  expect(configure).not.toHaveBeenCalled();expect(stored).not.toHaveBeenCalled();
});

it('keeps an unplaced watch staged without broadcasting it or publishing',async()=>{
  const client=new ControlClient('token',vi.fn()),stored=vi.fn();let error:unknown;
  vi.spyOn(client,'triggerAssignments').mockResolvedValue({items:[]});vi.spyOn(client,'triggerSubscriptions').mockResolvedValue({items:[]});
  vi.spyOn(client,'exportConfiguration').mockResolvedValue(snapshot);vi.spyOn(client,'previewConfiguration').mockResolvedValue({changes:[]});
  vi.spyOn(client,'all').mockResolvedValue(serviceBindings() as never);await uploads(client);
  const status={profileId:'support',profileDigest:'sha256:'+'a'.repeat(64),state:'REVIEWED' as const,generation:0,dependenciesReady:false};
  vi.spyOn(client,'stageWorkflowProfile').mockResolvedValue(status);vi.spyOn(client,'reviewWorkflowProfile').mockResolvedValue(status);
  vi.spyOn(client,'provisionWorkflowProfile').mockResolvedValue({profileId:'support',profileDigest:status.profileDigest,generation:1,state:'READY',packages:[],appliedAtUnixMs:0});
  const broadcast=vi.spyOn(client,'createTriggerAssignment'),publish=vi.spyOn(client,'publishWorkflowProfile');
  render(<WorkflowTransfer client={client} profile={profile} busy={false} stored={stored} run={async action=>{try{await action();}catch(value){error=value;}}}/>);
  await choose();await waitFor(()=>expect(error).toBeInstanceOf(BotImportError));
  expect((error as BotImportError).step).toBe('connections');expect(stored).toHaveBeenCalledOnce();
  expect(broadcast).not.toHaveBeenCalled();expect(publish).not.toHaveBeenCalled();
});
