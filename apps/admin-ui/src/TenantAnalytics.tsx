// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import {ControlClient,type TenantAnalyticsOverview} from './api';
import {Failure} from './Failure';
import {buckets,overview,ranking,duration,percent,time} from './insights/model';
import {OutcomeChart,LatencyChart} from './insights/charts';
import './insights/insights.css';

const toolFromHash=()=>new URLSearchParams(window.location.hash.split('?')[1]).get('tool')||undefined;

export function TenantAnalytics({client}:{client:ControlClient}){
  const [hours,setHours]=useState(24),[attempt,setAttempt]=useState(0);
  const [tool,setTool]=useState<string|undefined>(toolFromHash),[after,setAfter]=useState<string>();
  useEffect(()=>{const changed=()=>{setTool(toolFromHash());setAfter(undefined);};window.addEventListener('hashchange',changed);return()=>window.removeEventListener('hashchange',changed);},[]);
  const [data,setData]=useState<TenantAnalyticsOverview>(),[error,setError]=useState<unknown>();
  useEffect(()=>{
    const abort=new AbortController();setData(undefined);setError(undefined);
    const to=Date.now();
    client.analytics(to-hours*3_600_000,to,abort.signal,tool,after).then(value=>{
      if(!abort.signal.aborted)setData(value);
    }).catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    return()=>abort.abort();
  },[client,hours,attempt,tool,after]);
  const totals=data?overview(data.series):undefined;
  return <section className="tenant-analytics" aria-labelledby="analytics-heading">
    <p className="eyebrow">Tenant analytics</p><h1 id="analytics-heading">Tool usage and performance</h1>
    <p>Activity belongs to your signed-in tenant. Records without a matching tenant identity are excluded.</p>
    {tool&&<p><a href="#analytics">All tools</a> / <strong>Selected tool: {tool}</strong></p>}
    <div className="toolbar"><label>Time range <select value={hours} onChange={e=>{setHours(Number(e.target.value));setAfter(undefined);}}>
      <option value={24}>Last 24 hours</option><option value={168}>Last 7 days</option><option value={720}>Last 30 days</option>
    </select></label> <button type="button" onClick={()=>setAttempt(a=>a+1)}>Refresh analytics</button></div>
    {error?<Failure error={error} retry={()=>setAttempt(a=>a+1)}/>:!data?<p role="status">Loading tenant analytics…</p>:
      data.mode==='NONE'?<p role="status">Analytics collection is disabled. Ask your installation operator to enable Insights.</p>:<>
        <p className="hint">Tenant ID: {data.tenantId}. Updated {new Date(data.series.toUnixMs).toLocaleString()}. Chart times are UTC.</p>
        {data.series.unavailableBeforeUnixMs!=null&&<p role="status">Part of this range has expired. Charts mark unavailable history; totals cover retained data only.</p>}
        {totals&&<><div className="stats">
          <div className="stat"><span>Tool calls</span><strong>{totals.calls}</strong></div>
          <div className="stat"><span>Success rate</span><strong>{percent(totals.successRate)}</strong><small>{totals.succeeded} succeeded / {totals.finished} succeeded or failed</small></div>
          <div className="stat"><span>Failed calls</span><strong>{totals.failed}</strong><small>{totals.partial} partial; {totals.unknown} unknown</small></div>
          <div className="stat"><span>p95 execution duration</span><strong>{duration(totals.p95)}</strong><small>{totals.samples} measured calls; at least 20 required</small></div>
          {!tool&&<div className="stat"><span>Admission denials</span><strong>{totals.denials}</strong><small>Tenant-attributed requests only</small></div>}
        </div>{totals.calls===0&&<p role="status">No recorded tool calls in this time range.</p>}</>}
        <OutcomeChart data={buckets(data.series)} granularity={data.series.granularity} unavailableBefore={data.series.unavailableBeforeUnixMs}/>
        <LatencyChart data={buckets(data.series)} granularity={data.series.granularity} bounds={data.series.histogramBoundsMs}/>
        {tool&&data.invocations&&<section aria-labelledby="tool-calls-heading"><h2 id="tool-calls-heading">Recent calls for {tool}</h2>
          <p>Calls from your tenant in the selected time range, newest first.</p>
          {data.invocations.items.length===0?<p>No recorded calls for this tool in this time range.</p>:<table>
            <thead><tr><th>Invocation</th><th>Accepted (UTC)</th><th>Outcome</th><th>Execution duration</th><th>Session</th></tr></thead>
            <tbody>{data.invocations.items.map(call=><tr key={call.invocationId}><td>{call.invocationId}</td><td>{time(call.acceptedAtUnixMs)}</td><td>{call.outcome}</td><td>{duration(call.executionMs)}</td><td>{call.sessionId??'—'}</td></tr>)}</tbody>
          </table>}
          {after&&<button type="button" onClick={()=>setAfter(undefined)}>Newest calls</button>}
          {data.invocations.next&&<button type="button" onClick={()=>setAfter(data.invocations!.next!)}>Next calls</button>}
        </section>}
        <h2>Busiest tools</h2><table><thead><tr><th>Tool</th><th>Calls</th><th>Failed</th><th>p95 duration</th></tr></thead>
          <tbody>{ranking(data.tools).map(tool=><tr key={tool.key}><td><a href={`#analytics?tool=${encodeURIComponent(tool.key)}`} aria-current={tool.key===toolFromHash()?'page':undefined}>{tool.key}</a></td><td>{tool.calls}</td><td>{tool.failed}</td><td>{duration(tool.p95)}</td></tr>)}</tbody></table>
      </>}
  </section>;
}
