// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import type {DeploymentProfile,WorkflowConfiguration} from '@olo-labs/toolgate-contracts/v2';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';
import {WorkflowManagement,WorkflowWorkspaceProvider,type WorkflowPage} from '../src/WorkflowManagement';
import {ControlClient} from '../src/api';
afterEach(()=>{cleanup();location.hash='';vi.restoreAllMocks();});
const digest='sha256:'+'a'.repeat(64);
const profile:DeploymentProfile={profileVersion:2,profileId:'support',packages:[],workflows:fixture.WorkflowConfiguration as WorkflowConfiguration};
const status={profileId:'support',profileDigest:digest,state:'REVIEWED',generation:3,dependenciesReady:true};
const json=(body:unknown,code=200)=>new Response(JSON.stringify(body),{status:code});
function rig(initial:WorkflowPage='workflows',publishCode=200){
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
    if(String(url).includes('/triggers/definitions'))return json({items:[fixture.TriggerDefinitionRecord]});
    if(String(url).includes('/triggers/'))return json({items:[]});
    if(String(url).includes('/document'))return json(profile);
    if(String(url).includes('/revisions/'))return json(status);
    if(options?.method==='POST')return json({...status,state:'DRAFT'},String(url).endsWith('/publish')?publishCode:200);
    return json([status]);
  });
  const client=new ControlClient('token',vi.fn(),transport);
  const page=(value:WorkflowPage)=><WorkflowWorkspaceProvider><WorkflowManagement client={client} page={value}/></WorkflowWorkspaceProvider>;
  const view=render(page(initial));
  return {transport,client,navigate:(value:WorkflowPage)=>view.rerender(page(value))};
}
async function open(){fireEvent.click(await screen.findByRole('button',{name:'Open support aaaaaaaaaaaa'}));await screen.findByText(/REVIEWED; generation 3/);}
it('keeps workflow and agent edits in one profile draft across dedicated pages and stages exact content',async()=>{
  const {transport,navigate}=rig();await open();
  fireEvent.click(screen.getByRole('button',{name:`Edit ${profile.workflows!.workflows[0].id}`}));
  fireEvent.change(screen.getByLabelText('systemPrompt'),{target:{value:'Updated supervisor prompt'}});
  fireEvent.change(screen.getByLabelText('versionId'),{target:{value:'new-version'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));
  expect(screen.queryByRole('button',{name:'Review exact revision'})).toBeNull();
  navigate('agent-configuration');
  fireEvent.click(screen.getByRole('button',{name:`Edit ${profile.workflows!.agentDefinitions[0].agentId}`}));
  fireEvent.change(screen.getByLabelText('systemPrompt'),{target:{value:'Updated agent prompt'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));
  expect(transport.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0);
  fireEvent.click(screen.getByRole('button',{name:'Stage profile revision'}));
  await screen.findByText(/Staged exact profile revision/);
  const sent=JSON.parse(transport.mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as string) as DeploymentProfile;
  expect(sent.workflows!.workflows[0].systemPrompt).toBe('Updated supervisor prompt');
  expect(sent.workflows!.workflows[0].versionId).toBe('new-version');
  expect(sent.workflows!.agentDefinitions[0].systemPrompt).toBe('Updated agent prompt');
  expect(sent.workflows!.modelDeployments).toEqual(profile.workflows!.modelDeployments);
  expect(sent.workflows!.workflows[0].limits).toEqual(profile.workflows!.workflows[0].limits);
  expect(sent.workflows!.dispatchBindings[0].workflowVersionId).toBe('new-version');
});
it('edits workflow groups and discards the complete unsaved working copy',async()=>{
  const {transport}=rig('workflowGroups');await open();
  fireEvent.click(screen.getByRole('button',{name:`Edit ${profile.workflows!.workflowGroups[0].id}`}));
  fireEvent.change(screen.getByLabelText('displayName'),{target:{value:'Changed group'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));await screen.findByText('Changed group');
  fireEvent.click(screen.getByRole('button',{name:'Discard profile edits'}));
  expect(screen.queryByText('Changed group')).toBeNull();
  expect(screen.getByRole('button',{name:'Review exact revision'})).toBeTruthy();
  expect(transport.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0);
});
it('manages agent entry/delegation/group mappings without changing unrelated workflow properties',async()=>{
  const {transport}=rig('agent-workflow-mappings');await open();
  const binding=profile.workflows!.workflows[0].agents[0];
  fireEvent.click(screen.getAllByRole('button',{name:`Edit ${binding.agentId}`})[0]);
  fireEvent.click(screen.getByLabelText('entryEligible'));
  fireEvent.change(screen.getByLabelText('agentGroupId'),{target:{value:'new-agent-group'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));
  fireEvent.click(screen.getByRole('button',{name:'Stage profile revision'}));await screen.findByText(/Staged exact profile revision/);
  const sent=JSON.parse(transport.mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as string) as DeploymentProfile;
  expect(sent.workflows!.workflows[0].agents[0]).toEqual({...binding,entryEligible:!binding.entryEligible,agentGroupId:'new-agent-group'});
  expect(sent.workflows!.workflows[0].systemPrompt).toBe(profile.workflows!.workflows[0].systemPrompt);
  expect(sent.workflows!.workflows[0].versionId).not.toBe(profile.workflows!.workflows[0].versionId);
  expect(sent.workflows!.dispatchBindings[0].workflowVersionId).toBe(sent.workflows!.workflows[0].versionId);
});
it('clears publication controls after a stale generation denial and binds publication to the displayed revision',async()=>{
  const {transport}=rig('workflows',409);await open();
  fireEvent.click(screen.getByRole('button',{name:'Publish reviewed revision'}));await screen.findByRole('alert');
  expect(screen.queryByRole('button',{name:'Publish reviewed revision'})).toBeNull();
  const request=transport.mock.calls.find(([url])=>String(url).endsWith('/publish'))!;
  expect(JSON.parse(request[1]!.body as string)).toEqual({profileDigest:digest,expectedGeneration:3});
});
it('uses paired catalog cursors and stops pagination after a final page',async()=>{
  const entries=Array.from({length:50},(_,i)=>({...status,profileDigest:'sha256:'+i.toString(16).padStart(64,'0')}));
  const transport=vi.fn<typeof fetch>().mockImplementation(async url=>json(String(url).includes('afterProfileId=')?[]:entries));
  render(<WorkflowWorkspaceProvider><WorkflowManagement client={new ControlClient('token',vi.fn(),transport)} page="workflows"/></WorkflowWorkspaceProvider>);
  fireEvent.click(await screen.findByRole('button',{name:'More profile revisions'}));
  await waitFor(()=>expect(screen.queryByRole('button',{name:'More profile revisions'})).toBeNull());
  const query=new URL(String(transport.mock.calls[1][0]),'https://control.invalid').searchParams;
  expect(query.get('afterProfileId')).toBe('support');expect(query.get('afterDigest')).toBe(entries[49].profileDigest);
});
it('does not display private profile data when exact document loading is denied',async()=>{
  const {transport}=rig();await screen.findByRole('button',{name:'Open support aaaaaaaaaaaa'});
  transport.mockResolvedValue(json({},403));fireEvent.click(screen.getByRole('button',{name:'Open support aaaaaaaaaaaa'}));
  await screen.findByRole('alert');expect(screen.queryByRole('heading',{name:'Profile lifecycle'})).toBeNull();
});

it('pins the selected workflow version in a trigger mapping draft',async()=>{
  const {transport}=rig('workflow-dispatch');await open();
  const workflow=profile.workflows!.workflows[0];
  fireEvent.click(screen.getByRole('button',{name:'Add trigger mappings'}));
  fireEvent.change(screen.getByLabelText('workflowId'),{target:{value:workflow.id}});
  expect((screen.getByLabelText('workflowVersionId') as HTMLSelectElement).value).toBe(workflow.versionId);
  fireEvent.change(screen.getByLabelText('subscriptionUid'),{target:{value:'subscription-uid'}});
  fireEvent.change(screen.getByLabelText('sourceAssignmentUid'),{target:{value:'assignment-uid'}});
  fireEvent.change(screen.getByLabelText('serviceAgentId'),{target:{value:'listener-agent'}});
  fireEvent.change(screen.getByLabelText('serviceAgentGroupId'),{target:{value:'listener-group'}});
  fireEvent.change(screen.getByLabelText('messagePointer'),{target:{value:'/message'}});
  fireEvent.change(screen.getByLabelText('id'),{target:{value:'hotfolder-route'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));
  fireEvent.click(screen.getByRole('button',{name:'Stage profile revision'}));
  await screen.findByText(/Staged exact profile revision/);
  const sent=JSON.parse(transport.mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as string) as DeploymentProfile;
  expect(sent.workflows!.dispatchBindings.at(-1)).toMatchObject({
    id:'hotfolder-route',workflowId:workflow.id,workflowVersionId:workflow.versionId,messagePointer:'/message',
  });
});

it('edits a profile trigger against the installed export schema',async()=>{
  const {transport}=rig('triggers');await open();
  const definition=fixture.TriggerDefinitionRecord;
  await screen.findByText(`${definition.triggerId} (${definition.packageVersion})`);
  fireEvent.click(screen.getByRole('button',{name:'Add trigger packages'}));
  fireEvent.change(screen.getByLabelText('packageId'),{target:{value:definition.packageId}});
  fireEvent.change(screen.getByLabelText('version'),{target:{value:definition.packageVersion}});
  fireEvent.click(screen.getByRole('button',{name:'Add triggers'}));
  fireEvent.click(screen.getByRole('button',{name:'Add triggers item'}));
  fireEvent.change(screen.getByLabelText('assignment'),{target:{value:'inbox'}});
  fireEvent.change(screen.getByLabelText('triggerId'),{target:{value:definition.triggerId}});
  expect(screen.getByText('Signed configuration schema')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('config'),{target:{value:'{"folder":"inbox"}'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply to profile draft'}));
  fireEvent.click(screen.getByRole('button',{name:'Stage profile revision'}));
  await screen.findByText(/Staged exact profile revision/);
  const sent=JSON.parse(transport.mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as string) as DeploymentProfile;
  expect(sent.packages[0].triggers).toEqual([{assignment:'inbox',triggerId:definition.triggerId,config:{folder:'inbox'}}]);
});
