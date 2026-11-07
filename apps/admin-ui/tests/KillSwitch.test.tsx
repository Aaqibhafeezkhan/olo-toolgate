// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import type {KillOverview,KillProposal} from '@olo-labs/toolgate-contracts/v2';
import {ControlClient} from '../src/api';
import {KillSwitch} from '../src/KillSwitch';
import {FeatureAccess} from '../src/Permissions';
afterEach(cleanup);
const proposal=():KillProposal=>({id:'kill-request-1',requesterUserId:'maker',kind:'KILL',state:'PENDING',revision:3,
  authorizationEpoch:4,createdAtUnixMs:Date.now()-1000,expiresAtUnixMs:Date.now()+60000,
  scope:{kind:'POOL',value:'pool-a'},mode:'TERMINATE',reason:'Incident <img src=x onerror=alert(1)>',supersedes:[],reviews:[]});
const overview=():KillOverview=>({state:{tenantId:'10000000-0000-4000-8000-000000000001',authorizationEpoch:4,signature:'e30.e30.c2ln',
  kills:[{eventId:'control:1',issuer:'control',tenantId:'10000000-0000-4000-8000-000000000001',authorizationEpoch:3,
    scope:{kind:'POOL',value:'pool-a'},mode:'TERMINATE',reason:'Incident <img src=x onerror=alert(1)>',issuedAtUnixMs:Date.now()-2000,signature:'e30.e30.c2ln'}],
  recoveries:[]},components:[{kind:'TOOL_HOST',componentId:'host-a',authorizationEpoch:2,deliveryPath:'POLL',acknowledgedAtUnixMs:Date.now()-1000},
    {kind:'ENDPOINT',componentId:'native-a'}],retainedEventCount:3,archivedEventCount:1,observedAtUnixMs:Date.now()});
function setup(options:{user?:string;request?:KillProposal;permissions?:string[];post?:(body:unknown)=>Promise<Response>}={}){
  let current=options.request??proposal();
  const fetcher=vi.fn<typeof fetch>().mockImplementation(async(url,init)=>{
    if(init?.method==='POST'&&options.post)return options.post(JSON.parse(init.body as string));
    const path=String(url).split('?')[0];
    return new Response(JSON.stringify(path.endsWith('/status')?overview():path.endsWith('/proposals')?[current]:current));
  });
  const client=new ControlClient('private-access-token',vi.fn(),fetcher);
  vi.spyOn(client,'can').mockImplementation(permission=>!options.permissions||options.permissions.includes(permission));
  render(<FeatureAccess client={client} route="kill-events"><KillSwitch client={client} userId={options.user??'checker-two'}/></FeatureAccess>);
  return {fetcher,client,setCurrent:(request:KillProposal)=>{current=request;}};
}
async function open(){fireEvent.click(await screen.findByRole('button',{name:'kill-request-1'}));await screen.findByRole('region',{name:'Review exact kill request'});}
it('shows current and missing delivery safely while a read-only user can refresh',async()=>{
  setup({permissions:['kill.read']});
  await screen.findByText('2 · Pending update');expect(screen.getByText('Awaiting acknowledgement')).toBeTruthy();
  expect(screen.getByText('Incident <img src=x onerror=alert(1)>')).toBeTruthy();expect(document.querySelector('img')).toBeNull();
  expect(screen.queryByRole('button',{name:'Create kill proposal'})).toBeNull();
  expect(screen.getByRole('button',{name:'Refresh kill status'}).hasAttribute('disabled')).toBe(false);
  await open();expect(screen.queryByRole('button',{name:'Approve kill request'})).toBeNull();
  expect(document.body.textContent).not.toContain('private-access-token');
});
it('never offers a maker or a repeated reviewer an approval shortcut',async()=>{
  const view=setup({user:'maker'});await open();expect(screen.queryByRole('button',{name:'Approve kill request'})).toBeNull();
  cleanup();
  setup({request:{...proposal(),reviews:[{reviewerUserId:'checker-two',approved:true,reviewedAtUnixMs:Date.now()}]}});
  await open();expect(screen.queryByRole('button',{name:'Approve kill request'})).toBeNull();expect(view.fetcher.mock.calls.filter(call=>call[1]?.method==='POST')).toHaveLength(0);
});
it('requires exact confirmation, sends the current revision and retains the retry key after uncertainty',async()=>{
  const {fetcher}=setup({post:async()=>new Response(JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'}),{status:503})});await open();
  const button=screen.getByRole('button',{name:'Approve kill request'});expect(button.hasAttribute('disabled')).toBe(true);
  fireEvent.click(screen.getByLabelText('I reviewed this exact scope, mode and reason.'));fireEvent.click(button);
  await screen.findByRole('alert');fireEvent.click(screen.getByRole('button',{name:'Approve kill request'}));
  await waitFor(()=>expect(fetcher.mock.calls.filter(call=>call[1]?.method==='POST')).toHaveLength(2));
  const posts=fetcher.mock.calls.filter(call=>call[1]?.method==='POST');
  expect(JSON.parse(posts[0][1]?.body as string)).toEqual({expectedRevision:3,approve:true});
  expect(new Headers(posts[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(posts[1][1]?.headers).get('Idempotency-Key'));
});
it('proposes exact selected recoveries and explains that cancelled work does not restart',async()=>{
  const {fetcher}=setup({post:async()=>new Response(JSON.stringify({...proposal(),kind:'RECOVERY',supersedes:['control:1']}),{status:202})});
  fireEvent.click(await screen.findByLabelText('Recover control:1'));fireEvent.change(screen.getByLabelText('Recovery reason'),{target:{value:'Incident resolved'}});
  fireEvent.click(screen.getByRole('button',{name:'Propose recovery'}));
  await waitFor(()=>expect(fetcher.mock.calls.some(call=>String(call[0]).endsWith('/kill-events/recoveries'))).toBe(true));
  const post=fetcher.mock.calls.find(call=>call[1]?.method==='POST')!;
  expect(JSON.parse(post[1]?.body as string)).toEqual({supersedes:['control:1'],reason:'Incident resolved'});
  expect(screen.getByText('Recovery permits new work. Cancelled attempts remain cancelled.')).toBeTruthy();
});
it('recovery review also requires current recovery authority',async()=>{
  setup({request:{...proposal(),kind:'RECOVERY',supersedes:['control:1']},permissions:['kill.read','kill.approve']});
  await open();expect(screen.queryByRole('button',{name:'Approve kill request'})).toBeNull();
});
it('a stale revision clears the review and requires a fresh confirmation',async()=>{
  const {setCurrent}=setup({post:async()=>new Response(JSON.stringify({code:'CONFLICT'}),{status:409})});
  await open();fireEvent.click(screen.getByLabelText('I reviewed this exact scope, mode and reason.'));
  fireEvent.click(screen.getByRole('button',{name:'Approve kill request'}));await screen.findByRole('alert');
  expect(screen.queryByLabelText('I reviewed this exact scope, mode and reason.')).toBeNull();
  setCurrent({...proposal(),revision:4,reviews:[{reviewerUserId:'checker-one',approved:true,reviewedAtUnixMs:Date.now()}]});
  fireEvent.click(screen.getByRole('button',{name:'Refresh kill status'}));await screen.findByLabelText('I reviewed this exact scope, mode and reason.');
  expect(screen.getByRole('button',{name:'Approve kill request'}).hasAttribute('disabled')).toBe(true);
});

it('creates the exact chosen scope and mode as a proposal rather than applying a kill',async()=>{
  const {fetcher}=setup({post:async()=>new Response(JSON.stringify(proposal()),{status:202})});
  await screen.findByRole('button',{name:'Create kill proposal'});
  fireEvent.change(screen.getByLabelText('Kill scope'),{target:{value:'POOL'}});
  fireEvent.change(screen.getByLabelText('Scope value'),{target:{value:'pool-b'}});
  fireEvent.change(screen.getByLabelText('Kill mode'),{target:{value:'TERMINATE'}});
  fireEvent.change(screen.getByLabelText('Kill reason'),{target:{value:'Incident in pool B'}});
  fireEvent.click(screen.getByRole('button',{name:'Create kill proposal'}));
  await waitFor(()=>expect(fetcher.mock.calls.filter(call=>call[1]?.method==='POST')).toHaveLength(1));
  const post=fetcher.mock.calls.find(call=>call[1]?.method==='POST')!;
  expect(String(post[0])).toBe('/api/control/v1/kill-events');
  expect(JSON.parse(post[1]?.body as string)).toEqual({scope:{kind:'POOL',value:'pool-b'},mode:'TERMINATE',reason:'Incident in pool B'});
  expect(new Headers(post[1]?.headers).get('Idempotency-Key')).toBeTruthy();
});
