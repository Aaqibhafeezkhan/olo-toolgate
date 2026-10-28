// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useEffect,useState} from 'react';
import {ControlClient} from './api';
import {REVIEW_POLICY_CHANGE} from './ServerSettings';

export const NO_REVIEWS_BANNER='Configuration reviews are set to 0 on this server, so changes apply immediately.';

/** Shown on every page while the server applies configuration changes without review. Refreshes after a threshold change. */
export function ReviewPolicyBanner({client}:{client:ControlClient}){
  const [required,setRequired]=useState<number>();const [attempt,setAttempt]=useState(0);
  useEffect(()=>{const update=()=>setAttempt(value=>value+1);window.addEventListener(REVIEW_POLICY_CHANGE,update);return()=>window.removeEventListener(REVIEW_POLICY_CHANGE,update);},[]);
  useEffect(()=>{const abort=new AbortController();
    client.reviewPolicy(abort.signal).then(policy=>{if(!abort.signal.aborted)setRequired(typeof policy?.requiredReviews==='number'?policy.requiredReviews:undefined);},()=>{if(!abort.signal.aborted)setRequired(undefined);});
    return()=>abort.abort();},[client,attempt]);
  return required===0?<div className="notice review-banner" role="status">{NO_REVIEWS_BANNER}</div>:null;
}
