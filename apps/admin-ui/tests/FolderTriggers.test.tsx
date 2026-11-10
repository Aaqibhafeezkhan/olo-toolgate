// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,test,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {FolderTriggers,validTriggerFolder} from '../src/FolderTriggers';
import type {ControlClient} from '../src/api';
afterEach(cleanup);
test('accepts relative folders and refuses paths outside the authorized files area',()=>{
  expect(validTriggerFolder('orders/incoming')).toBe(true);
  for(const value of ['/etc','../orders','orders/../private','C:\\orders','orders//incoming',''])expect(validTriggerFolder(value)).toBe(false);
});
test('creates a trigger on one authorized device and the chosen folder',async()=>{
  const create=vi.fn().mockResolvedValue({assignment:{metadata:{name:'my-orders',uid:'uid'},spec:{config:{folder:'orders/incoming'},target:{devices:['my-device']},desiredState:'Running'},status:{instances:{running:0,failed:0}}}});
  const client={managedDevices:vi.fn().mockResolvedValue({items:[
    {deviceId:'my-device',directoryDevice:{name:'My computer',enabled:true},endpointDevice:{state:'ACTIVE'},permissions:['trigger.manage']},
    {deviceId:'another-device',directoryDevice:{name:'Other computer',enabled:true},endpointDevice:{state:'ACTIVE'},permissions:['device.read']},
  ]}),folderTriggers:vi.fn().mockResolvedValue({items:[]}),createFolderTrigger:create} as unknown as ControlClient;
  render(<FolderTriggers client={client}/>);
  await screen.findByRole('option',{name:'My computer (my-device)'});
  expect(screen.queryByRole('option',{name:/Other computer/})).toBeNull();
  fireEvent.change(screen.getByLabelText('Trigger name'),{target:{value:'my-orders'}});
  fireEvent.change(screen.getByLabelText('Device'),{target:{value:'my-device'}});
  fireEvent.change(screen.getByLabelText('Folder'),{target:{value:'orders/incoming'}});
  fireEvent.click(screen.getByRole('button',{name:'Create trigger'}));
  await waitFor(()=>expect(create).toHaveBeenCalledTimes(1));
  expect(create.mock.calls[0][0]).toEqual({name:'my-orders',spec:{trigger:'examples.inbox-watch/new-file',packageVersion:'0.1.0',target:{executor:'CLIENT',devices:['my-device']},config:{folder:'orders/incoming',extension:'.txt'},desiredState:'Running'}});
  await screen.findByRole('status');
});
