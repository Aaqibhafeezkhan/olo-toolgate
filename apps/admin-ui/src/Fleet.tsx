// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Table,ActionButton} from './Table';
import { useEffect, useState, type FormEvent } from 'react';
import type { FleetPackageRelease, FleetRolloutStatus } from '@olo-labs/toolgate-contracts';
import { ControlClient } from './api';
import { Failure } from './Failure';

/** Control owns assignment validation, compatibility and aggregation. The UI renders authoritative observations. */
export function Fleet({client}:{client:ControlClient}){
  const [releases,setReleases]=useState<readonly FleetPackageRelease[]>([]),[rollouts,setRollouts]=useState<readonly FleetRolloutStatus[]>();
  const [error,setError]=useState<unknown>(),[busy,setBusy]=useState(false),[attempt,setAttempt]=useState(0);
  const [selected,setSelected]=useState(''),[devices,setDevices]=useState(''),[percentage,setPercentage]=useState('10'),[presence,setPresence]=useState(true),[publication,setPublication]=useState('');
  useEffect(()=>{const abort=new AbortController();setError(undefined);setRollouts(undefined);
    Promise.all([client.fleetReleases(undefined,abort.signal),client.fleetRollouts(undefined,abort.signal)]).then(([r,s])=>{if(!abort.signal.aborted){setReleases(r.items);setRollouts(s.items);}}).catch(e=>{if(!abort.signal.aborted)setError(e);});return ()=>abort.abort();
  },[client,attempt]);
  async function mutate(action:()=>Promise<unknown>){if(busy)return;setBusy(true);setError(undefined);try{await action();setAttempt(a=>a+1);}catch(e){setError(e);}finally{setBusy(false);}}
  function assign(event:FormEvent){event.preventDefault();const release=releases.find(r=>`${r.packageId}@${r.version}`===selected);if(!release)return;
    void mutate(()=>client.assignPackage({id:crypto.randomUUID(),packageId:release.packageId,version:release.version,deviceIds:devices.split(/[,\s]+/).filter(Boolean),desiredPresence:presence,percentage:Number(percentage)},crypto.randomUUID()));
  }
  function publish(event:FormEvent){event.preventDefault();void mutate(()=>client.publishRelease(JSON.parse(publication) as FleetPackageRelease,crypto.randomUUID()));}
  return <><p className="eyebrow">Endpoint fleet</p><h1>Package deployments</h1><p className="intro">Assign reviewed releases, advance canaries, and observe client reconciliation. Rollback assigns an earlier release with a new generation.</p>
    {Boolean(error)&&<Failure error={error} retry={()=>setAttempt(a=>a+1)}/>}
    <button disabled={busy} onClick={()=>setAttempt(a=>a+1)}>Refresh fleet status</button>
    {!rollouts?<p role="status">Loading fleet state…</p>:rollouts.length===0?<p>No deployments yet.</p>:<div className="table-wrap"><Table><caption>Recent deployments (first 32; refresh to observe progress)</caption><thead><tr><th scope="col">Release</th><th scope="col">Rollout</th><th scope="col">Ready</th><th scope="col">Failed</th><th scope="col">Offline</th><th scope="col">Waiting</th><th scope="col">Pending</th><th scope="col">Superseded</th><th scope="col">Action</th></tr></thead><tbody>{rollouts.map(s=><tr key={s.rollout.id}><th scope="row">{s.rollout.packageId}@{s.rollout.version}</th><td>{s.rollout.desiredPresence?'Install':'Uninstall'} · {s.rollout.percentage}%</td><td>{s.ready}</td><td>{s.failed}</td><td>{s.offline}</td><td>{s.waiting}</td><td>{s.pending}</td><td>{s.superseded}</td><td>{s.rollout.percentage<100&&<ActionButton icon="advance" label={'Advance '+s.rollout.id+' to 100%'} disabled={busy} onClick={()=>void mutate(()=>client.advanceRollout(s.rollout.id,s.rollout.revision,100,crypto.randomUUID()))}/>}</td></tr>)}</tbody></Table></div>}
    <section className="guidance"><h2>Assign a release</h2><form onSubmit={assign}><label htmlFor="fleet-release">Approved release</label><select id="fleet-release" required value={selected} onChange={e=>setSelected(e.target.value)}><option value="">Choose a release</option>{releases.map(r=><option key={`${r.packageId}@${r.version}`}>{r.packageId}@{r.version}</option>)}</select><label htmlFor="fleet-devices">Enrolled device IDs, separated by commas</label><textarea id="fleet-devices" required maxLength={32768} value={devices} onChange={e=>setDevices(e.target.value)}/><label htmlFor="fleet-percent">Initial rollout percentage</label><input id="fleet-percent" type="number" min="1" max="100" required value={percentage} onChange={e=>setPercentage(e.target.value)}/><label><input type="checkbox" checked={presence} onChange={e=>setPresence(e.target.checked)}/>Install this release (clear to uninstall)</label><button disabled={busy||!releases.length}>Create deployment</button></form></section>
    <section className="guidance"><h2>Publish a signed release</h2><p>Paste a release envelope signed by an approved release authority. Control verifies its identity, hash and signature.</p><form onSubmit={publish}><label htmlFor="fleet-publication">Signed release JSON</label><textarea id="fleet-publication" required maxLength={65536} value={publication} onChange={e=>setPublication(e.target.value)}/><button disabled={busy}>Publish release</button></form></section>
  </>;
}
