// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState,useSyncExternalStore} from 'react';
import type {TriggerAssignment,TriggerAssignmentPage,TriggerInstancePage,TriggerLifecycleAction} from '@olo-labs/toolgate-contracts/v2';
import type {ControlClient} from './api';
import {Failure} from './Failure';
import {Table} from './Table';
import {EditorDialog} from './EditorDialog';

export function TriggerAssignments({client}:{client:ControlClient}){
  useSyncExternalStore(client.subscribePermissions,client.permissionSnapshot,client.permissionSnapshot);
  const canManage=client.can('trigger.manage');
  const [editing,setEditing]=useState<TriggerAssignment>();
  const [notice,setNotice]=useState('');
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
        <thead><tr><th>Assignment</th><th>Trigger</th><th>Package version</th><th>Running</th><th>Status</th><th>Instances</th>{canManage&&<th>Actions</th>}</tr></thead>
        <tbody>{page.items.map(item=><tr key={item.metadata.uid}>
          <th scope="row">{item.metadata.name}</th><td>{item.spec.trigger}</td><td>{item.spec.packageVersion}</td>
          <td>{item.status.instances.running} / {item.status.instances.desired}</td>
          <td>{item.status.conditions.map(condition=>condition.reason).join(', ')}</td>
          <td><button onClick={()=>setSelected(item.metadata.name)}>View {item.metadata.name} instances</button></td>
          {canManage&&<td><button onClick={()=>{setNotice('');setEditing(item);}}>Manage {item.metadata.name}</button></td>}
        </tr>)}</tbody>
      </Table></div>}
    {token&&<button onClick={()=>{setSelected(undefined);setToken(undefined);}}>First assignment page</button>}
    {page?.nextPageToken&&<button onClick={()=>{setSelected(undefined);setToken(page.nextPageToken);}}>Next assignment page</button>}
    {notice&&<p role="status">{notice}</p>}
    {canManage&&editing&&<AssignmentActions client={client} assignment={editing} close={()=>setEditing(undefined)}
      complete={()=>{setEditing(undefined);setNotice('Assignment updated.');refresh(value=>value+1);}}/>}
    {selected&&<TriggerInstances key={selected} client={client} assignment={selected} revision={revision}/>}
  </section>;
}

const actions=['Run','Pause','Stop','Clear','Deploy','Remove'] as const satisfies readonly TriggerLifecycleAction[];
type AssignmentAction=TriggerLifecycleAction|'Rollback';
type Scope='devices'|'deviceGroups'|'instances';

function AssignmentActions({client,assignment,close,complete}:{client:ControlClient;assignment:TriggerAssignment;close:()=>void;complete:()=>void}){
  const [action,setAction]=useState<AssignmentAction>('Run');
  const [scope,setScope]=useState<Scope>('devices');
  const [targets,setTargets]=useState('');
  const [reason,setReason]=useState('');
  const [desiredState,setDesiredState]=useState('Running');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState<unknown>();
  async function submit(){
    if(busy)return;
    setBusy(true);
    setError(undefined);
    const {name,resourceVersion}=assignment.metadata;
    try{
      if(action==='Rollback')await client.rollbackTriggerAssignment(name,resourceVersion);
      else{
        const ids=[...new Set(targets.split(',').map(id=>id.trim()).filter(Boolean))];
        if(!ids.length||!reason.trim())throw new Error('Enter at least one target and a reason.');
        await client.triggerAssignmentAction(name,resourceVersion,{
          action,scope:{[scope]:ids},reason:reason.trim(),...(action==='Deploy'?{desiredState}:{}),
        });
      }
      complete();
    }catch(error){setError(error);}
    finally{setBusy(false);}
  }
  return <EditorDialog title={`Manage ${assignment.metadata.name}`} close={close} busy={busy}>
    <form onSubmit={event=>{event.preventDefault();void submit();}}>
      <fieldset disabled={busy}>
        <label>Lifecycle action<select value={action} onChange={event=>{
          const next=event.target.value as AssignmentAction;
          setAction(next);
          if(next==='Deploy'&&scope==='instances')setScope('devices');
        }}>
          {actions.map(value=><option key={value}>{value}</option>)}<option>Rollback</option>
        </select></label>
        {action==='Rollback'?<p>Restore the previous configuration for this assignment.</p>:<>
          <label>Target scope<select value={scope} onChange={event=>setScope(event.target.value as Scope)}>
            <option value="devices">Devices</option><option value="deviceGroups">Device Groups</option>
            {action!=='Deploy'&&<option value="instances">Instances</option>}
          </select></label>
          <label>Target IDs (comma separated)<input required value={targets} onChange={event=>setTargets(event.target.value)}/></label>
          {action==='Deploy'&&<label>Initial desired state<select value={desiredState} onChange={event=>setDesiredState(event.target.value)}>
            <option>Running</option><option>Paused</option><option>Stopped</option>
          </select></label>}
          <label>Reason<input required maxLength={1024} value={reason} onChange={event=>setReason(event.target.value)}/></label>
        </>}
        {error!==undefined&&<Failure error={error}/>}
        <button className="primary">Apply {action}</button>
      </fieldset>
    </form>
  </EditorDialog>;
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
