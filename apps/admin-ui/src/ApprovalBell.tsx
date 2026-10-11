// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import {ApiError,ControlClient} from './api';

export function ApprovalBell({client,administrator,superAdmin,userId}:{client:ControlClient;administrator:boolean;superAdmin:boolean;userId?:string}){
  const [pending,setPending]=useState<string[]>([]),[unavailable,setUnavailable]=useState(false),[open,setOpen]=useState(false);
  useEffect(()=>{
    const abort=new AbortController();let loading=false,refreshPending=false;
    async function load(){
      if(abort.signal.aborted)return;
      if(loading){refreshPending=true;return;}loading=true;refreshPending=false;
      const queues:string[]=[];let failed=false;
      try{
        let cursor:string|undefined;const seen=new Set<string>();
        do{const page=await client.approvals(cursor,abort.signal);if(page.items.some(a=>a.state==='PENDING'&&a.expiresAtUnixMs>Date.now())){queues.push('approvals');break;}cursor=page.nextCursor;if(cursor&&seen.has(cursor))throw new Error('Repeated cursor');if(cursor)seen.add(cursor);}while(cursor&&seen.size<20);
      }catch(error){if(!(error instanceof ApiError&&error.status===403))failed=true;}
      if(administrator){
        try{
          let after:string|undefined;
          for(let index=0;index<10;index++){
            const page=await client.configurationChanges(after,abort.signal);
            if(page.items.some(c=>c.state==='PENDING'&&c.expiresAtUnixMs>Date.now()&&(superAdmin||c.requesterUserId!==userId&&!c.reviews.some(r=>r.reviewerUserId===userId)))){queues.push('config-reviews');break;}
            if(page.items.length<100)break;const next=page.items.at(-1)?.id;if(!next||next===after)break;after=next;
          }
        }catch(error){if(!(error instanceof ApiError&&error.status===403))failed=true;}
        try{const page=await client.managedDevices(abort.signal);if(page.items.some(row=>!row.systemExecutor&&(row.enrollment?.state==='PENDING'&&row.enrollment.expiresAtUnixMs>Date.now()||row.endpointDevice?.state==='ACTIVE'&&(row.endpointDevice.connectionApproved===false||row.endpointDevice.connectionExpiresAtUnixMs!==undefined&&row.endpointDevice.connectionExpiresAtUnixMs<=Date.now()))))queues.push('devices');}
        catch(error){if(!(error instanceof ApiError&&error.status===403))failed=true;}
      }
      if(!abort.signal.aborted){setPending(queues);setUnavailable(failed);}loading=false;
      if(refreshPending&&!abort.signal.aborted)void load();
    }
    void load();const timer=setInterval(()=>void load(),15000);const refresh=()=>void load();window.addEventListener('toolgate:approval-change',refresh);
    return()=>{abort.abort();clearInterval(timer);window.removeEventListener('toolgate:approval-change',refresh);};
  },[client,administrator,superAdmin,userId]);
  const label=pending.length?'Pending approvals':unavailable?'Approval status unavailable':'No pending approvals';
  return <div className="approval-notifications"><button className={`approval-bell ${pending.length?'pending':''}`} title={label} aria-label={label} aria-expanded={open} onClick={()=>setOpen(!open)}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5z M10 20h4 M12 2v2"/></svg></button>{open&&<div className="approval-popover" role="region" aria-label="Approval notifications"><p role="status">{label}</p>{pending.map(route=><a key={route} href={`#${route}`} onClick={()=>setOpen(false)}>{route==='approvals'?'Operation approvals':route==='config-reviews'?'Configuration reviews':'Device approvals'}</a>)}{unavailable&&pending.length>0&&<small>Some approval queues are unavailable.</small>}</div>}</div>;
}
