// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import type {EnterpriseConfigurationChange} from '@olo-labs/toolgate-contracts';
import {ConfigurationRequests} from '../src/ConfigurationRequests';
import {ControlClient,ConfigurationPending} from '../src/api';
import {Failure} from '../src/Failure';
afterEach(()=>{cleanup();window.location.hash='';});
const change=():EnterpriseConfigurationChange=>({id:'config-'+ 'a'.repeat(40),requesterUserId:'maker',command:{operation:'MEMBERSHIPS',kind:'USER',entityId:'employee',expectedRevision:7,document:'{"groupIds":["finance"]}'},requestDigest:'a'.repeat(64),directoryRevision:7,authorizationEpoch:7,state:'DRAFT',revision:1,createdAtUnixMs:Date.now(),expiresAtUnixMs:Date.now()+60000,requiredReviews:1,impact:[{kind:'TEAM',entityId:'finance',operation:'UPDATE',beforeDigest:'b'.repeat(64),afterDigest:'c'.repeat(64)}],affectedGroups:['TEAM:finance'],affectedIndividuals:['USER:<img src=x onerror=alert(1)>'],reviews:[]});
function setup(post?:()=>Promise<Response>){const fetcher=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>options?.method==='POST'&&post?post():new Response(JSON.stringify(String(url).split('?')[0].endsWith('configuration-changes')?{items:[change()]}:change())));render(<ConfigurationRequests client={new ControlClient('secret',vi.fn(),fetcher)}/>);return fetcher;}
async function open(){fireEvent.click(await screen.findByRole('button',{name:change().id}));await screen.findByText('Review configuration impact');}
it('shows exact group impact safely and requires explicit confirmation before submit',async()=>{setup();await open();expect(screen.getByText('USER:<img src=x onerror=alert(1)>')).toBeTruthy();expect(document.querySelector('img')).toBeNull();expect(screen.getByRole('button',{name:'Submit draft for independent review'}).hasAttribute('disabled')).toBe(true);expect(screen.queryByRole('button',{name:'Apply reviewed change'})).toBeNull();});
it('binds submission to the exact change revision and preserves retry keys after transport failure',async()=>{const fetcher=setup(async()=>new Response(JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'}),{status:503}));await open();fireEvent.click(screen.getByLabelText('I reviewed the affected groups, inherited access, complete configuration and digests.'));fireEvent.click(screen.getByRole('button',{name:'Submit draft for independent review'}));await screen.findByRole('alert');fireEvent.click(screen.getByRole('button',{name:'Submit draft for independent review'}));await waitFor(()=>expect(fetcher.mock.calls.filter(c=>c[1]?.method==='POST')).toHaveLength(2));const posts=fetcher.mock.calls.filter(c=>c[1]?.method==='POST');expect(JSON.parse(posts[0][1]?.body as string)).toEqual({expectedRevision:1,action:'SUBMIT'});expect(new Headers(posts[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(posts[1][1]?.headers).get('Idempotency-Key'));expect(document.body.textContent).not.toContain('secret');});
it('renders a created draft as an actionable review link',()=>{render(<Failure error={new ConfigurationPending(change().id)}/>);expect(screen.getByRole('link',{name:'Review configuration draft'}).getAttribute('href')).toBe('#config-reviews?id='+change().id);expect(screen.queryByRole('alert')).toBeNull();});
it('opens a different review from its link while the review screen remains mounted',async()=>{
  const first={...change(),state:'APPLIED' as const};const second={...change(),id:'config-'+'b'.repeat(40),state:'PENDING' as const,requestDigest:'d'.repeat(64)};
  const fetcher=vi.fn<typeof fetch>().mockImplementation(async url=>new Response(JSON.stringify(String(url).split('?')[0].endsWith('configuration-changes')?{items:[first,second]}:String(url).endsWith(second.id)?second:first)));
  window.location.hash='#config-reviews?id='+first.id;
  render(<ConfigurationRequests client={new ControlClient('secret',vi.fn(),fetcher)}/>);
  await screen.findByText('APPLIED',{selector:'strong'});
  window.location.hash='#config-reviews?id='+second.id;fireEvent(window,new HashChangeEvent('hashchange'));
  await screen.findByRole('button',{name:'Approve exact configuration'});
  expect(screen.getByText(second.requestDigest)).toBeTruthy();
  expect((screen.getByLabelText('I reviewed the affected groups, inherited access, complete configuration and digests.') as HTMLInputElement).checked).toBe(false);
  expect(screen.queryByText('APPLIED',{selector:'strong'})).toBeNull();
});

it('refreshes the linked review independently when the queue is unavailable',async()=>{
  let current={...change(),state:'PENDING' as EnterpriseConfigurationChange['state']};
  window.location.hash='#config-reviews?id='+current.id;
  const fetcher=vi.fn<typeof fetch>().mockImplementation(async url=>String(url).split('?')[0].endsWith('configuration-changes')
    ?new Response(JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'}),{status:503})
    :new Response(JSON.stringify(current)));
  render(<ConfigurationRequests client={new ControlClient('secret',vi.fn(),fetcher)}/>);
  await screen.findByRole('button',{name:'Approve exact configuration'});
  expect(screen.getByRole('alert')).toBeTruthy();
  fireEvent.click(screen.getByLabelText('I reviewed the affected groups, inherited access, complete configuration and digests.'));
  current={...current,state:'APPROVED',revision:3};
  fireEvent.click(screen.getByRole('button',{name:'Refresh changes'}));
  await screen.findByRole('button',{name:'Apply reviewed change'});
  expect((screen.getByLabelText('I reviewed the affected groups, inherited access, complete configuration and digests.') as HTMLInputElement).checked).toBe(false);
});

it('refreshes the exact review opened from the queue',async()=>{
  let current={...change(),state:'PENDING' as EnterpriseConfigurationChange['state']};
  const fetcher=vi.fn<typeof fetch>().mockImplementation(async url=>new Response(JSON.stringify(String(url).split('?')[0].endsWith('configuration-changes')?{items:[current]}:current)));
  render(<ConfigurationRequests client={new ControlClient('secret',vi.fn(),fetcher)}/>);
  await open();
  current={...current,state:'APPROVED',revision:3};
  fireEvent.click(screen.getByRole('button',{name:'Refresh changes'}));
  await screen.findByRole('button',{name:'Apply reviewed change'});
  expect(window.location.hash).toBe('#config-reviews?id='+current.id);
});

it('removes stale review actions when refreshing the detail fails',async()=>{
  let fail=false;
  window.location.hash='#config-reviews?id='+change().id;
  const current={...change(),state:'APPROVED'};
  const fetcher=vi.fn<typeof fetch>().mockImplementation(async url=>String(url).split('?')[0].endsWith('configuration-changes')
    ?new Response(JSON.stringify({items:[current]}))
    :fail?new Response(JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'}),{status:503}):new Response(JSON.stringify(current)));
  render(<ConfigurationRequests client={new ControlClient('secret',vi.fn(),fetcher)}/>);
  await screen.findByRole('button',{name:'Apply reviewed change'});
  fail=true;fireEvent.click(screen.getByRole('button',{name:'Refresh changes'}));
  await screen.findByRole('alert');
  expect(screen.queryByRole('button',{name:'Apply reviewed change'})).toBeNull();
});
