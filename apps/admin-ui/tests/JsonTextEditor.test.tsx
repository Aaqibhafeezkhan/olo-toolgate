// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import {JsonTextEditor} from '../src/JsonTextEditor';

afterEach(cleanup);
it('keeps invalid text out of the profile and explicitly applies valid sub JSON',()=>{
  const apply=vi.fn();render(<JsonTextEditor label="Agent JSON" value={{prompt:'old'}} apply={apply}/>);
  fireEvent.change(screen.getByLabelText('Agent JSON'),{target:{value:'{"prompt":'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply Agent JSON to draft'}));
  expect(apply).not.toHaveBeenCalled();expect(screen.getByRole('alert')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Agent JSON'),{target:{value:'{"prompt":"new"}'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply Agent JSON to draft'}));
  expect(apply).toHaveBeenCalledWith({prompt:'new'});
});
it('refuses a stale buffer until the current shared draft is reloaded',()=>{
  const apply=vi.fn();const view=render(<JsonTextEditor label="JSON" value={{prompt:'old'}} apply={apply}/>);
  fireEvent.change(screen.getByLabelText('JSON'),{target:{value:'{"prompt":"local"}'}});
  view.rerender(<JsonTextEditor label="JSON" value={{prompt:'remote'}} apply={apply}/>);
  expect((screen.getByRole('button',{name:'Apply JSON to draft'}) as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'Reload JSON'}));
  expect((screen.getByLabelText('JSON') as HTMLTextAreaElement).value).toContain('remote');
});
