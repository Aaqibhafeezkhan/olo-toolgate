// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type {InsightsGranularity,InsightsInvocationDetail,InsightsInvocationPage,InsightsSeries,InsightsSessionPage,InsightsSessionTimeline,InsightsStatus} from '@olo-labs/toolgate-contracts/v2';

/** A failed Insights request. The operator listener authenticates with the browser's client certificate, so there is
 *  no token here; a missing certificate fails before any request reaches the page. */
export class InsightsError extends Error {
  constructor(readonly status:number,readonly code:string){
    super(status===0?'Control could not be reached.':status===400?'The request was not valid.':status===404?'There is no Insights record for that invocation.'
      :status===409?'Not available while the tenant is over its Insights quota.':'Insights is unavailable right now.');
  }
}

type Fetch=typeof fetch;
export function client(fetcher:Fetch=(...args)=>fetch(...args)){
  async function get<T>(path:string,query:Record<string,string|number|undefined>={}):Promise<T>{
    const params=new URLSearchParams();
    for(const [k,v] of Object.entries(query))if(v!==undefined&&v!=='')params.set(k,String(v));
    let response:Response;
    try{response=await fetcher(`/insights/v1/${path}${params.size?'?'+params:''}`,{headers:{Accept:'application/json'},cache:'no-store'});}
    catch{throw new InsightsError(0,'NETWORK');}
    const body=await response.json().catch(()=>({}));
    if(!response.ok)throw new InsightsError(response.status,typeof body?.code==='string'?body.code:'UNKNOWN');
    return body as T;
  }
  return {
    status:()=>get<InsightsStatus>('status'),
    series:(q:{granularity:InsightsGranularity;from:number;to:number;groupBy?:'TOOL'|'CLIENT';tenant?:string;tool?:string;client?:string})=>get<InsightsSeries>('series',q),
    invocations:(q:{from:number;to:number;tenant?:string;tool?:string;outcome?:string;search?:string;after?:string;limit?:number})=>get<InsightsInvocationPage>('invocations',q),
    detail:(tenant:string,id:string)=>get<InsightsInvocationDetail>(`invocations/${encodeURIComponent(tenant)}/${encodeURIComponent(id)}`),
    sessions:(q:{tenant:string;from:number;to:number;after?:string;limit?:number})=>get<InsightsSessionPage>('sessions',q),
    // Session IDs may contain '/', so the timeline takes them as a query parameter.
    session:(tenant:string,session:string)=>get<InsightsSessionTimeline>('sessions/timeline',{tenant,session}),
  };
}
export type InsightsClient=ReturnType<typeof client>;
