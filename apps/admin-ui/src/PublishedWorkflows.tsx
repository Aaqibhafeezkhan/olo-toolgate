// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import type {ControlClient} from './api';
import {Failure} from './Failure';
import {type WorkflowPage,workflowPages} from './WorkflowManagement';

const sections = {
  workflows:'Workflows',workflowGroups:'Workflow groups',agentDefinitions:'Agent configuration',
  modelGroups:'Model groups',modelDeployments:'Model deployments',dispatchBindings:'Trigger mappings',
} as const;
const pages:Partial<Record<WorkflowPage,readonly (keyof typeof sections)[]>> = {
  workflows:['workflows'],workflowGroups:['workflowGroups'],'workflow-models':['modelGroups','modelDeployments'],
  'workflow-dispatch':['dispatchBindings'],'agent-configuration':['agentDefinitions','modelGroups','modelDeployments'],
  'agent-workflow-mappings':['workflows'],
};

function Properties({value}:{value:unknown}) {
  if(Array.isArray(value)) return value.length ? <ul>{value.map((item,i)=><li key={i}><Properties value={item}/></li>)}</ul> : <>None</>;
  if(value && typeof value==='object') return <dl>{Object.entries(value).map(([key,item])=><div key={key}>
    <dt>{key.replace(/([a-z])([A-Z])/g,'$1 $2').replace(/^./,c=>c.toUpperCase())}</dt><dd><Properties value={item}/></dd>
  </div>)}</dl>;
  return <>{typeof value==='boolean'?(value?'Yes':'No'):String(value??'—')}</>;
}

export function PublishedWorkflows({client,page}:{client:ControlClient;page?:WorkflowPage}) {
  type Profile = Awaited<ReturnType<ControlClient['publishedWorkflows']>>[number];
  const [catalog,setCatalog]=useState<readonly Profile[]>();
  const [selected,setSelected]=useState<Profile>();
  const [document,setDocument]=useState<Record<string,unknown[]>>();
  const [error,setError]=useState<unknown>();
  const [loading,setLoading]=useState(false);
  const [hasMore,setHasMore]=useState(false);
  useEffect(()=>{
    const abort=new AbortController();
    setCatalog(undefined);setDocument(undefined);setSelected(undefined);setError(undefined);
    void client.publishedWorkflows(undefined,abort.signal).then(items=>{
      if(!abort.signal.aborted){setCatalog(items);setHasMore(items.length===50);setSelected(items[0]);}
    }).catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    return ()=>abort.abort();
  },[client]);
  useEffect(()=>{
    if(!selected)return;
    const abort=new AbortController();
    setDocument(undefined);
    void client.publishedWorkflow(selected.profileId,selected.digest,abort.signal).then(result=>{
      if(!abort.signal.aborted)setDocument(result);
    }).catch(failure=>{if(!abort.signal.aborted)setError(failure);});
    return ()=>abort.abort();
  },[client,selected]);
  const Heading=page?'h1':'h2';
  const visible=page?pages[page]??Object.keys(sections) as (keyof typeof sections)[]:Object.keys(sections) as (keyof typeof sections)[];
  return <section className="guidance"><Heading>{page?workflowPages[page]:'Published workflows and agents'}</Heading>
    <p>View only. Private profile fields are available to Super Admins.</p>
    {error!==undefined&&<Failure error={error}/>}
    {!catalog&&!error&&<p role="status">Loading published profiles…</p>}
    {catalog?.length===0&&<p>No workflow profiles have been published.</p>}
    {catalog&&catalog.length>0&&<label>Published profile <select value={selected?.profileId??''} onChange={event=>{
      setError(undefined);setDocument(undefined);setSelected(catalog.find(item=>item.profileId===event.target.value));
    }}>{catalog.map(item=><option key={item.profileId} value={item.profileId}>{item.profileId}</option>)}</select></label>}
    {hasMore&&catalog&&<button disabled={loading} onClick={()=>{
      setLoading(true);setError(undefined);
      void client.publishedWorkflows(catalog.at(-1)?.profileId).then(items=>{
        setCatalog([...catalog,...items]);setHasMore(items.length===50);
      }).catch(setError).finally(()=>setLoading(false));
    }}>More published profiles</button>}
    {selected&&<p>Generation {selected.generation} · <code>{selected.digest}</code></p>}
    {selected&&!document&&!error&&<p role="status">Loading workflow configuration…</p>}
    {document&&visible.map(key=><details className="guidance" key={key} open={Boolean(page)}>
      <summary>{sections[key]}</summary><Properties value={document[key]??[]}/>
    </details>)}
  </section>;
}
