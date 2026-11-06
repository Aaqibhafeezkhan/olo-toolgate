// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import type {EnterpriseInvocation,EnterpriseScope} from '@olo-labs/toolgate-contracts';
import {EffectOutcomes,AccessSimulation} from '../src/EffectOutcomes';
import {EnterpriseScopeEditor} from '../src/EnterpriseScopeEditor';
import {ControlClient} from '../src/api';

afterEach(cleanup);
const invocation={id:'effect-1',revision:7,state:'OUTCOME_UNKNOWN',completedResources:[],evaluation:{toolId:'payment',argumentsDigest:'a'.repeat(64),context:{deviceId:'device-1'},resources:[{kind:'CUSTOM',locator:'account/123'}]}} as unknown as EnterpriseInvocation;
it('proposes an exact unknown outcome with downstream evidence and retains its retry key',async()=>{
  const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>new Response(JSON.stringify(options?.method==='POST'?{code:'DEPENDENCY_UNAVAILABLE'}:String(url).includes('/history')?{operation:{operationId:invocation.id,state:'OUTCOME_UNKNOWN',revision:1,grade:'REPORTED',retryable:false},attempts:[],effects:[],truncated:false}:{items:[invocation]}),{status:options?.method==='POST'?503:200}));
  render(<EffectOutcomes client={new ControlClient('secret-token',vi.fn(),transport)}/>);
  fireEvent.click(await screen.findByRole('button',{name:'effect-1'}));
  fireEvent.change(screen.getByLabelText('External evidence SHA-256'),{target:{value:'b'.repeat(64)}});
  fireEvent.click(screen.getByRole('button',{name:'Submit reconciliation for independent review'}));
  await screen.findByRole('alert');
  fireEvent.click(screen.getByRole('button',{name:'Submit reconciliation for independent review'}));
  await waitFor(()=>expect(transport.mock.calls.filter(c=>c[1]?.method==='POST')).toHaveLength(2));
  const posts=transport.mock.calls.filter(c=>c[1]?.method==='POST');
  expect(JSON.parse(posts[0][1]?.body as string)).toEqual({expectedRevision:7,state:'FAILED',completedResources:[],evidenceDigest:'b'.repeat(64)});
  expect(new Headers(posts[0][1]?.headers).get('Idempotency-Key')).toBe(new Headers(posts[1][1]?.headers).get('Idempotency-Key'));
  expect(document.body.textContent).not.toContain('secret-token');
});
it('shows verified business history separately from reported success and restarts stale pagination',async()=>{
 const reported={...invocation,state:'SUCCEEDED'};
 let stale=false;
 const first={operation:{operationId:invocation.id,state:'OUTCOME_UNKNOWN',revision:4,grade:'REPORTED',retryable:false},
  attempts:[],effects:[{effectId:'effect-a',generation:1,state:'AMBIGUOUS',grade:'REPORTED'}],next:'4:50',truncated:false};
 const transport=vi.fn<typeof fetch>().mockImplementation(async(url)=>{
  const path=String(url);
  if(path.includes('/history')){
   if(path.includes('cursor=')){stale=true;return new Response(JSON.stringify({code:'CONFLICT'}),{status:409});}
   return new Response(JSON.stringify(stale?{...first,next:undefined,operation:{...first.operation,revision:5}}:first));
  }
  return new Response(JSON.stringify({items:[reported]}));
 });
 render(<EffectOutcomes client={new ControlClient('secret-token',vi.fn(),transport)}/>);
 fireEvent.click(await screen.findByRole('button',{name:'effect-1'}));
 expect(await screen.findByText('Business outcome')).toBeTruthy();
 expect(screen.getByText('OUTCOME_UNKNOWN')).toBeTruthy();
 expect(screen.getByText('Disabled')).toBeTruthy();
 expect(screen.getByText(/Reported state: SUCCEEDED/)).toBeTruthy();
 fireEvent.click(screen.getByRole('button',{name:'More effect history'}));
 await screen.findByRole('alert');
 fireEvent.click(screen.getByRole('button',{name:'Refresh history'}));
 await waitFor(()=>expect(screen.queryByRole('button',{name:'More effect history'})).toBeNull());
 expect(transport.mock.calls.filter(c=>String(c[0]).includes('/history')&&!String(c[0]).includes('cursor='))).toHaveLength(2);
 expect(document.body.textContent).not.toContain('secret-token');
});
it('binds explicit attempt attestation to refreshed evidence and clears consent on refresh',async()=>{
 const reported={...invocation,state:'SUCCEEDED',completedResources:invocation.evaluation.resources,resultDigest:'a'.repeat(64)};
 let revision=7;let outcomeRevision=4;
 const transport=vi.fn<typeof fetch>().mockImplementation(async(url,options)=>{
  const path=String(url);
  if(options?.method==='POST'){
   const request=JSON.parse(String(options.body));
   return new Response(JSON.stringify({id:'review-1',invocationId:invocation.id,requesterUserId:'admin',request,requestDigest:'d'.repeat(64),revision:1,state:'PENDING'}));
  }
  if(path.includes('/history'))return new Response(JSON.stringify({
   operation:{operationId:invocation.id,state:'OUTCOME_UNKNOWN',revision:outcomeRevision,grade:'REPORTED',journalComplete:true,
    unresolvedEffectCount:0,unsettledAttemptCount:1,retryable:false},
   attempts:[{attemptId:'attempt-1',ordinal:1,state:'SUCCEEDED',consumed:true,scope:'LEGACY_UNKNOWN',manifestSealed:true,manifestEffectCount:0,settled:false,grade:'REPORTED'}],
   effects:[],truncated:false
  }));
  if(path.endsWith('/invocations/effect-1')){revision=8;outcomeRevision=5;return new Response(JSON.stringify({...reported,revision}));}
  return new Response(JSON.stringify({items:[reported]}));
 });
 render(<EffectOutcomes client={new ControlClient('secret-token',vi.fn(),transport)}/>);
 fireEvent.click(await screen.findByRole('button',{name:'effect-1'}));
 const scope=await screen.findByRole('checkbox',{name:/closed attempts had no effects outside their ledgers/});
 fireEvent.click(scope);fireEvent.change(screen.getByLabelText('External evidence SHA-256'),{target:{value:'b'.repeat(64)}});
 fireEvent.click(screen.getByRole('button',{name:'Refresh inspection'}));
 await waitFor(()=>expect((screen.getByRole('checkbox',{name:/closed attempts had no effects outside their ledgers/}) as HTMLInputElement).checked).toBe(false));
 fireEvent.click(screen.getByRole('checkbox',{name:/closed attempts had no effects outside their ledgers/}));
 fireEvent.click(screen.getByRole('button',{name:'Submit reconciliation for independent review'}));
 await screen.findByText('Attempt scope');
 const posts=transport.mock.calls.filter(c=>c[1]?.method==='POST');expect(posts).toHaveLength(1);
 expect(JSON.parse(String(posts[0][1]?.body))).toEqual({
  expectedRevision:8,state:'SUCCEEDED',completedResources:invocation.evaluation.resources,evidenceDigest:'b'.repeat(64),resultDigest:'a'.repeat(64),
  attemptSettlement:{expectedOutcomeRevision:5,attemptIds:['attempt-1'],noFurtherEffects:true}
 });
 expect(screen.getByText('attempt-1')).toBeTruthy();expect(document.body.textContent).not.toContain('secret-token');
});
it('invalid simulation JSON fails locally without issuing an effect request',async()=>{
  const transport=vi.fn<typeof fetch>();render(<AccessSimulation client={new ControlClient('token',vi.fn(),transport)}/>);
  fireEvent.change(screen.getByLabelText('Complete evaluation JSON'),{target:{value:'{bad json'}});
  fireEvent.click(screen.getByRole('button',{name:'Evaluate access'}));await screen.findByRole('alert');expect(transport).not.toHaveBeenCalled();
});
it('malformed advanced scope JSON remains editable without crashing the structured editor',()=>{
  render(<EnterpriseScopeEditor client={new ControlClient('token',vi.fn())} value={{resources:null} as unknown as EnterpriseScope} change={vi.fn()}/>);
  expect(screen.getByRole('alert')).toBeTruthy();expect(screen.queryByRole('button',{name:'Add resource rule'})).toBeNull();
});
