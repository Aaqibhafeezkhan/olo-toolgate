// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Devices} from '../src/Devices';
import {ControlClient} from '../src/api';
import type {EndpointManagedDevice} from '@olo-labs/toolgate-contracts';
afterEach(cleanup);
const active:EndpointManagedDevice={deviceId:'device-1',systemExecutor:false,directoryDevice:{id:'device-1',name:'Trading workstation',ownerUserId:'alice',enabled:true,revision:4},registeredUser:{id:'alice',name:'Alice Smith',enabled:true,revision:1},endpointDevice:{deviceId:'device-1',tenantId:'test',userId:'alice',keyFingerprint:'a'.repeat(64),state:'ACTIVE',revision:10,approvalRevision:3,connectionApproved:true,reportSequence:2,lastSeenUnixMs:Date.now()}};
const pending:EndpointManagedDevice={deviceId:'device-2',systemExecutor:false,enrollment:{enrollmentId:'enroll-2',deviceId:'device-2',userCode:'ABCDEF0123456789',platform:'WINDOWS',keyFingerprint:'b'.repeat(64),state:'PENDING',expiresAtUnixMs:Date.now()+600000}};
const system:EndpointManagedDevice={deviceId:'local-builtins',systemExecutor:true,systemExecutorKind:'BUILTINS',systemAvailable:true,directoryDevice:{id:'local-builtins',name:'Quickstart fixed executor',ownerUserId:'local-tools',enabled:true,revision:1},registeredUser:{id:'local-tools',name:'Local tool requester',enabled:true,revision:1}};
function setup(items:EndpointManagedDevice[],mutationStatus=200){const transport=vi.fn<typeof fetch>().mockImplementation(async(path,options)=>new Response(JSON.stringify(String(path).endsWith('/groups')?{entityType:'DEVICE',entityId:String(path).split('/').at(-2),groupIds:['default-devices'],revision:1}:options?.method==='POST'?{}:{items}),{status:options?.method==='POST'?mutationStatus:200}));render(<Devices client={new ControlClient('test-token',vi.fn(),transport)}/>);return transport;}
describe('Device management',()=>{
  it('shows reported system names and observed IPs, with honest missing metadata',async()=>{
    setup([{...active,systemName:'trading-desktop',ipAddress:'192.0.2.15'},pending]);
    expect(await screen.findByText('trading-desktop')).toBeTruthy();expect(screen.getByText('192.0.2.15')).toBeTruthy();
    expect(screen.getByRole('columnheader',{name:'System name'})).toBeTruthy();expect(screen.getByRole('columnheader',{name:'IP address'})).toBeTruthy();
    expect(screen.getAllByText('Not reported').length).toBe(2);
  });
  it('lists approved, pending and system devices with names, users and accessible details',async()=>{
    setup([active,pending,system]);await screen.findByText('Trading workstation');
    expect(screen.getByRole('columnheader',{name:'Registered user'})).toBeTruthy();expect(screen.getByText('Alice Smith')).toBeTruthy();expect(screen.getByText('Awaiting registration')).toBeTruthy();
    expect(screen.getByText('Available').className).toContain('online');
    expect(screen.queryByRole('button',{name:'Approve local-builtins'})).toBeNull();
    fireEvent.focus(screen.getByRole('button',{name:'Details for device-1'}));expect(screen.getByRole('tooltip').textContent).toContain('Alice Smith (alice)');expect(screen.getByRole('tooltip').textContent).toContain('protected device key');
    fireEvent.keyDown(screen.getByRole('button',{name:'Details for device-1'}),{key:'Escape'});expect(screen.queryByRole('tooltip')).toBeNull();
  });
  it('does not show unavailable server executors green',async()=>{setup([{...system,systemAvailable:false}]);expect((await screen.findByText('Unavailable')).className).toContain('offline');});
  it('shows a disabled registered user as blocked immediately',async()=>{setup([{...active,registeredUser:{...active.registeredUser!,enabled:false}}]);expect((await screen.findByText('User disabled')).className).toContain('offline');});
  it('approves a pending device explicitly for unlimited time without a deadline',async()=>{
    const transport=setup([pending]);fireEvent.click(await screen.findByRole('button',{name:'Approve device-2'}));
    fireEvent.change(screen.getByLabelText('Connection duration'),{target:{value:'unlimited'}});expect(screen.queryByLabelText('Allow connection until')).toBeNull();
    const submit=screen.getByRole('button',{name:'Approve device'});expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox',{name:/I compared|I confirm/}));fireEvent.click(submit);await screen.findByText('Device approved.');
    const mutation=transport.mock.calls.find(call=>call[1]?.method==='POST')!;expect(mutation[0]).toBe('/api/control/v1/endpoint/enrollments/decision');expect(JSON.parse(String(mutation[1]?.body))).toEqual({choice:'APPROVE',userCode:pending.enrollment!.userCode,keyFingerprint:pending.enrollment!.keyFingerprint,unlimitedConnection:true});
  });
  it('deapproves without disabling, and disables with directory concurrency independently',async()=>{
    const transport=setup([active]);fireEvent.click(await screen.findByRole('button',{name:'Deapprove device-1'}));await screen.findByText('Device deapproved.');
    fireEvent.click(screen.getByRole('switch',{name:'Disable device-1'}));await screen.findByText('Device disabled.');
    const calls=transport.mock.calls.filter(call=>call[1]?.method==='POST');expect(calls.map(call=>call[0])).toEqual(['/api/control/v1/endpoint/devices/device-1/approval','/api/control/v1/endpoint/devices/device-1/enabled']);
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({expectedApprovalRevision:3,approved:false});expect(JSON.parse(String(calls[1][1]?.body))).toEqual({expectedRevision:4,enabled:false});
  });
  it('reapproves the same enrolled owner for unlimited time and clears a stale decision on conflict',async()=>{
    const transport=setup([{...active,endpointDevice:{...active.endpointDevice!,connectionApproved:false}}],409);
    fireEvent.click(await screen.findByRole('button',{name:'Approve device-1'}));fireEvent.change(screen.getByLabelText('Connection duration'),{target:{value:'unlimited'}});fireEvent.click(screen.getByRole('checkbox',{name:/I compared|I confirm/}));await waitFor(()=>expect((screen.getByRole('button',{name:'Approve device'}) as HTMLButtonElement).disabled).toBe(false));fireEvent.click(screen.getByRole('button',{name:'Approve device'}));
    await screen.findByRole('alert');expect(screen.queryByRole('heading',{name:'Approve device-1'})).toBeNull();
    const mutation=transport.mock.calls.find(call=>call[1]?.method==='POST')!;expect(JSON.parse(String(mutation[1]?.body))).toEqual({expectedApprovalRevision:3,approved:true,unlimitedConnection:true});
    expect(new Headers(mutation[1]?.headers).has('Idempotency-Key')).toBe(true);
  });
});
