// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState,type FormEvent} from 'react';
import type {EndpointManagedDevice} from '@olo-labs/toolgate-contracts';
import type {TriggerAssignment,TriggerDefinitionRecord} from '@olo-labs/toolgate-contracts/v2';
import {ControlClient} from './api';
import {Failure} from './Failure';

import {testDefaults} from './testDefaults';

function isObject(value:unknown):value is Record<string,unknown>{
  return value!==null&&typeof value==='object'&&!Array.isArray(value);
}

/** Control supplies device capabilities and enforces ownership again on every mutation. */
export function Triggers({client}:{client:ControlClient}){
  const [devices,setDevices]=useState<readonly EndpointManagedDevice[]>([]);
  const [items,setItems]=useState<readonly TriggerAssignment[]>([]);
  const [device,setDevice]=useState('');
  const [definitions,setDefinitions]=useState<TriggerDefinitionRecord[]>([]);
  const [selected,setSelected]=useState('');
  const [config,setConfig]=useState<Record<string,string>>({});
  const definition=definitions.find(item=>definitionKey(item)===selected);
  const schema=definition?.descriptor.configSchema;
  const properties=isObject(schema?.properties)?schema.properties:{};
  const required=Array.isArray(schema?.required)?schema.required:[];
  const [name,setName]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>();
  const [saved,setSaved]=useState('');
  useEffect(()=>{const abort=new AbortController();void(async()=>{
    try{
      const page=await client.managedDevices(abort.signal);if(!abort.signal.aborted)setDevices(page.items);
      const assignments:TriggerAssignment[]=[];let cursor:string|undefined;const seen=new Set<string>();
      do{const result=await client.triggerAssignments(cursor,abort.signal);assignments.push(...result.items);cursor=result.nextPageToken;if(cursor&&seen.has(cursor))throw new Error('Repeated page cursor');if(cursor)seen.add(cursor);}while(cursor);
      if(!abort.signal.aborted)setItems(assignments);
    }catch(failure){if(!abort.signal.aborted)setError(failure);}
  })();return()=>abort.abort();},[client]);
  const selectable=devices.filter(row=>row.endpointDevice?.state==='ACTIVE'&&row.directoryDevice?.enabled&&row.permissions?.includes('trigger.manage'));
  useEffect(()=>{
    setDefinitions([]);setSelected('');setConfig({});
    if(!device)return;
    const abort=new AbortController();
    void(async()=>{
      try{
        const items:TriggerDefinitionRecord[]=[];
        let cursor:string|undefined;
        const seen=new Set<string>();
        do{
          const page=await client.triggerDefinitions({deviceId:device,pageToken:cursor,signal:abort.signal});
          items.push(...page.items);cursor=page.nextPageToken;
          if(cursor&&seen.has(cursor))throw new Error('Repeated page cursor');
          if(cursor)seen.add(cursor);
        }while(cursor);
        if(!abort.signal.aborted)setDefinitions(items);
      }catch(failure){if(!abort.signal.aborted)setError(failure);}
    })();
    return()=>abort.abort();
  },[client,device]);

  function choose(key:string){
    setSelected(key);
    const next=definitions.find(item=>definitionKey(item)===key);
    const defaults=testDefaults(next?.descriptor.configSchema).value;
    const fields=next?.descriptor.configSchema.properties;
    setConfig(Object.fromEntries(Object.entries(defaults).map(([field,value])=>{
      const property=isObject(fields)?fields[field]:undefined;
      return [field,isObject(property)&&property.type==='string'?String(value):JSON.stringify(value)];
    })));
  }
  const valid=Boolean(definition)&&selectable.some(row=>row.deviceId===device)
    && /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/.test(name)
    && required.every(field=>typeof field==='string'&&Object.hasOwn(config,field));
  async function save(event:FormEvent){
    event.preventDefault();
    if(!valid||busy||!definition)return;
    setBusy(true);setError(undefined);setSaved('');
    try{
      let values:Record<string,unknown>;
      try{
        values=Object.fromEntries(Object.entries(config).map(([field,value])=>{
          const property=properties[field];
          return [field,isObject(property)&&property.type==='string'?value:JSON.parse(value)];
        }));
      }catch{
        setError(new Error('Enter valid JSON for each non-text setting.'));
        return;
      }
      const result=await client.createTriggerAssignment({
        name,
        spec:{trigger:definition.triggerId,packageVersion:definition.packageVersion,
          target:{executor:'CLIENT',devices:[device]},config:values,desiredState:'Running'},
      },crypto.randomUUID());
      setItems(old=>[...old,result.assignment]);setSaved(name);setName('');
    }catch(failure){setError(failure);}finally{setBusy(false);}
  }
  return <section aria-label="Triggers"><h1>Triggers</h1>
    <p>Choose an approved device and a trigger exported by one of its packages.</p>
    <Failure error={error}/>{saved&&<p role="status">Trigger {saved} created.</p>}
    <form onSubmit={event=>void save(event)}><fieldset disabled={busy}><legend>Create an assignment</legend>
      <label>Assignment name<input value={name} onChange={e=>setName(e.target.value)} required maxLength={63}/></label>
      <label>Device<select value={device} onChange={e=>setDevice(e.target.value)} required>
        <option value="">Choose your device</option>
        {selectable.map(row=><option key={row.deviceId} value={row.deviceId}>{row.directoryDevice?.name??row.deviceId} ({row.deviceId})</option>)}
      </select></label>
      <label>Package trigger<select value={selected} onChange={e=>choose(e.target.value)} required disabled={!definitions.length}>
        <option value="">Choose a trigger</option>
        {definitions.map(item=><option key={definitionKey(item)} value={definitionKey(item)}>{item.triggerId} ({item.packageVersion})</option>)}
      </select></label>
      {definition&&<p>{definition.descriptor.eventType} · {definition.descriptor.mode}</p>}
      {typeof schema?.description==='string'&&<p>{schema.description}</p>}
      {Object.entries(properties).map(([field,property])=>{
        const spec=isObject(property)?property:{};
        const label=typeof spec.title==='string'?spec.title:field;
        const text=spec.type==='string';
        return <label key={field}>{label}
          <input value={config[field]??''} required={required.includes(field)}
            pattern={text&&typeof spec.pattern==='string'?spec.pattern:undefined}
            onChange={e=>{
              const value=e.target.value;
              setConfig(old=>{
                const next={...old};
                if(value==='')delete next[field];
                else next[field]=value;
                return next;
              });
            }}/>
          {typeof spec.description==='string'&&<small>{spec.description}</small>}
          {!text&&<small>Enter a JSON value.</small>}
        </label>;
      })}
      <button type="submit" disabled={!valid}>{busy?'Creating…':'Create trigger'}</button>
    </fieldset></form>
    {!selectable.length&&<p>No approved device is available for trigger management.</p>}
    <h2>Your visible triggers</h2>
    <ul>{items.map(item=><li key={item.metadata.uid}><strong>{item.metadata.name}</strong>: {item.spec.trigger} ({item.spec.packageVersion}) — {item.spec.desiredState}; {item.status.instances.running} running, {item.status.instances.failed} failed</li>)}</ul>
  </section>;
}

function definitionKey(item:TriggerDefinitionRecord){
  return item.triggerId+'@'+item.packageDigest;
}
