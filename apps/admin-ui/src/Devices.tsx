// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table,ActionButton,StatusToggle} from './Table';
import {useEffect,useRef,useState,type FormEvent} from 'react';
import type {EndpointManagedDevice,DeviceDeploymentMetadata} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {ApprovalDuration,defaultDuration,durationBody,localDateTime,validDuration} from './ApprovalDuration';
import {DirectoryEditor} from './DirectoryEditor';
import {EditorDialog} from './EditorDialog';
import {DeviceDetails} from './DeviceDetails';
import {DeviceMembership,PublishAccess} from './AccessMapping';
import {ScopePicker} from './ScopePicker';
import {useRecordActions} from './RecordActions';

function approved(row:DeviceView,now:number){const device=row.endpointDevice;return Boolean(device&&device.state==='ACTIVE'&&device.connectionApproved!==false&&(device.connectionExpiresAtUnixMs===undefined||device.connectionExpiresAtUnixMs>now));}
function connection(row:DeviceView,now:number){
  if(row.registeredUser?.enabled===false)return 'User disabled';
  if(row.systemExecutor)return row.directoryDevice?.enabled===false?'Disabled':row.systemAvailable===true?'Available':row.systemAvailable===false?'Unavailable':'Availability unknown';
  if(row.directoryDevice?.enabled===false)return 'Disabled';
  if(!approved(row,now))return row.endpointDevice?.state==='REVOKED'?'Revoked':'Needs approval';
  const device=row.endpointDevice!;const age=now-device.lastSeenUnixMs;
  // Only the device's owner switches it; the console shows the reason instead of waiting for Offline.
  if(device.report?.power==='OFF')return 'Powered off by owner';
  return device.reportSequence>0&&device.lastSeenUnixMs>0&&age>=-5000&&age<120000?'Connected':'Offline';
}
function purpose(row:DeviceView){return row.systemExecutorKind==='HOTFOLDER'?'Runs file tools inside the server’s configured HotFolder. Disabling this device blocks HotFolder operations.':row.systemExecutorKind==='REST_FORWARDING'?'The existing ToolGate gateway forwards tool calls to approved clients. Disabling this device blocks that forwarding.':row.systemExecutor?'Quickstart’s internal fixed executor runs compute and server tools. Disabling it blocks all server built-in operations, including HotFolder.':'An installed client with a protected device key. Both approval and enablement are required for access.';}
function baseDetails(row:DeviceView,now:number){return `${purpose(row)}\nDevice: ${row.directoryDevice?.name??`${row.enrollment?.platform??'New'} client`} (${row.deviceId})\nSystem name: ${row.systemName??row.endpointDevice?.systemName??row.deployment?.systemName??'Not reported'}\nIP address: ${row.ipAddress??row.endpointDevice?.ipAddress??'Not reported'}\nOwner: ${row.registeredUser?.name??row.directoryDevice?.ownerUserId??'Awaiting registration'}${row.registeredUser?` (${row.registeredUser.id})`:''}\nConnection: ${connection(row,now)}\n${row.systemExecutor?'Identity and approval: server managed':`Key fingerprint: ${row.endpointDevice?.keyFingerprint??row.enrollment?.keyFingerprint??'No enrolled key'}\nApproval: ${approved(row,now)?'Approved':'Needs approval'}\nLast check-in: ${row.endpointDevice?.lastSeenUnixMs?new Date(row.endpointDevice.lastSeenUnixMs).toLocaleString():'No check-in yet'}`}`;}

type DeviceView=EndpointManagedDevice & {readonly deployment?:DeviceDeploymentMetadata};
const deploymentKinds={QUICKSTART_CLIENT:'Quickstart built-in endpoint',ENDPOINT_CLIENT:'Endpoint client',TOOL_HOST_DIRECT:'Tool Host · direct dispatch',TOOL_HOST_PULL:'Tool Host · pull mode'} as const;
function details(row:DeviceView,now:number){const d=row.deployment;return baseDetails(row,now)+(d?`\nExecution service: ${deploymentKinds[d.executionKind]}\nDeployment container: ${d.containerName}\nContainer ID: ${d.containerId}\nDeployment network: ${d.networkName}\nNetwork ID: ${d.networkId}\nDeployment IP: ${d.ipAddress}\nDeployment details are display information, not permission evidence.`:'');}

const permitted=(row:DeviceView,action:string)=>row.permissions?.includes(`device.${action}`)===true;
const groupApprover=(row:DeviceView)=>permitted(row,'manage_groups');
const canApprove=(row:DeviceView)=>permitted(row,'approve');

/** Single bounded snapshot includes enrolled keys, directory-only devices and pending requests. */
export function Devices({client,superAdmin=false}:{client:ControlClient;superAdmin?:boolean;userId?:string}){
  const [items,setItems]=useState<readonly DeviceView[]>();const [error,setError]=useState<unknown>();const [listError,setListError]=useState<unknown>();
  const [now,setNow]=useState(Date.now());const [refresh,setRefresh]=useState(0);const [busy,setBusy]=useState(false);
  const [selected,setSelected]=useState<DeviceView>();const [creating,setCreating]=useState(false);const [editing,setEditing]=useState<DeviceView>();
  const [duration,setDuration]=useState(defaultDuration);const [confirmed,setConfirmed]=useState(false);const [notice,setNotice]=useState('');
  const [membership,setMembership]=useState<string>();
  const [approvalGroups,setApprovalGroups]=useState<string[]>(['default-devices']);
  const [approvalGroupsReady,setApprovalGroupsReady]=useState(true);const approvalChoice=useRef(0);
  const alive=useRef(true);const guard=useRef(false);
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  useEffect(()=>{
    const controller=new AbortController();let timer:ReturnType<typeof setTimeout>;
    async function poll(){
      setNow(Date.now());
      try{const page=await client.managedDeviceDeployments(controller.signal);const deployments=new Map(page.deployments?.map(value=>[value.deviceId,value.deployment]));if(!controller.signal.aborted){setItems(page.items.map(row=>({...row,deployment:deployments.get(row.deviceId)})));setListError(undefined);}}
      catch(failure){if(!controller.signal.aborted)setListError(failure);}
      if(!controller.signal.aborted)timer=setTimeout(()=>void poll(),1000);
    }
    void poll();return()=>{controller.abort();clearTimeout(timer);};
  },[client,refresh]);
  const reload=()=>setRefresh(value=>value+1);
  useEffect(()=>{
    const update=()=>setRefresh(value=>value+1);
    window.addEventListener('toolgate:approval-change',update);window.addEventListener('focus',update);
    return()=>{window.removeEventListener('toolgate:approval-change',update);window.removeEventListener('focus',update);};
  },[]);
  const recordActions=useRecordActions(client,'devices',reload);
  async function mutate(action:()=>Promise<unknown>,message:string){
    if(guard.current)return;guard.current=true;setBusy(true);setError(undefined);setNotice('');
    try{await action();if(alive.current){setSelected(undefined);setConfirmed(false);setNotice(message);reload();window.dispatchEvent(new Event('toolgate:approval-change'));}}
    catch(failure){if(alive.current){setError(failure);setSelected(undefined);setConfirmed(false);reload();}}
    finally{guard.current=false;if(alive.current)setBusy(false);}
  }
  function choose(row:DeviceView){
    const choice=++approvalChoice.current;const groups=groupApprover(row);setApprovalGroupsReady(!row.directoryDevice||!groups);
    setApprovalGroups(['default-devices']);
    if(row.directoryDevice&&groups)void client.memberships('devices',row.deviceId).then(value=>{if(alive.current&&choice===approvalChoice.current){setApprovalGroups([...value.groupIds]);setApprovalGroupsReady(true);}}).catch(failure=>{if(alive.current&&choice===approvalChoice.current)setError(failure);});
    setSelected(row);setCreating(false);setEditing(undefined);setConfirmed(false);setError(undefined);setNotice('');
    const deadline=row.endpointDevice?.connectionExpiresAtUnixMs;
    setDuration(row.endpointDevice&&deadline===undefined?{...defaultDuration(),unlimited:true}:deadline!==undefined&&deadline>Date.now()?{unlimited:false,until:localDateTime(deadline)}:defaultDuration());
  }
  function submit(event:FormEvent){
    event.preventDefault();if(!selected||!confirmed||!approvalGroupsReady||!approvalGroups.length||!validDuration(duration,Date.now()))return;
    const row=selected;const endpoint=row.endpointDevice;
    void mutate(async()=>{
      if(endpoint)await client.setDeviceApproval(row.deviceId,{expectedApprovalRevision:endpoint.approvalRevision??1,approved:true,...durationBody(duration)},crypto.randomUUID());
      else await client.decideEnrollment({userCode:row.enrollment!.userCode!,keyFingerprint:row.enrollment!.keyFingerprint!,choice:'APPROVE',...durationBody(duration)},crypto.randomUUID());
      // Own-device approval: Control places the device in the rule's group itself.
      if(!groupApprover(row))return;
      const current=await client.memberships('devices',row.deviceId);
      if([...current.groupIds].sort().join('\n')!==[...approvalGroups].sort().join('\n'))await client.saveMemberships('devices',{...current,groupIds:approvalGroups},crypto.randomUUID());
    },'Device approved.');
  }
  return <><div className="page-heading"><div><p className="eyebrow">Device access</p><h1>Clients</h1></div><div className="actions"><button onClick={reload} disabled={busy}>Refresh</button><button disabled={busy||!client.can('device.create')} onClick={()=>{setCreating(true);setEditing(undefined);setSelected(undefined);}}>Add client</button></div></div>
    <p>Devices and pending requests available to your account. Enablement and approval are separate: both are required for client access. Changes are saved immediately; this list checks for new requests every second.</p>
    {!superAdmin&&<p>Installing a client does not yet identify its owner. <a href="#enroll">Connect this computer or verify its enrollment code</a> to add its pending request to your list, then approve it here.</p>}
    {Boolean(error)&&!selected&&<Failure error={error}/>}{Boolean(listError)&&<Failure error={listError}/>}{notice&&<p role="status">{notice}</p>}
    {!items&&!listError&&<p role="status">Loading clients…</p>}
    {items?.length===0&&<p>No devices or pending requests.</p>}
    {items?.some(row=>row.deployment)&&<p className="hint">Docker deployment: {new Set(items.flatMap(row=>row.deployment?[row.deployment.containerId]:[])).size} containers. Rows identify enrolled execution services; several services can share one container. Network details are display information.</p>}
    {Boolean(items?.length)&&<div className="table-wrap"><Table disabled={busy||recordActions.busy} bulkActions={keys=>{const selected=items!.filter(row=>keys.includes(row.deviceId)&&!row.systemExecutor&&row.directoryDevice);const records=(action:string)=>selected.filter(row=>permitted(row,action)).map(row=>row.directoryDevice!);return <><button disabled={busy||recordActions.busy||!records('enable').some(row=>!row.enabled)} onClick={()=>void recordActions.run(records('enable'),'enable')}>Enable selected</button><button disabled={busy||recordActions.busy||!records('disable').some(row=>row.enabled)} onClick={()=>void recordActions.run(records('disable'),'disable')}>Disable selected</button><button className="danger" disabled={busy||recordActions.busy||!records('delete').length} onClick={()=>recordActions.requestDelete(records('delete'))}>Delete selected</button></>;}}><caption className="sr-only">Clients and device approvals</caption><thead><tr><th scope="col">Device name</th><th scope="col">System name</th><th scope="col">IP address</th><th scope="col">Deployment container</th><th scope="col">Docker network</th><th scope="col">Owner</th><th scope="col">Identifier</th><th scope="col">Connection</th><th scope="col">Directory status</th><th scope="col">Approval</th><th scope="col">Allowed until</th><th scope="col">Actions</th></tr></thead>
      <tbody>{items?.map(row=>{
        const directory=row.directoryDevice,endpoint=row.endpointDevice;const isApproved=approved(row,now);const state=connection(row,now);const approvalAllowed=canApprove(row);
        return <tr key={row.deviceId} title={details(row,now)}><th scope="row"><span>{directory?directory.name:`${row.enrollment?.platform??'New'} client`}</span> <DeviceDetails deviceId={row.deviceId} text={details(row,now)}/>{row.systemExecutor&&<small>Internal Quickstart device</small>}{row.deployment&&<small>{deploymentKinds[row.deployment.executionKind]}</small>}</th><td>{row.systemName??endpoint?.systemName??row.deployment?.systemName??'Not reported'}</td><td><code>{row.deployment?.ipAddress??row.ipAddress??endpoint?.ipAddress??'Not reported'}</code>{row.deployment&&(row.ipAddress??endpoint?.ipAddress)&&row.deployment.ipAddress!==(row.ipAddress??endpoint?.ipAddress)&&<small>Observed: {row.ipAddress??endpoint?.ipAddress}</small>}</td><td>{row.deployment?<>{row.deployment.containerName}<small><code>{row.deployment.containerId.slice(0,12)}</code></small></>:'—'}</td><td>{row.deployment?<>{row.deployment.networkName}<small><code title={row.deployment.networkId}>{row.deployment.networkId.slice(0,12)}</code></small></>:'—'}</td><td>{row.registeredUser?<>{row.registeredUser.name}<small>{row.registeredUser.id}{!row.registeredUser.enabled?' · Disabled user':''}</small></>:directory?.ownerUserId??endpoint?.userId??'Awaiting registration'}</td><td><code>{row.deviceId}</code></td>
          <td><span className={`client-connection ${!listError&&(state==='Connected'||state==='Available')?'online':'offline'}`}><span className="connection-dot" aria-hidden="true"/>{listError?'Status unavailable':state}</span></td>
          <td data-sort-value={directory?.enabled?'Enabled':directory?'Disabled':'Awaiting enrollment'}>{directory?<StatusToggle enabled={directory.enabled} label={row.deviceId} disabled={busy||recordActions.busy||!permitted(row,directory.enabled?'disable':'enable')} onClick={()=>void mutate(()=>client.setDeviceEnabled(row.deviceId,{expectedRevision:directory.revision,enabled:!directory.enabled},crypto.randomUUID()),directory.enabled?'Device disabled.':'Device enabled.')}/>:'Awaiting enrollment'}</td>
          <td>{row.systemExecutor?'Built-in executor':isApproved?'Approved':endpoint?.state==='REVOKED'?'Revoked':row.enrollment||endpoint?'Approval required':'Needs enrollment'}</td>
          <td data-sort-value={isApproved?(endpoint?.connectionExpiresAtUnixMs??Number.MAX_SAFE_INTEGER):0}>{row.systemExecutor?'Server managed':isApproved?(endpoint?.connectionExpiresAtUnixMs===undefined?'Unlimited':new Date(endpoint.connectionExpiresAtUnixMs).toLocaleString()):'—'}</td>
          <td><div className="actions">{directory&&<><ActionButton icon="edit" label={(permitted(row,'update')?'Edit ':'View ')+row.deviceId} disabled={busy||recordActions.busy} onClick={()=>{setEditing(row);setCreating(false);setSelected(undefined);}}/><ActionButton icon="groups" label={'Groups for '+row.deviceId} disabled={busy||recordActions.busy||!permitted(row,'manage_groups')} onClick={()=>setMembership(row.deviceId)}/></>}
            {!row.systemExecutor&&approvalAllowed&&endpoint?.state==='REVOKED'&&<ActionButton icon="approve" label={'Request approval again '+row.deviceId} title="Start a fresh review; access stays blocked until approval" disabled={busy||recordActions.busy} onClick={()=>void mutate(()=>client.requestDeviceApproval(row.deviceId,{expectedRevision:endpoint.revision},crypto.randomUUID()),'Approval requested. Waiting for the client to submit a fresh enrollment.')}/>}
            {!row.systemExecutor&&approvalAllowed&&(endpoint||row.enrollment)&&endpoint?.state!=='REVOKED'&&<ActionButton icon={isApproved?'deny':'approve'} label={(isApproved?'Deapprove':'Approve')+' '+row.deviceId} disabled={busy||recordActions.busy||Boolean(endpoint&&endpoint.state!=='ACTIVE')} onClick={()=>isApproved?void mutate(()=>client.setDeviceApproval(row.deviceId,{expectedApprovalRevision:endpoint?.approvalRevision??1,approved:false},crypto.randomUUID()),'Device deapproved.'):choose(row)}/>}
            {isApproved&&approvalAllowed&&<ActionButton icon="edit" label={'Change approval '+row.deviceId} disabled={busy||recordActions.busy} onClick={()=>choose(row)}/>}
            {directory&&<ActionButton icon="delete" label={'Delete '+row.deviceId} title={row.systemExecutor?'Internal executors are managed by Quickstart':'Delete device; a live client will request approval again'} disabled={busy||recordActions.busy||row.systemExecutor||!permitted(row,'delete')} onClick={()=>recordActions.requestDelete([directory])}/>}</div></td></tr>;
      })}</tbody></Table></div>}
    {items?.some(row=>row.systemExecutor)&&<p className="hint">Quickstart’s internal devices are created automatically for the fixed executor, HotFolder, and REST call forwarding. They use server-managed identity and appear green while available and enabled. Installed clients require separate approval.</p>}
    {selected&&<EditorDialog title={`Approve ${selected.deviceId}`} close={()=>setSelected(undefined)} busy={busy} closeLabel="Close approval"><section className="guidance">{Boolean(error)&&<Failure error={error}/>}<p>Owner: {selected.endpointDevice?.userId??selected.directoryDevice?.ownerUserId??'Your signed-in user'}</p><p>Key fingerprint: <code>{selected.endpointDevice?.keyFingerprint??selected.enrollment?.keyFingerprint}</code></p>
      {selected.enrollment&&<p>Enrollment code: <code>{selected.enrollment.userCode}</code></p>}
      <form onSubmit={submit}><ApprovalDuration value={duration} onChange={setDuration} now={now} disabled={busy}/>
        {!approvalGroupsReady&&<p role="status">Loading current device groups…</p>}
        {groupApprover(selected)?<><ScopePicker client={client} kind="deviceGroups" label="Device groups" value={approvalGroups} onChange={setApprovalGroups} disabled={busy||!approvalGroupsReady}/>
        <p className="hint">The device is approved first. A membership change creates a configuration draft and requires independent approval before its group permissions apply.</p></>
        :<p className="hint">You own this device. ToolGate places it in the group your role assigns to owned devices.</p>}
        <label><input type="checkbox" checked={confirmed} onChange={event=>setConfirmed(event.target.checked)} disabled={busy}/> {selected.enrollment?'I compared the code and fingerprint on the requesting device.':'I confirm access for this enrolled device and owner.'}</label>
        <div className="actions"><button disabled={busy||!confirmed||!approvalGroupsReady||!approvalGroups.length||!validDuration(duration,now)||Boolean(selected.enrollment&&selected.enrollment.expiresAtUnixMs<=now)}>Approve device</button><button type="button" disabled={busy} onClick={()=>setSelected(undefined)}>Cancel</button></div></form></section></EditorDialog>}
    {membership&&<DeviceMembership key={membership} client={client} id={membership} close={()=>setMembership(undefined)} saved={()=>{setNotice('Device membership saved. Publish access changes to update gateways.');reload();}}/>}
    {recordActions.feedback}
    <PublishAccess client={client}/>
    {(creating||editing?.directoryDevice)&&<DirectoryEditor readOnly={editing?!permitted(editing,'update'):!client.can('device.create')} key={editing?.deviceId??'new'} client={client} kind="devices" record={editing?.directoryDevice} close={()=>{setCreating(false);setEditing(undefined);}} saved={reload}/>}</>;
}
