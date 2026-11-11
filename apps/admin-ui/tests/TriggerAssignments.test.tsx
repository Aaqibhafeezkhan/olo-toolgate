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

it('uses the displayed resource version for a scoped lifecycle action',async()=>{
  const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>{
    return new Response(JSON.stringify(options?.method==='POST'?{}:{items:[fixture.TriggerAssignment]}),{status:200});
  });
  const client=new ControlClient('token',vi.fn(),transport);
  vi.spyOn(client,'can').mockImplementation(permission=>permission==='trigger.manage');
  render(<TriggerAssignments client={client}/>);
  fireEvent.click(await screen.findByRole('button',{name:'Manage finance-inbox-pdf'}));
  fireEvent.change(screen.getByLabelText('Lifecycle action'),{target:{value:'Pause'}});
  fireEvent.change(screen.getByLabelText('Target scope'),{target:{value:'deviceGroups'}});
  fireEvent.change(screen.getByLabelText('Target IDs (comma separated)'),{target:{value:'finance'}});
  fireEvent.change(screen.getByLabelText('Reason'),{target:{value:'Maintenance'}});
  fireEvent.click(screen.getByRole('button',{name:'Apply Pause'}));
  await screen.findByText('Assignment updated.');
  const mutation=transport.mock.calls.find(([,options])=>options?.method==='POST');
  expect(mutation?.[0]).toBe('/api/control/v1/triggers/assignments/finance-inbox-pdf:action');
  expect(new Headers(mutation?.[1]?.headers).get('If-Match')).toBe(fixture.TriggerAssignment.metadata.resourceVersion);
  expect(JSON.parse(String(mutation?.[1]?.body))).toEqual({
    action:'Pause',scope:{deviceGroups:['finance']},reason:'Maintenance',
  });
});

it('uploads the exact selected package and creates a raw assignment with an idempotency key',async()=>{
  const transport=vi.fn<typeof fetch>().mockImplementation(async(_url,options)=>{
    const body=options?.method!=='POST'?{items:[]}:options?.body instanceof Blob?{packageId:'acme.watch',version:'1.0.0'}:{};
    return new Response(JSON.stringify(body),{status:200});
  });
  const client=new ControlClient('token',vi.fn(),transport);
  vi.spyOn(client,'can').mockImplementation(permission=>['package.import','trigger.manage'].includes(permission));
  render(<TriggerAssignments client={client}/>);
  const file=new File(['signed bytes'],'watch.tgpkg');
  fireEvent.change(screen.getByLabelText('Upload trigger package'),{target:{files:[file]}});
  await screen.findByText('Verified acme.watch 1.0.0.');
  const upload=transport.mock.calls.find(([,options])=>options?.body===file);
  expect(upload).toBeDefined();
  expect(new Headers(upload?.[1]?.headers).get('Content-Type')).toBe('application/vnd.ololabs.toolgate.package+zip');

  fireEvent.click(screen.getByRole('button',{name:'Create trigger assignment'}));
  fireEvent.change(screen.getByLabelText('Assignment JSON'),{target:{value:JSON.stringify(fixture.TriggerAssignmentCreate)}});
  fireEvent.click(screen.getByRole('button',{name:'Create assignment'}));
  await screen.findByText('Assignment created.');
  const create=transport.mock.calls.find(([url,options])=>String(url).endsWith('/triggers/assignments')&&options?.method==='POST');
  expect(JSON.parse(String(create?.[1]?.body))).toEqual(fixture.TriggerAssignmentCreate);
  expect(new Headers(create?.[1]?.headers).get('Idempotency-Key')).toBeTruthy();
});
