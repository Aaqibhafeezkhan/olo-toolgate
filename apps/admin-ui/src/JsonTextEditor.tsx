// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {useState} from 'react';

/** Phase 1 JSON buffer: apply explicitly; invalid/stale text never overwrites the draft. */
export function JsonTextEditor({label,value,disabled=false,apply}:{label:string;value:unknown;disabled?:boolean;apply:(value:Record<string,unknown>)=>void}){
  const original=JSON.stringify(value,null,2);
  const [base,setBase]=useState(original),[text,setText]=useState(original),[error,setError]=useState('');
  const stale=base!==original;
  function parse(){
    if(new TextEncoder().encode(text).length>262144)throw Error('JSON exceeds the profile size limit.');
    const next:unknown=JSON.parse(text);
    if(!next||typeof next!=='object'||Array.isArray(next))throw Error('Enter a JSON object.');
    return next as Record<string,unknown>;
  }
  return <section className="workflow-profile-editor"><label>{label}<textarea rows={16} spellCheck={false} disabled={disabled} value={text} aria-invalid={Boolean(error)} onChange={event=>{setText(event.target.value);setError('');}}/></label>
    {error&&<p role="alert">{error}</p>}{stale&&<p role="alert">The working draft changed. Reload it before applying this JSON.</p>}
    <button type="button" disabled={disabled||stale} onClick={()=>{try{const next=parse();apply(next);setBase(JSON.stringify(next,null,2));setText(JSON.stringify(next,null,2));setError('');}catch{setError('Enter a valid JSON object within the size limit. Required profile fields must remain present.');}}}>Apply {label} to draft</button>
    <button type="button" disabled={disabled} onClick={()=>{setBase(original);setText(original);setError('');}}>Reload {label}</button>
    <button type="button" disabled={disabled} onClick={()=>{try{setText(JSON.stringify(parse(),null,2));setError('');}catch{setError('Enter a valid JSON object within the size limit.');}}}>Format {label}</button>
    <p>Text changes stay in this editor until applied. Stage, review and publish the profile to activate them. Control validates the complete configuration.</p>
  </section>;
}
