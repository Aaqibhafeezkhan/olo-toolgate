// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {AgentToolMapping,DeviceMembership,PublishAccess} from '../src/AccessMapping';
import {ScopePicker} from '../src/ScopePicker';
import {ControlClient} from '../src/api';
import {emptyScope} from '../src/EnterpriseScopeEditor';
afterEach(cleanup);
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
it('hides access snapshot publishing when the account cannot use it',async()=>{
  const transport=vi.fn<typeof fetch>().mockResolvedValue(json({code:'FORBIDDEN'},403));
  render(<PublishAccess client={new ControlClient('token',vi.fn(),transport)}/>);
  await waitFor(()=>expect(screen.queryByRole('heading',{name:'Publish current access snapshot'})).toBeNull());
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByRole('button',{name:'Publish access changes'})).toBeNull();
  expect(transport).not.toHaveBeenCalled();
});
it('opens an existing mapping from its edit icon and leaves the name read-only',async()=>{
  const grant={id:'capability',name:'Read ledger',enabled:true,revision:3,sourceType:'AGENT_GROUP',sourceId:'finance',purpose:'CAPABILITY',scope:emptyScope};
  const transport=vi.fn<typeof fetch>().mockImplementation(async path=>json({items:String(path).includes('agent-groups')?[{id:'finance',name:'Finance agents',enabled:true,revision:1,agentIds:[],roleIds:[]}]:String(path).includes('/grants')?[grant]:[]}));
  render(<AgentToolMapping client={new ControlClient('token',vi.fn(),transport)}/>);await screen.findByRole('option',{name:'Finance agents (finance)'});fireEvent.change(screen.getByLabelText('Agent Group'),{target:{value:'finance'}});
  fireEvent.click(screen.getByText('Read ledger'));expect(screen.queryByRole('dialog')).toBeNull();
  const edit=screen.getByRole('button',{name:'Edit capability'});edit.focus();fireEvent.click(edit);expect(screen.getByRole('dialog',{name:'Edit capability mapping'})).toBeTruthy();expect((screen.getByLabelText('Grant identifier') as HTMLInputElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'Close editor'}));expect(document.activeElement).toBe(edit);expect(screen.queryByRole('dialog')).toBeNull();
});
it('maps an Agent Group through a capability grant and preserves an exact retry key',async()=>{
  let fail=true;const transport=vi.fn<typeof fetch>().mockImplementation(async(path,options)=>options?.method==='POST'?json({},fail?503:200):json({items:String(path).includes('agent-groups')?[{id:'finance',name:'Finance agents',enabled:true,revision:1,agentIds:[],roleIds:[]}]:[]}));
  render(<AgentToolMapping client={new ControlClient('token',vi.fn(),transport)}/>);await screen.findByRole('option',{name:'Finance agents (finance)'});
  fireEvent.change(screen.getByLabelText('Agent Group'),{target:{value:'finance'}});fireEvent.click(screen.getByRole('button',{name:'Add capability mapping'}));fireEvent.change(screen.getByLabelText('Grant identifier'),{target:{value:'capability'}});fireEvent.change(screen.getByLabelText('Mapping name'),{target:{value:'Read ledger'}});
  fireEvent.click(screen.getByRole('button',{name:'Save capability mapping'}));await screen.findByRole('alert');fail=false;fireEvent.click(screen.getByRole('button',{name:'Save capability mapping'}));await screen.findByText(/Agent Group capability saved/);
  const calls=transport.mock.calls.filter(c=>c[1]?.method==='POST');const body=JSON.parse(calls[0][1]?.body as string);expect(body.sourceType).toBe('AGENT_GROUP');expect(body.sourceId).toBe('finance');expect(body.purpose).toBe('CAPABILITY');expect(body.scope.toolGroups).toEqual({ids:[],all:false});expect(body).not.toHaveProperty('allowedToolIds');expect(calls[0][0]).toBe('/api/control/v1/grants');expect(new Headers(calls[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(calls[1][1]?.headers).get('Idempotency-Key'));
});
it('changes mandatory membership using one atomic directory revision',async()=>{
  const saved=vi.fn(),close=vi.fn();const transport=vi.fn<typeof fetch>().mockImplementation(async(path,options)=>options?.method==='PUT'?json({entityType:'DEVICE',entityId:'device',groupIds:['finance'],revision:10}):String(path).includes('effective-access')?json({entityId:'device',kind:'DEVICE',directoryRevision:9,authorizationEpoch:9,memberships:[],bindings:[],managementRoles:[]}):String(path).includes('/groups')?json({entityType:'DEVICE',entityId:'device',groupIds:['default-devices'],revision:9}):json({items:[{id:'default-devices',name:'Default',enabled:true,revision:1,deviceIds:['device']},{id:'finance',name:'Finance',enabled:true,revision:1,deviceIds:[]}]}));
  const membershipClient=new ControlClient('token',vi.fn(),(input,init)=>String(input).endsWith('/admin-session')?Promise.resolve(json({role:'ADMINISTRATOR',permissions:['device.read','device.manage_groups','device_group.manage_members']})):transport(input,init));await membershipClient.adminSession();
  render(<DeviceMembership client={membershipClient} id="device" saved={saved} close={close}/>);fireEvent.click(await screen.findByRole('button',{name:'Choose device groups'}));fireEvent.click(await screen.findByRole('checkbox',{name:/Default/}));fireEvent.click(screen.getByRole('checkbox',{name:/Finance/}));fireEvent.click(screen.getByRole('button',{name:'Apply selection'}));
  expect(screen.getByRole('button',{name:'Save group membership'}).hasAttribute('disabled')).toBe(true);fireEvent.click(screen.getByLabelText('I reviewed the membership changes'));fireEvent.click(screen.getByRole('button',{name:'Save group membership'}));await waitFor(()=>expect(saved).toHaveBeenCalledOnce());expect(close).toHaveBeenCalledOnce();
  const call=transport.mock.calls.find(c=>c[1]?.method==='PUT')!;expect(new Headers(call[1]?.headers).get('If-Match')).toBe('"9"');expect(JSON.parse(call[1]?.body as string)).toEqual({entityType:'DEVICE',entityId:'device',groupIds:['finance'],revision:9});
});
it('rejects malformed membership responses without crashing the editor',async()=>{render(<DeviceMembership client={new ControlClient('token',vi.fn(),vi.fn().mockImplementation(async()=>json({deviceId:'device',deviceGroupIds:[]})))} id="device" saved={vi.fn()} close={vi.fn()}/>);await screen.findByRole('alert');expect(screen.queryByRole('button',{name:'Save group membership'})).toBeNull();});
async function publishingClient(transport:typeof fetch){const client=new ControlClient('token',vi.fn(),(input,init)=>String(input).endsWith('/admin-session')?Promise.resolve(json({role:'ADMINISTRATOR',permissions:['access_snapshot.read','access_snapshot.publish']})):transport(input,init));await client.adminSession();return client;}
it('publishes the current graph without requiring configuration backup export',async()=>{
 const transport=vi.fn<typeof fetch>().mockImplementation(async(_path,init)=>json(init?.method==='POST'?{jws:'published'}:{directoryRevision:15,authorizationEpoch:15,snapshotSequence:7}));
 render(<PublishAccess client={await publishingClient(transport)}/>);fireEvent.click(screen.getByRole('button',{name:'Publish access changes'}));await screen.findByText(/Snapshot published/);
 expect(JSON.parse(transport.mock.calls.find(c=>c[1]?.method==='POST')![1]?.body as string)).toEqual({directoryRevision:15,expectedSequence:7,lifetimeMs:3600000,graceMs:0});
 expect(transport.mock.calls.some(([path])=>String(path).includes('export'))).toBe(false);
});
it('initial publication retries the same revision and key after a network failure',async()=>{
 let fail=true;const transport=vi.fn<typeof fetch>().mockImplementation(async(_path,options)=>options?.method==='POST'&&fail?json({},503):json({directoryRevision:1,authorizationEpoch:1,snapshotSequence:0}));
 render(<PublishAccess client={await publishingClient(transport)}/>);fireEvent.click(screen.getByRole('button',{name:'Publish access changes'}));await screen.findByRole('alert');fail=false;fireEvent.click(screen.getByRole('button',{name:'Publish access changes'}));await screen.findByText(/Snapshot published/);
 const calls=transport.mock.calls.filter(c=>c[1]?.method==='POST');expect(calls[0][1]?.body).toBe(calls[1][1]?.body);expect(new Headers(calls[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(calls[1][1]?.headers).get('Idempotency-Key'));
});
it('keeps unavailable picker values until explicitly removed',async()=>{const changed=vi.fn();render(<ScopePicker client={new ControlClient('token',vi.fn(),vi.fn().mockImplementation(async()=>json({items:[]})))} kind="deviceGroups" label="Groups" value={['retired']} onChange={changed}/>);fireEvent.click(screen.getByRole('button',{name:'Choose groups'}));await screen.findByRole('checkbox',{name:/Unavailable selection/});fireEvent.click(screen.getByRole('button',{name:'Cancel'}));expect(changed).not.toHaveBeenCalled();});
it('bounds paging when Control returns a repeated cursor',async()=>{const client=new ControlClient('token',vi.fn(),vi.fn().mockImplementation(async()=>json({items:[],nextCursor:'repeat'})));await expect(client.all('tools')).rejects.toMatchObject({code:'RESPONSE_LIMIT'});});
