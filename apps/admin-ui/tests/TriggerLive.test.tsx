// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// @vitest-environment-options {"url":"https://control.example.test"}
import {afterEach,expect,it,vi} from 'vitest';
import {act,cleanup,render,screen,waitFor} from '@testing-library/react';
import {ControlClient} from '../src/api';
import {TriggerLive} from '../src/TriggerLive';

afterEach(()=>{cleanup();vi.unstubAllGlobals();});

it('watches without advancing a subscription cursor and clears events on revocation',async()=>{
  const socket={
    protocol:'toolgate.triggers.v1',send:vi.fn(),close:vi.fn(),
    onopen:undefined as (()=>void)|undefined,
    onmessage:undefined as ((event:{data:string})=>void)|undefined,
  };
  const open=vi.fn(function(){return socket;});
  vi.stubGlobal('WebSocket',open);
  const transport=vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ticket:'opaque',expiresAtUnixMs:99}),{status:200}));
  const client=new ControlClient('token',vi.fn(),transport);
  render(<TriggerLive client={client} assignment="inbox" close={vi.fn()}/>);
  await waitFor(()=>expect(open).toHaveBeenCalledOnce());
  expect(open).toHaveBeenCalledWith('wss://control.example.test/api/control/v1/triggers/stream',['toolgate.triggers.v1','ticket.opaque']);
  expect(transport.mock.calls[0]?.[1]?.method).toBe('POST');
  act(()=>socket.onopen?.());
  expect(JSON.parse(socket.send.mock.calls[0]?.[0])).toEqual({type:'watch',sources:{assignments:['inbox']}});
  const emit=(message:object)=>act(()=>socket.onmessage?.({data:JSON.stringify(message)}));
  emit({type:'subscribed'});
  expect(screen.getByText('Watching')).toBeTruthy();
  emit({type:'event',event:{id:'one',source:'device-a',type:'new-file'}});
  expect(screen.getByText('new-file · device-a')).toBeTruthy();
  expect(JSON.parse(socket.send.mock.calls[1]?.[0])).toEqual({type:'credit',n:1});
  emit({type:'revoked'});
  expect(screen.getByText('Access to this trigger stream was revoked.')).toBeTruthy();
  expect(screen.queryByText('new-file · device-a')).toBeNull();
  expect(socket.close).toHaveBeenCalled();
});
