// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import type {DeploymentProfile,WorkflowDefinition,WorkflowProfileStatus,WorkflowRunProgressEvent,WorkflowRunStartRequest} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from './api';
import {EditorDialog} from './EditorDialog';
import {Failure} from './Failure';

type Run=Awaited<ReturnType<ControlClient['workflowRun']>>;
const terminal=new Set(['SUCCEEDED','FAILED','AWAITING_APPROVAL','CANCELLED','EXPIRED']);
const labels:Record<string,string>={QUEUED:'Queued',RUNNING:'Running',STEP_AUTHORIZED:'Authorized execution step',NODE_COMPLETED:'Node completed',AGENT_STARTED:'Agent started',AGENT_COMPLETED:'Agent returned',MODEL_STARTED:'Calling model',MODEL_COMPLETED:'Model returned',TOOL_STARTED:'Calling tool',TOOL_COMPLETED:'Tool returned',TOOL_FAILED:'Tool call stopped',SUCCEEDED:'Run succeeded',FAILED:'Run failed',AWAITING_APPROVAL:'Awaiting approval',CANCELLED:'Cancelled',EXPIRED:'Expired'};

/** One explicit submission; progress polls never enqueue work or retry a tool effect. */
export function BotRunDialog({client,profile,status,workflow,close,chooseWorkflow}:{client:ControlClient;profile:DeploymentProfile;status:WorkflowProfileStatus;workflow:WorkflowDefinition;close:()=>void;chooseWorkflow?:(id:string)=>void}){
  const entries=workflow.agents.filter(agent=>agent.entryEligible);
  const [agent,setAgent]=useState(entries[0]?.agentId??''),[message,setMessage]=useState(''),[busy,setBusy]=useState(false),[route,setRoute]=useState('');
  const routes=profile.workflows!.dispatchBindings.filter(item=>item.workflowId===workflow.id&&item.workflowVersionId===workflow.versionId&&item.serviceAgentId===agent);
  const chooseRoute=new Set(routes.map(item=>item.remediationMode)).size>1;
  const [runId,setRunId]=useState<string>(),[run,setRun]=useState<Run>(),[events,setEvents]=useState<WorkflowRunProgressEvent[]>([]);
  const [error,setError]=useState<unknown>(),[pollAttempt,setPollAttempt]=useState(0),[uncertain,setUncertain]=useState(false);
  const submission=useRef<{key:string;body:WorkflowRunStartRequest}|undefined>(undefined),sending=useRef(false);
  const published=status.state==='PUBLISHED'&&status.profileDigest.length>0;
  const complete=run!==undefined&&terminal.has(run.state);
  useEffect(()=>{
    if(!runId)return;
    const abort=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined,cursor=0;
    async function poll(){
      try{
        const current=await client.workflowRun(runId!,abort.signal);
        const collected:WorkflowRunProgressEvent[]=[];
        for(let pages=0;pages<32;pages++){
          const page=await client.workflowRunProgress(runId!,cursor,abort.signal);
          if(!Number.isSafeInteger(page.nextCursor)||page.nextCursor<cursor||page.items.length>200)throw new ApiError(502,'INVALID_PROGRESS');
          collected.push(...page.items.filter(item=>item.sequence>cursor));
          if(page.items.length&&page.nextCursor<=cursor)throw new ApiError(502,'INVALID_PROGRESS');
          cursor=page.nextCursor;if(page.items.length<200)break;
        }
        if(abort.signal.aborted)return;
        setRun(current);setEvents(previous=>{const ids=new Set(previous.map(item=>item.sequence));return [...previous,...collected.filter(item=>!ids.has(item.sequence))].sort((a,b)=>a.sequence-b.sequence);});setError(undefined);
        if(!terminal.has(current.state))timer=setTimeout(()=>void poll(),1000);
      }catch(value){if(!abort.signal.aborted)setError(value);}
    }
    void poll();return()=>{abort.abort();if(timer)clearTimeout(timer);};
  },[client,runId,pollAttempt]);
  async function start(){
    if(sending.current||runId||!published)return;
    sending.current=true;setBusy(true);setError(undefined);
    const pending=submission.current??{key:crypto.randomUUID(),body:{profileDigest:status.profileDigest,expectedGeneration:status.generation,entryAgentId:agent,message,...(chooseRoute?{dispatchBindingId:route}:{})}};
    submission.current=pending;
    try{
      const receipt=await client.startWorkflowRun(profile.profileId,workflow.id,pending.body,pending.key);
      setRunId(receipt.runId);setUncertain(false);
    }catch(value){
      setError(value);const unknown=!(value instanceof ApiError)||value.status===0||value.status>=500||value.status===408||value.status===429;
      setUncertain(unknown);if(!unknown)submission.current=undefined;
    }finally{sending.current=false;setBusy(false);}
  }
  return <EditorDialog title={`Run ${workflow.id}`} close={close} busy={busy} closeLabel="Close bot run">
    <p>Run the published bot with the input it expects. Progress and the result appear here. Closing this panel leaves an accepted run running.</p>
    {!published&&<p role="status">Publish this bot revision before running it.</p>}
    {!entries.length&&<p role="status">Configure an entry-eligible agent before running this bot.</p>}
    {chooseWorkflow&&profile.workflows!.workflows.length>1&&<label>Bot<select value={workflow.id} disabled={busy||Boolean(runId)||uncertain} onChange={event=>chooseWorkflow(event.target.value)}>{profile.workflows!.workflows.map(item=><option key={item.id} value={item.id}>{item.id}</option>)}</select></label>}
    <form onSubmit={event=>{event.preventDefault();void start();}}><fieldset disabled={busy||Boolean(runId)||uncertain||!published}>
      {entries.length>1&&<label>Entry agent<select value={agent} onChange={event=>{setAgent(event.target.value);setRoute('');}}>{entries.map(item=><option key={item.agentId} value={item.agentId}>{item.agentId}</option>)}</select></label>}
      {chooseRoute&&<label>Configured execution route<select required value={route} onChange={event=>setRoute(event.target.value)}><option value="">Choose a configured route</option>{routes.map(item=><option key={item.id} value={item.id}>{item.id} ({item.remediationMode})</option>)}</select></label>}
      <label>Bot input<textarea required rows={7} maxLength={32768} value={message} onChange={event=>setMessage(event.target.value)} placeholder="Enter the text or input this bot expects"/></label>
    </fieldset>{!runId&&<button className="primary" type="submit" disabled={busy||!published||!agent||!message.trim()||chooseRoute&&!route}><span aria-hidden="true">▶</span> {busy?'Submitting…':uncertain?'Check same submission':'Run bot'}</button>}</form>
    {uncertain&&<p role="status">Submission status is unknown. Check the same submission to recover its run without starting another one.</p>}
    {error!==undefined&&<><Failure error={error}/>{runId&&<button onClick={()=>setPollAttempt(value=>value+1)}>Resume progress</button>}</>}
    {runId&&<section aria-label="Bot run progress"><h3>Execution progress</h3><p>Run: <code>{runId}</code></p><p role="status">{run?labels[run.state]??run.state:'Accepted; loading progress…'}</p>
      {events.length>0?<ol className="bot-run-progress" role="log" aria-live="polite" aria-label="Execution timeline">{events.map(item=><li key={item.sequence}><time dateTime={item.occurredAt}>{new Date(item.occurredAt).toLocaleTimeString()}</time>{' '}<strong>{labels[item.kind]??item.kind.replaceAll('_',' ').toLowerCase()}</strong>{item.nodeId&&<> — {item.nodeId.replace(/^_node_/,'')}</>}{item.subjectId&&<> — {item.subjectId}</>}{item.toolRef&&<> — {item.toolRef}</>}{item.source==='RUNTIME'&&<small> (runtime reported)</small>}</li>)}</ol>:<p>Waiting for recorded execution progress…</p>}
      {complete&&run.outcome&&<section aria-label="Bot run result"><h3>Result</h3>{run.outcome.code&&<p>Code: <code>{run.outcome.code}</code></p>}<pre className="bot-run-result">{typeof run.outcome.result==='string'?run.outcome.result:JSON.stringify(run.outcome.result,null,2)}</pre></section>}
    </section>}
  </EditorDialog>;
}
