// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {DirectoryEditor} from '../src/DirectoryEditor';
import {Audit} from '../src/Audit';
import {App} from '../src/App';
import {ControlClient} from '../src/api';
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.location.hash='';});

it('edits typed management roles through the same canonical guided and advanced record',async()=>{
  const role={id:'cloud',name:'Cloud operator',enabled:true,revision:2,portalRole:'ADMINISTRATOR' as const,roleType:'MANAGEMENT' as const,managementRules:[]};
  const saved=vi.fn();const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify(role)));
  render(<DirectoryEditor client={new ControlClient('token',vi.fn(),transport)} kind="roles" record={role} saved={saved} close={vi.fn()}/>);
  expect((screen.getByLabelText('Role type') as HTMLSelectElement).value).toBe('MANAGEMENT');fireEvent.click(screen.getByRole('button',{name:'Add management rule'}));
  const config=JSON.parse((screen.getByLabelText('Configuration (JSON)') as HTMLTextAreaElement).value);expect(config.managementRules).toHaveLength(1);expect(config.managementRules[0].groups).toEqual({ids:[],all:false});
  fireEvent.click(screen.getByRole('button',{name:'Save role'}));await waitFor(()=>expect(saved).toHaveBeenCalled());
  const body=JSON.parse(transport.mock.calls[0][1]?.body as string);expect(body.roleType).toBe('MANAGEMENT');expect(body).not.toHaveProperty('rules');
});
it('malformed advanced JSON disables guided fields and saving',()=>{
  const transport=vi.fn<typeof fetch>();render(<DirectoryEditor client={new ControlClient('token',vi.fn(),transport)} kind="roles" saved={vi.fn()} close={vi.fn()}/>);
  fireEvent.change(screen.getByLabelText('Configuration (JSON)'),{target:{value:'[]'}});expect(screen.getByRole('alert')).toBeTruthy();expect(screen.getByRole('button',{name:'Save role'}).hasAttribute('disabled')).toBe(true);expect(transport).not.toHaveBeenCalled();
});
it('groups all navigation into six expandable submenus and exposes Roles',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockImplementation(async()=>new Response(JSON.stringify({items:[]}))));render(<App/>);
  fireEvent.change(screen.getByLabelText('Access token'),{target:{value:'token'}});fireEvent.click(screen.getByRole('button',{name:'Connect to workspace'}));
  await screen.findByRole('navigation');expect(document.querySelectorAll('nav details')).toHaveLength(6);
  expect([...document.querySelectorAll('nav summary')].map(element=>element.textContent)).toEqual(['Audit','Configuration','Tools','Devices','Users','Agents']);
  expect(screen.getByRole('link',{name:'Typed roles'}).closest('details')?.querySelector('summary')?.textContent).toBe('Users');
  expect(screen.getByRole('link',{name:'Tool builder'}).closest('details')?.querySelector('summary')?.textContent).toBe('Tools');
  expect(screen.getByRole('link',{name:'Enroll device'}).closest('details')?.querySelector('summary')?.textContent).toBe('Devices');
  expect(screen.getByRole('link',{name:'Agents'}).closest('details')?.querySelector('summary')?.textContent).toBe('Agents');
  expect(screen.getByRole('link',{name:'Agent Group tool mapping'}).closest('details')?.querySelector('summary')?.textContent).toBe('Agents');
  expect(screen.getByRole('link',{name:'Audit log'}).closest('details')?.querySelector('summary')?.textContent).toBe('Audit');
});
it('loads audit metadata, advances the cursor, refreshes and handles errors',async()=>{
  const event={sequence:1,tenantId:'tenant',actorId:'a'.repeat(64),operation:'UPDATE',target:'roles:cloud',revision:2,requestId:'request-1',occurredAt:'2026-10-04T00:00:00Z',requestDigest:'b'.repeat(64)};
  const transport=vi.fn<typeof fetch>().mockImplementation(async url=>new Response(JSON.stringify(String(url).includes('cursor=1')?{items:[]}:{items:[event],nextCursor:'1'})));
  render(<Audit client={new ControlClient('token',vi.fn(),transport)}/>);await screen.findByText('roles:cloud');
  fireEvent.click(screen.getByRole('button',{name:'Next page'}));await screen.findByText('No audit events on this page.');
  fireEvent.click(screen.getByRole('button',{name:'First page'}));await screen.findByText('roles:cloud');
  transport.mockResolvedValue(new Response('{}',{status:503}));fireEvent.click(screen.getByRole('button',{name:'Refresh'}));await screen.findByRole('alert');
  transport.mockResolvedValue(new Response(JSON.stringify({items:[]})));fireEvent.click(screen.getByRole('button',{name:'Try again'}));await screen.findByText('No audit events on this page.');
});
