// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
let listener,state={},nativeCalls=[],nativeResponse;
const id='emmemldedebhbloibichmmdlbpjakfkf',origin='https://gate.example';
globalThis.chrome={
  runtime:{id,onInstalled:{addListener(){}},onMessage:{addListener(fn){listener=fn;}},getManifest(){return {version_name:'0.10.0-dev',version:'0.10.0.23'};},async sendNativeMessage(host,message){nativeCalls.push(message);if(nativeResponse instanceof Error)throw nativeResponse;const value=typeof nativeResponse==='function'?await nativeResponse(message):message.operation==='enroll'?{challenge:{userCode:'ABCDEF0123456789'}}:nativeResponse;return value?.error?value:{...value,consoleOrigin:value?.consoleOrigin??new URL(message.consoleUrl).origin};}},
  storage:{local:{async get(key){return {[key]:state[key]};},async set(values){Object.assign(state,values);},async remove(key){delete state[key];}}}
};
await import('../worker.js');
beforeEach(()=>{state={};nativeCalls=[];nativeResponse=Error('Host unavailable');});
function request(operation,url=origin+'/console/#enroll'){
  return new Promise(resolve=>listener({operation},{tab:{id:2,url}},resolve));
}
test('extension availability does not probe IPC before Connect',async()=>{
  assert.equal((await request('hello')).phase,'available');
  assert.equal((await request('status')).phase,'available');
  assert.equal(nativeCalls.length,0);
  assert.equal((await request('status','https://gate.example/other')).error,'INVALID_ORIGIN');
});
test('Connect passes the current console to the system and starts enrollment for an installed client',async()=>{
  nativeResponse={health:{state:'UNENROLLED',ready:false},serverUrl:'https://control.example'};
  const result=await request('connect');
  assert.deepEqual(nativeCalls,[{operation:'connect',consoleUrl:origin+'/console/#enroll'},{operation:'enroll',consoleUrl:origin+'/console/#enroll'}]);
  assert.equal(result.phase,'ready');assert.equal(result.userCode,'ABCDEF0123456789');
  assert.equal((await request('status')).userCode,'ABCDEF0123456789');
});
test('already enrolled clients are not enrolled again',async()=>{
  nativeResponse={health:{state:'ACTIVE',ready:true},serverUrl:origin};
  await request('connect');assert.equal(nativeCalls.length,1);
});
test('optional UUID assertion reaches native calls and is retained for later status',async()=>{
  const tenant='10000000-0000-4000-8000-000000000001';
  nativeResponse={health:{state:'ACTIVE',ready:true},serverUrl:origin};
  const send=message=>new Promise(resolve=>listener(message,{tab:{id:2,url:origin+'/console/'}},resolve));
  assert.equal((await send({operation:'connect',expectedTenantId:tenant})).phase,'ready');
  assert.equal(nativeCalls[0].expectedTenantId,tenant);
  await send({operation:'status'});assert.equal(nativeCalls[1].expectedTenantId,tenant);
  const count=nativeCalls.length;
  assert.equal((await send({operation:'status',expectedTenantId:'10000000-0000-4000-8000-000000000002'})).error,'TENANT_MISMATCH');
  assert.equal(nativeCalls.length,count);
  assert.equal((await send({operation:'status',expectedTenantId:'quickstart'})).error,'INVALID_TENANT_ID');
});
test('a completed repair replaces a stale enrollment code in an open tab',async()=>{
  state[origin]={approved:true,phase:'ready',serverUrl:origin,userCode:'1111111111111111'};
  nativeResponse={health:{state:'UNENROLLED',ready:false},serverUrl:origin};
  const result=await request('status');
  assert.equal(result.userCode,'ABCDEF0123456789');assert.equal(state[origin].userCode,result.userCode);
  assert.deepEqual(nativeCalls,[{operation:'health',consoleUrl:origin+'/console/#enroll'},{operation:'enroll',consoleUrl:origin+'/console/#enroll'}]);
});
test('cancel during status enrollment cannot restore the old Connect session',async()=>{
  state[origin]={approved:true,phase:'ready',serverUrl:origin};let complete;
  nativeResponse=message=>message.operation==='enroll'?new Promise(resolve=>{complete=resolve;}):{health:{state:'UNENROLLED',ready:false},serverUrl:origin};
  const pending=request('status');while(!complete)await new Promise(resolve=>setImmediate(resolve));
  await request('cancel');complete({challenge:{userCode:'ABCDEF0123456789'}});
  assert.equal((await pending).phase,'cancelled');assert.equal(state[origin],undefined);
});
test('native host absence reports setup required without claiming a client',async()=>{
  const result=await request('connect');assert.equal(result.phase,'setup-required');assert.equal(result.client,null);
});
test('cancel prevents a pending gateway operation from publishing or enrolling',async()=>{
  let complete;
  nativeResponse={};
  const saved=chrome.runtime.sendNativeMessage;
  chrome.runtime.sendNativeMessage=async()=>new Promise(resolve=>{complete=resolve;});
  const pending=request('connect');
  while(!complete)await new Promise(resolve=>setImmediate(resolve));
  await request('cancel');complete({health:{state:'UNENROLLED',ready:false}});
  assert.equal((await pending).phase,'cancelled');assert.equal(state[origin],undefined);
  chrome.runtime.sendNativeMessage=saved;
});
test('only one Connect operation per origin can run at a time',async()=>{
  state[origin]={approved:true,phase:'connecting'};
  assert.equal((await request('connect')).phase,'connecting');assert.equal(nativeCalls.length,0);
});
test('a gateway switch in another tab cannot report this origin as connected',async()=>{
  state[origin]={approved:true,phase:'ready',serverUrl:origin,userCode:'ABCDEF0123456789'};
  nativeResponse={health:{state:'ACTIVE',ready:true},serverUrl:'https://other.example',consoleOrigin:'https://other.example'};
  const status=await request('status');
  assert.equal(status.phase,'tenant-mismatch');assert.equal(status.client,null);assert.equal(status.userCode,undefined);
});
test('installation appearing after Connect resumes without a repeated Windows prompt',async()=>{
  await request('connect');
  nativeResponse={health:{state:'UNENROLLED',ready:false},serverUrl:origin};
  assert.equal((await request('status')).phase,'installation-detected');
  assert.equal((await request('status')).phase,'available');
  assert.equal((await request('connect')).phase,'ready');
});
test('a cancelled gateway change is not retried automatically while a client exists',async()=>{
  nativeResponse=message=>message.operation==='connect'?{error:'UNAVAILABLE'}:{health:{state:'ACTIVE',ready:true}};
  await request('connect');
  assert.equal(state[origin].waitForInstallation,false);
  assert.equal((await request('status')).phase,'setup-required');
});

test('device proof requires Connect, an exact nonce, and the requesting console origin',async()=>{
  const nonce='a'.repeat(64);
  const proofRequest=(value,url=origin+'/console/#enroll')=>new Promise(resolve=>listener({operation:'proof',nonce:value},{tab:{id:2,url}},resolve));
  assert.equal((await proofRequest(nonce)).error,'CONNECT_REQUIRED');assert.equal(nativeCalls.length,0);
  state[origin]={approved:true,phase:'ready',serverUrl:origin};
  assert.equal((await proofRequest('bad')).error,'CONNECT_REQUIRED');assert.equal(nativeCalls.length,0);
  nativeResponse={proof:{nonce,deviceId:'device-1',keyFingerprint:'b'.repeat(64),signature:'signature'}};
  const response=await proofRequest(nonce);assert.equal(response.proof.nonce,nonce);
  assert.deepEqual(nativeCalls,[{operation:'proof',consoleUrl:origin+'/console/#enroll',nonce}]);
  assert.equal((await proofRequest(nonce,'https://other.example/console/')).error,'CONNECT_REQUIRED');
});
test('wrong tenant stops Connect without probing health or requesting enrollment',async()=>{
  nativeResponse={error:'TENANT_MISMATCH'};
  const result=await request('connect');assert.equal(result.phase,'tenant-mismatch');assert.equal(result.error,'TENANT_MISMATCH');
  assert.equal(result.client,null);assert.equal(result.userCode,undefined);assert.equal(nativeCalls.length,1);
  assert.equal(state[origin].waitForInstallation,false);
  assert.equal((await request('status')).phase,'tenant-mismatch');assert.equal(nativeCalls.length,1);
});
test('wrong tenant during status drops the old code and never starts enrollment or repair',async()=>{
  state[origin]={approved:true,phase:'ready',serverUrl:origin,userCode:'ABCDEF0123456789'};
  nativeResponse={error:'TENANT_MISMATCH'};
  const result=await request('status');assert.equal(result.phase,'tenant-mismatch');assert.equal(result.client,null);assert.equal(result.userCode,undefined);
  assert.equal(state[origin].userCode,undefined);assert.equal(nativeCalls.length,1);
});
test('cancelled health results cannot expose device metadata or an old enrollment code',async()=>{
  state[origin]={approved:true,phase:'ready',serverUrl:origin,userCode:'ABCDEF0123456789'};let complete;
  nativeResponse=()=>new Promise(resolve=>{complete=resolve;});
  const pending=request('status');while(!complete)await new Promise(resolve=>setImmediate(resolve));
  await request('cancel');complete({health:{state:'ACTIVE',ready:true},serverUrl:origin});
  const result=await pending;assert.equal(result.phase,'cancelled');assert.equal(result.client,null);assert.equal(result.userCode,undefined);
});
