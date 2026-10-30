// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import {pendingOperations,type LoginHints as Hints} from './pendingContracts';

const account=(value:unknown):value is Hints['accounts'][number]=>Boolean(value)&&typeof value==='object'
  &&typeof (value as {username?:unknown}).username==='string'&&/^[a-zA-Z0-9][a-zA-Z0-9._@-]{0,127}$/.test((value as {username:string}).username)
  &&typeof (value as {password?:unknown}).password==='string'&&(value as {password:string}).password.length>0&&(value as {password:string}).password.length<=256;
/** The shared agent token is optional; a malformed one is dropped and the account still shows. */
const agentToken=(value:unknown):string|undefined=>typeof value==='string'&&/^[A-Za-z0-9_-]{16,512}$/.test(value)?value:undefined;
/** Accepts only the bounded public shape; anything else shows no hints. */
export function parseLoginHints(value:unknown):Hints|undefined{
  if(!value||typeof value!=='object')return undefined;
  const body=value as {accounts?:unknown;refreshAt?:unknown};
  const accounts=Array.isArray(body.accounts)?body.accounts.filter(account).slice(0,20).map(({username,password,agentToken:token})=>{
    const valid=agentToken(token);return valid?{username,password,agentToken:valid}:{username,password};}):[];
  const refreshAt=typeof body.refreshAt==='string'&&body.refreshAt.length<=64&&Number.isFinite(Date.parse(body.refreshAt))?body.refreshAt:null;
  return {accounts,refreshAt};
}

/** Quickstart demo accounts marked showOnLogin, with the shared agent token when there is one, and the next demo reset. Administrator and reviewer passwords are never listed. */
export function LoginHints({use}:{use:(username:string,password:string)=>void}){
  const [hints,setHints]=useState<Hints>();const [copied,setCopied]=useState('');
  useEffect(()=>{const abort=new AbortController();
    fetch(pendingOperations.quickstartLoginHints.path,{credentials:'omit',cache:'no-store',redirect:'error',signal:abort.signal})
      .then(response=>response.ok?response.json():undefined).then(body=>{if(!abort.signal.aborted)setHints(parseLoginHints(body));}).catch(()=>{});
    return()=>abort.abort();},[]);
  async function copy(text:string,label:string){
    try{await navigator.clipboard.writeText(text);setCopied(`${label} copied.`);}catch{setCopied(`Select the ${label.toLowerCase()} and copy it.`);}
  }
  if(!hints||(!hints.accounts.length&&!hints.refreshAt))return null;
  return <section className="login-hints" aria-label="Demo sign-in">
    {hints.refreshAt&&<p role="status" className="notice">This demo resets at {new Date(hints.refreshAt).toLocaleString()}.</p>}
    {hints.accounts.length>0&&<><h2>Demo accounts</h2>
      <p className="hint">Anyone can use these shared accounts. Their passwords{hints.accounts.some(value=>value.agentToken)?' and agent tokens':''} change at every reset.</p>
      <ul className="demo-accounts">{hints.accounts.map(value=><li key={value.username} className="demo-account">
        <dl>
          <dt>Username</dt><dd><code>{value.username}</code></dd>
          <dd><button type="button" onClick={()=>void copy(value.username,'Username')} aria-label={`Copy username ${value.username}`}>Copy</button></dd>
          <dt>Password</dt><dd><code>{value.password}</code></dd>
          <dd><button type="button" onClick={()=>void copy(value.password,'Password')} aria-label={`Copy password for ${value.username}`}>Copy</button></dd>
          {value.agentToken&&<>
            <dt>Agent token</dt><dd><code>{value.agentToken}</code></dd>
            <dd><button type="button" onClick={()=>void copy(value.agentToken!,'Agent token')} aria-label={`Copy agent token for ${value.username}`}>Copy</button></dd>
          </>}
        </dl>
        <button type="button" className="primary" onClick={()=>use(value.username,value.password)}>Use {value.username}</button>
      </li>)}</ul>
      {copied&&<p role="status">{copied}</p>}</>}
  </section>;
}
