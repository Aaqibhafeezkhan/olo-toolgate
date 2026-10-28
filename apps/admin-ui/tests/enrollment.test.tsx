// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Enrollment } from '../src/Enrollment';
import { ControlClient } from '../src/api';
afterEach(() => { cleanup();vi.useRealTimers();window.location.hash = ''; });
const review = {enrollmentId:'enrollment-1', userCode:'ABCDEF0123456789', deviceId:'device-1', platform:'LINUX',
  keyFingerprint:'a'.repeat(64), state:'PENDING', expiresAtUnixMs:1900000000000};
function clientFor(transport:typeof fetch){const client=new ControlClient('test-only-token',vi.fn(),async(url,options)=>
  url==='/api/control/v1/admin-session'?new Response(JSON.stringify({role:'ADMINISTRATOR',permissions:['device.read','device.enroll','device.approve']})):
  url==='/api/control/v1/endpoint/enrollments'?new Response(JSON.stringify({items:[]})):transport(url,options));void client.adminSession();return client;}
function authorizedClient(transport:typeof fetch){const client=new ControlClient('test-only-token',vi.fn(),(url,options)=>url==='/api/control/v1/admin-session'?Promise.resolve(new Response(JSON.stringify({role:'ADMINISTRATOR',permissions:['device.read','device.enroll','device.approve']}))):transport(url,options));void client.adminSession();return client;}
describe('Endpoint enrollment', () => {
  it('verifies a code supplied by the client without approving access',async()=>{
    window.location.hash='#enroll?code=ABCDEF0123456789';
    const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(review)));
    render(<Enrollment client={clientFor(transport)}/>);
    await screen.findByRole('heading',{name:'Device confirmation'});
    expect(screen.getByRole('link',{name:'Open Clients to review and approve it.'}).getAttribute('href')).toBe('#devices');
    expect(transport.mock.calls).toHaveLength(1);
    expect(String(transport.mock.calls[0][0])).toMatch(/\/enrollments\/lookup$/);
    expect((screen.getByRole('button',{name:'Enroll device'}) as HTMLButtonElement).disabled).toBe(true);
  });
  it('supports explicit unlimited approval and omits the finite deadline',async()=>{
    window.location.hash='#enroll?code=ABCDEF0123456789';
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review))).mockResolvedValue(new Response(JSON.stringify({...review,state:'APPROVED',unlimitedConnection:true})));
    render(<Enrollment client={clientFor(transport)}/>);fireEvent.click(screen.getByRole('button',{name:'Review device'}));await screen.findByRole('checkbox',{name:/I compared/});
    fireEvent.change(screen.getByLabelText('Connection duration'),{target:{value:'unlimited'}});fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));
    await screen.findByText('Connection approved for unlimited time.');
    expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toEqual({userCode:review.userCode,keyFingerprint:review.keyFingerprint,choice:'APPROVE',unlimitedConnection:true});
  });
  it('automatically finds new requests and stops polling when unmounted',async()=>{
    vi.useFakeTimers();let items:typeof review[]=[];
    const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify({items})));
    const {unmount}=render(<Enrollment client={authorizedClient(transport)} superAdmin/>);
    await act(async()=>{await vi.advanceTimersByTimeAsync(0);});expect(screen.getByText('No devices are waiting for approval.')).toBeTruthy();
    items=[review];await act(async()=>{await vi.advanceTimersByTimeAsync(5000);});expect(screen.getByRole('button',{name:'Review device-1'})).toBeTruthy();
    unmount();await vi.advanceTimersByTimeAsync(10000);expect(transport).toHaveBeenCalledTimes(2);
  });
  it('shows every pending device and reviews a selected request without typing its code',async()=>{
    const second={...review,enrollmentId:'enrollment-2',deviceId:'device-2',userCode:'1111111111111111',platform:'WINDOWS'};
    let items=[review,second];
    const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
      if(url==='/api/control/v1/endpoint/enrollments')return new Response(JSON.stringify({items}));
      if(String(url).endsWith('/lookup'))return new Response(JSON.stringify(second));
      if(String(url).endsWith('/decision')){items=[review];return new Response(JSON.stringify({...second,state:'APPROVED',connectionExpiresAtUnixMs:JSON.parse(String(options?.body)).connectionExpiresAtUnixMs}));}
      return new Response(JSON.stringify({state:'ACTIVE',reportSequence:0,lastSeenUnixMs:0}));
    });
    render(<Enrollment client={authorizedClient(transport)}/>);
    await screen.findByRole('button',{name:'Review device-1'});fireEvent.click(screen.getByRole('button',{name:'Review device-2'}));
    const until=await screen.findByLabelText('Allow connection until');
    fireEvent.change(until,{target:{value:'2030-01-01T18:30'}});fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));await screen.findByText('Connection allowed until');
    const decision=transport.mock.calls.find(call=>String(call[0]).endsWith('/decision'))!;
    expect(JSON.parse(String(decision[1]?.body))).toEqual({userCode:second.userCode,keyFingerprint:second.keyFingerprint,choice:'APPROVE',connectionExpiresAtUnixMs:new Date('2030-01-01T18:30').getTime()});
    expect(transport.mock.calls.some(call=>String(call[0]).endsWith('/enrollments/lookup')&&call[1]?.method==='POST'&&JSON.parse(String(call[1]?.body)).userCode===second.userCode)).toBe(true);
    await waitFor(()=>expect(screen.queryByRole('button',{name:'Review device-2'})).toBeNull());
    expect(screen.getByRole('button',{name:'Review device-1'})).toBeTruthy();
  });
  it('blocks invalid or past deadlines while allowing denial without a deadline',async()=>{
    window.location.hash='#enroll?code=ABCDEF0123456789';
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review)))
      .mockResolvedValueOnce(new Response(JSON.stringify({...review,state:'DENIED'})));
    render(<Enrollment client={clientFor(transport)}/>);fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    const until=await screen.findByLabelText('Allow connection until');fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    for(const value of ['2000-01-01T00:00','']){fireEvent.change(until,{target:{value}});expect((screen.getByRole('button',{name:'Enroll device'}) as HTMLButtonElement).disabled).toBe(true);}
    fireEvent.click(screen.getByRole('button',{name:'Deny enrollment'}));await screen.findByText('DENIED');
    expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toEqual({userCode:review.userCode,keyFingerprint:review.keyFingerprint,choice:'DENY'});
  });
  it('refreshes requests and clears list failures without approving any device',async()=>{
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response('{}',{status:503}))
      .mockResolvedValueOnce(new Response(JSON.stringify({items:[review]})));
    render(<Enrollment client={authorizedClient(transport)} superAdmin/>);await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button',{name:'Refresh requests'}));await screen.findByRole('button',{name:'Review device-1'});
    expect(screen.queryByRole('alert')).toBeNull();expect(transport.mock.calls.every(call=>call[1]?.method==='GET')).toBe(true);
  });
  it('requires explicit fingerprint comparison and binds the decision to the reviewed key', async () => {
    window.location.hash = '#enroll?code=ABCDEF0123456789';
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review)))
      .mockResolvedValueOnce(new Response(JSON.stringify({...review,state:'APPROVED'})));
    render(<Enrollment client={clientFor(transport)} />);
    fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    const approve = await screen.findByRole('button',{name:'Enroll device'});
    expect((approve as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Allow connection until'),{target:{value:'2030-01-01T18:30'}});
    fireEvent.click(screen.getByRole('checkbox',{name:/I compared/})); fireEvent.click(approve);
    await screen.findByText(/protected client will retrieve/);
    const options = transport.mock.calls[1][1]!;
    expect(JSON.parse(String(options.body))).toEqual({userCode:review.userCode,keyFingerprint:review.keyFingerprint,choice:'APPROVE',connectionExpiresAtUnixMs:new Date('2030-01-01T18:30').getTime()});
    expect(String(options.body)).not.toContain('deviceCode');
  });
  it('clears the reviewed identity after a competing decision and requires review again', async () => {
    window.location.hash = '#enroll?code=ABCDEF0123456789';
    const transport = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review)))
      .mockResolvedValueOnce(new Response(JSON.stringify({code:'CONFLICT',message:'Changed',requestId:'request-1'}),{status:409}));
    render(<Enrollment client={clientFor(transport)} />);
    fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    await screen.findByRole('checkbox',{name:/I compared/}); fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(screen.getByRole('button',{name:'Deny enrollment'}));
    await screen.findByRole('alert'); expect(screen.queryByRole('button',{name:'Enroll device'})).toBeNull();
  });
  it('confirms only a fresh authenticated check-in for the reviewed device and key',async()=>{
    window.location.hash='#enroll?code=ABCDEF0123456789';
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review)))
      .mockResolvedValueOnce(new Response(JSON.stringify({...review,state:'APPROVED'})))
      .mockResolvedValueOnce(new Response(JSON.stringify({deviceId:review.deviceId,keyFingerprint:review.keyFingerprint,state:'ACTIVE',reportSequence:1,lastSeenUnixMs:Date.now()})));
    render(<Enrollment client={clientFor(transport)}/>);
    fireEvent.click(screen.getByRole('button',{name:'Review device'}));await screen.findByRole('checkbox',{name:/I compared/});fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));await screen.findByText(/Connected: this device/);
    expect(transport.mock.calls[2][0]).toBe('/api/control/v1/endpoint/devices/device-1');
  });
  it.each([{lastSeenUnixMs:1},{deviceId:'another-device'},{keyFingerprint:'b'.repeat(64)},{reportSequence:0},{state:'REVOKED'}])('does not declare a mismatched, stale or revoked device connected (%j)',async(change)=>{
    window.location.hash='#enroll?code=ABCDEF0123456789';
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify(review)))
      .mockResolvedValueOnce(new Response(JSON.stringify({...review,state:'APPROVED'})))
      .mockResolvedValueOnce(new Response(JSON.stringify({deviceId:review.deviceId,keyFingerprint:review.keyFingerprint,state:'ACTIVE',reportSequence:1,lastSeenUnixMs:Date.now(),...change})));
    render(<Enrollment client={clientFor(transport)}/>);
    fireEvent.click(screen.getByRole('button',{name:'Review device'}));await screen.findByRole('checkbox',{name:/I compared/});fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));
    await screen.findByText(change.state==='REVOKED'?/Device revoked/:/Waiting for this device/);
    expect(screen.queryByText(/Connected: this device/)).toBeNull();
  });
  it('enrolls by code: looks up the device ID, platform and fingerprint, then approves with the same code and key',async()=>{
    const lookup={enrollmentId:'enrollment-9',deviceId:'visitor-laptop',platform:'MACOS',keyFingerprint:'c'.repeat(64),expiresAtUnixMs:1900000000000};
    const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
      if(url==='/api/control/v1/endpoint/enrollments')return new Response(JSON.stringify({items:[]}));
      if(String(url).endsWith('/lookup'))return new Response(JSON.stringify(lookup));
      if(String(url).endsWith('/decision'))return new Response(JSON.stringify({...lookup,userCode:'0123456789ABCDEF',state:'APPROVED',connectionExpiresAtUnixMs:JSON.parse(String(options?.body)).connectionExpiresAtUnixMs}));
      return new Response(JSON.stringify({state:'ACTIVE',reportSequence:0,lastSeenUnixMs:0}));
    });
    render(<Enrollment client={authorizedClient(transport)}/>);
    expect(screen.queryByRole('heading',{name:'Devices waiting for approval'})).toBeNull();
    await waitFor(()=>expect((screen.getByRole('button',{name:'Review device'}) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.change(screen.getByLabelText('Enrollment code'),{target:{value:'0123456789abcdef'}});fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    await screen.findByRole('heading',{name:'Device confirmation'});
    expect(screen.getByText('visitor-laptop')).toBeTruthy();expect(screen.getByText('MACOS')).toBeTruthy();expect(screen.getByText('c'.repeat(64))).toBeTruthy();
    const call=transport.mock.calls.find(([url])=>String(url).endsWith('/lookup'))!;
    expect(call[0]).toBe('/api/control/v1/endpoint/enrollments/lookup');expect(call[1]?.method).toBe('POST');expect(JSON.parse(String(call[1]?.body))).toEqual({userCode:'0123456789ABCDEF'});
    fireEvent.change(screen.getByLabelText('Allow connection until'),{target:{value:'2030-01-01T18:30'}});
    fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));
    await screen.findByText(/protected client will retrieve/);
    const decision=transport.mock.calls.find(([url])=>String(url).endsWith('/decision'))!;
    expect(JSON.parse(String(decision[1]?.body))).toEqual({userCode:'0123456789ABCDEF',keyFingerprint:lookup.keyFingerprint,choice:'APPROVE',connectionExpiresAtUnixMs:new Date('2030-01-01T18:30').getTime()});
  });
  it('reveals nothing for an unknown code and explains rate limiting',async()=>{
    const transport=vi.fn<typeof fetch>().mockResolvedValueOnce(new Response(JSON.stringify({code:'NOT_FOUND'}),{status:404})).mockResolvedValueOnce(new Response(JSON.stringify({code:'RATE_LIMITED'}),{status:429}));
    render(<Enrollment client={clientFor(transport)}/>);
    await waitFor(()=>expect((screen.getByRole('button',{name:'Review device'}) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.change(screen.getByLabelText('Enrollment code'),{target:{value:'FFFFFFFFFFFFFFFF'}});fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    expect((await screen.findByRole('alert')).textContent).toContain('No pending enrollment you can approve matches this code');
    expect(screen.queryByRole('heading',{name:'Device confirmation'})).toBeNull();
    fireEvent.click(screen.getByRole('button',{name:'Review device'}));
    await waitFor(()=>expect(screen.getByRole('alert').textContent).toContain('Too many attempts'));
  });
  it('shows no pending list to callers without a group rule, and only approvable requests to the rest',async()=>{
    const hidden={...review,userCode:''};let items=[hidden];
    const transport=vi.fn<typeof fetch>().mockImplementation(async()=>new Response(JSON.stringify({items})));
    const view=render(<Enrollment client={authorizedClient(transport)}/>);
    await waitFor(()=>expect(transport).toHaveBeenCalled());await act(async()=>{await Promise.resolve();});
    expect(screen.queryByRole('heading',{name:'Devices waiting for approval'})).toBeNull();expect(screen.queryByText('No devices are waiting for approval.')).toBeNull();
    view.unmount();items=[hidden,{...review,enrollmentId:'enrollment-3',deviceId:'device-3'}];
    render(<Enrollment client={authorizedClient(transport)}/>);
    await screen.findByRole('button',{name:'Review device-3'});expect(screen.queryByRole('button',{name:'Review device-1'})).toBeNull();
  });
});
