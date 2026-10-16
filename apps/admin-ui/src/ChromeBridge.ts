// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type {ConsoleDeviceProof} from '@olo-labs/toolgate-contracts';
export type ConnectStatus={protocol:number;version:string;chromeVersion:string;phase:string;serverUrl?:string;userCode?:string;error?:string;proof?:ConsoleDeviceProof;client?:{health?:{ready:boolean;state?:string};error?:unknown}};

/** Same-window transport only. Replies are untrusted until Control validates device proof. */
export function extensionStatus(signal:AbortSignal,operation:'hello'|'connect'|'status'|'cancel'|'proof'='status',nonce?:string):Promise<ConnectStatus>{
 return new Promise((resolve,reject)=>{
  const id=crypto.randomUUID();
  const cleanup=()=>{clearTimeout(timer);window.removeEventListener('message',receive);signal.removeEventListener('abort',abort);};
  const abort=()=>{cleanup();reject(new DOMException('Cancelled','AbortError'));};
  const receive=(event:MessageEvent)=>{if(event.source!==window||event.origin!==location.origin||event.data?.channel!=='toolgate-connect-response'||event.data.id!==id)return;cleanup();resolve(event.data as ConnectStatus);};
  const timer=setTimeout(()=>{cleanup();reject(Error('Extension unavailable'));},operation==='hello'?2000:operation==='connect'?900000:25000);
  window.addEventListener('message',receive);signal.addEventListener('abort',abort,{once:true});if(signal.aborted){abort();return;}
  window.postMessage({channel:'toolgate-connect-request',id,operation,...(operation==='proof'?{nonce}:{})},location.origin);
 });
}
