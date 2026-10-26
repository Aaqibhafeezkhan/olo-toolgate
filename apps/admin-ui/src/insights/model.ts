// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type {InsightsGranularity,InsightsInvocation,InsightsOutcome,InsightsSeries,InsightsSeriesPoint} from '@olo-labs/toolgate-contracts/v2';

/** The time ranges the page offers; custom ranges use the same bucket rule. */
export const RANGES=[{id:'1h',label:'Last hour',ms:3_600_000},{id:'24h',label:'Last 24 hours',ms:86_400_000},{id:'7d',label:'Last 7 days',ms:604_800_000},{id:'30d',label:'Last 30 days',ms:2_592_000_000}] as const;
export type RangeId=typeof RANGES[number]['id'];

const WIDTH:Record<InsightsGranularity,number>={MINUTE:60_000,HOUR:3_600_000,DAY:86_400_000};
/** The finest granularity that keeps the range within 2,000 buckets (the server's bound) and readable (≤ 360 bars). */
export function granularity(from:number,to:number):InsightsGranularity {
  for(const g of ['MINUTE','HOUR','DAY'] as const)if((to-from)/WIDTH[g]<=360)return g;
  return 'DAY';
}
export function bucketWidth(g:InsightsGranularity):number{return WIDTH[g];}

/** Outcome groups the charts show, in stacking order; every outcome belongs to exactly one. */
export const GROUPS=[
  {id:'succeeded',label:'Succeeded',outcomes:['SUCCEEDED']},
  {id:'failed',label:'Failed',outcomes:['FAILED']},
  {id:'partial',label:'Partial',outcomes:['PARTIAL']},
  {id:'unknown',label:'Outcome unknown',outcomes:['OUTCOME_UNKNOWN']},
  {id:'other',label:'In progress, cancelled or expired',outcomes:['PENDING_APPROVAL','IN_PROGRESS','CANCELLED','EXPIRED']},
] as const satisfies readonly {id:string;label:string;outcomes:readonly InsightsOutcome[]}[];
export type GroupId=typeof GROUPS[number]['id'];
export function group(outcome:InsightsOutcome):GroupId{return GROUPS.find(g=>(g.outcomes as readonly string[]).includes(outcome))!.id;}

export interface Bucket{start:number;counts:Record<GroupId,number>;total:number;histogram:number[];samples:number}
/** One bucket per interval of the range, including empty ones; durations come only from finished calls. */
export function buckets(series:InsightsSeries):Bucket[]{
  const width=WIDTH[series.granularity],out=new Map<number,Bucket>();
  const empty=(start:number):Bucket=>({start,counts:{succeeded:0,failed:0,partial:0,unknown:0,other:0},total:0,histogram:new Array(series.histogramBoundsMs.length+1).fill(0),samples:0});
  for(let start=series.fromUnixMs;start<series.toUnixMs;start+=width)out.set(start,empty(start));
  for(const p of series.points){
    const b=out.get(p.bucketStartUnixMs)??empty(p.bucketStartUnixMs);out.set(p.bucketStartUnixMs,b);
    b.counts[group(p.outcome)]+=p.count;b.total+=p.count;
    p.histogram.forEach((v,i)=>{b.histogram[i]=(b.histogram[i]??0)+v;});b.samples+=p.durationCount;
  }
  return [...out.values()].sort((a,b)=>a.start-b.start);
}

/** Percentile from a merged histogram, as the upper bound of the bucket holding it (the server's rule). Null below
 *  the sample floor: 20 for p95, 100 for p99, 1 otherwise. */
export function percentile(histogram:readonly number[],bounds:readonly number[],q:number):number|null{
  const total=histogram.reduce((a,b)=>a+b,0),floor=q>=0.99?100:q>=0.95?20:1;
  if(total<floor)return null;
  const rank=Math.ceil(q*total);let seen=0;
  for(let i=0;i<histogram.length;i++){seen+=histogram[i]!;if(seen>=rank)return bounds[Math.min(i,bounds.length-1)]!;}
  return bounds[bounds.length-1]!;
}

export interface Overview{calls:number;finished:number;succeeded:number;failed:number;partial:number;unknown:number;other:number;
  successRate:number|null;p50:number|null;p95:number|null;p99:number|null;samples:number;denials:number;approvals:number}
/** Headline numbers. The success rate's denominator is succeeded + failed: partial, unknown and unfinished calls are
 *  shown separately, never counted as failures. */
export function overview(series:InsightsSeries):Overview{
  const all=buckets(series),sum=(id:GroupId)=>all.reduce((a,b)=>a+b.counts[id],0);
  const histogram=new Array(series.histogramBoundsMs.length+1).fill(0);
  for(const b of all)b.histogram.forEach((v,i)=>{histogram[i]+=v;});
  const succeeded=sum('succeeded'),failed=sum('failed');
  const denials=series.admissions.filter(a=>a.category!=='APPROVAL_REQUIRED').reduce((a,b)=>a+b.count,0);
  const approvals=series.admissions.filter(a=>a.category==='APPROVAL_REQUIRED').reduce((a,b)=>a+b.count,0);
  return {calls:all.reduce((a,b)=>a+b.total,0),finished:succeeded+failed,succeeded,failed,partial:sum('partial'),unknown:sum('unknown'),other:sum('other'),
    successRate:succeeded+failed?succeeded/(succeeded+failed):null,
    p50:percentile(histogram,series.histogramBoundsMs,0.5),p95:percentile(histogram,series.histogramBoundsMs,0.95),p99:percentile(histogram,series.histogramBoundsMs,0.99),
    samples:histogram.reduce((a,b)=>a+b,0),denials,approvals};
}

export interface ToolRow{key:string;calls:number;failed:number;p95:number|null;samples:number}
/** Per-key totals from a grouped series, busiest first. */
export function ranking(series:InsightsSeries):ToolRow[]{
  const rows=new Map<string,{calls:number;failed:number;histogram:number[]}>();
  for(const p of series.points as InsightsSeriesPoint[]){
    const r=rows.get(p.key)??{calls:0,failed:0,histogram:new Array(series.histogramBoundsMs.length+1).fill(0)};rows.set(p.key,r);
    r.calls+=p.count;if(p.outcome==='FAILED')r.failed+=p.count;p.histogram.forEach((v,i)=>{r.histogram[i]+=v;});
  }
  return [...rows.entries()].map(([key,r])=>({key,calls:r.calls,failed:r.failed,p95:percentile(r.histogram,series.histogramBoundsMs,0.95),samples:r.histogram.reduce((a,b)=>a+b,0)}))
    .sort((a,b)=>b.calls-a.calls||a.key.localeCompare(b.key));
}

/** Orders a session's calls depth-first under their declared parents, children by acceptance. A call whose parent is
 *  not in the set is a root. */
export function callTree(calls:readonly InsightsInvocation[]):{call:InsightsInvocation;depth:number}[]{
  const ids=new Set(calls.map(c=>c.invocationId)),children=new Map<string,InsightsInvocation[]>(),out:{call:InsightsInvocation;depth:number}[]=[];
  const byAcceptance=(a:InsightsInvocation,b:InsightsInvocation)=>a.acceptedAtUnixMs-b.acceptedAtUnixMs||a.invocationId.localeCompare(b.invocationId);
  for(const c of calls){const p=c.parentInvocationId&&ids.has(c.parentInvocationId)&&c.parentInvocationId!==c.invocationId?c.parentInvocationId:'';children.set(p,[...(children.get(p)??[]),c]);}
  const seen=new Set<string>();
  const walk=(parent:string,depth:number)=>{for(const c of (children.get(parent)??[]).sort(byAcceptance)){if(seen.has(c.invocationId))continue;seen.add(c.invocationId);out.push({call:c,depth});walk(c.invocationId,depth+1);}};
  walk('',0);
  // A parent cycle has no root; show those calls flat rather than lose them.
  for(const c of [...calls].sort(byAcceptance))if(!seen.has(c.invocationId)){seen.add(c.invocationId);out.push({call:c,depth:0});}
  return out;
}

/** The most calls running at once. A call runs from acceptance to its terminal time, or to `end` while unfinished;
 *  one ending exactly when another starts does not overlap it. */
export function peakParallel(calls:readonly InsightsInvocation[],end:number):number{
  const events=calls.flatMap(c=>[[c.acceptedAtUnixMs,1],[Math.max(c.terminalAtUnixMs??end,c.acceptedAtUnixMs),-1]] as const);
  events.sort((a,b)=>a[0]-b[0]||a[1]-b[1]);
  let running=0,peak=0;
  for(const [,delta] of events){running+=delta;peak=Math.max(peak,running);}
  return peak;
}

export function duration(ms:number|null|undefined):string{
  if(ms==null)return 'Not measured';
  if(ms<1_000)return `${ms} ms`;if(ms<60_000)return `${(ms/1_000).toFixed(ms<10_000?1:0)} s`;
  if(ms<3_600_000)return `${Math.round(ms/60_000)} min`;return `${(ms/3_600_000).toFixed(1)} h`;
}
export function percent(v:number|null):string{return v==null?'No finished calls':`${(v*100).toFixed(1)}%`;}
export function time(ms:number|null|undefined,g?:InsightsGranularity):string{
  if(ms==null)return '—';
  const d=new Date(ms);
  return g==='DAY'?d.toLocaleDateString(undefined,{timeZone:'UTC',month:'short',day:'numeric'})
    :d.toLocaleString(undefined,{timeZone:'UTC',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false});
}
