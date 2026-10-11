// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {DirectoryEditor} from '../src/DirectoryEditor';
import {ControlClient} from '../src/api';
afterEach(cleanup);
it('creates a disabled team with multiple users and retains an exact retry key',async()=>{
  const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response('{}',{status:503}));
  render(<DirectoryEditor client={new ControlClient('token',vi.fn(),transport)} kind="teams" close={vi.fn()} saved={vi.fn()}/>);
  expect((screen.getByLabelText('Enabled') as HTMLInputElement).checked).toBe(false);
  fireEvent.change(screen.getByLabelText('Identifier'),{target:{value:'team-new'}});
  fireEvent.change(screen.getByLabelText('Display name'),{target:{value:'Team'}});
  fireEvent.change(screen.getByLabelText('Configuration (JSON)'),{target:{value:JSON.stringify({userIds:['alice','bob'],roleIds:[]})}});
  fireEvent.click(screen.getByRole('button',{name:'Save team'}));await screen.findByRole('alert');
  fireEvent.click(screen.getByRole('button',{name:'Save team'}));await waitFor(()=>expect(transport).toHaveBeenCalledTimes(2));
  expect(JSON.parse(transport.mock.calls[0][1]?.body as string)).toEqual({id:'team-new',name:'Team',enabled:false,revision:1,userIds:['alice','bob'],roleIds:[]});
  expect(new Headers(transport.mock.calls[1][1]?.headers).get('Idempotency-Key')).toBe(new Headers(transport.mock.calls[0][1]?.headers).get('Idempotency-Key'));
});
it('saves an agent group route slug and drops it when cleared',async()=>{
  const transport=vi.fn<typeof fetch>().mockImplementation(async url=>new Response(String(url).includes('/agent-groups')&&String(url).includes('?')?JSON.stringify({items:[]}):'{}',{status:String(url).includes('?')?200:503}));
  render(<DirectoryEditor client={new ControlClient('token',vi.fn(),transport)} kind="agentGroups" close={vi.fn()} saved={vi.fn()}/>);
  fireEvent.change(screen.getByLabelText('Identifier'),{target:{value:'finance'}});
  fireEvent.change(screen.getByLabelText('Display name'),{target:{value:'Finance'}});
  fireEvent.change(screen.getByLabelText('Route slug'),{target:{value:'finance-bots'}});
  fireEvent.click(screen.getByRole('button',{name:'Save agent group'}));await screen.findByRole('alert');
  const saved=transport.mock.calls.find(([,options])=>options?.method==='POST');
  expect(JSON.parse(saved?.[1]?.body as string).slug).toBe('finance-bots');
  fireEvent.change(screen.getByLabelText('Route slug'),{target:{value:''}});
  expect(JSON.parse((screen.getByLabelText('Configuration (JSON)') as HTMLTextAreaElement).value)).not.toHaveProperty('slug');
});
