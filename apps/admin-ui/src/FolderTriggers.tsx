// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState,type FormEvent} from 'react';
import type {EndpointManagedDevice} from '@olo-labs/toolgate-contracts';
import type {TriggerAssignment} from '@olo-labs/toolgate-contracts/v2';
import {ControlClient} from './api';
import {Failure} from './Failure';

const folderPattern=/^[a-z0-9][a-z0-9._ -]{0,127}(\/[a-z0-9][a-z0-9._ -]{0,127}){0,7}$/;
export function validTriggerFolder(value:string){return folderPattern.test(value)&&value.split('/').every(p=>p!=='.'&&p!=='..');}

/** Control supplies device capabilities and enforces ownership again on every mutation. */
export function FolderTriggers({client}:{client:ControlClient}){
  const [devices,setDevices]=useState<readonly EndpointManagedDevice[]>([]);
  const [items,setItems]=useState<readonly TriggerAssignment[]>([]);
  const [device,setDevice]=useState(''),[folder,setFolder]=useState('inbox'),[extension,setExtension]=useState('.txt');
  const [name,setName]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>();
  const [saved,setSaved]=useState('');
  useEffect(()=>{const abort=new AbortController();void(async()=>{
    try{
      const page=await client.managedDevices(abort.signal);if(!abort.signal.aborted)setDevices(page.items);
      const assignments:TriggerAssignment[]=[];let cursor:string|undefined;const seen=new Set<string>();
      do{const result=await client.folderTriggers(cursor,abort.signal);assignments.push(...result.items);cursor=result.nextPageToken;if(cursor&&seen.has(cursor))throw new Error('Repeated page cursor');if(cursor)seen.add(cursor);}while(cursor);
      if(!abort.signal.aborted)setItems(assignments);
    }catch(failure){if(!abort.signal.aborted)setError(failure);}
  })();return()=>abort.abort();},[client]);
  const selectable=devices.filter(row=>row.endpointDevice?.state==='ACTIVE'&&row.directoryDevice?.enabled&&row.permissions?.includes('trigger.manage'));
  const valid=selectable.some(row=>row.deviceId===device)&&validTriggerFolder(folder)&&/^\.[a-z0-9]{1,16}$/.test(extension)&&/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name);
  async function save(event:FormEvent){event.preventDefault();if(!valid||busy)return;setBusy(true);setError(undefined);setSaved('');
    try{const result=await client.createFolderTrigger({name,spec:{trigger:'examples.inbox-watch/new-file',packageVersion:'0.1.0',target:{executor:'CLIENT',devices:[device]},config:{folder,extension},desiredState:'Running'}},crypto.randomUUID());setItems(old=>[...old,result.assignment]);setSaved(name);setName('');}
    catch(failure){setError(failure);}finally{setBusy(false);}
  }
  return <section aria-label="Folder triggers"><h1>Folder triggers</h1>
    <p>Choose an approved device and a folder inside its authorized files area. Matching files are checked every minute. Keep your LangChain trigger listener running to receive events.</p>
    <Failure error={error}/>{saved&&<p role="status">Trigger {saved} created. Use this name in the LangChain example.</p>}
    <form onSubmit={event=>void save(event)}><fieldset disabled={busy}><legend>Watch a folder</legend>
      <label>Trigger name<input value={name} onChange={e=>setName(e.target.value)} placeholder="my-orders" required maxLength={63}/></label>
      <label>Device<select value={device} onChange={e=>setDevice(e.target.value)} required><option value="">Choose your device</option>{selectable.map(row=><option key={row.deviceId} value={row.deviceId}>{row.directoryDevice?.name??row.deviceId} ({row.deviceId})</option>)}</select></label>
      <label>Folder<input value={folder} onChange={e=>setFolder(e.target.value)} placeholder="orders/incoming" required/></label>
      <p>Use a relative folder such as orders/incoming, not an absolute path. Configure the authorized files area in the device settings first.</p>
      <label>File extension<input value={extension} onChange={e=>setExtension(e.target.value)} placeholder=".txt" required/></label>
      <button type="submit" disabled={!valid}>{busy?'Creating…':'Create trigger'}</button>
    </fieldset></form>
    {!selectable.length&&<p>No approved device is available for trigger management. Enroll and approve your device, then ask your administrator for trigger permissions on your owned devices and the inbox-watch package deployment.</p>}
    <h2>Your visible triggers</h2><ul>{items.map(item=><li key={item.metadata.uid}><strong>{item.metadata.name}</strong>: {String(item.spec.config.folder??'')} ({item.spec.target.devices?.join(', ')??'device group'}) — {item.spec.desiredState}; {item.status.instances.running} running, {item.status.instances.failed} failed</li>)}</ul>
  </section>;
}
