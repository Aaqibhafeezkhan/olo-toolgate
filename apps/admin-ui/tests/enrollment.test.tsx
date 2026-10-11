// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Enrollment } from '../src/Enrollment';
import { ControlClient } from '../src/api';
afterEach(() => { cleanup();vi.useRealTimers();window.location.hash = ''; });
const review = {enrollmentId:'enrollment-1', userCode:'ABCDEF0123456789', deviceId:'device-1', platform:'LINUX',
  keyFingerprint:'a'.repeat(64), state:'PENDING', expiresAtUnixMs:1900000000000};
function clientFor(transport:typeof fetch){return new ControlClient('test-only-token',vi.fn(),async(url,options)=>
  url==='/api/control/v1/endpoint/enrollments'?new Response(JSON.stringify({items:[]})):transport(url,options));}
describe('Endpoint enrollment', () => {
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
    const {unmount}=render(<Enrollment client={new ControlClient('test-only-token',vi.fn(),transport)}/>);
    await act(async()=>{await vi.advanceTimersByTimeAsync(0);});expect(screen.getByText('No devices are waiting for approval.')).toBeTruthy();
    items=[review];await act(async()=>{await vi.advanceTimersByTimeAsync(5000);});expect(screen.getByRole('button',{name:'Review device-1'})).toBeTruthy();
    unmount();await vi.advanceTimersByTimeAsync(10000);expect(transport).toHaveBeenCalledTimes(2);
  });
  it('shows every pending device and reviews a selected request without typing its code',async()=>{
    const second={...review,enrollmentId:'enrollment-2',deviceId:'device-2',userCode:'1111111111111111',platform:'WINDOWS'};
    let items=[review,second];
    const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
      if(url==='/api/control/v1/endpoint/enrollments')return new Response(JSON.stringify({items}));
      if(String(url).includes('/review?'))return new Response(JSON.stringify(second));
      if(String(url).endsWith('/decision')){items=[review];return new Response(JSON.stringify({...second,state:'APPROVED',connectionExpiresAtUnixMs:JSON.parse(String(options?.body)).connectionExpiresAtUnixMs}));}
      return new Response(JSON.stringify({state:'ACTIVE',reportSequence:0,lastSeenUnixMs:0}));
    });
    render(<Enrollment client={new ControlClient('test-only-token',vi.fn(),transport)}/>);
    await screen.findByRole('button',{name:'Review device-1'});fireEvent.click(screen.getByRole('button',{name:'Review device-2'}));
    const until=await screen.findByLabelText('Allow connection until');
    fireEvent.change(until,{target:{value:'2030-01-01T18:30'}});fireEvent.click(screen.getByRole('checkbox',{name:/I compared/}));
    fireEvent.click(screen.getByRole('button',{name:'Enroll device'}));await screen.findByText('Connection allowed until');
    const decision=transport.mock.calls.find(call=>String(call[0]).endsWith('/decision'))!;
    expect(JSON.parse(String(decision[1]?.body))).toEqual({userCode:second.userCode,keyFingerprint:second.keyFingerprint,choice:'APPROVE',connectionExpiresAtUnixMs:new Date('2030-01-01T18:30').getTime()});
    expect(transport.mock.calls.some(call=>String(call[0]).endsWith(`review?code=${second.userCode}`))).toBe(true);
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
    render(<Enrollment client={new ControlClient('test-only-token',vi.fn(),transport)}/>);await screen.findByRole('alert');
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
});
