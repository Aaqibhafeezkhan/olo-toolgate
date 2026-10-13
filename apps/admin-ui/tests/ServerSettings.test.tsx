// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {describe,it,expect,vi,afterEach} from 'vitest';
import {render,screen,fireEvent,cleanup} from '@testing-library/react';
import {ServerConfiguration,DeviceConfiguration,CHANGED_ELSEWHERE,REVIEW_POLICY_CHANGE} from '../src/ServerSettings';
import {ControlClient} from '../src/api';
afterEach(cleanup);
const DAY=86400000;
const initial={formatVersion:2,revision:3,gateway:{gatewayName:'ToolGate'},deviceApproval:{autoApprove:false,durationMs:30*DAY},clientCheckIn:{checkInMs:2000,jitterPct:20,offlineAfterMs:120000,source:'DEFAULT'}};
const client=(transport:typeof fetch)=>new ControlClient('token',vi.fn(),transport);
const ok=(body:unknown)=>new Response(JSON.stringify(body),{status:200});

describe('Configuration settings pages',()=>{
  it('Server patches only the gateway group with the loaded revision',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>ok(options?.method==='PATCH'?{...initial,revision:4,...JSON.parse(String(options.body))}:initial));
    render(<ServerConfiguration client={client(transport)}/>);
    const name=await screen.findByLabelText('Gateway name');
    const save=screen.getByRole('button',{name:'Save server settings'});expect((save as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(name,{target:{value:' Lab Gateway '}});fireEvent.click(save);
    await screen.findByText('Server settings saved.');
    const [url,options]=transport.mock.calls[1];expect(url).toBe('/api/control/v1/settings');expect(options?.method).toBe('PATCH');
    const headers=new Headers(options?.headers);
    expect(headers.get('Content-Type')).toBe('application/merge-patch+json');expect(headers.get('If-Match')).toBe('"3"');expect(headers.get('Idempotency-Key')).toBeTruthy();
    expect(JSON.parse(String(options?.body))).toEqual({gateway:{gatewayName:'Lab Gateway'}});
  });
  it('Device patches only device approval and shows check-in read-only',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>ok(options?.method==='PATCH'?{...initial,revision:4,...JSON.parse(String(options.body))}:initial));
    render(<DeviceConfiguration client={client(transport)}/>);
    const duration=await screen.findByLabelText('Auto approve duration (days)');expect((duration as HTMLInputElement).value).toBe('30');
    expect(screen.getByText('2000 ms')).toBeTruthy();expect(screen.getByText('20 %')).toBeTruthy();expect(screen.getByText('120000 ms')).toBeTruthy();
    expect(screen.getByText(/Set by the deployment \(built-in defaults\)/)).toBeTruthy();
    expect(screen.queryByLabelText('Check-in interval')).toBeNull();
    fireEvent.click(screen.getByLabelText('Auto approve devices'));
    const save=screen.getByRole('button',{name:'Save device settings'});expect((save as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Owner user ID for auto-approved devices'),{target:{value:'admin'}});
    fireEvent.change(duration,{target:{value:'7'}});fireEvent.click(save);
    await screen.findByText('Device settings saved.');
    const [,options]=transport.mock.calls[1];expect(options?.method).toBe('PATCH');
    expect(JSON.parse(String(options?.body))).toEqual({deviceApproval:{autoApprove:true,durationMs:7*DAY,ownerUserId:'admin'}});
  });
  it('a stale revision reloads the current values and asks the administrator to retry',async()=>{
    const current={...initial,revision:5,gateway:{gatewayName:'Renamed elsewhere'}};let reads=0;
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>options?.method==='PATCH'?new Response(JSON.stringify({code:'CONFLICT'}),{status:409}):ok(reads++?current:initial));
    render(<ServerConfiguration client={client(transport)}/>);
    fireEvent.change(await screen.findByLabelText('Gateway name'),{target:{value:'Mine'}});
    fireEvent.click(screen.getByRole('button',{name:'Save server settings'}));
    await screen.findByText(CHANGED_ELSEWHERE);
    await screen.findByDisplayValue('Renamed elsewhere');
    expect(transport).toHaveBeenCalledTimes(3);
  });
  it('Server saves the configuration review threshold and refreshes the review banner',async()=>{
    const settings={...initial,configuration:{requiredReviews:0}};
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>ok(options?.method==='PATCH'?{...settings,revision:4,...JSON.parse(String(options.body))}:settings));
    const changed=vi.fn();window.addEventListener(REVIEW_POLICY_CHANGE,changed);
    render(<ServerConfiguration client={client(transport)}/>);
    const reviews=await screen.findByLabelText('Configuration reviews');expect((reviews as HTMLSelectElement).value).toBe('0');
    const save=screen.getByRole('button',{name:'Save configuration reviews'});expect((save as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(reviews,{target:{value:'2'}});fireEvent.click(save);
    await screen.findByText('Configuration reviews saved.');
    expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toEqual({configuration:{requiredReviews:2}});
    expect(changed).toHaveBeenCalledTimes(1);window.removeEventListener(REVIEW_POLICY_CHANGE,changed);
  });
  it('a threshold change under an active threshold becomes a reviewed configuration change',async()=>{
    const settings={...initial,configuration:{requiredReviews:2}};const id='config-'+'b'.repeat(40);
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>options?.method==='PATCH'?new Response(JSON.stringify({id,state:'DRAFT'}),{status:202}):ok(settings));
    render(<ServerConfiguration client={client(transport)}/>);
    fireEvent.change(await screen.findByLabelText('Configuration reviews'),{target:{value:'0'}});
    expect(screen.getByText(/two reviewers must approve/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button',{name:'Save configuration reviews'}));
    expect((await screen.findByRole('link',{name:'Review configuration draft'})).getAttribute('href')).toBe('#config-reviews?id='+id);
    expect(screen.queryByText('Configuration reviews saved.')).toBeNull();
  });
});
