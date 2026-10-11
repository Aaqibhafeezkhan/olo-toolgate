// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useId,useLayoutEffect,useRef,type ReactNode} from 'react';
import {createPortal} from 'react-dom';

/** Native modal focus/inert behavior, with a fixed header and its own scrolling body. */
export function EditorDialog({title,close,busy=false,closeLabel='Close editor',children}:{title:string;close:()=>void;busy?:boolean;closeLabel?:string;children:ReactNode}) {
  const dialog=useRef<HTMLDialogElement>(null),heading=useRef<HTMLHeadingElement>(null),id=useId();
  useLayoutEffect(()=>{
    const trigger=document.activeElement instanceof HTMLElement?document.activeElement:undefined;
    const node=dialog.current!;
    if(node.showModal)node.showModal();else node.setAttribute('open','');
    heading.current?.focus({preventScroll:true});
    return()=>{
      if(node.open&&node.close)node.close();
      if(trigger?.isConnected)trigger.focus({preventScroll:true});else document.getElementById('main')?.focus({preventScroll:true});
    };
  },[]);
  return createPortal(<dialog ref={dialog} className="editor-dialog" aria-labelledby={id}
    onKeyDown={event=>{
      const node=event.currentTarget;
      if(event.key!=='Tab'||!(event.target instanceof Element)||event.target.closest('dialog')!==node)return;
      const controls=Array.from(node.querySelectorAll<HTMLElement>('button,a[href],input,select,textarea,summary,[tabindex]')).filter(control=>control.tabIndex>=0&&!control.matches(':disabled')&&control.getClientRects().length>0);
      const first=controls[0],last=controls.at(-1),active=document.activeElement;
      if(!first){event.preventDefault();heading.current?.focus();}
      else if(event.shiftKey&&(active===first||active===heading.current)){event.preventDefault();last?.focus();}
      else if(!event.shiftKey&&active===last){event.preventDefault();first.focus();}
    }}
    onCancel={event=>{event.preventDefault();event.stopPropagation();if(!busy)close();}}>
    <header className="editor-dialog-heading"><h2 ref={heading} id={id} tabIndex={-1}>{title}</h2>
      <button type="button" aria-label={closeLabel} title={closeLabel} disabled={busy} onClick={close}>Close <span aria-hidden="true">×</span></button></header>
    <div className="editor-dialog-body">{children}</div>
  </dialog>,document.body);
}
