// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,within} from '@testing-library/react';
import {Table,ActionButton,StatusToggle} from '../src/Table';
afterEach(cleanup);
function rows(){return screen.getAllByRole('row').slice(1).map(row=>within(row).getByRole('rowheader').textContent);}
it('sorts headers in both directions with numeric values and retains selection by identity',()=>{
  render(<Table><caption>Test records</caption><thead><tr><th>Name</th><th>Revision</th><th>Actions</th></tr></thead><tbody>{[{id:'z',name:'Zulu',revision:2},{id:'a',name:'Alpha',revision:10}].map(row=><tr key={row.id}><th scope="row">{row.name}</th><td data-sort-value={row.revision}>{row.revision}</td><td><ActionButton icon="edit" label={`Edit ${row.name}`}/></td></tr>)}</tbody></Table>);
  fireEvent.click(screen.getByRole('checkbox',{name:'Select Zulu'}));
  fireEvent.click(screen.getByRole('button',{name:'Sort by Name'}));expect(rows()).toEqual(['Alpha','Zulu']);expect((screen.getByRole('checkbox',{name:'Select Zulu'}) as HTMLInputElement).checked).toBe(true);
  expect(screen.getByRole('columnheader',{name:'Name'}).getAttribute('aria-sort')).toBe('ascending');
  fireEvent.click(screen.getByRole('button',{name:'Sort by Name'}));expect(rows()).toEqual(['Zulu','Alpha']);
  fireEvent.click(screen.getByRole('button',{name:'Sort by Revision'}));expect(rows()).toEqual(['Zulu','Alpha']);
  expect((screen.getByRole('checkbox',{name:'Select all rows on this page'}) as HTMLInputElement).indeterminate).toBe(true);
  fireEvent.click(screen.getByRole('checkbox',{name:'Select all rows on this page'}));expect(screen.getByText('2 selected on this page')).toBeTruthy();
  fireEvent.click(screen.getByRole('button',{name:'Clear selection'}));expect(screen.getByText('0 selected on this page')).toBeTruthy();
  expect(screen.queryByRole('button',{name:'Sort by Actions'})).toBeNull();expect(screen.getByRole('button',{name:'Edit Zulu'}).title).toBe('Edit Zulu');
});
it('drops selections when the displayed page changes',()=>{
  const table=(id:string)=><Table><thead><tr><th>Name</th></tr></thead><tbody><tr key={id}><th scope="row">{id}</th></tr></tbody></Table>;
  const view=render(table('first'));fireEvent.click(screen.getByRole('checkbox',{name:'Select first'}));view.rerender(table('second'));expect(screen.getByText('0 selected on this page')).toBeTruthy();
});
it('exposes current enablement through an accessible switch',()=>{
  const change=vi.fn();const view=render(<StatusToggle enabled label="record" onClick={change}/>);const toggle=screen.getByRole('switch',{name:'Disable record'});expect(toggle.getAttribute('aria-checked')).toBe('true');fireEvent.click(toggle);expect(change).toHaveBeenCalledOnce();view.rerender(<StatusToggle enabled={false} label="record" onClick={change}/>);expect(screen.getByRole('switch',{name:'Enable record'}).getAttribute('aria-checked')).toBe('false');
});
