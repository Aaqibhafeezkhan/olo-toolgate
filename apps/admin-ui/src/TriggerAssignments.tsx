// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import type {TriggerAssignmentPage,TriggerInstancePage} from '@olo-labs/toolgate-contracts/v2';
import type {ControlClient} from './api';
import {Failure} from './Failure';
import {Table} from './Table';

export function TriggerAssignments({client}:{client:ControlClient}){
  const [page,setPage]=useState<TriggerAssignmentPage>();
  const [error,setError]=useState<unknown>();
  const [selected,setSelected]=useState<string>();
  const [token,setToken]=useState<string>();
  const [revision,refresh]=useState(0);
  useEffect(()=>{
    const abort=new AbortController();
    setPage(undefined);
    setError(undefined);
    void client.triggerAssignments(abort.signal,token).then(result=>{
      if(!abort.signal.aborted)setPage(result);
    }).catch(error=>{if(!abort.signal.aborted)setError(error);});
    return()=>abort.abort();
  },[client,token,revision]);

  return <section className="guidance">
    <div className="page-heading"><h2>Trigger assignments</h2><button onClick={()=>refresh(value=>value+1)}>Refresh assignments</button></div>
    {error!==undefined?<Failure error={error}/>:!page?<p role="status">Loading trigger assignments…</p>:page.items.length===0?<p>No trigger assignments.</p>:
      <div className="table-wrap"><Table>
        <caption>Assignments in Control</caption>
        <thead><tr><th>Assignment</th><th>Trigger</th><th>Package version</th><th>Running</th><th>Status</th><th>Instances</th></tr></thead>
        <tbody>{page.items.map(item=><tr key={item.metadata.uid}>
          <th scope="row">{item.metadata.name}</th><td>{item.spec.trigger}</td><td>{item.spec.packageVersion}</td>
          <td>{item.status.instances.running} / {item.status.instances.desired}</td>
          <td>{item.status.conditions.map(condition=>condition.reason).join(', ')}</td>
          <td><button onClick={()=>setSelected(item.metadata.name)}>View {item.metadata.name} instances</button></td>
        </tr>)}</tbody>
      </Table></div>}
    {token&&<button onClick={()=>{setSelected(undefined);setToken(undefined);}}>First assignment page</button>}
    {page?.nextPageToken&&<button onClick={()=>{setSelected(undefined);setToken(page.nextPageToken);}}>Next assignment page</button>}
    {selected&&<TriggerInstances key={selected} client={client} assignment={selected} revision={revision}/>}
  </section>;
}

function TriggerInstances({client,assignment,revision}:{client:ControlClient;assignment:string;revision:number}){
  const [page,setPage]=useState<TriggerInstancePage>();
  const [error,setError]=useState<unknown>();
  const [token,setToken]=useState<string>();
  useEffect(()=>{
    const abort=new AbortController();
    setPage(undefined);
    setError(undefined);
    void client.triggerInstances(assignment,abort.signal,token).then(result=>{
      if(!abort.signal.aborted)setPage(result);
    }).catch(error=>{if(!abort.signal.aborted)setError(error);});
    return()=>abort.abort();
  },[client,assignment,revision,token]);

  return <section>
    <h3>Instances for {assignment}</h3>
    {error!==undefined?<Failure error={error}/>:!page?<p role="status">Loading instances…</p>:page.items.length===0?<p>No instances.</p>:
      <div className="table-wrap"><Table>
        <caption>Instance status</caption>
        <thead><tr><th>Device</th><th>Desired state</th><th>State source</th><th>Phase</th><th>Config revision</th></tr></thead>
        <tbody>{page.items.map(item=><tr key={item.instanceId}>
          <th scope="row">{item.executorId}</th><td>{item.desiredState}</td><td>{item.desiredStateSource}</td>
          <td>{item.phase}</td><td>{item.configRevision}</td>
        </tr>)}</tbody>
      </Table></div>}
    {token&&<button onClick={()=>setToken(undefined)}>First instance page</button>}
    {page?.nextPageToken&&<button onClick={()=>setToken(page.nextPageToken)}>Next instance page</button>}
  </section>;
}
