// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState,useId} from 'react';
import {ControlClient} from './api';
import type {DirectoryKind,DirectoryRecords} from './operations.generated';
import {Failure} from './Failure';

/** Options are fetched only when opened, including every page and retained disabled selections. */
export function ScopePicker({client,kind,label,value,onChange,disabled=false,single=false}:{client:ControlClient;kind:DirectoryKind;label:string;value:readonly string[];onChange:(ids:string[])=>void;disabled?:boolean;single?:boolean}) {
  const [open,setOpen]=useState(false);const [draft,setDraft]=useState<string[]>([]);const [rows,setRows]=useState<DirectoryRecords[DirectoryKind][]>();
  const [search,setSearch]=useState('');const [error,setError]=useState<unknown>();const [attempt,setAttempt]=useState(0);
  const dialog=useRef<HTMLDialogElement>(null);const trigger=useRef<HTMLButtonElement>(null);const title=useId();
  useEffect(()=>{if(!open)return;const abort=new AbortController();setRows(undefined);setError(undefined);
    client.all(kind,abort.signal).then(items=>{if(!abort.signal.aborted)setRows(items);}).catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    if(dialog.current?.showModal)dialog.current.showModal();else dialog.current?.setAttribute('open','');return()=>abort.abort();
  },[open,client,kind,attempt]);
  const close=()=>{if(dialog.current?.open&&dialog.current.close)dialog.current.close();setOpen(false);trigger.current?.focus({preventScroll:true});};
  const options=rows?[...rows,...draft.filter(id=>!rows.some(row=>row.id===id)).map(id=>({id,name:'Unavailable selection',enabled:false,revision:1}))]:[];
  return <div className="scope-field"><span>{label}</span><p className="hint">{value.length?value.join(', '):'None selected'}</p>
    <button ref={trigger} type="button" disabled={disabled} onClick={()=>{setDraft([...value]);setSearch('');setOpen(true);}}>Choose {label.toLowerCase()}</button>
    {open&&<dialog ref={dialog} aria-labelledby={title} onCancel={event=>{event.preventDefault();event.stopPropagation();close();}} className="scope-dialog"><h2 id={title}>{label}</h2>
      <label>Search<input autoFocus value={search} onChange={event=>setSearch(event.target.value)}/></label>
      {error!==undefined?<Failure error={error} retry={()=>setAttempt(attempt+1)}/>:!rows?<p role="status">Loading options…</p>:<fieldset className="scope-options"><legend>{single?'Choose one':'Choose any that apply'}</legend>
        {options.filter(row=>`${row.name} ${row.id}`.toLowerCase().includes(search.toLowerCase())).map(row=><label className="scope-option" key={row.id}><input type={single?'radio':'checkbox'} name={single?title:undefined} checked={draft.includes(row.id)} disabled={!row.enabled&&!draft.includes(row.id)} onChange={event=>setDraft(single?[row.id]:event.target.checked?[...draft,row.id]:draft.filter(id=>id!==row.id))}/><span>{row.name}<small>{row.id}{!row.enabled?' · Disabled or unavailable':''}</small></span></label>)}
        {options.length===0&&<p>No records available. Create them in the directory first.</p>}</fieldset>}
      <div className="actions"><button type="button" className="primary" disabled={!rows||Boolean(error)||single&&draft.length!==1} onClick={()=>{onChange([...draft].sort());close();}}>Apply selection</button><button type="button" onClick={close}>Cancel</button></div>
    </dialog>}
  </div>;
}
