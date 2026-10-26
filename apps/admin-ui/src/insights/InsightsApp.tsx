// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useCallback,useEffect,useMemo,useState,type FormEvent,type ReactNode} from 'react';
import type {InsightsInvocation,InsightsInvocationDetail,InsightsOutcome,InsightsSeries,InsightsStatus} from '@olo-labs/toolgate-contracts/v2';
import {EditorDialog} from '../EditorDialog';
import {client as defaultClient,InsightsError,type InsightsClient} from './api';
import {LatencyChart,OutcomeChart} from './charts';
import {RANGES,buckets,duration,granularity,overview,percent,ranking,time,type RangeId} from './model';

type Load<T>={state:'loading'}|{state:'error';error:InsightsError}|{state:'ready';value:T};
const OUTCOMES:InsightsOutcome[]=['SUCCEEDED','FAILED','PARTIAL','OUTCOME_UNKNOWN','IN_PROGRESS','PENDING_APPROVAL','CANCELLED','EXPIRED'];
const label=(o:string)=>o.charAt(0)+o.slice(1).toLowerCase().replaceAll('_',' ');
const failure=(e:unknown)=>e instanceof InsightsError?e:new InsightsError(0,'NETWORK');

function Problem({error,retry}:{error:InsightsError;retry?:()=>void}){
  return <div className="notice error" role="alert"><strong>{error.message}</strong>{retry&&<button type="button" onClick={retry}>Try again</button>}</div>;
}
function Copy({value}:{value:string}){
  const [done,setDone]=useState(false);
  return <button type="button" className="copy-button" aria-label={`Copy ${value}`} title="Copy"
    onClick={()=>{void navigator.clipboard?.writeText(value).then(()=>{setDone(true);setTimeout(()=>setDone(false),1500);});}}>{done?'Copied':'Copy'}</button>;
}
function Field({name,value,copy=false}:{name:string;value:ReactNode;copy?:boolean}){
  return <div className="insights-field"><dt>{name}</dt><dd>{value??<span className="muted">Not supplied</span>}{copy&&typeof value==='string'&&<Copy value={value}/>}</dd></div>;
}

/** The installation-operator Insights page (ADR 031). Served only by Control's operator listener, which requires an
 *  operator client certificate; it shows every tenant and is never part of the tenant console. */
/** Stable defaults: a new client or clock on every render would restart every data effect. */
const DEFAULT_CLIENT=defaultClient(),DEFAULT_NOW=()=>Date.now();
export function InsightsApp({api=DEFAULT_CLIENT,now=DEFAULT_NOW}:{api?:InsightsClient;now?:()=>number}){
  const [status,setStatus]=useState<Load<InsightsStatus>>({state:'loading'});
  const loadStatus=useCallback(()=>{setStatus({state:'loading'});api.status().then(value=>setStatus({state:'ready',value}),e=>setStatus({state:'error',error:failure(e)}));},[api]);
  useEffect(loadStatus,[loadStatus]);

  return <main id="main" className="insights-page" tabIndex={-1}>
    <header className="insights-header"><h1>Operator Insights</h1>
      {status.state==='ready'&&<span className={`mode-badge mode-${status.value.mode.toLowerCase()}`}>{status.value.mode}</span>}</header>
    {status.state==='loading'&&<p role="status">Loading Insights status…</p>}
    {status.state==='error'&&<Problem error={status.error} retry={loadStatus}/>}
    {status.state==='ready'&&(status.value.mode==='NONE'
      ?<section className="notice" role="status"><strong>Insights is off for this installation.</strong>
        <p>Set <code>TOOLGATE_INSIGHTS_MODE</code> to <code>BASIC</code> on Control and every Gateway, then restart them. Only activity after that is recorded; earlier calls are never reconstructed.</p></section>
      :<Workspace api={api} status={status.value} now={now}/>)}
  </main>;
}

function Workspace({api,status,now}:{api:InsightsClient;status:InsightsStatus;now:()=>number}){
  const [range,setRange]=useState<RangeId>('24h');
  const [filters,setFilters]=useState({tenant:'',tool:'',client:''}),[draft,setDraft]=useState(filters);
  const [to]=useState(()=>now());
  const from=to-RANGES.find(r=>r.id===range)!.ms,g=granularity(from,to);
  const [series,setSeries]=useState<Load<InsightsSeries>>({state:'loading'}),[tools,setTools]=useState<Load<InsightsSeries>>({state:'loading'});
  const load=useCallback(()=>{
    const q={granularity:g,from,to,tenant:filters.tenant||undefined,tool:filters.tool||undefined,client:filters.client||undefined};
    setSeries({state:'loading'});setTools({state:'loading'});
    api.series(q).then(value=>setSeries({state:'ready',value}),e=>setSeries({state:'error',error:failure(e)}));
    api.series({...q,groupBy:'TOOL',tool:undefined,client:undefined}).then(value=>setTools({state:'ready',value}),e=>setTools({state:'error',error:failure(e)}));
  },[api,g,from,to,filters]);
  useEffect(load,[load]);
  const apply=(e:FormEvent)=>{e.preventDefault();setFilters(draft);};
  const data=useMemo(()=>series.state==='ready'?buckets(series.value):[],[series]);
  const summary=series.state==='ready'?overview(series.value):undefined;

  return <>
    <StatusBar status={status} now={now}/>
    <form className="insights-filters" onSubmit={apply} aria-label="Filters">
      <label>Range<select value={range} onChange={e=>setRange(e.target.value as RangeId)}>{RANGES.map(r=><option key={r.id} value={r.id}>{r.label}</option>)}</select></label>
      <label>Tenant<input value={draft.tenant} onChange={e=>setDraft({...draft,tenant:e.target.value.trim()})} placeholder="All tenants" spellCheck={false}/></label>
      <label>Tool<input value={draft.tool} disabled={!!draft.client} onChange={e=>setDraft({...draft,tool:e.target.value.trim()})} placeholder="All tools" spellCheck={false}/></label>
      <label>Client<input value={draft.client} disabled={!!draft.tool} onChange={e=>setDraft({...draft,client:e.target.value.trim()})} placeholder="All clients" spellCheck={false}/></label>
      <button type="submit">Apply</button>
      <small>{g==='MINUTE'?'1-minute':g==='HOUR'?'1-hour':'1-day'} buckets, UTC</small>
    </form>
    {series.state==='loading'&&<p role="status">Loading usage…</p>}
    {series.state==='error'&&<Problem error={series.error} retry={load}/>}
    {series.state==='ready'&&summary&&<>
      {summary.calls===0&&summary.denials===0?<p className="notice" role="status">No calls were recorded in this range{filters.tenant||filters.tool||filters.client?' for these filters':''}. Insights has been collecting since this installation enabled it; earlier activity is not shown.</p>:null}
      <section className="insights-tiles" aria-label="Overview">
        <Tile name="Logical calls" value={summary.calls.toLocaleString()} detail={`${summary.other.toLocaleString()} in progress, cancelled or expired`}/>
        <Tile name="Success rate" value={percent(summary.successRate)} detail={`${summary.succeeded.toLocaleString()} of ${summary.finished.toLocaleString()} succeeded or failed`}/>
        <Tile name="Failed" value={summary.failed.toLocaleString()} detail={`${summary.partial} partial · ${summary.unknown} outcome unknown`}/>
        <Tile compact name="p50 · p95 · p99" value={`${duration(summary.p50)} · ${summary.p95==null?'—':duration(summary.p95)} · ${summary.p99==null?'—':duration(summary.p99)}`}
          detail={`${summary.samples.toLocaleString()} measured calls${summary.samples<100?'; p95 needs 20, p99 needs 100':''}`}/>
        <Tile name="Admission denials" value={summary.denials.toLocaleString()} detail={`${summary.approvals.toLocaleString()} sent for approval${status.sourceGaps.length?' · counts may be incomplete (see gaps)':''}`}/>
      </section>
      <div className="insights-charts">
        <OutcomeChart data={data} granularity={g} unavailableBefore={series.value.unavailableBeforeUnixMs}/>
        <LatencyChart data={data} granularity={g} bounds={series.value.histogramBoundsMs}/>
      </div>
    </>}
    <section className="insights-section" aria-labelledby="tools-heading"><h2 id="tools-heading">Busiest tools</h2>
      {tools.state==='loading'&&<p role="status">Loading tools…</p>}
      {tools.state==='error'&&<Problem error={tools.error} retry={load}/>}
      {tools.state==='ready'&&<ToolRanking series={tools.value} select={tool=>{setDraft({...draft,tool,client:''});setFilters({...filters,tool,client:''});}}/>}
    </section>
    <Explorer api={api} from={from} to={to} tenant={filters.tenant} tool={filters.tool}/>
  </>;
}

function Tile({name,value,detail,compact=false}:{name:string;value:string;detail:string;compact?:boolean}){
  return <div className="insights-tile"><span>{name}</span><strong className={compact?'compact':undefined}>{value}</strong><small>{detail}</small></div>;
}

function StatusBar({status,now}:{status:InsightsStatus;now:()=>number}){
  const lag=status.oldestUnprojectedUnixMs==null?null:now()-status.oldestUnprojectedUnixMs;
  return <section className="insights-status" aria-label="Collection status">
    <span>{status.tenants} tenants</span>
    <span>{status.unprojectedFacts===0?'Up to date':`${status.unprojectedFacts.toLocaleString()} transitions waiting${lag!=null?` (oldest ${duration(lag)})`:''}`}</span>
    {status.summariesSuspendedTenants>0&&<span className="warning-text">{status.summariesSuspendedTenants} tenant(s) over the summary quota: the explorer is incomplete for them; totals stay exact</span>}
    {status.failedTenants>0&&<span className="warning-text">{status.failedTenants} tenant(s) could not be read</span>}
    {status.sourceGaps.length>0&&<details><summary className="warning-text">{status.sourceGaps.length} admission-counter gap(s)</summary>
      <ul>{status.sourceGaps.map(g=><li key={`${g.tenantId}/${g.gatewayId}/${g.epoch}`}>Tenant {g.tenantId}, Gateway {g.gatewayId}: {g.missingSequences} missing batch(es), {g.droppedRequests} dropped, last seen {time(g.lastSeenUnixMs)} UTC</li>)}</ul></details>}
    <small>Updated {time(status.generatedAtUnixMs)} UTC</small>
  </section>;
}

function ToolRanking({series,select}:{series:InsightsSeries;select:(tool:string)=>void}){
  const rows=ranking(series).slice(0,10);
  if(!rows.length)return <p className="muted">No tool calls in this range.</p>;
  return <div className="table-scroll"><table className="insights-table"><thead><tr><th scope="col">Tool</th><th scope="col">Calls</th><th scope="col">Failed</th><th scope="col">p95</th></tr></thead>
    <tbody>{rows.map(r=><tr key={r.key}><th scope="row"><button type="button" className="link-button" onClick={()=>select(r.key)} title="Filter by this tool">{r.key}</button></th>
      <td>{r.calls.toLocaleString()}</td><td>{r.failed.toLocaleString()}</td><td>{r.p95==null?<span className="muted" title={`${r.samples} measured calls; p95 needs 20`}>Too few</span>:duration(r.p95)}</td></tr>)}</tbody></table></div>;
}

function Explorer({api,from,to,tenant,tool}:{api:InsightsClient;from:number;to:number;tenant:string;tool:string}){
  const [search,setSearch]=useState(''),[outcome,setOutcome]=useState('');
  const [rows,setRows]=useState<InsightsInvocation[]>(),[next,setNext]=useState<string>(),[error,setError]=useState<InsightsError>(),[busy,setBusy]=useState(false);
  const [open,setOpen]=useState<InsightsInvocation>();
  const query=useCallback((after?:string)=>{
    if(!tenant&&!search){setRows(undefined);setNext(undefined);return;}
    setBusy(true);setError(undefined);
    api.invocations({from,to,tenant:search?undefined:tenant,tool:search?undefined:tool||undefined,outcome:search?undefined:outcome||undefined,search:search||undefined,after,limit:50})
      .then(page=>{setRows(previous=>after?[...(previous??[]),...page.items]:[...page.items]);setNext(page.next);},e=>setError(failure(e))).finally(()=>setBusy(false));
  },[api,from,to,tenant,tool,search,outcome]);
  useEffect(()=>{query();},[tenant,tool,outcome]);// eslint-disable-line react-hooks/exhaustive-deps
  return <section className="insights-section" aria-labelledby="explorer-heading"><h2 id="explorer-heading">Invocations</h2>
    <form className="insights-filters" onSubmit={e=>{e.preventDefault();query();}} aria-label="Search invocations">
      <label>Find by ID<input value={search} onChange={e=>setSearch(e.target.value.trim())} placeholder="Invocation, execution, tool-call, session or run ID" spellCheck={false} maxLength={128}/></label>
      <label>Outcome<select value={outcome} disabled={!!search} onChange={e=>setOutcome(e.target.value)}><option value="">Any</option>{OUTCOMES.map(o=><option key={o} value={o}>{label(o)}</option>)}</select></label>
      <button type="submit" disabled={busy}>Search</button>
    </form>
    {!tenant&&!search&&<p className="muted">Set a tenant filter to browse, or find a call by any ID across all tenants.</p>}
    {error&&<Problem error={error} retry={()=>query()}/>}
    {rows&&rows.length===0&&<p className="muted" role="status">No invocations match.</p>}
    {rows&&rows.length>0&&<div className="table-scroll"><table className="insights-table">
      <thead><tr><th scope="col">Accepted (UTC)</th><th scope="col">Tenant</th><th scope="col">Tool</th><th scope="col">Outcome</th><th scope="col">Attempts</th><th scope="col">End to end</th><th scope="col">Tool call</th></tr></thead>
      <tbody>{rows.map(r=><tr key={`${r.tenantId}/${r.invocationId}`}>
        <th scope="row"><button type="button" className="link-button" onClick={()=>setOpen(r)}>{time(r.acceptedAtUnixMs)}</button></th>
        <td>{r.tenantId}</td><td>{r.toolId}</td><td><span className={`outcome-pill outcome-${r.outcome.toLowerCase()}`}>{label(r.outcome)}</span></td>
        <td>{r.attempts}</td><td>{duration(r.endToEndMs)}</td><td>{r.toolCallId??<span className="muted">—</span>}</td></tr>)}</tbody></table></div>}
    {next&&<button type="button" disabled={busy} onClick={()=>query(next)}>Load more</button>}
    {open&&<Detail api={api} row={open} close={()=>setOpen(undefined)}/>}
  </section>;
}

function Detail({api,row,close}:{api:InsightsClient;row:InsightsInvocation;close:()=>void}){
  const [detail,setDetail]=useState<Load<InsightsInvocationDetail>>({state:'loading'});
  const load=useCallback(()=>{setDetail({state:'loading'});api.detail(row.tenantId,row.invocationId).then(value=>setDetail({state:'ready',value}),e=>setDetail({state:'error',error:failure(e)}));},[api,row]);
  useEffect(load,[load]);
  return <EditorDialog title={`Invocation ${row.invocationId}`} close={close} closeLabel="Close invocation details">
    {detail.state==='loading'&&<p role="status">Loading…</p>}
    {detail.state==='error'&&<Problem error={detail.error} retry={load}/>}
    {detail.state==='ready'&&<InvocationDetail detail={detail.value}/>}
  </EditorDialog>;
}

export function InvocationDetail({detail}:{detail:InsightsInvocationDetail}){
  const i=detail.invocation;
  return <div className="insights-detail">
    <section aria-labelledby="d-overview"><h3 id="d-overview">Overview</h3><dl>
      <Field name="Tool" value={`${i.toolId} (${i.action})`}/>
      <Field name="Outcome" value={<span className={`outcome-pill outcome-${i.outcome.toLowerCase()}`}>{label(i.outcome)}</span>}/>
      <Field name="Error category" value={i.errorCategory?label(i.errorCategory):'None'}/>
      <Field name="Attempts" value={String(i.attempts)}/>
      {i.partialCoverage&&<p className="insights-note" role="note">Insights started collecting after this call was accepted, so its early stages are unknown.</p>}
    </dl></section>
    <section aria-labelledby="d-identity"><h3 id="d-identity">Identity and correlation</h3>
      <p className="muted">Tool-call, session and run IDs are declared by the caller and are not verified.</p><dl>
      <Field name="Tenant" value={i.tenantId} copy/><Field name="Invocation" value={i.invocationId} copy/>
      <Field name="Client (binding)" value={i.clientId} copy/><Field name="User" value={i.userId} copy/><Field name="Agent" value={i.agentId} copy/>
      <Field name="Tool call" value={i.toolCallId} copy/><Field name="Session" value={i.sessionId} copy/><Field name="Agent run" value={i.agentRunId} copy/>
    </dl></section>
    <section aria-labelledby="d-timing"><h3 id="d-timing">Timing</h3><dl>
      <Field name="Accepted" value={`${time(i.acceptedAtUnixMs)} UTC`}/>
      {i.approvalRequestedAtUnixMs!=null&&<Field name="Approval" value={duration(i.approvalMs)}/>}
      <Field name="Queue wait" value={duration(i.queueMs)}/><Field name="Execution (last attempt)" value={duration(i.executionMs)}/>
      <Field name="End to end" value={duration(i.endToEndMs)}/>
    </dl></section>
    <section aria-labelledby="d-attempts"><h3 id="d-attempts">Attempts</h3>
      {detail.attempts.length===0?<p className="muted">No attempt started.</p>:<div className="table-scroll"><table className="insights-table">
        <thead><tr><th scope="col">#</th><th scope="col">Execution ID</th><th scope="col">Executor</th><th scope="col">Started (UTC)</th><th scope="col">Outcome</th><th scope="col">Duration</th></tr></thead>
        <tbody>{detail.attempts.map(a=><tr key={a.attemptNumber}><th scope="row">{a.attemptNumber}</th>
          <td>{a.executionId?<><code>{a.executionId}</code><Copy value={a.executionId}/></>:'—'}</td><td>{a.executorId??'—'}</td>
          <td>{time(a.startedAtUnixMs)}</td><td>{a.outcome?label(a.outcome):'—'}</td><td>{duration(a.durationMs)}</td></tr>)}</tbody></table></div>}
    </section>
    <section aria-labelledby="d-transitions"><h3 id="d-transitions">Ledger transitions</h3>
      {detail.transitionsRetained?<ol className="insights-timeline">{detail.transitions.map(t=><li key={t.factSeq}>
        <time>{time(t.occurredAtUnixMs)}</time> {t.fromState?`${label(t.fromState)} → `:''}{label(t.toState)}</li>)}</ol>
        :<p className="muted">Transitions are kept for a limited time after a call finishes and are no longer available for this one.</p>}
    </section>
  </div>;
}
