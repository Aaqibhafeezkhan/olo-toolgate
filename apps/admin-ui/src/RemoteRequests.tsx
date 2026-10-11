// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table,ActionButton} from './Table';
import {useEffect,useRef,useState} from 'react';
import type {RemoteToolInspection,RemoteToolRecord,RemoteToolState} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';

const labels:Record<RemoteToolState,string>={RECEIVED:'Agent request received',WAITING_FOR_POLL:'Waiting for next client poll',SUBMITTED:'Request submitted to client',RESPONSE_RECEIVED:'Response received',DONE:'Done',FAILED:'Failed',EXPIRED:'Expired'};
export function RemoteRequests({client,compact=false}:{client:ControlClient;compact?:boolean}){
  const [items,setItems]=useState<readonly RemoteToolRecord[]>(),[error,setError]=useState<unknown>(),[attempt,setAttempt]=useState(0);
  const [selected,setSelected]=useState<string>(),[inspection,setInspection]=useState<RemoteToolInspection>(),[responseError,setResponseError]=useState<unknown>();
  const responseHeading=useRef<HTMLHeadingElement>(null),responseTrigger=useRef<HTMLButtonElement>(null);
  useEffect(()=>{if(selected)responseHeading.current?.focus();},[selected]);
  useEffect(()=>{const abort=new AbortController();let timer:ReturnType<typeof setTimeout>;
    async function poll(){try{const page=await client.remoteRequests(abort.signal);if(!abort.signal.aborted){setItems(page.items);setError(undefined);}}catch(failure){if(!abort.signal.aborted)setError(failure);}finally{if(!abort.signal.aborted)timer=setTimeout(()=>void poll(),2000);}}
    void poll();return ()=>{abort.abort();clearTimeout(timer);};
  },[client,attempt]);
  const visible=compact?items?.slice(0,10):items;
  const selectedState=items?.find(record=>record.requestId===selected)?.state;
  useEffect(()=>{
    setInspection(undefined);setResponseError(undefined);if(!selected)return;
    const abort=new AbortController();
    void client.remoteRequest(selected,abort.signal).then(value=>{if(!abort.signal.aborted)setInspection(value);}).catch(failure=>{if(!abort.signal.aborted)setResponseError(failure);});
    return()=>abort.abort();
  },[client,selected,selectedState,attempt]);
  return <section aria-label="Client tool requests" className={compact?'guidance':'remote-requests'}>
    {compact?<h2>Client tool requests</h2>:<div className="page-heading"><div><p className="eyebrow">Client execution</p><h1>Client tool requests</h1></div><button onClick={()=>setAttempt(value=>value+1)}>Refresh</button></div>}
    <p>Agent requests and client responses. Status refreshes every two seconds.</p>
    {Boolean(error)&&<><p role="status">Updates are unavailable. Displayed requests may be out of date.</p><Failure error={error} retry={()=>setAttempt(value=>value+1)}/></>}
    {!visible?<p role="status">Loading client requests…</p>:visible.length===0?<p>No client tool requests yet.</p>:<div className="table-wrap"><Table><caption>{compact?'Latest 10 client requests':'Latest 100 client requests'}</caption><thead><tr><th scope="col">Tool</th><th scope="col">Agent</th><th scope="col">Client</th><th scope="col">Current status</th><th scope="col">Progress</th>{!compact&&<th scope="col">Response</th>}</tr></thead><tbody>{visible.map(request=><tr key={request.requestId}>
      <th scope="row">{request.toolId}<small className="hint" style={{display:'block'}}>{request.requestId}</small></th><td>{request.agentId}</td><td>{request.deviceId}</td><td><strong>{labels[request.state]}</strong>{request.error&&<small className="hint" style={{display:'block'}}>{request.error}</small>}</td>
      <td><ol aria-label={`Progress for ${request.requestId}`}><li>Agent request received for tool {request.toolId}</li><li>Waiting for next client poll</li>{request.submittedAtUnixMs!==undefined&&<li>Request submitted to client</li>}{request.responseAtUnixMs!==undefined&&<li>Response received</li>}{request.completedAtUnixMs!==undefined&&<li>{labels[request.state]}</li>}</ol></td>
      {!compact&&<td><ActionButton icon="view" label={'View response '+request.requestId} onClick={event=>{responseTrigger.current=event.currentTarget;setSelected(request.requestId);}}/></td>}
    </tr>)}</tbody></Table></div>}
    {compact&&<a href="#requests">View all client tool requests →</a>}
    {!compact&&selected&&<section className="request-response" aria-label="Client response"><div className="page-heading"><h2 ref={responseHeading} tabIndex={-1}>Client response</h2><button onClick={()=>{setSelected(undefined);responseTrigger.current?.focus();}}>Close response</button></div><p>Request: <code>{selected}</code></p>
      {Boolean(responseError)?<Failure error={responseError} retry={()=>setAttempt(value=>value+1)}/>:!inspection?<p role="status">Loading response…</p>:<><p>{labels[inspection.record.state]}</p>{inspection.record.error&&<p role="status">Client error: {inspection.record.error}</p>}{inspection.output!==undefined?<pre>{JSON.stringify(inspection.output,null,2)}</pre>:<p>{inspection.record.completedAtUnixMs!==undefined?'No output was returned.':'The client response is not available yet.'}</p>}</>}
    </section>}
  </section>;
}
