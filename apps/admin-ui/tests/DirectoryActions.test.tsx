// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {managementCatalog} from '../src/managementPermissions.generated';
const allPermissions=managementCatalog.resources.flatMap(r=>r.actions.map(a=>`${r.id}.${a}`));
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {App} from '../src/App';
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
afterEach(()=>{cleanup();vi.unstubAllGlobals();localStorage.clear();window.location.hash='';});
async function setup(status=200,bootstrapAdminUserId?:string){
  const users=[{id:'alice',name:'Alice',enabled:true,revision:4,...(bootstrapAdminUserId?{email:'owner@example.com'}:{})},{id:'bob',name:'Bob',enabled:false,revision:2}];
  const fetcher=vi.fn(async(url:string,options:RequestInit)=>{
    if(['PUT','DELETE'].includes(options.method!)){
      if(status!==200)return json({id:'config-'+'a'.repeat(40),state:'DRAFT'},status);
      const id=decodeURIComponent(url.split('/').at(-1)!);if(options.method==='DELETE'){users.splice(users.findIndex(user=>user.id===id),1);return new Response(null,{status:204});}
      const body=JSON.parse(String(options.body));Object.assign(users.find(user=>user.id===id)!,body,{revision:body.revision+1});return json(body);
    }
    return json(url.endsWith('/admin-session')?{role:bootstrapAdminUserId?'SUPER_ADMIN':'ADMINISTRATOR',bootstrapAdminUserId,permissions:allPermissions}:{items:url.startsWith('/api/control/v1/users?')?users:[]});
  });vi.stubGlobal('fetch',fetcher);window.location.hash='#users';render(<App/>);fireEvent.change(screen.getByLabelText('Access token'),{target:{value:'test-only-token'}});fireEvent.click(screen.getByRole('button',{name:'Connect to workspace'}));await screen.findByRole('switch',{name:'Disable alice'});return fetcher;
}
it('opens editing only from the action icon and keeps the row read-only',async()=>{
  const fetcher=await setup();fireEvent.click(screen.getByText('Alice'));expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByRole('button',{name:'Alice'})).toBeNull();
  const edit=screen.getByRole('button',{name:'Edit alice'});edit.focus();fireEvent.click(edit);
  expect(screen.getByRole('dialog',{name:'Edit user'})).toBeTruthy();expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Alice');
  fireEvent.change(screen.getByLabelText('Display name'),{target:{value:'Unsaved change'}});fireEvent.click(screen.getByRole('button',{name:'Close editor'}));
  expect(document.activeElement).toBe(edit);expect(screen.queryByRole('dialog')).toBeNull();expect(fetcher.mock.calls.some(([,options])=>options.method==='PUT')).toBe(false);
  fireEvent.click(edit);expect((screen.getByLabelText('Display name') as HTMLInputElement).value).toBe('Alice');
});
it('changes status in one click with the expected revision and retains backend errors',async()=>{
  const fetcher=await setup();fireEvent.click(screen.getByRole('switch',{name:'Disable alice'}));await screen.findByRole('switch',{name:'Enable alice'});
  const mutation=fetcher.mock.calls.find(([,options])=>options.method==='PUT')!;expect(JSON.parse(String(mutation[1].body))).toMatchObject({id:'alice',enabled:false,revision:4});expect(new Headers(mutation[1].headers).get('If-Match')).toBe('"4"');expect(new Headers(mutation[1].headers).get('Idempotency-Key')).toBeTruthy();
});
it('requires explicit bulk delete confirmation and reports each result',async()=>{
  const fetcher=await setup();fireEvent.click(screen.getByRole('checkbox',{name:'Select all rows on this page'}));fireEvent.click(screen.getByRole('button',{name:'Delete selected'}));expect(fetcher.mock.calls.some(([,options])=>options.method==='DELETE')).toBe(false);expect(screen.getByRole('region',{name:'Confirm deletion'})).toBeTruthy();fireEvent.click(screen.getByRole('button',{name:'Confirm delete'}));await screen.findByRole('heading',{name:'No users on this page'});expect(fetcher.mock.calls.filter(([,options])=>options.method==='DELETE')).toHaveLength(2);expect(screen.getAllByText('Deleted. Fresh onboarding requires a new identifier.')).toHaveLength(2);
});
it('shows draft links without pretending reviewed changes have already applied',async()=>{
  await setup(202);fireEvent.click(screen.getByRole('checkbox',{name:'Select Alice'}));fireEvent.click(screen.getByRole('button',{name:'Disable selected'}));await screen.findByRole('link',{name:'Review configuration draft'});await waitFor(()=>expect(screen.getByRole('switch',{name:'Disable alice'}).getAttribute('aria-checked')).toBe('true'));
});
it('protects the setup administrator in rows, bulk actions and editing while allowing email updates',async()=>{
  const fetcher=await setup(200,'alice');
  expect((screen.getByRole('switch',{name:'Disable alice'}) as HTMLButtonElement).disabled).toBe(true);
  expect((screen.getByRole('button',{name:'Delete alice'}) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'Edit alice'}));
  expect((screen.getByLabelText('Enabled in directory') as HTMLInputElement).disabled).toBe(true);
  expect(screen.queryByRole('button',{name:'Delete user'})).toBeNull();
  expect((screen.getByLabelText('Email') as HTMLInputElement).value).toBe('owner@example.com');
  fireEvent.change(screen.getByLabelText('Email'),{target:{value:'new-owner@example.com'}});
  fireEvent.click(screen.getByRole('button',{name:'Save user'}));
  await waitFor(()=>expect(screen.queryByRole('dialog')).toBeNull());
  const mutation=fetcher.mock.calls.find(([,options])=>options.method==='PUT')!;
  expect(JSON.parse(String(mutation[1].body))).toMatchObject({id:'alice',email:'new-owner@example.com',enabled:true});
  fireEvent.click(screen.getByRole('checkbox',{name:'Select all rows on this page'}));
  fireEvent.click(screen.getByRole('button',{name:'Delete selected'}));fireEvent.click(screen.getByRole('button',{name:'Confirm delete'}));
  await waitFor(()=>expect(fetcher.mock.calls.filter(([,options])=>options.method==='DELETE')).toHaveLength(1));
  expect(fetcher.mock.calls.find(([,options])=>options.method==='DELETE')![0]).toContain('/users/bob');
});
