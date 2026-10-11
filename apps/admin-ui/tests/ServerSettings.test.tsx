// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {describe,it,expect,vi,afterEach} from 'vitest';
import {render,screen,fireEvent,cleanup} from '@testing-library/react';
import {ServerConfiguration,DeviceConfiguration,SettingsTransfer,importedSettings,CHANGED_ELSEWHERE} from '../src/ServerSettings';
import {ControlClient} from '../src/api';
afterEach(cleanup);
const DAY=86400000;
const initial={formatVersion:2,revision:3,gateway:{gatewayName:'ToolGate'},deviceApproval:{autoApprove:false,durationMs:30*DAY},clientCheckIn:{checkInMs:2000,jitterPct:20,offlineAfterMs:120000,source:'DEFAULT'}};
const client=(transport:typeof fetch)=>new ControlClient('token',vi.fn(),transport);
const ok=(body:unknown)=>new Response(JSON.stringify(body),{status:200});

describe('Configuration settings pages',()=>{
  it('accepts only exported formatVersion 2 settings files',()=>{
    const owned={...initial,deviceApproval:{autoApprove:true,durationMs:7*DAY,ownerUserId:'admin'}};
    expect(importedSettings(owned)).toEqual(owned);
    expect(()=>importedSettings({...initial,formatVersion:1})).toThrow();
    expect(()=>importedSettings({formatVersion:1,revision:0,autoApproveDevices:false,autoApproveDurationDays:30})).toThrow();
    expect(()=>importedSettings({...initial,gateway:{gatewayName:' spaced'}})).toThrow();
    expect(()=>importedSettings({...initial,deviceApproval:{autoApprove:false,durationMs:DAY-1}})).toThrow();
    expect(()=>importedSettings({...initial,deviceApproval:{autoApprove:true}})).toThrow();
  });
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
  it('restores a settings file with a full PUT only after review',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>ok(options?.method==='PUT'?{...JSON.parse(String(options.body)),revision:4,clientCheckIn:initial.clientCheckIn}:initial));
    render(<SettingsTransfer client={client(transport)}/>);
    await screen.findByRole('button',{name:'Export server settings'});
    const exported={...initial,revision:9,gateway:{gatewayName:'Restored'},deviceApproval:{autoApprove:true,durationMs:7*DAY,ownerUserId:'owner'}};
    const file=new File(['ignored'],'settings.json',{type:'application/json'});Object.defineProperty(file,'text',{value:async()=>JSON.stringify(exported)});
    await vi.waitFor(()=>expect((screen.getByLabelText('Server settings JSON file') as HTMLInputElement).disabled).toBe(false));
    fireEvent.change(screen.getByLabelText('Server settings JSON file'),{target:{files:[file]}});
    await screen.findByText('Settings file loaded. Review it, then restore.');
    expect(screen.getByText('Restored')).toBeTruthy();expect(screen.getByText('7 days')).toBeTruthy();expect(transport).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button',{name:'Restore server settings'}));
    await screen.findByText('Server settings restored.');
    const [,options]=transport.mock.calls[1];expect(options?.method).toBe('PUT');expect(new Headers(options?.headers).get('If-Match')).toBe('"3"');
    expect(JSON.parse(String(options?.body))).toEqual({...exported,revision:3});
  });
  it('rejects a v1 settings file',async()=>{
    render(<SettingsTransfer client={client(vi.fn<typeof fetch>().mockImplementation(async()=>ok(initial)))}/>);
    await vi.waitFor(()=>expect((screen.getByLabelText('Server settings JSON file') as HTMLInputElement).disabled).toBe(false));
    const file=new File(['ignored'],'settings.json',{type:'application/json'});Object.defineProperty(file,'text',{value:async()=>JSON.stringify({formatVersion:1,revision:0,autoApproveDevices:false,autoApproveDurationDays:30})});
    fireEvent.change(screen.getByLabelText('Server settings JSON file'),{target:{files:[file]}});
    await screen.findByText('The file is not an exported ToolGate server settings document (format version 2).');
  });
});
