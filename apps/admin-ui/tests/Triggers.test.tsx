// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,test,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {Triggers} from '../src/Triggers';
import type {ControlClient} from '../src/api';

afterEach(cleanup);
test('uses the selected package export, version and configuration schema',async()=>{
  const definition={
    triggerId:'examples.temperature/threshold',
    packageVersion:'2.3.4',
    packageDigest:'temperature-digest',
    descriptor:{
      eventType:'temperature.threshold',
      mode:'POLL',
      configSchema:{type:'object',required:['sensor','limit'],properties:{
        sensor:{type:'string',title:'Sensor name',description:'Enter the sensor identifier.'},
        limit:{type:'integer',title:'Threshold',default:40},
      }},
    },
  };
  const create=vi.fn().mockResolvedValue({assignment:{
    metadata:{name:'threshold',uid:'uid'},
    spec:{trigger:definition.triggerId,packageVersion:'2.3.4',desiredState:'Running'},
    status:{instances:{running:0,failed:0}},
  }});
  const discover=vi.fn().mockResolvedValue({items:[definition]});
  const client={
    managedDevices:vi.fn().mockResolvedValue({items:[
      {deviceId:'my-device',directoryDevice:{name:'My computer',enabled:true},endpointDevice:{state:'ACTIVE'},permissions:['trigger.manage']},
      {deviceId:'other-device',directoryDevice:{name:'Other computer',enabled:true},endpointDevice:{state:'ACTIVE'},permissions:['device.read']},
    ]}),
    triggerAssignments:vi.fn().mockResolvedValue({items:[]}),
    triggerDefinitions:discover,createTriggerAssignment:create,
  } as unknown as ControlClient;
  render(<Triggers client={client}/>);
  await screen.findByRole('option',{name:'My computer (my-device)'});
  expect(screen.queryByRole('option',{name:/Other computer/})).toBeNull();
  fireEvent.change(screen.getByLabelText('Assignment name'),{target:{value:'threshold'}});
  fireEvent.change(screen.getByLabelText('Device'),{target:{value:'my-device'}});
  const option=await screen.findByRole<HTMLOptionElement>('option',{name:'examples.temperature/threshold (2.3.4)'});
  expect(discover).toHaveBeenCalledWith({deviceId:'my-device',pageToken:undefined,signal:expect.any(AbortSignal)});
  fireEvent.change(screen.getByLabelText('Package trigger'),{target:{value:option.value}});
  expect(screen.getByText('Enter the sensor identifier.')).toBeTruthy();
  expect(screen.getByDisplayValue('40')).toBeTruthy();
  fireEvent.change(screen.getByLabelText(/Sensor name/),{target:{value:'sensor-a'}});
  fireEvent.click(screen.getByRole('button',{name:'Create trigger'}));
  await waitFor(()=>expect(create).toHaveBeenCalledTimes(1));
  expect(create.mock.calls[0][0]).toEqual({name:'threshold',spec:{
    trigger:definition.triggerId,packageVersion:'2.3.4',target:{executor:'CLIENT',devices:['my-device']},
    config:{sensor:'sensor-a',limit:40},desiredState:'Running',
  }});
});
