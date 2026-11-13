// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ProfileFields,initialField,profileSchema,resolveField,type FieldSchema} from '../src/ProfileFields';
import {WorkflowProfiles} from '../src/WorkflowProfiles';
import {ControlClient} from '../src/api';
afterEach(()=>{cleanup();vi.restoreAllMocks();});
const digest='sha256:'+'a'.repeat(64);
const document={profileVersion:2,profileId:'example',packages:[]};
const status={profileId:'example',profileDigest:digest,state:'DRAFT',generation:0,dependenciesReady:false};
const response=(value:unknown,code=200)=>new Response(JSON.stringify(value),{status:code});
function populated(input:FieldSchema,file?:string):unknown{
  const resolved=resolveField(input,file),s=resolved.schema;
  if(s.properties)return Object.fromEntries(Object.entries(s.properties).map(([key,child])=>[key,populated(child,resolved.file)]));
  if(s.items)return [populated(s.items,resolved.file)];
  return initialField(input,file);
}
it('exposes every reachable canonical property including optional nested profile fields',()=>{
  const value=populated(profileSchema);
  render(<ProfileFields value={value} change={vi.fn()}/>);
  function check(input:FieldSchema,file?:string){const resolved=resolveField(input,file),s=resolved.schema;
    for(const [key,child] of Object.entries(s.properties??{})){
      const target=resolveField(child,resolved.file).schema;
      if(target.properties||target.items)expect(screen.getAllByText(key,{selector:'legend'}).length).toBeGreaterThan(0);
      else expect(screen.getAllByLabelText(key).length).toBeGreaterThan(0);
      check(child,resolved.file);
    }
    if(s.items)check(s.items,resolved.file);
  }
  check(profileSchema);
});
it('loads exact bytes, edits workflow fields and invalidates lifecycle authority on edits',async()=>{
  const loaded=populated(profileSchema) as Record<string,unknown>;loaded.profileId='example';
  const transport=vi.fn<typeof fetch>().mockImplementation(async url=>response(String(url).endsWith('/document')?loaded:status));
  render(<WorkflowProfiles client={new ControlClient('token',vi.fn(),transport)}/>);
  fireEvent.click(screen.getByText('Manage workflow profile'));
  fireEvent.change(screen.getByLabelText('Profile identifier'),{target:{value:'example'}});
  fireEvent.change(screen.getByLabelText('Revision digest'),{target:{value:digest}});
  fireEvent.click(screen.getByText('Load revision'));await screen.findByLabelText('Profile lifecycle');
  fireEvent.change(screen.getByLabelText('parameterSchema'),{target:{value:'{'}});
  expect(screen.queryByLabelText('Profile lifecycle')).toBeNull();
  expect((screen.getByLabelText('parameterSchema') as HTMLTextAreaElement).validity.valid).toBe(false);
  fireEvent.change(screen.getByLabelText('parameterSchema'),{target:{value:'{}'}});
  fireEvent.change(screen.getAllByLabelText('systemPrompt')[0],{target:{value:'Changed supervisor prompt'}});
  expect(screen.queryByLabelText('Profile lifecycle')).toBeNull();
  fireEvent.submit(screen.getByText('Stage profile revision').closest('form')!);
  await waitFor(()=>expect(transport.mock.calls.some(([,init])=>init?.method==='POST')).toBe(true));
  const body=JSON.parse(transport.mock.calls.find(([,init])=>init?.method==='POST')![1]!.body as string);
  expect(body.workflows.agentDefinitions[0].systemPrompt).toBe('Changed supervisor prompt');
  expect(body.workflows.modelDeployments).toEqual((loaded.workflows as Record<string,unknown>).modelDeployments);
});
it('stages, reviews, provisions and publishes only an exact ready revision and generation',async()=>{
  let current={...status};
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url,init)=>{
    if(String(url).endsWith('/review'))current={...current,state:'REVIEWED'};
    if(String(url).endsWith('/provision')){current={...current,dependenciesReady:true};return response({state:'READY'});}
    if(String(url).endsWith('/publish'))current={...current,state:'PUBLISHED',generation:1};
    return response(current,init?.method==='POST'?201:200);
  });
  render(<WorkflowProfiles client={new ControlClient('token',vi.fn(),transport)}/>);
  fireEvent.click(screen.getByText('Manage workflow profile'));
  fireEvent.change(screen.getByLabelText('profileId'),{target:{value:'example'}});
  fireEvent.submit(screen.getByText('Stage profile revision').closest('form')!);
  await screen.findByText('example: DRAFT');
  expect((screen.getByText('Publish reviewed revision') as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByText('Review exact revision'));await screen.findByText('example: REVIEWED');
  expect((screen.getByText('Publish reviewed revision') as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByText('Provision reviewed packages'));await screen.findByText('Package provisioning: READY');
  fireEvent.click(screen.getByText('Publish reviewed revision'));await screen.findByText('example: PUBLISHED');
  const publish=transport.mock.calls.find(([url])=>String(url).endsWith('/publish'))!;
  expect(JSON.parse(publish[1]!.body as string)).toEqual({profileDigest:digest,expectedGeneration:0});
  expect(JSON.parse(transport.mock.calls[0][1]!.body as string)).toEqual(document);
});
it('reports denied staging without exposing review or publication',async()=>{
  const transport=vi.fn<typeof fetch>().mockResolvedValue(response({},403));
  render(<WorkflowProfiles client={new ControlClient('token',vi.fn(),transport)}/>);
  fireEvent.click(screen.getByText('Manage workflow profile'));
  fireEvent.submit(screen.getByText('Stage profile revision').closest('form')!);
  await screen.findByRole('alert');expect(screen.queryByLabelText('Profile lifecycle')).toBeNull();
  expect(transport).toHaveBeenCalledTimes(1);
});
