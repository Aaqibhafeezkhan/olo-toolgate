// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {ControlClient} from '../src/api';
import {TenantAnalytics} from '../src/TenantAnalytics';
import {FeatureAccess} from '../src/Permissions';
afterEach(cleanup);
const series={granularity:'HOUR',fromUnixMs:0,toUnixMs:3600000,groupBy:'',histogramBoundsMs:[1,5],points:[],admissions:[]};
function fixture(mode='BASIC',permissions=['audit.read']){
 const transport=vi.fn<typeof fetch>(async input=>new Response(JSON.stringify(String(input).endsWith('/admin-session')?
  {role:'ADMINISTRATOR',permissions}:{tenantId:'tenant-a',mode,series,tools:series})));
 return {transport,client:new ControlClient('tenant-token',vi.fn(),transport)};
}
it('uses authenticated tenant analytics without exposing a tenant selector or operator API',async()=>{
 const {client,transport}=fixture();render(<TenantAnalytics client={client}/>);
 await screen.findByText('No recorded tool calls in this time range.');
 const [url,options]=transport.mock.calls[0];
 expect(String(url)).toMatch(/^\/api\/control\/v1\/analytics\?from=\d+&to=\d+$/);
 expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer tenant-token');
 expect(options?.cache).toBe('no-store');
 expect(screen.queryByLabelText('Tenant')).toBeNull();
 fireEvent.change(screen.getByLabelText('Time range'),{target:{value:'168'}});
 await waitFor(()=>expect(transport).toHaveBeenCalledTimes(2));
 expect(screen.getByRole('button',{name:'Refresh analytics'})).toBeTruthy();
});
it('explains disabled collection and hides metrics',async()=>{
 const {client}=fixture('NONE');render(<TenantAnalytics client={client}/>);
 await screen.findByText(/Analytics collection is disabled/);
 expect(screen.queryByText('Busiest tools')).toBeNull();
});
it('does not grant analytics from an administrator role label alone',async()=>{
 const {client,transport}=fixture('BASIC',[]);await client.adminSession();
 render(<FeatureAccess client={client} route="analytics"><TenantAnalytics client={client}/></FeatureAccess>);
 expect(screen.getByText('Access unavailable')).toBeTruthy();expect(transport).toHaveBeenCalledTimes(1);
});
it('shows a retryable failure without displaying stale analytics',async()=>{
 const {client,transport}=fixture();transport.mockResolvedValue(new Response('{}',{status:503}));
 render(<TenantAnalytics client={client}/>);await screen.findByRole('button',{name:/try again/i});
 expect(screen.queryByText('Busiest tools')).toBeNull();
});
