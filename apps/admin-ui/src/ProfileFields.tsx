// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useRef,useState} from 'react';
import profile from '../../../packages/contracts/schemas/v2/profile.schema.json';
import workflow from '../../../packages/contracts/schemas/v2/workflow.schema.json';
import identifiers from '../../../packages/contracts/schemas/v2/identifiers.schema.json';
import trigger from '../../../packages/contracts/schemas/v2/trigger.schema.json';

export type FieldSchema={description?:string;$ref?:string;type?:string;properties?:Record<string,FieldSchema>;required?:string[];items?:FieldSchema;enum?:string[];const?:unknown;minimum?:number;maximum?:number;minLength?:number;maxLength?:number;minItems?:number;maxItems?:number;pattern?:string};
const documents:Record<string,{$defs:Record<string,FieldSchema>}>={'profile.schema.json':profile,'workflow.schema.json':workflow,'identifiers.schema.json':identifiers,'trigger.schema.json':trigger};
export function resolveField(schema:FieldSchema,file='profile.schema.json'):{schema:FieldSchema;file:string}{
  if(!schema.$ref)return {schema,file};
  const [target,fragment]=schema.$ref.split('#/$defs/');
  return resolveField(documents[target||file].$defs[fragment],target||file);
}
export const profileSchema:FieldSchema={$ref:'profile.schema.json#/$defs/DeploymentProfile'};
export function initialField(input:FieldSchema,file?:string):unknown {
  const resolved=resolveField(input,file),s=resolved.schema;
  if(s.const!==undefined)return s.const;
  if(s.enum)return s.enum[0];
  if(s.type==='object')return Object.fromEntries((s.required??[]).map(key=>[key,initialField(s.properties![key],resolved.file)]));
  if(s.type==='array')return [];
  if(s.type==='boolean')return false;
  if(s.type==='integer'||s.type==='number')return s.minimum??0;
  return '';
}
function JsonObject({label,value,change}:{label:string;value:unknown;change:(v:unknown)=>void}){
  const [text,setText]=useState(JSON.stringify(value,null,2)),[invalid,setInvalid]=useState(false);
  const element=useRef<HTMLTextAreaElement>(null);
  useEffect(()=>{setText(JSON.stringify(value,null,2));setInvalid(false);element.current?.setCustomValidity('');},[value]);
  return <><label>{label}<textarea ref={element} required rows={5} value={text} aria-invalid={invalid} onChange={e=>{setText(e.target.value);try{const parsed:unknown=JSON.parse(e.target.value);if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw Error();setInvalid(false);e.target.setCustomValidity('');change(parsed);}catch{setInvalid(true);e.target.setCustomValidity('Enter a JSON object.');change(value);}}}/></label>{invalid&&<span role="alert">Enter a JSON object.</span>}</>;
}
/** Canonical schema properties drive the complete editor; Control owns semantic validation. */
export function ProfileFields({input=profileSchema,file,label='Profile',value,change}:{input?:FieldSchema;file?:string;label?:string;value:unknown;change:(v:unknown)=>void}){
  const resolved=resolveField(input,file),s=resolved.schema;
  if(s.type==='object'&&s.properties){const object=value as Record<string,unknown>;return <fieldset><legend>{label}</legend>{Object.entries(s.properties).map(([key,child])=>{
    const present=Object.hasOwn(object,key),required=s.required?.includes(key);
    return <div key={key}>{!required&&<button type="button" onClick={()=>{const next={...object};if(present)delete next[key];else next[key]=initialField(child,resolved.file);change(next);}}>{present?'Remove':'Add'} {key}</button>}{present&&<ProfileFields input={child} file={resolved.file} label={key} value={object[key]} change={v=>change({...object,[key]:v})}/>}</div>;
  })}</fieldset>;}
  if(s.type==='object')return <JsonObject label={label} value={value} change={change}/>;
  if(s.type==='array'){const items=value as unknown[];return <fieldset><legend>{label}</legend>{items.map((item,index)=><div key={index}><ProfileFields input={s.items} file={resolved.file} label={`${label} ${index+1}`} value={item} change={v=>change(items.map((old,i)=>i===index?v:old))}/><button type="button" onClick={()=>change(items.filter((_,i)=>i!==index))}>Remove {label} {index+1}</button></div>)}<button type="button" disabled={s.maxItems!==undefined&&items.length>=s.maxItems} onClick={()=>change([...items,initialField(s.items!,resolved.file)])}>Add {label} item</button></fieldset>;}
  if(s.enum)return <label>{label}<select value={String(value)} onChange={e=>change(e.target.value)}>{s.enum.map(v=><option key={v}>{v}</option>)}</select></label>;
  if(s.type==='boolean')return <label className="checkbox"><input type="checkbox" checked={Boolean(value)} onChange={e=>change(e.target.checked)}/>{label}</label>;
  if(s.type==='integer'||s.type==='number')return <label>{label}<input required type="number" step={s.type==='integer'?1:'any'} min={s.minimum} max={s.maximum} readOnly={s.const!==undefined} value={Number(value)} onChange={e=>change(e.target.valueAsNumber)}/></label>;
  if(!s.maxLength||s.maxLength<=2048)return <label>{label}<input required={Boolean(s.minLength)} minLength={s.minLength} maxLength={s.maxLength} pattern={s.pattern} value={String(value)} onChange={e=>change(e.target.value)}/></label>;
  return <label>{label}<textarea required={Boolean(s.minLength)} rows={5} minLength={s.minLength} maxLength={s.maxLength} value={String(value)} onChange={e=>change(e.target.value)}/></label>;
}
