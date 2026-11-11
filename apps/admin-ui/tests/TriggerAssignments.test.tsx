// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen} from '@testing-library/react';
import fixture from '../../../tests/fixtures/contracts/v2/valid.json';
import {ControlClient} from '../src/api';
import {TriggerAssignments} from '../src/TriggerAssignments';

afterEach(cleanup);

it('loads assignment status and queries only the selected assignment’s instances',async()=>{
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url)=>{
    const path=String(url);
    const items=path.includes('/triggers/instances')?[fixture.TriggerInstanceView]:[fixture.TriggerAssignment];
    return new Response(JSON.stringify({items}),{status:200});
  });
  const client=new ControlClient('token',vi.fn(),transport);
  render(<TriggerAssignments client={client}/>);
  fireEvent.click(await screen.findByRole('button',{name:'View finance-inbox-pdf instances'}));
  await screen.findByRole('rowheader',{name:'device-42'});
  expect(transport.mock.calls.map(([url])=>String(url))).toEqual([
    '/api/control/v1/triggers/assignments',
    '/api/control/v1/triggers/instances?assignment=finance-inbox-pdf',
  ]);
});
