// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import type {EnterpriseOperationHistoryPage} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {Failure} from './Failure';

export function OperationHistory({client,id,onLoaded}:{client:ControlClient;id:string;onLoaded?:(page:EnterpriseOperationHistoryPage)=>void}){
 const loaded=useRef(onLoaded);useEffect(()=>{loaded.current=onLoaded;},[onLoaded]);
 const [page,setPage]=useState<EnterpriseOperationHistoryPage>();const [error,setError]=useState<unknown>();
 const [cursor,setCursor]=useState<string>();const [refresh,setRefresh]=useState(0);
 useEffect(()=>{const abort=new AbortController();setPage(undefined);setError(undefined);
  void client.operationHistory(id,cursor,abort.signal).then(value=>{if(!abort.signal.aborted){setPage(value);loaded.current?.(value);}})
   .catch(failure=>{if(!abort.signal.aborted)setError(failure);});return()=>abort.abort();
 },[client,id,cursor,refresh]);
 const reload=()=>{setCursor(undefined);setRefresh(n=>n+1);};
 return <section aria-label="Verified operation history"><h3>Business outcome and history</h3>
  <p>Worker reports and Control evidence are separate. Unknown outcomes do not permit an automatic retry.</p>
  {Boolean(error)&&<Failure error={error} retry={reload}/>}
  {!page&&!error&&<p role="status">Loading operation history…</p>}
  {page&&<><dl><dt>Business outcome</dt><dd>{page.operation.state}</dd>
   <dt>Evidence grade</dt><dd>{page.operation.grade}</dd><dt>Evidence revision</dt><dd>{page.operation.revision}</dd>
   <dt>Automatic retry</dt><dd>{page.operation.retryable?'Allowed':'Disabled'}</dd></dl>
   {page.truncated&&<p role="status">History is incomplete and cannot establish final completion.</p>}
   <h4>Attempts</h4><ul>{page.attempts.map(a=><li key={a.attemptId}>{a.attemptId}: {a.state}; {a.grade}; {a.settled?'settled':'unsettled'}</li>)}</ul>
   <h4>Effect generations</h4><ul>{page.effects.map(e=><li key={e.effectId+':'+e.generation}>{e.effectId}, generation {e.generation}: {e.state}; {e.grade}</li>)}</ul>
   {page.next&&<button onClick={()=>setCursor(page.next)}>More effect history</button>}</>}
  <button onClick={reload}>Refresh history</button>
 </section>;
}
