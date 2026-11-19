// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import type {WorkflowProfileStatus} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from './api';
import {Failure} from './Failure';

/** Persistent current-revision diagnostics, never a substitute for backend admission. */
export function BotIssues({client,profileId,status,dirty,busy,refreshKey,onStatus,actionError}:{client:ControlClient;profileId?:string;status?:WorkflowProfileStatus;dirty:boolean;busy:boolean;refreshKey:number;onStatus:(status:WorkflowProfileStatus)=>void;actionError?:unknown}){
  const [report,setReport]=useState<{identity:string;issues:readonly string[];failed:boolean}>();
  const callback=useRef(onStatus);callback.current=onStatus;
  const digest=status?.profileDigest,identity=profileId&&digest?`${profileId}/${digest}`:'';
  useEffect(()=>{
    if(!profileId||!digest||dirty||busy)return;
    const abort=new AbortController();let timer:ReturnType<typeof setTimeout>|undefined;
    async function check(){
      try{
        const [diagnostics,current]=await Promise.all([client.workflowProfileDiagnostics(profileId!,digest!,abort.signal),client.workflowProfileStatus(profileId!,digest!,abort.signal)]);
        if(abort.signal.aborted)return;
        if(diagnostics.profileId!==profileId||diagnostics.profileDigest!==digest)throw new ApiError(502,'INVALID_RESPONSE');
        setReport(previous=>previous?.identity===identity&&!previous.failed&&JSON.stringify(previous.issues)===JSON.stringify(diagnostics.blockers)?previous:{identity,issues:diagnostics.blockers,failed:false});
        callback.current(current);
      }catch{if(!abort.signal.aborted)setReport(previous=>({identity,issues:previous?.identity===identity?previous.issues:[],failed:true}));}
      finally{if(!abort.signal.aborted)timer=setTimeout(()=>void check(),5000);}
    }
    void check();return()=>{abort.abort();if(timer)clearTimeout(timer);};
  },[client,profileId,digest,identity,dirty,busy,refreshKey]);
  const current=report?.identity===identity?report:undefined;
  const issues=dirty?['Stage the edited bot revision before review and publication.']:current?.issues??(status?.dependenciesReady===false?['Checking this revision for publication blockers…']:[]);
  const unavailable=Boolean(profileId&&!dirty&&(!digest||current?.failed));
  if(!issues.length&&!unavailable&&actionError===undefined)return null;
  return <section className="notice error bot-issues" aria-label="Bot issues" aria-live="polite"><h2>Bot issues</h2>
    <p>Resolve these issues before publishing or running this bot. This list stays visible and checks readiness every five seconds.</p>
    <ul>{actionError!==undefined&&<li><Failure error={actionError}/></li>}{issues.map(issue=><li key={issue}>{issue}</li>)}
      {unavailable&&<li>Unable to check this bot's current readiness. Refresh its lifecycle status or reopen the revision. Previously reported issues remain until a successful check.</li>}
    </ul>
  </section>;
}
