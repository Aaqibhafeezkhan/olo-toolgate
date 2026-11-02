// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import {expect,it} from 'vitest';
import {reviewedEffects} from '../src/EffectPolicyReview';
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
