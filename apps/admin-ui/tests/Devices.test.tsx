// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import {Devices} from '../src/Devices';
import {ControlClient} from '../src/api';
import type {EndpointManagedDevice} from '@olo-labs/toolgate-contracts';
afterEach(cleanup);
const active:EndpointManagedDevice={permissions:['device.read','device.approve','device.manage_groups','device.update','device.delete','device.enable','device.disable'],deviceId:'device-1',directoryDevice:{id:'device-1',name:'Trading workstation',ownerUserId:'alice',enabled:true,revision:4},registeredUser:{id:'alice',name:'Alice Smith',enabled:true,revision:1},endpointDevice:{deviceId:'device-1',tenantId:'test',userId:'alice',keyFingerprint:'a'.repeat(64),state:'ACTIVE',revision:10,approvalRevision:3,connectionApproved:true,reportSequence:2,lastSeenUnixMs:Date.now()}};
const pending:EndpointManagedDevice={permissions:['device.read','device.approve','device.manage_groups'],deviceId:'device-2',enrollment:{enrollmentId:'enroll-2',deviceId:'device-2',userCode:'ABCDEF0123456789',platform:'WINDOWS',keyFingerprint:'b'.repeat(64),state:'PENDING',expiresAtUnixMs:Date.now()+600000}};
function setup(items:EndpointManagedDevice[],mutationStatus=200){const transport=vi.fn<typeof fetch>().mockImplementation(async(path,options)=>new Response(JSON.stringify(String(path).endsWith('/groups')?{entityType:'DEVICE',entityId:String(path).split('/').at(-2),groupIds:['default-devices'],revision:1}:options?.method==='POST'?{}:{items}),{status:options?.method==='POST'?mutationStatus:200}));render(<Devices client={new ControlClient('test-token',vi.fn(),transport)}/>);return transport;}
describe('Device management',()=>{
  it('approves a code-verified owned pending request without changing group memberships',async()=>{
    const mine={...pending,permissions:['device.read','device.approve'],registeredUser:{id:'visitor-001',name:'Visitor',enabled:true,revision:1}};
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_path,options)=>new Response(JSON.stringify(options?.method==='POST'?{}:{items:[mine]}),{status:200}));
    render(<Devices client={new ControlClient('test-token',vi.fn(),transport)} userId="visitor-001"/>);
    fireEvent.click(await screen.findByRole('button',{name:'Approve device-2'}));
    const dialog=screen.getByRole('dialog',{name:'Approve device-2'});
    expect(within(dialog).queryByText('Device groups')).toBeNull();
    fireEvent.click(within(dialog).getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(within(dialog).getByRole('button',{name:'Approve device'}));
    await screen.findByText('Device approved.');
    expect(transport.mock.calls.filter(call=>call[1]?.method==='POST').map(call=>call[0])).toEqual(['/api/control/v1/endpoint/enrollments/decision']);
    expect(transport.mock.calls.some(call=>String(call[0]).endsWith('/groups'))).toBe(false);
  });
  it('shows shared deployment hosts, distinct execution roles and actual network details',async()=>{
    const deployment={executionKind:'ENDPOINT_CLIENT' as const,containerId:'a'.repeat(64),containerName:'toolgate-langchain-quickstart-1',systemName:'quickstart-container',networkId:'b'.repeat(64),networkName:'toolgate-langchain_default',ipAddress:'172.18.0.2'};
    const local={...active,deviceId:'tool-host-local',directoryDevice:{...active.directoryDevice!,id:'tool-host-local',name:'Local host'},systemName:undefined,ipAddress:'::1'};
    const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify({items:[{...active,systemName:'quickstart-container',ipAddress:'127.0.0.1'},local],deployments:[{deviceId:active.deviceId,deployment},{deviceId:local.deviceId,deployment:{...deployment,executionKind:'TOOL_HOST_DIRECT'}}]}),{status:200}));
    render(<Devices client={new ControlClient('test-token',vi.fn(),transport)}/>);
    await screen.findByText('Tool Host · direct dispatch');expect(screen.getByText('Endpoint client')).toBeTruthy();
    expect(screen.getByText(/Docker deployment: 1 containers/)).toBeTruthy();
    expect(screen.getAllByText('toolgate-langchain-quickstart-1')).toHaveLength(2);
    const row=screen.getByRole('row',{name:/Local host/});
    expect(within(row).getByText('172.18.0.2')).toBeTruthy();expect(within(row).getByText('Observed: ::1')).toBeTruthy();
    expect(within(row).getByText('b'.repeat(12)).getAttribute('title')).toBe('b'.repeat(64));
    expect(transport.mock.calls.some(call=>call[0]==='/api/control/v1/endpoint/devices?deployment=true')).toBe(true);
  });
  it('shows incoming enrollment requests without a manual refresh',async()=>{
    let incoming=false;const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify({items:incoming?[active,pending]:[active]}),{status:200}));
    render(<Devices client={new ControlClient('test-token',vi.fn(),transport)}/>);await screen.findByText('Trading workstation');
    expect(screen.queryByRole('button',{name:'Approve device-2'})).toBeNull();
    incoming=true;
    await waitFor(()=>expect(screen.getByRole('button',{name:'Approve device-2'})).toBeTruthy(),{timeout:2500});
  });
  it('reopens a revoked device for fresh approval without approving it',async()=>{
    const transport=setup([{...active,endpointDevice:{...active.endpointDevice!,state:'REVOKED',connectionApproved:false}}]);
    const retry=await screen.findByRole('button',{name:'Request approval again device-1'});
    expect(screen.queryByRole('button',{name:'Approve device-1'})).toBeNull();fireEvent.click(retry);
    await screen.findByText('Approval requested. Waiting for the client to submit a fresh enrollment.');
    const calls=transport.mock.calls.filter(call=>call[1]?.method==='POST');expect(calls).toHaveLength(1);
    expect(calls[0][0]).toBe('/api/control/v1/endpoint/devices/device-1/request-approval');
    expect(JSON.parse(String(calls[0][1]?.body))).toEqual({expectedRevision:10});expect(new Headers(calls[0][1]?.headers).has('Idempotency-Key')).toBe(true);
  });
  it('shows group-loading failures inside the approval popup and keeps approval disabled',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async path=>new Response(JSON.stringify(String(path).endsWith('/groups')?{}:{items:[active]}),{status:String(path).endsWith('/groups')?503:200}));
    render(<Devices client={new ControlClient('test-token',vi.fn(),transport)}/>);fireEvent.click(await screen.findByRole('button',{name:'Change approval device-1'}));
    const dialog=screen.getByRole('dialog',{name:'Approve device-1'});expect(dialog.contains(await screen.findByRole('alert'))).toBe(true);expect((screen.getByRole('button',{name:'Approve device'}) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button',{name:'Close approval'}));expect(screen.queryByRole('dialog')).toBeNull();
  });
  it('shows reported system names and observed IPs, with honest missing metadata',async()=>{
    setup([{...active,systemName:'trading-desktop',ipAddress:'192.0.2.15'},pending]);
    expect(await screen.findByText('trading-desktop')).toBeTruthy();expect(screen.getByText('192.0.2.15')).toBeTruthy();
    expect(screen.getByRole('columnheader',{name:'System name'})).toBeTruthy();expect(screen.getByRole('columnheader',{name:'IP address'})).toBeTruthy();
    expect(screen.getAllByText('Not reported').length).toBe(2);
  });
  it('shows a device its owner switched off as powered off, not offline, in the list and details',async()=>{
    setup([{...active,endpointDevice:{...active.endpointDevice!,report:{deviceId:'device-1',clientVersion:'0.10.0',appliedRevision:0,packages:[],power:'OFF'}}}]);
    const state=await screen.findByText('Powered off by owner');expect(state.className).toContain('offline');expect(screen.queryByText('Offline')).toBeNull();
    fireEvent.focus(screen.getByRole('button',{name:'Details for device-1'}));expect(screen.getByRole('tooltip').textContent).toContain('Connection: Powered off by owner');
  });
  it('lists approved and pending devices with names, users and accessible details',async()=>{
    setup([active,pending]);await screen.findByText('Trading workstation');
    expect(screen.getByRole('columnheader',{name:'Owner'})).toBeTruthy();expect(screen.getByText('Alice Smith')).toBeTruthy();expect(screen.getByText('Awaiting registration')).toBeTruthy();
    fireEvent.focus(screen.getByRole('button',{name:'Details for device-1'}));expect(screen.getByRole('tooltip').textContent).toContain('Alice Smith (alice)');expect(screen.getByRole('tooltip').textContent).toContain('protected device key');
    fireEvent.keyDown(screen.getByRole('button',{name:'Details for device-1'}),{key:'Escape'});expect(screen.queryByRole('tooltip')).toBeNull();
  });
  it('keeps expired approvals listed as approval required with an approve action',async()=>{
    setup([{...active,endpointDevice:{...active.endpointDevice!,connectionExpiresAtUnixMs:Date.now()-1000}}]);
    expect(await screen.findByText('Approval required')).toBeTruthy();
    expect(screen.getByText('Trading workstation')).toBeTruthy();
    expect(screen.getByRole('button',{name:'Approve device-1'})).toBeTruthy();
  });
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
  it('shows owners and hides approval actions where Control would refuse them',async()=>{
    const {keyFingerprint:_key,...redacted}=active.endpointDevice!;
    const lab={...active,permissions:['device.read'],deviceId:'lab-1',directoryDevice:{...active.directoryDevice!,id:'lab-1',name:'Lab device',ownerUserId:'admin'},registeredUser:undefined,endpointDevice:{...redacted,deviceId:'lab-1',userId:'admin'}} as EndpointManagedDevice;
    const mine={...active,permissions:['device.read','device.approve'],deviceId:'own-1',directoryDevice:{...active.directoryDevice!,id:'own-1',name:'My laptop',ownerUserId:'visitor-001'},registeredUser:undefined,endpointDevice:{...redacted,deviceId:'own-1',userId:'visitor-001',connectionApproved:false}} as EndpointManagedDevice;
    const hiddenPending={...pending,permissions:[],deviceId:'device-9',enrollment:{...pending.enrollment!,deviceId:'device-9',userCode:'',keyFingerprint:''}};
    const transport=vi.fn<typeof fetch>().mockImplementation(async(path,options)=>new Response(JSON.stringify(options?.method==='POST'?{}:String(path).endsWith('/groups')?{}:{items:[lab,mine,hiddenPending]}),{status:200}));
    const view=render(<Devices client={new ControlClient('test-token',vi.fn(),transport)} userId="visitor-001"/>);
    await screen.findByText('My laptop');
    expect(within(screen.getByRole('row',{name:/Lab device/})).getByText('admin')).toBeTruthy();
    for(const id of ['lab-1','device-9'])expect(screen.queryByRole('button',{name:new RegExp(`(Approve|Deapprove|Change approval) ${id}$`)})).toBeNull();
    fireEvent.click(screen.getByRole('button',{name:'Approve own-1'}));
    const dialog=screen.getByRole('dialog',{name:'Approve own-1'});
    expect(within(dialog).queryByText('Device groups')).toBeNull();expect(within(dialog).getByText(/You own this device/)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('checkbox',{name:/I confirm/}));fireEvent.click(within(dialog).getByRole('button',{name:'Approve device'}));
    await screen.findByText('Device approved.');
    expect(transport.mock.calls.filter(call=>call[1]?.method==='POST').map(call=>call[0])).toEqual(['/api/control/v1/endpoint/devices/own-1/approval']);
    expect(transport.mock.calls.some(call=>String(call[0]).endsWith('/groups'))).toBe(false);
    view.unmount();
    render(<Devices client={new ControlClient('test-token',vi.fn(),transport)} superAdmin/>);
    // A presentation prop cannot override the server's target-specific decision.
    await screen.findByText('Lab device');expect(screen.queryByRole('button',{name:'Deapprove lab-1'})).toBeNull();
  });
});
