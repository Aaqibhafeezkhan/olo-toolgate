// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {Children,Fragment,cloneElement,isValidElement,useEffect,useRef,useState,type ReactElement,type ReactNode,type TableHTMLAttributes,type ButtonHTMLAttributes} from 'react';

type Element = ReactElement<{children?:ReactNode;[key:string]:unknown}>;
function elements(children:ReactNode):Element[]{
  const result:Element[]=[];
  Children.forEach(children,child=>{if(isValidElement(child)){const element=child as Element;if(element.type===Fragment)result.push(...elements(element.props.children));else result.push(element);}});
  return result;
}
function content(node:ReactNode):string {
  if(typeof node==='string'||typeof node==='number')return String(node);
  if(Array.isArray(node))return node.map(content).join(' ');
  if(isValidElement(node)){const p=(node as Element).props;return String(p['data-sort-value']??p['data-label']??content(p.children));}
  return '';
}
function SelectAll({checked,mixed,disabled,onChange}:{checked:boolean;mixed:boolean;disabled?:boolean;onChange:()=>void}){
  const ref=useRef<HTMLInputElement>(null);
  useEffect(()=>{if(ref.current)ref.current.indeterminate=mixed;},[mixed]);
  return <input ref={ref} type="checkbox" aria-label="Select all rows on this page" checked={checked} disabled={disabled} onChange={onChange}/>;
}

/** Sorting and selection apply to the displayed page; server cursors remain authoritative. */
export function Table({children,bulkActions,disabled=false,...props}:TableHTMLAttributes<HTMLTableElement>&{bulkActions?:(keys:string[],clear:()=>void)=>ReactNode;disabled?:boolean}){
  const parts=elements(children),head=parts.find(p=>p.type==='thead'),body=parts.find(p=>p.type==='tbody');
  const headers=elements(elements(head?.props.children)[0]?.props.children),rows=elements(body?.props.children);
  const [sort,setSort]=useState<{column:number;direction:1|-1}>(),[selected,setSelected]=useState<string[]>([]);
  const rowKey=(row:Element,index:number)=>String(row.key??index);
  const keys=rows.map(rowKey),signature=JSON.stringify(keys);
  useEffect(()=>{setSelected(previous=>previous.filter(key=>keys.includes(key)));},[signature]);
  const checked=selected.filter(key=>keys.includes(key));
  const sorted=rows.map((row,index)=>({row,index,key:rowKey(row,index)}));
  if(sort)sorted.sort((a,b)=>{
    const value=(row:Element)=>{const cell=elements(row.props.children)[sort.column];return cell?.props['data-sort-value']??content(cell?.props.children);};
    const av=value(a.row),bv=value(b.row);
    return sort.direction*(typeof av==='number'&&typeof bv==='number'?av-bv:String(av).localeCompare(String(bv),undefined,{numeric:true,sensitivity:'base'}))||a.index-b.index;
  });
  function download(){
    const quote=(value:string)=>`"${(/^[=+@\-\t\r]/.test(value)?"'":"")+value.replaceAll('"','""')}"`;
    const lines=[headers.map(h=>quote(content(h.props.children))).join(','),...rows.filter((row,index)=>checked.includes(rowKey(row,index))).map(row=>elements(row.props.children).map(cell=>quote(content(cell.props.children))).join(','))];
    const url=URL.createObjectURL(new Blob([lines.join('\r\n')],{type:'text/csv;charset=utf-8'})),link=document.createElement('a');link.href=url;link.download='selected-rows.csv';link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
  }
  return <>{rows.length>0&&<div className="bulk-actions"><span>{checked.length} selected on this page</span>{bulkActions?bulkActions(checked,()=>setSelected([])):<button disabled={!checked.length||disabled} onClick={download}>Export selected</button>}{checked.length>0&&<button disabled={disabled} onClick={()=>setSelected([])}>Clear selection</button>}<small>Sort and select the displayed page.</small></div>}
    <table {...props}>{parts.map(part=>part===head?<thead key="head"><tr><th scope="col" className="selection-cell"><SelectAll checked={keys.length>0&&checked.length===keys.length} mixed={checked.length>0&&checked.length<keys.length} disabled={disabled||!keys.length} onChange={()=>setSelected(checked.length===keys.length?[]:keys)}/></th>{headers.map((header,column)=>{
      const label=content(header.props.children),action=/^actions?$/i.test(label);
      return cloneElement(header,{key:column,scope:'col','aria-sort':action?undefined:sort?.column===column?sort.direction===1?'ascending':'descending':'none',children:action?header.props.children:<button className="sort-header" onClick={()=>setSort({column,direction:sort?.column===column&&sort.direction===1?-1:1})} title={`Sort by ${label}`} aria-label={`Sort by ${label}`}>{header.props.children}<span aria-hidden="true">{sort?.column===column?sort.direction===1?' ↑':' ↓':' ↕'}</span></button>});
    })}</tr></thead>:part===body?<tbody key="body">{sorted.map(({row,key})=>cloneElement(row,{key,children:<><td className="selection-cell"><input type="checkbox" aria-label={`Select ${content(elements(row.props.children)[0]?.props.children)}`} checked={checked.includes(key)} disabled={disabled} onChange={()=>setSelected(previous=>previous.includes(key)?previous.filter(k=>k!==key):[...previous,key])}/></td>{row.props.children}</>}))}</tbody>:part)}</table></>;
}

const paths={edit:'M4 16l12-12 4 4-12 12H4z M14 6l4 4',delete:'M3 6h18 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7',groups:'M3 3h6v6H3z M15 3h6v6h-6z M9 15h6v6H9z M6 9v3h12V9 M12 12v3',approve:'M4 12l5 5L20 6',deny:'M5 5l14 14 M19 5L5 19',view:'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6',advance:'M4 12h16 M13 5l7 7-7 7'};
export function ActionButton({icon,label,...props}:{icon:keyof typeof paths;label:string}&ButtonHTMLAttributes<HTMLButtonElement>){
  return <button type="button" {...props} className={`icon-action ${props.className??''}`} title={props.title??label} aria-label={label}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[icon]}/></svg></button>;
}
export function StatusToggle({enabled,label,...props}:{enabled:boolean;label:string}&ButtonHTMLAttributes<HTMLButtonElement>){
  return <button type="button" {...props} role="switch" aria-checked={enabled} aria-label={`${enabled?'Disable':'Enable'} ${label}`} title={`${enabled?'Disable':'Enable'} ${label}`} data-sort-value={enabled?'Enabled':'Disabled'} className={`status-toggle ${enabled?'enabled':'disabled'}`}><span className="switch-track" aria-hidden="true"><span/></span>{enabled?'Enabled':'Disabled'}</button>;
}
