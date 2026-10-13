// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useRef,useState} from 'react';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {EditorDialog} from './EditorDialog';
import type {PendingWorkloadBinding} from './pendingContracts';

/** Badge for a delegated binding imported for a mapped or placeholder user, and the Super Admin reauthorization that issues its new agent token once. */
export function WorkloadReauthorization({client,binding,allowed,done}:{client:ControlClient;binding:PendingWorkloadBinding;allowed:boolean;done:()=>void}){
  const [confirming,setConfirming]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState<unknown>();
  const [token,setToken]=useState('');const [copied,setCopied]=useState('');const key=useRef('');
  if(!binding.needsReauthorization&&!token)return null;
  async function reauthorize(){
    if(busy)return;setBusy(true);setError(undefined);if(!key.current)key.current=crypto.randomUUID();
    try{const result=await client.reauthorizeWorkload(binding.id,key.current);key.current='';
      if(typeof result?.token!=='string'||!result.token||result.token.length>4096)throw new Error('Invalid token');
      setToken(result.token);setConfirming(false);}
    catch(failure){setError(failure);}finally{setBusy(false);}
  }
  function close(){setToken('');setCopied('');setConfirming(false);setError(undefined);done();}
  async function copy(){try{await navigator.clipboard.writeText(token);setCopied('Token copied.');}catch{setCopied('Select the token and copy it.');}}
  return <>{binding.needsReauthorization&&<span className="badge warning">Needs reauthorization</span>}
    {binding.needsReauthorization&&allowed&&<button type="button" disabled={busy} onClick={()=>{setConfirming(true);setError(undefined);}}>Reauthorize {binding.id}</button>}
    {(confirming||token)&&<EditorDialog title={token?'New agent token':`Reauthorize ${binding.id}`} close={close} busy={busy} closeLabel="Close reauthorization"><section className="guidance">
      {token?<><p role="status">Reauthorized. Copy this token now and hand it to the agent’s owner; it is shown only once and any earlier token for this binding no longer works.</p>
        <label htmlFor="reauthorized-token">Agent token</label><input id="reauthorized-token" readOnly value={token} spellCheck={false} onFocus={event=>event.currentTarget.select()}/>
        <div className="actions"><button type="button" onClick={()=>void copy()}>Copy token</button><button type="button" className="primary" onClick={close}>Done</button></div>{copied&&<p role="status">{copied}</p>}</>
      :<><p>This delegated binding was imported for a mapped or placeholder user and stays disabled until it is reauthorized. Reauthorizing generates a new agent token, binds it to the user’s current session, enables the binding and waits for the Gateway to load it.</p>
        {Boolean(error)&&<Failure error={error}/>}
        <div className="actions"><button type="button" className="primary" disabled={busy} onClick={()=>void reauthorize()}>{busy?'Reauthorizing…':'Generate new token'}</button><button type="button" disabled={busy} onClick={()=>setConfirming(false)}>Cancel</button></div></>}
    </section></EditorDialog>}</>;
}
