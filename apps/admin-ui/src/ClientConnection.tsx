// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect, useState} from 'react';
import type {EndpointDeviceRecord} from '@olo-labs/toolgate-contracts';
import {ApiError, ControlClient} from './api';

type Snapshot = {device?:EndpointDeviceRecord;error?:'missing'|'unavailable'};

/** Connection is established by authenticated check-ins, not directory activation. */
export function ClientConnection({client,id,enabled,ownerUserId}:{client:ControlClient;id:string;enabled:boolean;ownerUserId:string}) {
  const [snapshot,setSnapshot] = useState<Snapshot>();
  const [now,setNow] = useState(Date.now());
  useEffect(()=>{
    const controller = new AbortController();let pending=false;
    setSnapshot(undefined);
    async function poll() {
      setNow(Date.now());
      if(pending||!enabled)return;
      pending=true;
      try {
        const device=await client.endpointDevice(id,controller.signal);
        if(!controller.signal.aborted)setSnapshot({device});
      } catch(error) {
        if(!controller.signal.aborted)setSnapshot({error:error instanceof ApiError&&error.status===404?'missing':'unavailable'});
      } finally {pending=false;}
    }
    void poll();const timer=setInterval(()=>void poll(),2000);
    return()=>{controller.abort();clearInterval(timer);};
  },[client,id,enabled,ownerUserId]);

  let label='Checking connection…',connected=false;
  const device=snapshot?.device;
  const valid=device?.deviceId===id&&Number.isSafeInteger(device?.lastSeenUnixMs)&&Number.isSafeInteger(device?.reportSequence)
    &&device.lastSeenUnixMs>=0&&device.reportSequence>=0;
  if(!enabled)label='Disabled';
  else if(snapshot?.error==='missing')label='Not enrolled';
  else if(snapshot?.error==='unavailable'||snapshot&&!valid)label='Status unavailable';
  else if(device&&valid) {
    if(device.state==='REVOKED')label='Revoked';
    else if(device.connectionApproved===false)label='Approval required';
    else if(device.connectionExpiresAtUnixMs!==undefined&&device.connectionExpiresAtUnixMs<=now)label='Approval required';
    else if(device.userId!==ownerUserId)label='Enrollment mismatch';
    else if(device.state!=='ACTIVE')label='Offline';
    else if(device.reportSequence===0||device.lastSeenUnixMs===0)label='No check-in yet';
    else {
      const age=now-device.lastSeenUnixMs;
      connected=age>=-5000&&age<120000;
      label=connected?'Connected':'Offline';
    }
  }
  const lastSeen=valid&&device.lastSeenUnixMs>0?new Date(device.lastSeenUnixMs).toLocaleString():undefined;
  return <span className={`client-connection ${connected?'online':label==='Checking connection…'?'checking':'offline'}`}
    title={(lastSeen?`${label}. Last check-in: ${lastSeen}`:label)+(device?.connectionExpiresAtUnixMs?`. Allowed until: ${new Date(device.connectionExpiresAtUnixMs).toLocaleString()}`:'')}>
    <span className="connection-dot" aria-hidden="true"/>{label}
  </span>;
}
