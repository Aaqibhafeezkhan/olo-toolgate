// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState, type FormEvent } from 'react';
import { ControlClient } from './api';
import { Failure } from './Failure';

/** Quickstart's encrypted local vault. Tools run only from installed packages; use Test a tool to run one. */
export function QuickstartTools({client}:{client:ControlClient}) {
  const [error,setError]=useState<unknown>(); const [busy,setBusy]=useState(false); const [stored,setStored]=useState(false);
  const [name,setName]=useState(''); const [secret,setSecret]=useState(''); const [names,setNames]=useState<string[]>([]);
  useEffect(()=>{let alive=true;client.quickstart<{names:string[]}>('vault').then(vault=>{if(alive)setNames(vault.names);}).catch(failure=>{if(alive)setError(failure);});return()=>{alive=false;};},[client]);
  async function store(event:FormEvent){event.preventDefault();if(busy)return;setBusy(true);setError(undefined);setStored(false);const value=secret;setSecret('');
    try{await client.quickstart('vault',{name,value});setNames((await client.quickstart<{names:string[]}>('vault')).names);setStored(true);}catch(failure){setError(failure);}finally{setBusy(false);}}
  return <><p className="eyebrow">Quickstart · Non-HA</p><h1>Local vault</h1><p>Tools run only from verified installed packages. To try one, open <a className="inline-link" href="#test">Test a tool</a>; a fresh installation has no runtime grants.</p>
    {Boolean(error)&&<Failure error={error}/>}
    <section><h2>Encrypted local vault</h2><p>Secrets stay encrypted in /data. This console lists names only; values are never exported.</p><p>Stored references: {names.join(', ')||'None'}</p>
    {stored&&<p role="status">Secret stored.</p>}
    <form className="user-form" onSubmit={store}><label htmlFor="vault-name">Secret name</label><input id="vault-name" required maxLength={128} value={name} onChange={e=>setName(e.target.value)} disabled={busy}/><label htmlFor="vault-secret">Secret value</label><input id="vault-secret" type="password" autoComplete="off" required maxLength={32768} value={secret} onChange={e=>setSecret(e.target.value)} disabled={busy}/><button disabled={busy}>Store encrypted secret</button></form></section></>;
}
