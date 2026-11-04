// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor,within} from '@testing-library/react';
import type {RecoveryPreflightPage,ToolRecoveryReadiness} from '@olo-labs/toolgate-contracts/v2';
import {InsightsApp} from '../src/insights/InsightsApp';
import {RecoveryPreflight} from '../src/insights/RecoveryPreflight';
import {client} from '../src/insights/api';

afterEach(cleanup);
const NOW=Date.UTC(2026,10,3);
const tool:ToolRecoveryReadiness={tenantId:'10000000-0000-4000-8000-000000000001',toolId:'acme.orders/pay',name:'Pay',
  automaticResendEligible:false,effectiveMethod:'STATUS_LOOKUP',reason:'UNQUALIFIED_EXECUTOR',
  routes:[{routeId:'payment',reason:'ELIGIBLE',lookupAvailable:true,reviewExpiresAtUnixMs:NOW+86_400_000}]};
const page=(items:ToolRecoveryReadiness[]=[],nextCursor?:string):RecoveryPreflightPage=>({items,nextCursor,observedAtUnixMs:NOW,buildMode:'CURRENT_ONLY'});

it('shows preflight with Insights off and lists the safe recovery method without a retry action',async()=>{
  const asked:URL[]=[];
  const fetcher=vi.fn<typeof fetch>(async input=>{
    const url=new URL(String(input),'https://operator.example');asked.push(url);
    return new Response(JSON.stringify(url.pathname.endsWith('/status')
      ?{mode:'NONE',generatedAtUnixMs:NOW,tenants:1,unprojectedFacts:0,summariesSuspendedTenants:0,failedTenants:0,sourceGaps:[]}
      :page([tool])),{status:200});
  });
  render(<InsightsApp api={client(fetcher)} now={()=>NOW}/>);
  await screen.findByRole('heading',{name:'Recovery preflight'});
  expect(asked.map(url=>url.pathname)).toEqual(['/insights/v1/status']);
  fireEvent.click(screen.getByRole('button',{name:'Run preflight'}));
  const table=await screen.findByRole('table',{name:'Current recovery configuration'});
  expect(within(table).getByText('Pay')).toBeTruthy();
  expect(within(table).getByText('Read-only status lookup')).toBeTruthy();
  expect(within(table).getByText('No matching qualified executor is configured')).toBeTruthy();
  expect(screen.queryByRole('button',{name:/retry|resend/i})).toBeNull();
  expect(asked[1]?.searchParams.get('limit')).toBe('50');
  expect(asked[1]?.searchParams.has('tenant')).toBe(false);
});

it('keeps the submitted tenant with its opaque cursor and refreshes from the first page',async()=>{
  const asked:URL[]=[];const fetcher=vi.fn<typeof fetch>(async input=>{
    const url=new URL(String(input),'https://operator.example');asked.push(url);
    return new Response(JSON.stringify(url.searchParams.has('cursor')?page([{...tool,name:'Second'}]):page([tool],'opaque-current-cursor')),{status:200});
  });
  render(<RecoveryPreflight api={client(fetcher)}/>);
  fireEvent.change(screen.getByRole('textbox',{name:/Tenant UUID/}),{target:{value:tool.tenantId}});
  fireEvent.click(screen.getByRole('button',{name:'Run preflight'}));
  await screen.findByText('Pay');
  fireEvent.change(screen.getByRole('textbox',{name:/Tenant UUID/}),{target:{value:''}});
  fireEvent.click(screen.getByRole('button',{name:'Next preflight page'}));
  await screen.findByText('Second');
  expect(asked[1]?.searchParams.get('tenant')).toBe(tool.tenantId);
  expect(asked[1]?.searchParams.get('cursor')).toBe('opaque-current-cursor');
  fireEvent.click(screen.getByRole('button',{name:'Refresh preflight'}));
  await waitFor(()=>expect(asked).toHaveLength(3));
  expect(asked[2]?.searchParams.has('cursor')).toBe(false);
  expect(asked[2]?.searchParams.has('tenant')).toBe(false);
});

it('clears stale readiness on a fenced cursor and explains how to refresh',async()=>{
  let calls=0;const fetcher=vi.fn<typeof fetch>(async()=>++calls===1
    ?new Response(JSON.stringify(page([tool],'cursor')),{status:200})
    :new Response(JSON.stringify({code:'CONFLICT'}),{status:409}));
  render(<RecoveryPreflight api={client(fetcher)}/>);
  fireEvent.click(screen.getByRole('button',{name:'Run preflight'}));await screen.findByText('Pay');
  fireEvent.click(screen.getByRole('button',{name:'Next preflight page'}));
  expect((await screen.findByRole('alert')).textContent).toContain('configuration changed');
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.getByRole('button',{name:'Run preflight'})).toBeTruthy();
});
