// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import {ConfigurationPending,ControlClient} from './api';
import type {DirectoryKind,DirectoryRecords} from './operations.generated';
import {Failure} from './Failure';

export const protectedRecord=(kind:DirectoryKind,id:string)=>({teams:'team-default',agentGroups:'default-agents',toolGroups:'default-tools',deviceGroups:'default-devices'} as Partial<Record<DirectoryKind,string>>)[kind]===id;
type RecordValue=DirectoryRecords[DirectoryKind];
type Report={id:string;error?:unknown;message?:string};
export function useRecordActions(client:ControlClient,kind:DirectoryKind,refresh:()=>void){
  const [busy,setBusy]=useState(false),[reports,setReports]=useState<Report[]>([]),[deleting,setDeleting]=useState<RecordValue[]>([]);
  const locked=useRef(false),alive=useRef(true),pending=useRef(new Map<string,{key:string;record:RecordValue}>());
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[client,kind]);
  async function run(records:RecordValue[],operation:'enable'|'disable'|'delete'){
    if(locked.current)return;locked.current=true;setBusy(true);setReports([]);setDeleting([]);
    const results:Report[]=[];
    try{for(const record of records){
      if(operation!=='delete'&&record.enabled===(operation==='enable'))continue;
      if(protectedRecord(kind,record.id)&&(operation==='delete'||operation==='disable'))continue;
      const body=JSON.stringify({operation,record});
      let request=pending.current.get(body);
      if(!request){
        const next={...record,enabled:operation==='enable'};
        if('sessionEpoch' in next){next.sessionEpoch=Number(next.sessionEpoch)+1;if('sessionsValidAfterUnixMs' in next)next.sessionsValidAfterUnixMs=Math.floor(Date.now()/1000)*1000+1000;}
        if('credentialEpoch' in next)next.credentialEpoch=Number(next.credentialEpoch)+1;
        request={key:crypto.randomUUID(),record:next};pending.current.set(body,request);
      }
      try{
        if(operation==='delete')await client.deleteRecord(kind,record,request.key);
        else{
          if(kind==='devices')await client.setDeviceEnabled(record.id,{expectedRevision:record.revision,enabled:request.record.enabled},request.key);
          else await client.saveRecord(kind,request.record,true,request.key);
        }
        pending.current.delete(body);results.push({id:record.id,message:operation==='delete'?'Deleted. Fresh onboarding requires a new identifier.':operation==='enable'?'Enabled.':'Disabled.'});
      }catch(error){if(error instanceof ConfigurationPending)pending.current.delete(body);results.push({id:record.id,error});}
      if(!alive.current)break;
    }}finally{locked.current=false;if(alive.current){setReports(results);setBusy(false);refresh();}}
  }
  const feedback=<>{reports.length>0&&<div className="operation-results" aria-label="Operation results">{reports.map(report=><div key={report.id}><strong>{report.id}</strong>{report.error?<Failure error={report.error}/>:<p role="status">{report.message}</p>}</div>)}</div>}{deleting.length>0&&<section className="delete-area" aria-label="Confirm deletion"><h2>Delete {deleting.length} {deleting.length===1?'record':'records'}?</h2><ul>{deleting.map(record=><li key={record.id}>{record.name} ({record.id})</li>)}</ul><p>Delete the active records completely. Existing credentials and identifiers cannot restore access. Fresh onboarding, group assignment and required approvals must be completed again. Remove dependent references first. Required default groups cannot be deleted.</p><button className="danger" disabled={busy} onClick={()=>void run(deleting,'delete')}>Confirm delete</button><button disabled={busy} onClick={()=>setDeleting([])}>Cancel delete</button></section>}</>;
  return {busy,run,requestDelete:setDeleting,feedback};
}
