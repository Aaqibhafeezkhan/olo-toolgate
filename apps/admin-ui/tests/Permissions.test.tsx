// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ControlClient} from '../src/api';
import {FeatureAccess} from '../src/Permissions';
import {ManagementRulesEditor} from '../src/ManagementRulesEditor';
import {Enrollment} from '../src/Enrollment';
import {emptyConditions} from '../src/EnterpriseScopeEditor';

afterEach(()=>{cleanup();window.location.hash='';});
function fixture(permissions:string[],role='ADMINISTRATOR'){
 const transport=vi.fn<typeof fetch>(async input=>new Response(JSON.stringify(String(input).endsWith('/admin-session')?{role,permissions}:{items:[]})));
 return {client:new ControlClient('fixture',vi.fn(),transport),transport};
}
it('uses server capabilities, refreshes revocations, and never infers access from a Super Admin label',async()=>{
 const {client,transport}=fixture([],'SUPER_ADMIN');await client.adminSession();
 const view=render(<FeatureAccess client={client} route="config-server"><h1>Server configuration</h1><button>Save settings</button></FeatureAccess>);
 expect(screen.getByText('Access unavailable')).toBeTruthy();
 transport.mockResolvedValue(new Response(JSON.stringify({role:'ADMINISTRATOR',permissions:['server_configuration.read']})));
 await client.adminSession();await screen.findByText('Server configuration');
 expect((screen.getByRole('button',{name:'Save settings'}).closest('fieldset') as HTMLFieldSetElement).disabled).toBe(true);
 transport.mockResolvedValue(new Response(JSON.stringify({role:'ADMINISTRATOR',permissions:[]})));
 await client.adminSession();await screen.findByText('Access unavailable');view.unmount();
});
it('view-only enrollment cannot claim a code or start Chrome Connect',async()=>{
 const {client,transport}=fixture(['device.read']);await client.adminSession();window.location.hash='#enroll?code=ABCDEF0123456789';
 render(<Enrollment client={client}/>);
 expect((screen.getByRole('button',{name:'Connect'}) as HTMLButtonElement).disabled).toBe(true);
 expect((screen.getByRole('button',{name:'Review device'}) as HTMLButtonElement).disabled).toBe(true);
 await waitFor(()=>expect(transport).toHaveBeenCalled());
 expect(transport.mock.calls.some(([,init])=>init?.method==='POST')).toBe(false);
});
it('shows feature checkboxes and locks Super Admin permissions',()=>{
 const {client}=fixture([]);const change=vi.fn();
 const scope={actions:['device.read'],groupType:'DEVICE_GROUP' as const,groups:{ids:['devices'],all:false},grantableScopes:[],conditions:emptyConditions};
 const view=render(<ManagementRulesEditor client={client} value={[scope]} change={change} disabled={false}/>);
 fireEvent.click(screen.getByLabelText('Device: Approve in scope 1'));
 expect(change.mock.calls[0][0][0].actions).toEqual(['device.approve','device.read']);
 fireEvent.click(screen.getByLabelText('Self (this device)'));
 expect(change.mock.calls[1][0][0].deviceScope).toBe('CURRENT');
 view.rerender(<ManagementRulesEditor client={client} value={[]} change={change} disabled={false} superAdmin/>);
 expect(screen.getAllByRole('checkbox').every(input=>(input as HTMLInputElement).checked&&!!input.closest('fieldset')?.disabled)).toBe(true);
});
