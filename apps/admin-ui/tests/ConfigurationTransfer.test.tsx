// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {describe,it,expect,vi,afterEach} from 'vitest';
import {render,screen,fireEvent,cleanup} from '@testing-library/react';
import {ConfigurationTransfer,completeSnapshot} from '../src/ConfigurationTransfer';
import {ControlClient} from '../src/api';
import type {ControlSnapshot} from '@olo-labs/toolgate-contracts';
afterEach(cleanup);
const snapshot={formatVersion:2,tenantId:'test',revision:4,...Object.fromEntries(['users','teams','agents','tools','policies','devices','roles','deviceGroups','agentGroups','toolGroups','grants','delegations','agentDelegations','bindings','extractors','workloadBindings','identityBindings','deviceEvidence'].map(key=>[key,[]]))} as unknown as ControlSnapshot;
describe('Complete configuration transfer',()=>{
  it('rejects truncated snapshots instead of treating missing collections as removals',()=>{expect(completeSnapshot(snapshot)).toBe(snapshot);expect(()=>completeSnapshot({...snapshot,grants:undefined})).toThrow();});
  it('previews edits and submits the identical complete graph with independent review',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>{
      const body=JSON.parse(String(options?.body));
      return new Response(JSON.stringify(body.dryRun?{applied:false,revision:4,changes:[{kind:'USER',id:'user',operation:'UPDATE'}]}:{id:'config-'+ 'a'.repeat(40),state:'DRAFT'}),{status:body.dryRun?200:202});
    });
    render(<ConfigurationTransfer client={new ControlClient('token',vi.fn(),transport)}/>);
    const file=new File(['ignored'],'configuration.json',{type:'application/json'});Object.defineProperty(file,'text',{value:async()=>JSON.stringify(snapshot)});
    fireEvent.change(screen.getByLabelText('Configuration JSON files'),{target:{files:[file]}});
    fireEvent.click(screen.getByRole('button',{name:'Preview import'}));await screen.findByText('Proposed changes: 1');
    const submit=screen.getByRole('button',{name:'Create import for independent review'});expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed all/}));fireEvent.click(submit);await screen.findByRole('link',{name:'Review configuration draft'});
    expect(transport.mock.calls.map(call=>JSON.parse(String(call[1]?.body)))).toEqual([{snapshot,mode:'REPLACE',dryRun:true},{snapshot,mode:'REPLACE',dryRun:false}]);
    expect(new Headers(transport.mock.calls[1][1]?.headers).get('If-Match')).toBe('"4"');
  });
});
