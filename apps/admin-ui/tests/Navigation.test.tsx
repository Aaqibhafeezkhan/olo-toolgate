// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {App} from '../src/App';
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
const token=(user='alice')=>`header.${btoa(JSON.stringify({iss:'test',tenant_id:'tenant',sub:user,user_id:user}))}.signature`;
async function connect(value=token()){fireEvent.change(screen.getByLabelText('Access token'),{target:{value}});fireEvent.click(screen.getByRole('button',{name:'Connect to workspace'}));await screen.findByRole('navigation');}
function navigate(hash:string){window.location.hash=hash;fireEvent(window,new HashChangeEvent('hashchange'));}
afterEach(()=>{cleanup();vi.unstubAllGlobals();localStorage.clear();window.location.hash='';});
it('starts at Overview with collapsed groups and restores the last page after disconnect',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/users/alice')?json({id:'alice',name:'Alice Example',enabled:true,revision:1}):url.endsWith('/admin-session')?json({role:'SUPER_ADMIN'}):json({items:[]})));
  const view=render(<App/>);await connect();await screen.findByRole('heading',{name:'Your organization, at a glance'});
  expect([...document.querySelectorAll<HTMLDetailsElement>('nav details')].every(group=>!group.open)).toBe(true);
  expect(document.querySelector('nav > details summary')?.textContent).toBe('Administration');
  const about=screen.getByRole('link',{name:'About'});
  expect(about.closest('details')).toBeNull();
  expect(about.previousElementSibling?.querySelector('summary')?.textContent).toBe('Administration');
  fireEvent.click(screen.getByRole('button',{name:'Collapse navigation'}));
  expect(screen.getByRole('button',{name:'Administration'})).toBeTruthy();
  expect(screen.getByRole('link',{name:'About'}).previousElementSibling).toBe(screen.getByRole('button',{name:'Administration'}));
  fireEvent.click(screen.getByRole('button',{name:'Expand navigation'}));
  navigate('#users');await screen.findByRole('heading',{name:'Users'});fireEvent.click(screen.getByRole('button',{name:'Disconnect'}));expect(window.location.hash).toBe('');view.unmount();
  render(<App/>);await connect();await screen.findByRole('heading',{name:'Users'});expect(window.location.hash).toBe('#users');
  expect(JSON.stringify(localStorage)).not.toContain(token());expect(sessionStorage.length).toBe(0);
});
it('isolates browser page history by tenant and identity and ignores malformed preferences',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({role:'ADMINISTRATOR'}):url.includes('/users/')&&!url.includes('?')?json({name:'User'}):json({items:[]})));
  render(<App/>);await connect();navigate('#teams');await screen.findByRole('heading',{name:'Teams'});fireEvent.click(screen.getByRole('button',{name:'Disconnect'}));
  await connect(token('bob'));await screen.findByRole('heading',{name:'Your organization, at a glance'});fireEvent.click(screen.getByRole('button',{name:'Disconnect'}));
  localStorage.setItem('toolgate.navigation.'+JSON.stringify(['test','tenant','bob']),'{broken');await connect(token('bob'));await screen.findByRole('heading',{name:'Your organization, at a glance'});
});
it('aligns the username and bell in the sidebar and refreshes green/gray pending status',async()=>{
  let pending=true;
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({role:'SUPER_ADMIN'}):url.endsWith('/users/alice')?json({name:'Alice Example'}):url.startsWith('/api/control/v1/approvals?')?json({items:pending?[{id:'approval',state:'PENDING',expiresAtUnixMs:Date.now()+60000}]:[]}):json({items:[]})));
  render(<App/>);await connect();await screen.findByText('Alice Example');const bell=await screen.findByRole('button',{name:'Pending approvals'});expect(bell.className).toContain('pending');expect(document.querySelector('.sidebar-user')?.contains(bell)).toBe(true);expect(document.querySelector('.sidebar-user')?.textContent).toContain('Alice Example');
  fireEvent.click(bell);expect(screen.getByRole('region',{name:'Approval notifications'})).toBeTruthy();expect(screen.getByRole('link',{name:'Operation approvals'})).toBeTruthy();
  pending=false;fireEvent(window,new Event('toolgate:approval-change'));await waitFor(()=>expect(screen.getByRole('button',{name:'No pending approvals'}).className).not.toContain('pending'));
});
it('uses a gray bell with an honest message when approval queues are unavailable',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({role:'ADMINISTRATOR'}):url.includes('approvals?')?json({},503):json({items:[]})));
  render(<App/>);await connect('opaque-token');expect((await screen.findByRole('button',{name:'Approval status unavailable'})).className).not.toContain('pending');
});

it('keeps About available to approval-only users without Administration in either sidebar mode',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({},403):json({items:[]})));
  render(<App/>);await connect();
  expect([...document.querySelectorAll('nav summary')].some(summary=>summary.textContent==='Administration')).toBe(false);
  expect(screen.getByRole('link',{name:'About'}).closest('details')).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'Collapse navigation'}));
  expect(screen.queryByRole('button',{name:'Administration'})).toBeNull();
  fireEvent.click(screen.getByRole('link',{name:'About'}));fireEvent(window,new HashChangeEvent('hashchange'));
  expect(await screen.findByRole('heading',{name:'About ToolGate'})).toBeTruthy();
});
