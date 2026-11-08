// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ControlClient} from '../src/api';
import {TenantAnalytics} from '../src/TenantAnalytics';
import {FeatureAccess} from '../src/Permissions';
afterEach(()=>{cleanup();window.location.hash='';});
const series={granularity:'HOUR',fromUnixMs:0,toUnixMs:3600000,groupBy:'',histogramBoundsMs:[1,5],points:[],admissions:[]};
const evidence={grades:{TARGET_CONFIRMED:2,BROKER_OBSERVED:0,HUMAN_RECONCILED:1,CONTROL_PROVEN:0,EXECUTOR_REPORTED:3,UNKNOWN:4},
 coverage:{NO_AUTHORITY_CONSUMED:0,MEDIATED_ONLY:3,UNMEDIATED_POSSIBLE:7},fencedEffects:2,unfencedEffects:5,operations:10,truncated:false};
function fixture(mode='BASIC',permissions=['audit.read']){
 const transport=vi.fn<typeof fetch>(async input=>new Response(JSON.stringify(String(input).endsWith('/admin-session')?
  {role:'ADMINISTRATOR',permissions}:{tenantId:'tenant-a',mode,series,tools:series,evidence})));
 return {transport,client:new ControlClient('tenant-token',vi.fn(),transport)};
}
it('uses authenticated tenant analytics without exposing a tenant selector or operator API',async()=>{
 const {client,transport}=fixture();render(<TenantAnalytics client={client}/>);
 await screen.findByText('No recorded tool calls in this time range.');
 const [url,options]=transport.mock.calls[0];
 expect(String(url)).toMatch(/^\/api\/control\/v1\/analytics\?from=\d+&to=\d+$/);
 expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer tenant-token');
 expect(options?.cache).toBe('no-store');
 expect(screen.queryByLabelText('Tenant')).toBeNull();
 fireEvent.change(screen.getByLabelText('Time range'),{target:{value:'168'}});
 await waitFor(()=>expect(transport).toHaveBeenCalledTimes(2));
 expect(screen.getByRole('button',{name:'Refresh analytics'})).toBeTruthy();
});
it('explains disabled collection and hides metrics',async()=>{
 const {client}=fixture('NONE');render(<TenantAnalytics client={client}/>);
 await screen.findByText(/Analytics collection is disabled/);
 expect(screen.queryByText('Busiest tools')).toBeNull();
});
it('does not grant analytics from an administrator role label alone',async()=>{
 const {client,transport}=fixture('BASIC',[]);await client.adminSession();
 render(<FeatureAccess client={client} route="analytics"><TenantAnalytics client={client}/></FeatureAccess>);
 expect(screen.getByText('Access unavailable')).toBeTruthy();expect(transport).toHaveBeenCalledTimes(1);
});
it('shows a retryable failure without displaying stale analytics',async()=>{
 const {client,transport}=fixture();transport.mockResolvedValue(new Response('{}',{status:503}));
 render(<TenantAnalytics client={client}/>);await screen.findByRole('button',{name:/try again/i});
 expect(screen.queryByText('Busiest tools')).toBeNull();
});

it('links to a tool-filtered tenant view with recent calls, paging and all-tools navigation',async()=>{
 const tool='mock.insights-admin/lookup';
 const point={bucketStartUnixMs:0,key:tool,outcome:'SUCCEEDED',count:40,durationCount:40,durationSumMs:200,histogram:[0,40,0]};
 const transport=vi.fn<typeof fetch>(async input=>{
  const query=new URL(String(input),'http://localhost').searchParams;
  const selected=query.get('tool');
  return new Response(JSON.stringify({tenantId:'tenant-a',mode:'BASIC',evidence,series:{...series,points:selected?[{...point,key:''}]:[point]},tools:{...series,points:[point]},
   invocations:selected?{items:[{invocationId:query.has('after')?'older-call':'mock-call',acceptedAtUnixMs:1,outcome:'SUCCEEDED',executionMs:5,sessionId:'mock-session'}],next:query.has('after')?null:'1:mock-call'}:null}));
 });
 render(<TenantAnalytics client={new ControlClient('tenant-token',vi.fn(),transport)}/>);
 const link=await screen.findByRole('link',{name:tool});
 expect(link.getAttribute('href')).toBe('#analytics?tool=mock.insights-admin%2Flookup');
 fireEvent.click(link);
 await screen.findByRole('heading',{name:`Recent calls for ${tool}`});
 await screen.findByText('mock-call');
 expect(transport.mock.calls.some(([url])=>new URL(String(url),'http://localhost').searchParams.get('tool')===tool)).toBe(true);
 expect(screen.queryByText('Admission denials')).toBeNull();
 fireEvent.click(screen.getByRole('button',{name:'Next calls'}));
 await screen.findByText('older-call');
 expect(transport.mock.calls.some(([url])=>new URL(String(url),'http://localhost').searchParams.get('after')==='1:mock-call')).toBe(true);
 fireEvent.click(screen.getByRole('link',{name:'All tools'}));
 await waitFor(()=>expect(screen.queryByRole('heading',{name:`Recent calls for ${tool}`})).toBeNull());
});
it('shows Control evidence grades apart from Gateway call states, even with collection disabled',async()=>{
 const {client}=fixture('NONE');render(<TenantAnalytics client={client}/>);
 await screen.findByRole('heading',{name:'Outcome evidence'});
 expect(screen.getByText(/carry no evidence grade/)).toBeTruthy();
 const grade=screen.getByRole('table',{name:'Evidence grade'});
 expect(grade.textContent).toContain('Executor-reported3');expect(grade.textContent).toContain('Unknown4');
 expect(screen.getByRole('table',{name:'Effect coverage'}).textContent).toContain('Unmediated effects possible7');
 expect(screen.getByText(/2 fenced, 5 unfenced/)).toBeTruthy();
});
it('exports every page of current operation evidence and marks the export complete',async()=>{
 const outcome=(id:string)=>({operationId:id,state:'SUCCEEDED',revision:1,grade:'TARGET_CONFIRMED',coverage:'MEDIATED_ONLY',journalComplete:true,
  attemptCount:1,effectCount:1,fencedEffectCount:1,unfencedEffectCount:0,unresolvedEffectCount:0,unsettledAttemptCount:0,finalized:true,
  retryable:false,deduplicationAssumed:false,updatedAtUnixMs:1});
 const transport=vi.fn<typeof fetch>(async input=>{
  const url=new URL(String(input),'http://localhost');
  if(url.pathname.endsWith('/access/operations/evidence'))return new Response(JSON.stringify(url.searchParams.get('after')
   ?{items:[outcome('op-b')],exportedAtUnixMs:5}:{items:[outcome('op-a')],next:'op-a',exportedAtUnixMs:5}));
  return new Response(JSON.stringify({tenantId:'tenant-a',mode:'BASIC',series,tools:series,evidence}));
 });
 let saved:Blob|undefined;
 const create=vi.spyOn(URL,'createObjectURL').mockImplementation(blob=>{saved=blob as Blob;return 'blob:evidence';});
 const revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{});
 const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
 try{
  render(<TenantAnalytics client={new ControlClient('tenant-token',vi.fn(),transport)}/>);
  fireEvent.click(await screen.findByRole('button',{name:'Download evidence export'}));
  await screen.findByText('Exported 2 operations.');
  const body=JSON.parse(await saved!.text());
  expect(body.complete).toBe(true);expect(body.items.map((i:{operationId:string})=>i.operationId)).toEqual(['op-a','op-b']);
  expect(transport.mock.calls.some(([url])=>new URL(String(url),'http://localhost').searchParams.get('after')==='op-a')).toBe(true);
 }finally{create.mockRestore();revoke.mockRestore();click.mockRestore();}
});
