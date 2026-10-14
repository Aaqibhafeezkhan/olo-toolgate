// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import {App} from '../src/App';
import {parseLoginHints} from '../src/LoginHints';
import {NO_REVIEWS_BANNER} from '../src/ReviewPolicyBanner';
import {REVIEW_POLICY_CHANGE} from '../src/ServerSettings';
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
const token=(user='alice')=>`header.${btoa(JSON.stringify({iss:'test',tenant_id:'tenant',sub:user,user_id:user}))}.signature`;
async function connect(){fireEvent.change(screen.getByLabelText('Access token'),{target:{value:token()}});fireEvent.click(screen.getByRole('button',{name:'Connect to workspace'}));await screen.findByRole('navigation');}
function navigate(hash:string){window.location.hash=hash;fireEvent(window,new HashChangeEvent('hashchange'));}
function quickstartMode(){const meta=document.createElement('meta');meta.name='toolgate-mode';meta.content='quickstart';document.head.append(meta);}
afterEach(()=>{cleanup();document.querySelector('meta[name=toolgate-mode]')?.remove();vi.unstubAllGlobals();localStorage.clear();window.location.hash='';});

describe('Login page',()=>{
  it('lists showOnLogin accounts with copy buttons and the next demo reset, and signs in without a new-password step',async()=>{
    quickstartMode();
    const refreshAt='2030-01-02T03:00:00Z';const writeText=vi.fn(async()=>{});vi.stubGlobal('navigator',{...navigator,clipboard:{writeText}});
    const transport=vi.fn(async(input:string,_init?:RequestInit)=>input.endsWith('/login-hints')?json({accounts:[{username:'public',password:'shown-demo-password'}],refreshAt})
      :input.endsWith('/session')?json({},401):input.endsWith('/status')?json({passwordRequired:true,tenant:'quickstart'}):input.endsWith('/login')?json({accessToken:token('public')}):input.includes('/api/public/')?json({},503):json({items:[]}));
    vi.stubGlobal('fetch',transport);render(<App/>);
    const hints=await screen.findByRole('region',{name:'Demo sign-in'});
    await screen.findByText('Quickstart · Single node · Non-HA · Tenant: quickstart.');
    expect(within(hints).getByText('public')).toBeTruthy();expect(within(hints).getByText('shown-demo-password')).toBeTruthy();
    expect(within(hints).getByText(`This demo resets at ${new Date(refreshAt).toLocaleString()}.`)).toBeTruthy();
    fireEvent.click(screen.getByRole('button',{name:'Copy password for public'}));await screen.findByText('Password copied.');expect(writeText).toHaveBeenCalledWith('shown-demo-password');
    fireEvent.click(screen.getByRole('button',{name:'Copy username public'}));expect(writeText).toHaveBeenLastCalledWith('public');
    expect(screen.queryByLabelText(/New password/)).toBeNull();expect(document.body.textContent).not.toContain('First login requires');
    expect(transport.mock.calls.find(([url])=>url.endsWith('/login-hints'))?.[1]?.credentials).toBe('omit');
    fireEvent.click(screen.getByRole('button',{name:'Use public'}));
    expect((screen.getByLabelText('Username') as HTMLInputElement).value).toBe('public');
    await waitFor(()=>expect((screen.getByRole('button',{name:'Connect to workspace'}) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'Connect to workspace'}));await screen.findByRole('navigation');
    expect(screen.getByText('Tenant: quickstart')).toBeTruthy();
    expect(JSON.parse(String(transport.mock.calls.find(([url])=>url.endsWith('/login'))![1]!.body))).toEqual({username:'public',password:'shown-demo-password'});
    expect(JSON.stringify(localStorage)).not.toContain('shown-demo-password');
  });
  it('shows nothing extra without hints and rejects malformed hint data',async()=>{
    expect(parseLoginHints({accounts:[{username:'<b>',password:'x'},{username:'ok',password:''},'bad'],refreshAt:'not a date'})).toEqual({accounts:[],refreshAt:null});
    expect(parseLoginHints(null)).toBeUndefined();
    quickstartMode();
    vi.stubGlobal('fetch',vi.fn(async(input:string)=>input.endsWith('/login-hints')?json({accounts:[],refreshAt:null}):input.endsWith('/session')?json({},401):json({items:[]})));
    render(<App/>);await screen.findByLabelText('Username');
    expect(screen.queryByRole('region',{name:'Demo sign-in'})).toBeNull();expect(document.body.textContent).not.toContain('This demo resets');
  });
});

describe('Configuration review banner',()=>{
  it('is shown on every page while reviews are set to 0 and disappears when they are raised',async()=>{
    let required=0;
    vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/review-policy')?json({requiredReviews:required}):url.endsWith('/admin-session')?json({role:'ADMINISTRATOR'}):json({items:[]})));
    render(<App/>);await connect();
    expect((await screen.findByText(NO_REVIEWS_BANNER)).getAttribute('role')).toBe('status');
    navigate('#teams');await screen.findByRole('heading',{name:'Teams'});expect(screen.getByText(NO_REVIEWS_BANNER)).toBeTruthy();
    required=2;fireEvent(window,new Event(REVIEW_POLICY_CHANGE));
    await waitFor(()=>expect(screen.queryByText(NO_REVIEWS_BANNER)).toBeNull());
  });
  it('is shown in the approval workspace too and never when the policy cannot be read',async()=>{
    vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/review-policy')?json({requiredReviews:0}):url.endsWith('/admin-session')?json({},403):json({items:[]})));
    const view=render(<App/>);await connect();await screen.findByText(NO_REVIEWS_BANNER);view.unmount();
    vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/review-policy')?json({},503):json({items:[]})));
    render(<App/>);await connect();await screen.findByRole('heading',{name:'Your organization, at a glance'});
    expect(screen.queryByText(NO_REVIEWS_BANNER)).toBeNull();
  });
});

describe('Administration',()=>{
  it('is limited to Super Admin: other administrators see only About and no settings request is made',async()=>{
    const transport=vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({role:'ADMINISTRATOR'}):json({items:[]}));
    vi.stubGlobal('fetch',transport);render(<App/>);await connect();
    const administration=[...document.querySelectorAll('nav details')].find(group=>group.querySelector('summary')?.textContent==='Administration')!;
    expect([...administration.querySelectorAll('a')].map(link=>link.getAttribute('href'))).toEqual(['#about']);
    navigate('#config-server');expect(await screen.findByText('Administration is available to Super Admin accounts only.')).toBeTruthy();
    navigate('#config-backup');expect(screen.getByText('Administration is available to Super Admin accounts only.')).toBeTruthy();
    expect(transport.mock.calls.some(([url])=>url.includes('/settings')||url.includes('/config/export'))).toBe(false);
  });
  it('shows Server, Device and Backup/Restore with the review threshold to Super Admin',async()=>{
    const settings={formatVersion:2,revision:1,gateway:{gatewayName:'ToolGate'},deviceApproval:{autoApprove:false,durationMs:2592000000},configuration:{requiredReviews:1},clientCheckIn:{checkInMs:2000,jitterPct:20,offlineAfterMs:120000,source:'DEFAULT'}};
    vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/admin-session')?json({role:'SUPER_ADMIN'}):url==='/api/control/v1/settings'?json(settings):json({items:[]})));
    render(<App/>);await connect();
    const administration=[...document.querySelectorAll('nav details')].find(group=>group.querySelector('summary')?.textContent==='Administration')!;
    expect([...administration.querySelectorAll('a')].map(link=>link.getAttribute('href'))).toEqual(['#config-server','#config-device','#config-backup','#about']);
    navigate('#config-server');expect(((await screen.findByLabelText('Configuration reviews')) as HTMLSelectElement).value).toBe('1');
    navigate('#config-backup');await screen.findByRole('heading',{name:'Backup/Restore'});
    expect(screen.queryByText('Server settings file')).toBeNull();expect(screen.getByRole('button',{name:'Export complete configuration'})).toBeTruthy();
  });
});

describe('Workload reauthorization',()=>{
  const binding={id:'visitor-001-binding',name:'Visitor 001 agent',enabled:false,revision:2,agentId:'visitor-001-agent',mode:'DELEGATED',needsReauthorization:true};
  it('badges bindings that need reauthorization and lets Super Admin issue the new token exactly once',async()=>{
    quickstartMode();
    const transport=vi.fn(async(url:string,_init?:RequestInit)=>url.endsWith('/session')?json({accessToken:token('admin')}):url.endsWith('/admin-session')?json({role:'SUPER_ADMIN'})
      :url.endsWith('/reauthorize')?json({token:'tg_new_agent_token_once'}):url.startsWith('/api/control/v1/workload-bindings?')?json({items:[binding,{...binding,id:'ready',name:'Ready agent',enabled:true,needsReauthorization:false}]})
      :url.includes('/api/public/')?json({},503):json({items:[]}));
    vi.stubGlobal('fetch',transport);window.location.hash='#workloadBindings';render(<App/>);
    await screen.findByText('Visitor 001 agent');
    expect(screen.getAllByText('Needs reauthorization')).toHaveLength(1);
    expect(screen.queryByRole('button',{name:'Reauthorize ready'})).toBeNull();
    fireEvent.click(screen.getByRole('button',{name:'Reauthorize visitor-001-binding'}));
    fireEvent.click(await screen.findByRole('button',{name:'Generate new token'}));
    expect(((await screen.findByLabelText('Agent token')) as HTMLInputElement).value).toBe('tg_new_agent_token_once');
    const call=transport.mock.calls.find(([url])=>url.endsWith('/reauthorize'))!;
    expect(call[0]).toBe('/api/quickstart/v1/workloads/visitor-001-binding/reauthorize');expect(call[1]?.method).toBe('POST');
    expect(new Headers(call[1]?.headers).get('Idempotency-Key')).toBeTruthy();
    fireEvent.click(screen.getByRole('button',{name:'Done'}));
    await waitFor(()=>expect(screen.queryByLabelText('Agent token')).toBeNull());
    expect(document.body.textContent).not.toContain('tg_new_agent_token_once');expect(JSON.stringify(localStorage)).not.toContain('tg_new_agent_token_once');
    expect(transport.mock.calls.filter(([url])=>url.endsWith('/reauthorize'))).toHaveLength(1);
  });
  it('shows the badge without a Reauthorize button to other administrators',async()=>{
    quickstartMode();
    vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/session')?json({accessToken:token('operator')}):url.endsWith('/admin-session')?json({role:'ADMINISTRATOR'})
      :url.startsWith('/api/control/v1/workload-bindings?')?json({items:[binding]}):url.includes('/api/public/')?json({},503):json({items:[]})));
    window.location.hash='#workloadBindings';render(<App/>);
    await screen.findByText('Needs reauthorization');expect(screen.queryByRole('button',{name:/^Reauthorize/})).toBeNull();
  });
});
