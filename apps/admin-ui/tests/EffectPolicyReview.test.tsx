// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {expect,it} from 'vitest';
import {useState} from 'react';
import {render,screen,fireEvent} from '@testing-library/react';
import {EffectPolicyReview,reviewedEffects,type EffectDrafts} from '../src/EffectPolicyReview';
import type {RouteDeclaration} from '@olo-labs/toolgate-contracts/v2';
const route:RouteDeclaration={id:'send',destination:'external',method:'POST',path:'/send',query:[],headers:[],
  requestContentTypes:['application/json'],maxRequestBytes:1024,maxResponseBytes:1024,streaming:'NONE',timeoutMs:1000};
it('does not infer a classification or accept an absent, array or malformed intent',()=>{
 expect(()=>reviewedEffects([route],{})).toThrow(/classification/);
 for(const intent of ['', '[]', 'null', '"text"'])expect(()=>reviewedEffects([route],{
  send:{classification:'WRITE',consent:false,risk:false,intent}})).toThrow(/intent JSON object/);
});
it('requires per-route critical risk acknowledgment and preserves exact consent refusal intent',()=>{
 expect(()=>reviewedEffects([route],{send:{classification:'DESTRUCTIVE',consent:true,risk:false,intent:'{}'}})).toThrow(/unfenced target risk/);
 expect(reviewedEffects([route],{send:{classification:'DESTRUCTIVE',consent:true,risk:true,intent:'{}'}})[0])
  .toMatchObject({consentRequired:true,acknowledgedUnfencedRisk:true});
});

it('binds an explicit fenced identity and class without claiming unfenced acknowledgement',()=>{
 const fencedTarget={targetId:'billing',targetClass:'SHARED_SQL' as const};
 expect(reviewedEffects([route],{send:{classification:'FINANCIAL',consent:true,risk:true,intent:'{}',fencedTarget}})[0])
  .toMatchObject({fencedTarget,consentRequired:true,acknowledgedUnfencedRisk:false});
});
it('refuses a fenced read or missing and malformed target identities',()=>{
 for(const targetId of ['', '../other', 'Other', 'billing/other']){
  expect(()=>reviewedEffects([route],{send:{classification:'WRITE',consent:false,risk:false,intent:'{}',
   fencedTarget:{targetId,targetClass:'SHARED_SQL'}}})).toThrow(/fenced target identity/);
 }
 expect(()=>reviewedEffects([route],{send:{classification:'READ',consent:false,risk:false,intent:'{}',
  fencedTarget:{targetId:'billing',targetClass:'SQL_WITH_EXTERNAL_ACTION'}}})).toThrow(/fenced target identity/);
});

it('reviews a fenced class and target identity and clears fencing when changing to READ',()=>{
 function Review(){
  const [values,setValues]=useState<EffectDrafts>({send:{classification:'FINANCIAL',consent:true,risk:false,intent:'{}'}});
  return <EffectPolicyReview routes={[route]} values={values} disabled={false} onChange={setValues}/>;
 }
 render(<Review/>);
 fireEvent.change(screen.getByLabelText('Target fencing for send'),{target:{value:'SHARED_SQL'}});
 expect(screen.queryByLabelText(/I acknowledge/)).toBeNull();
 fireEvent.change(screen.getByLabelText('Fenced target identity for send'),{target:{value:'billing'}});
 fireEvent.change(screen.getByLabelText('Target fencing for send'),{target:{value:'SQL_WITH_EXTERNAL_ACTION'}});
 expect((screen.getByLabelText('Fenced target identity for send') as HTMLInputElement).value).toBe('billing');
 fireEvent.change(screen.getByLabelText('Effect classification for send'),{target:{value:'READ'}});
 expect(screen.queryByLabelText('Fenced target identity for send')).toBeNull();
 expect((screen.getByLabelText('Target fencing for send') as HTMLSelectElement).disabled).toBe(true);
});
it('declares an authoritative response only when the reviewer says so, never for reads',()=>{
 expect(reviewedEffects([route],{send:{classification:'WRITE',consent:false,risk:false,intent:'{}'}})[0]).not.toHaveProperty('authoritativeResponse');
 expect(reviewedEffects([route],{send:{classification:'WRITE',consent:false,risk:false,intent:'{}',authoritative:true}})[0])
  .toMatchObject({authoritativeResponse:true});
 expect(reviewedEffects([route],{send:{classification:'READ',consent:false,risk:false,intent:'{}',authoritative:true}})[0]).not.toHaveProperty('authoritativeResponse');
 function Review(){
  const [values,setValues]=useState<EffectDrafts>({send:{classification:'WRITE',consent:false,risk:false,intent:'{}'}});
  return <><EffectPolicyReview routes={[route]} values={values} disabled={false} onChange={setValues}/>
   <output data-testid="policy">{JSON.stringify(reviewedEffects([route],values)[0])}</output></>;
 }
 render(<Review/>);
 fireEvent.click(screen.getByLabelText('send commits the effect before it returns a success response'));
 expect(screen.getByText(/do not resolve the outcome/)).toBeTruthy();
 expect(JSON.parse(screen.getByTestId('policy').textContent!)).toMatchObject({authoritativeResponse:true});
});
