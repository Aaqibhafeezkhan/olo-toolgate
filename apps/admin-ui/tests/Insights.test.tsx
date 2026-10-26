// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import type {InsightsInvocation,InsightsSeries,InsightsStatus} from '@olo-labs/toolgate-contracts/v2';
import {InsightsApp} from '../src/insights/InsightsApp';
import {client,InsightsError} from '../src/insights/api';
import {buckets,granularity,overview,percentile,ranking} from '../src/insights/model';
import {niceMax,ticks} from '../src/insights/charts';

afterEach(cleanup);
const NOW=Date.UTC(2026,9,26,12,0,0),BOUNDS=[1,2,5,10,20,50,100,200,500,1000,2000,5000,10000,30000,60000,120000,300000,600000,1800000,3600000];
const hist=(at:number,n:number)=>{const h=new Array(21).fill(0);h[at]=n;return h;};
const status=(mode:InsightsStatus['mode'],extra:Partial<InsightsStatus>={}):InsightsStatus=>({mode,generatedAtUnixMs:NOW,tenants:2,unprojectedFacts:0,summariesSuspendedTenants:0,failedTenants:0,sourceGaps:[],...extra});
const series=(from:number,to:number,g:InsightsSeries['granularity']):InsightsSeries=>({granularity:g,fromUnixMs:from,toUnixMs:to,groupBy:'',histogramBoundsMs:BOUNDS,
  points:[{bucketStartUnixMs:from,key:'',outcome:'SUCCEEDED',count:18,durationCount:18,durationSumMs:2700,histogram:hist(7,18)},
    {bucketStartUnixMs:from,key:'',outcome:'FAILED',count:2,durationCount:2,durationSumMs:8000,histogram:hist(11,2)},
    {bucketStartUnixMs:from,key:'',outcome:'OUTCOME_UNKNOWN',count:1,durationCount:0,durationSumMs:0,histogram:hist(0,0)}],
  admissions:[{bucketStartUnixMs:from,category:'POLICY_DENIED',count:4},{bucketStartUnixMs:from,category:'APPROVAL_REQUIRED',count:1}]});
const invocation:InsightsInvocation={tenantId:'tenant-a',invocationId:'inv-1',toolId:'acme.orders/orders.lookup',action:'invoke',clientId:'bind-1',
  toolCallId:'toolu_01A9q',outcome:'SUCCEEDED',attempts:1,acceptedAtUnixMs:NOW-60_000,queueMs:20,executionMs:150,endToEndMs:190,partialCoverage:false};

/** A fake operator API answering by path, recording what was asked. */
function api(mode:InsightsStatus['mode'],overrides:Record<string,(url:URL)=>Response>={}){
  const asked:URL[]=[];
  const fetcher=vi.fn<typeof fetch>(async input=>{
    const url=new URL(String(input),'https://operator.example');asked.push(url);
    for(const [prefix,answer] of Object.entries(overrides))if(url.pathname.startsWith(prefix))return answer(url);
    const json=(v:unknown)=>new Response(JSON.stringify(v),{status:200});
    if(url.pathname==='/insights/v1/status')return json(status(mode));
    if(url.pathname==='/insights/v1/series'){
      const s=series(Number(url.searchParams.get('from')),Number(url.searchParams.get('to')),url.searchParams.get('granularity') as InsightsSeries['granularity']);
      return json(url.searchParams.get('groupBy')==='TOOL'?{...s,points:s.points.map(p=>({...p,key:'acme.orders/orders.lookup'}))}:s);
    }
    if(url.pathname==='/insights/v1/invocations')return json({items:[invocation]});
    if(url.pathname.startsWith('/insights/v1/invocations/'))return json({invocation,attempts:[{attemptNumber:1,executionId:'ex-7576f712baed62964bc828cdba88bdbe',executorId:'host-1',outcome:'SUCCEEDED',durationMs:150}],
      transitions:[{factSeq:1,toState:'QUEUED',occurredAtUnixMs:NOW-60_000},{factSeq:2,fromState:'QUEUED',toState:'EXECUTING',occurredAtUnixMs:NOW-59_980}],transitionsRetained:true});
    return new Response('{}',{status:404});
  });
  return {api:client(fetcher),asked,fetcher};
}

describe('model',()=>{
  it('chooses buckets the server accepts and keeps empty intervals',()=>{
    expect(granularity(NOW-3_600_000,NOW)).toBe('MINUTE');expect(granularity(NOW-86_400_000,NOW)).toBe('HOUR');expect(granularity(NOW-30*86_400_000,NOW)).toBe('DAY');
    const b=buckets(series(NOW-3_600_000,NOW,'MINUTE'));
    expect(b).toHaveLength(60);expect(b[0]!.total).toBe(21);expect(b[1]!.total).toBe(0);
  });
  it('rates success over finished calls only and needs enough samples for tail percentiles',()=>{
    const o=overview(series(NOW-3_600_000,NOW,'MINUTE'));
    expect(o.successRate).toBeCloseTo(0.9);expect(o.finished).toBe(20);expect(o.unknown).toBe(1);
    expect(o.denials).toBe(4);expect(o.approvals).toBe(1);
    expect(o.p50).toBe(200);expect(o.p95).toBe(5000);expect(o.p99).toBeNull();
    expect(percentile(hist(3,19),BOUNDS,0.95)).toBeNull();
  });
  it('ranks keys by calls and rounds axes cleanly',()=>{
    const base=series(NOW-3_600_000,NOW,'MINUTE'),s={...base,points:base.points.map((p,i)=>({...p,key:i===0?'a':'b'}))};
    expect(ranking(s).map(r=>[r.key,r.calls,r.failed])).toEqual([['a',18,0],['b',3,2]]);
    expect([niceMax(0),niceMax(7),niceMax(21),niceMax(130)]).toEqual([1,10,50,200]);
    expect(ticks(5000)).toEqual([0,1000,2000,3000,4000,5000]);expect(ticks(200)).toEqual([0,50,100,150,200]);
  });
});

describe('page',()=>{
  it('renders with its default client without re-running its effects',async()=>{
    const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(JSON.stringify(status('NONE'))));
    try{
      render(<InsightsApp/>);
      await screen.findByText('Insights is off for this installation.');
      await new Promise(r=>setTimeout(r,50));
      expect(fetcher).toHaveBeenCalledTimes(1);
    }finally{fetcher.mockRestore();}
  });
  it('explains how to enable Insights and asks for no data while it is off',async()=>{
    const {api:a,asked}=api('NONE');
    render(<InsightsApp api={a} now={()=>NOW}/>);
    await screen.findByText('Insights is off for this installation.');
    expect(asked.map(u=>u.pathname)).toEqual(['/insights/v1/status']);
  });
  it('shows a retryable error rather than an empty page when status fails',async()=>{
    let fail=true;
    const {api:a}=api('BASIC',{'/insights/v1/status':()=>fail?new Response('{"code":"DEPENDENCY_UNAVAILABLE"}',{status:503}):new Response(JSON.stringify(status('BASIC')))});
    render(<InsightsApp api={a} now={()=>NOW}/>);
    expect((await screen.findByRole('alert')).textContent).toContain('Insights is unavailable right now.');
    fail=false;fireEvent.click(screen.getByRole('button',{name:'Try again'}));
    await screen.findByRole('region',{name:'Overview'});
  });
  it('shows tiles, charts with table views, tool ranking and gap warnings',async()=>{
    const {api:a,asked}=api('BASIC',{'/insights/v1/status':()=>new Response(JSON.stringify(status('BASIC',{sourceGaps:[{tenantId:'tenant-a',gatewayId:'gw-1',epoch:'e1',lastSequence:4,missingSequences:1,lastSeenUnixMs:NOW,droppedRequests:0}]})))});
    render(<InsightsApp api={a} now={()=>NOW}/>);
    const tiles=await screen.findByRole('region',{name:'Overview'});
    expect(within(tiles).getByText('90.0%')).toBeTruthy();
    expect(within(tiles).getByText('18 of 20 succeeded or failed')).toBeTruthy();
    expect(screen.getByText('1 admission-counter gap(s)')).toBeTruthy();
    const calls=screen.getByRole('figure',{name:'Calls by outcome'});
    fireEvent.click(within(calls).getByRole('button',{name:'Show table'}));
    expect(within(calls).getByRole('table')).toBeTruthy();
    expect(await screen.findByRole('button',{name:'acme.orders/orders.lookup'})).toBeTruthy();
    const range=asked.find(u=>u.pathname==='/insights/v1/series')!;
    expect(range.searchParams.get('granularity')).toBe('HOUR');expect(Number(range.searchParams.get('to'))).toBe(NOW);
  });
  it('finds a call by ID across tenants and opens its detail window with copyable identifiers',async()=>{
    const {api:a,asked}=api('BASIC');
    render(<InsightsApp api={a} now={()=>NOW}/>);
    await screen.findByText('Set a tenant filter to browse, or find a call by any ID across all tenants.');
    fireEvent.change(screen.getByLabelText('Find by ID'),{target:{value:'toolu_01A9q'}});
    fireEvent.click(screen.getByRole('button',{name:'Search'}));
    const open=await screen.findByRole('button',{name:/11:59/});
    const search=asked.find(u=>u.pathname==='/insights/v1/invocations')!;
    expect(search.searchParams.get('search')).toBe('toolu_01A9q');expect(search.searchParams.has('tenant')).toBe(false);
    fireEvent.click(open);
    const dialog=await screen.findByRole('dialog');
    await within(dialog).findByText('ex-7576f712baed62964bc828cdba88bdbe');
    expect(within(dialog).getByRole('button',{name:'Copy toolu_01A9q'})).toBeTruthy();
    expect(within(dialog).getByText('Tool-call, session and run IDs are declared by the caller and are not verified.')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button',{name:'Close invocation details'}));
    await waitFor(()=>expect(screen.queryByRole('dialog')).toBeNull());
  });
  it('maps failures to safe messages',async()=>{
    const fetcher=vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline'));
    await expect(client(fetcher).status()).rejects.toEqual(new InsightsError(0,'NETWORK'));
    const missing=vi.fn<typeof fetch>().mockResolvedValue(new Response('{"code":"NOT_FOUND"}',{status:404}));
    await expect(client(missing).detail('t','i')).rejects.toMatchObject({status:404,message:'There is no Insights record for that invocation.'});
  });
});
