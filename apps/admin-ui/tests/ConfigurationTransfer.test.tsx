// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {describe,it,expect,vi,afterEach} from 'vitest';
import {render,screen,fireEvent,cleanup,waitFor,within} from '@testing-library/react';
import {ConfigurationTransfer,completeSnapshot} from '../src/ConfigurationTransfer';
import {ControlClient} from '../src/api';
import type {ControlSnapshot} from '@olo-labs/toolgate-contracts';
afterEach(cleanup);
const snapshot={formatVersion:2,tenantId:'test',revision:4,...Object.fromEntries(['users','teams','agents','tools','policies','devices','roles','deviceGroups','agentGroups','toolGroups','grants','delegations','agentDelegations','bindings','extractors','workloadBindings','identityBindings','deviceEvidence'].map(key=>[key,[]]))} as unknown as ControlSnapshot;
const exported=Object.fromEntries(Object.entries(snapshot).filter(([key])=>!['users','identityBindings','deviceEvidence'].includes(key)));
const withIdentities={...exported,settings:{gateway:{gatewayName:'Restored'},configuration:{requiredReviews:0}},requiredIdentities:[{id:'alice',teamIds:['Support','Operations']},{id:'bob',teamIds:['Support']},{id:'carol',teamIds:[]}]};
function choose(value:unknown){
  const file=new File(['ignored'],'configuration.json',{type:'application/json'});Object.defineProperty(file,'text',{value:async()=>JSON.stringify(value)});
  fireEvent.change(screen.getByLabelText('Configuration JSON files'),{target:{files:[file]}});
}
const bodies=(transport:ReturnType<typeof vi.fn<typeof fetch>>)=>transport.mock.calls.filter(call=>call[1]?.method==='POST').map(call=>JSON.parse(String(call[1]?.body)));
describe('Complete configuration transfer',()=>{
  it('rejects truncated snapshots instead of treating missing collections as removals',()=>{
    expect(completeSnapshot(snapshot)).toBe(snapshot);expect(()=>completeSnapshot({...snapshot,grants:undefined})).toThrow();
    expect(completeSnapshot(exported)).toBe(exported);
    expect(()=>completeSnapshot({...exported,users:{}})).toThrow();
    expect(()=>completeSnapshot({...exported,requiredIdentities:[{id:'alice'}]})).toThrow();
  });
  it('previews edits and submits the identical complete graph with the chosen mode',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>{
      const body=JSON.parse(String(options?.body));
      return new Response(JSON.stringify(body.dryRun?{applied:false,revision:4,changes:[{kind:'USER',id:'user',operation:'UPDATE'}]}:{id:'config-'+ 'a'.repeat(40),state:'DRAFT'}),{status:body.dryRun?200:202});
    });
    render(<ConfigurationTransfer client={new ControlClient('token',vi.fn(),transport)}/>);
    choose(snapshot);
    expect((screen.getByRole('radio',{name:/^Merge/}) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole('radio',{name:/^Replace/}));
    fireEvent.click(screen.getByRole('button',{name:'Preview import'}));await screen.findByText('Proposed changes: 1');
    const submit=screen.getByRole('button',{name:'Import configuration'});expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed all/}));fireEvent.click(submit);await screen.findByRole('link',{name:'Review configuration draft'});
    expect(bodies(transport)).toEqual([{snapshot,mode:'REPLACE',dryRun:true},{snapshot,mode:'REPLACE',dryRun:false}]);
    expect(new Headers(transport.mock.calls[1][1]?.headers).get('If-Match')).toBe('"4"');
  });
  it('resolves required identities as existing, mapped or placeholder users and adds them to the ticked teams',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
      if(String(url).startsWith('/api/control/v1/users?'))return new Response(JSON.stringify({items:[{id:'alice',name:'Alice',enabled:true,revision:1},{id:'dave',name:'Dave',enabled:true,revision:1}]}));
      const body=JSON.parse(String(options?.body));
      return new Response(JSON.stringify(body.dryRun?{changes:[{kind:'TEAM',id:'Support',operation:'UPDATE'}]}:{applied:true}),{status:200});
    });
    render(<ConfigurationTransfer client={new ControlClient('token',vi.fn(),transport)}/>);
    choose(withIdentities);
    expect(await screen.findByText(/This file includes server settings/)).toBeTruthy();
    const alice=await screen.findByLabelText('Resolution for alice');await waitFor(()=>expect((alice as HTMLSelectElement).value).toBe('EXISTING'));
    const bob=screen.getByLabelText('Resolution for bob');
    expect((within(bob).getByRole('option',{name:'Existing user'}) as HTMLOptionElement).disabled).toBe(true);
    expect(screen.getByText('2 identities still need a choice.')).toBeTruthy();
    fireEvent.change(bob,{target:{value:'MAP'}});fireEvent.change(screen.getByLabelText('Map bob to user'),{target:{value:'dave'}});
    fireEvent.change(screen.getByLabelText('Resolution for carol'),{target:{value:'PLACEHOLDER'}});
    expect(screen.queryByText(/still needs? a choice/)).toBeNull();
    const operations=screen.getAllByRole('checkbox',{name:'Add to Operations'});expect(operations).toHaveLength(1);fireEvent.click(operations[0]);
    const remove=screen.getByRole('checkbox',{name:'Also remove from teams not in the backup'}) as HTMLInputElement;expect(remove.checked).toBe(false);fireEvent.click(remove);
    fireEvent.click(screen.getByRole('button',{name:'Preview import'}));await screen.findByText('Proposed changes: 1');
    fireEvent.click(screen.getByRole('checkbox',{name:/I reviewed all/}));fireEvent.click(screen.getByRole('button',{name:'Import configuration'}));
    await screen.findByText('Configuration imported.');
    const identityResolution={alice:{action:'EXISTING',teamIds:['Support']},bob:{action:'MAP',targetUserId:'dave',teamIds:['Support']},carol:{action:'PLACEHOLDER',teamIds:[]}};
    expect(bodies(transport)).toEqual([{snapshot:withIdentities,mode:'MERGE',dryRun:true,identityResolution,removeUnlistedMemberships:true},{snapshot:withIdentities,mode:'MERGE',dryRun:false,identityResolution,removeUnlistedMemberships:true}]);
  });
  it('shows the identities Control reports as unresolved and keeps the import blocked',async()=>{
    const transport=vi.fn<typeof fetch>().mockImplementation(async url=>String(url).startsWith('/api/control/v1/users?')?new Response(JSON.stringify({items:[{id:'alice',name:'Alice',enabled:true,revision:1}]}))
      :new Response(JSON.stringify({error:'UNRESOLVED_IDENTITIES',ids:['bob','carol','<script>']}),{status:409}));
    render(<ConfigurationTransfer client={new ControlClient('token',vi.fn(),transport)}/>);
    choose(withIdentities);await waitFor(()=>expect((screen.getByLabelText('Resolution for alice') as HTMLSelectElement).value).toBe('EXISTING'));
    fireEvent.click(screen.getByRole('button',{name:'Preview import'}));
    const alert=await screen.findByRole('alert');
    expect(alert.textContent).toContain('every required identity must be resolved');expect(alert.textContent).toContain('Unresolved: bob, carol.');expect(alert.textContent).not.toContain('script');
    expect(screen.getAllByText('Unresolved')).toHaveLength(2);expect(screen.queryByText(/Proposed changes/)).toBeNull();
    expect(bodies(transport)[0].identityResolution).toEqual({alice:{action:'EXISTING',teamIds:['Support','Operations']}});
    expect(bodies(transport)[0].removeUnlistedMemberships).toBe(false);
    fireEvent.change(screen.getByLabelText('Resolution for bob'),{target:{value:'PLACEHOLDER'}});
    expect(screen.getAllByText('Unresolved')).toHaveLength(1);
  });
});
