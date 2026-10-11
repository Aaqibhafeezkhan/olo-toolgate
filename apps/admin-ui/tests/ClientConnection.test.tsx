// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {act,cleanup,render,screen} from '@testing-library/react';
import {ClientConnection} from '../src/ClientConnection';
import {ControlClient} from '../src/api';

afterEach(()=>{cleanup();vi.useRealTimers();vi.restoreAllMocks();});
const device={deviceId:'client-1',tenantId:'tenant-1',userId:'owner-1',keyFingerprint:'a'.repeat(64),state:'ACTIVE',revision:1,reportSequence:1};
function clientWith(body:unknown,status=200) {
  const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify(body),{status}));
  return {client:new ControlClient('test-only-token',vi.fn(),transport),transport};
}
it('shows green only for a fresh authenticated check-in, with a last-seen tooltip',async()=>{
  const {client,transport}=clientWith({...device,lastSeenUnixMs:Date.now()-2000});
  render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  const status=await screen.findByText('Connected');
  expect(status.className).toContain('online');expect(status.title).toContain('Last check-in:');
  expect(status.querySelector('.connection-dot')?.getAttribute('aria-hidden')).toBe('true');
  expect(transport.mock.calls[0][0]).toBe('/api/control/v1/endpoint/devices/client-1');
});
it.each([
  [{lastSeenUnixMs:Date.now()-121000},'Offline'],
  [{lastSeenUnixMs:0,reportSequence:0},'No check-in yet'],
  [{lastSeenUnixMs:Date.now(),state:'REVOKED'},'Revoked'],
  [{lastSeenUnixMs:Date.now(),connectionExpiresAtUnixMs:Date.now()-1},'Approval required'],
  [{lastSeenUnixMs:Date.now(),userId:'another-owner'},'Enrollment mismatch'],
  [{lastSeenUnixMs:Date.now(),deviceId:'another-client'},'Status unavailable'],
  [{lastSeenUnixMs:'not-a-timestamp'},'Status unavailable'],
] as const)('shows red for a client without a valid live connection (%j)',async(change,label)=>{
  const {client}=clientWith({...device,...change});
  render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  expect((await screen.findByText(label)).className).toContain('offline');
});
it('does not equate a directory-enabled client with an enrolled client',async()=>{
  const {client}=clientWith({code:'NOT_FOUND'},404);
  render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  expect((await screen.findByText('Not enrolled')).className).toContain('offline');
});
it('stops showing connected when approval expires even with fresh check-ins',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-08T12:00:00Z'));
  const expires=Date.now()+2000;
  const {client}=clientWith({...device,lastSeenUnixMs:Date.now(),connectionExpiresAtUnixMs:expires});
  render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  await act(async()=>{await vi.advanceTimersByTimeAsync(0);});expect(screen.getByText('Connected').title).toContain('Allowed until:');
  await act(async()=>{await vi.advanceTimersByTimeAsync(2000);});
  expect(screen.getByText('Approval required').className).toContain('offline');
});
it('shows disabled clients as red without polling their connection',async()=>{
  const {client,transport}=clientWith({...device,lastSeenUnixMs:Date.now()});
  render(<ClientConnection client={client} id="client-1" enabled={false} ownerUserId="owner-1"/>);
  expect(screen.getByText('Disabled').className).toContain('offline');expect(transport).not.toHaveBeenCalled();
});
it('refreshes status, recovers from an unavailable request and stops when unmounted',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-08T12:00:00Z'));
  let available=false;
  const transport=vi.fn<typeof fetch>().mockImplementation(async()=>available
    ?new Response(JSON.stringify({...device,lastSeenUnixMs:Date.now()}))
    :new Response('{}',{status:503}));
  const client=new ControlClient('test-only-token',vi.fn(),transport);
  const {unmount}=render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  await act(async()=>{await vi.advanceTimersByTimeAsync(0);});expect(screen.getByText('Status unavailable').className).toContain('offline');
  available=true;await act(async()=>{await vi.advanceTimersByTimeAsync(2000);});
  expect(screen.getByText('Connected').className).toContain('online');expect(transport).toHaveBeenCalledTimes(2);
  unmount();await vi.advanceTimersByTimeAsync(6000);expect(transport).toHaveBeenCalledTimes(2);
});
it('does not overlap slow requests and expires green status while a poll is pending',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-08T12:00:00Z'));
  let resolve!: (response:Response)=>void;
  const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({...device,lastSeenUnixMs:Date.now()-119000})))
    .mockImplementation(()=>new Promise<Response>(done=>{resolve=done;}));
  const client=new ControlClient('test-only-token',vi.fn(),transport);
  render(<ClientConnection client={client} id="client-1" enabled ownerUserId="owner-1"/>);
  await act(async()=>{await vi.advanceTimersByTimeAsync(0);});expect(screen.getByText('Connected')).toBeTruthy();
  await act(async()=>{await vi.advanceTimersByTimeAsync(6000);});
  expect(screen.getByText('Offline').className).toContain('offline');expect(transport).toHaveBeenCalledTimes(2);
  await act(async()=>{resolve(new Response(JSON.stringify({...device,lastSeenUnixMs:Date.now()})));await vi.advanceTimersByTimeAsync(0);});
  expect(screen.getByText('Connected')).toBeTruthy();
});
