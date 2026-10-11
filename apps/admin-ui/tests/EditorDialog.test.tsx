// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useState} from 'react';
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {EditorDialog} from '../src/EditorDialog';

afterEach(cleanup);
function Harness({busy=false}:{busy?:boolean}) {
  const [open,setOpen]=useState(false);
  return <><button onClick={()=>setOpen(true)}>Edit record</button>{open&&<EditorDialog title="Edit example" close={()=>setOpen(false)} busy={busy}><label>Name<input defaultValue="Example"/></label></EditorDialog>}</>;
}
function open(){const trigger=screen.getByRole('button',{name:'Edit record'});trigger.focus();fireEvent.click(trigger);return trigger;}
it('opens a named modal and restores focus when Close returns to the parent',()=>{
  const {container}=render(<Harness/>);const trigger=open();const dialog=screen.getByRole('dialog',{name:'Edit example'});
  expect(container.contains(dialog)).toBe(false);expect(dialog.hasAttribute('open')).toBe(true);
  expect(document.activeElement).toBe(screen.getByRole('heading',{name:'Edit example'}));
  fireEvent.click(screen.getByRole('button',{name:'Close editor'}));expect(screen.queryByRole('dialog')).toBeNull();expect(document.activeElement).toBe(trigger);
});
it('handles native Escape cancellation without dismissing a busy editor',()=>{
  const {rerender}=render(<Harness busy/>);const trigger=open();const cancel=new Event('cancel',{cancelable:true});
  fireEvent(screen.getByRole('dialog'),cancel);expect(cancel.defaultPrevented).toBe(true);expect(screen.getByRole('button',{name:'Close editor'}).hasAttribute('disabled')).toBe(true);
  rerender(<Harness/>);fireEvent(screen.getByRole('dialog'),new Event('cancel',{cancelable:true}));expect(screen.queryByRole('dialog')).toBeNull();expect(document.activeElement).toBe(trigger);
});
it('returns focus to main if a refresh removes the opening control',()=>{
  const close=vi.fn();const {rerender}=render(<><main id="main" tabIndex={-1}/><button>Original opener</button></>);screen.getByRole('button').focus();
  rerender(<><main id="main" tabIndex={-1}/><button>Original opener</button><EditorDialog title="Edit example" close={close}>Editor</EditorDialog></>);
  rerender(<><main id="main" tabIndex={-1}/><EditorDialog title="Edit example" close={close}>Editor</EditorDialog></>);
  rerender(<main id="main" tabIndex={-1}/>);expect(document.activeElement).toBe(screen.getByRole('main'));
});
