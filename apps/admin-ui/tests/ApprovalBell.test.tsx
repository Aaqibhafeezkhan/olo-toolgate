// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ApprovalBell} from '../src/ApprovalBell';
import type {ControlClient} from '../src/api';

afterEach(cleanup);

function pendingRequest(){
  let finish!:(value:{items:unknown[]})=>void;
  const response=new Promise<{items:unknown[]}>(resolve=>{finish=resolve;});
  const approvals=vi.fn().mockReturnValueOnce(response).mockResolvedValue({items:[]});
  const client={approvals,configurationChanges:vi.fn().mockResolvedValue({items:[]}),managedDevices:vi.fn().mockResolvedValue({items:[]})} as unknown as ControlClient;
  return {client,approvals,finish};
}

it('coalesces approval changes during loading into a fresh read after the stale response',async()=>{
  const request=pendingRequest();
  render(<ApprovalBell client={request.client} administrator superAdmin userId="reviewer"/>);
  await waitFor(()=>expect(request.approvals).toHaveBeenCalledTimes(1));
  fireEvent(window,new Event('toolgate:approval-change'));
  fireEvent(window,new Event('toolgate:approval-change'));
  expect(request.approvals).toHaveBeenCalledTimes(1);
  await act(async()=>request.finish({items:[{state:'PENDING',expiresAtUnixMs:Date.now()+60000}]}));
  await waitFor(()=>expect(request.approvals).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('button',{name:'No pending approvals'})).toBeTruthy();
});

it('does not replay a queued refresh after the bell is unmounted',async()=>{
  const request=pendingRequest();
  const view=render(<ApprovalBell client={request.client} administrator superAdmin userId="reviewer"/>);
  fireEvent(window,new Event('toolgate:approval-change'));
  view.unmount();
  await act(async()=>request.finish({items:[]}));
  expect(request.approvals).toHaveBeenCalledTimes(1);
});
