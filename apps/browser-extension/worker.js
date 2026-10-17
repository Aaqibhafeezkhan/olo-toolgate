// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {consoleOrigin, protocol} from './protocol.js';
chrome.runtime.onInstalled.addListener(async () => {
  const tabs = await chrome.tabs.query({url:['https://*/*','http://127.0.0.1/*','http://localhost/*']});
  for (const tab of tabs) {
    try {
      consoleOrigin(tab.url);
      await chrome.scripting.executeScript({target:{tabId:tab.id},files:['bridge.js']});
    } catch { /* Only existing console tabs need a bridge. */ }
  }
});
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  (async () => {
    let origin;
    try { origin = consoleOrigin(sender.tab?.url ?? ''); } catch { return {error:'INVALID_ORIGIN'}; }
    const result = {protocol,version:chrome.runtime.getManifest().version_name,chromeVersion:chrome.runtime.getManifest().version,client:null};
    if (message.operation === 'hello') return {...result,phase:'available'};
    const state = (await chrome.storage.local.get(origin))[origin];
    if (message.operation==='proof') {
      if(!state?.approved||state.phase!=='ready'||!/^[a-f0-9]{64}$/.test(message.nonce))return {...result,error:'CONNECT_REQUIRED'};
      const proof=await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'proof',consoleUrl:sender.tab.url,nonce:message.nonce});
      return proof.error?{...result,error:'PROOF_UNAVAILABLE'}:{...result,proof:proof.proof};
    }
    if (message.operation === 'cancel') {
      await chrome.storage.local.remove(origin);
      return {...result,phase:'cancelled'};
    }
    if (message.operation === 'connect') {
      if (state?.phase === 'connecting' && Date.now() - (state.started ?? Date.now()) < 900000) return {...result,phase:'connecting'};
      const operationId = crypto.randomUUID();
      await chrome.storage.local.set({[origin]:{approved:true,phase:'connecting',operationId,started:Date.now()}});
      try {
        const client = await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect', {
          operation:'connect',consoleUrl:sender.tab.url
        });
        if ((await chrome.storage.local.get(origin))[origin]?.operationId !== operationId) return {...result,phase:'cancelled'};
        if (client.error || !client.health) throw Error('Client unavailable');
        let userCode;
        if (['UNENROLLED','PENDING'].includes(client.health.state)) {
          const enrollment = await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'enroll'});
          if (!enrollment.error && enrollment.challenge) userCode = enrollment.challenge.userCode;
        }
        if ((await chrome.storage.local.get(origin))[origin]?.operationId !== operationId) return {...result,phase:'cancelled'};
        await chrome.storage.local.set({[origin]:{approved:true,phase:'ready',serverUrl:client.serverUrl,userCode}});
        return {...result,phase:'ready',client,userCode};
      } catch {
        let waitForInstallation=true;
        try {
          const health=await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'health'});
          waitForInstallation=Boolean(health.error)||!health.health;
        } catch { /* A missing host or service can appear after the combined EXE is opened. */ }
        if ((await chrome.storage.local.get(origin))[origin]?.operationId === operationId)
          await chrome.storage.local.set({[origin]:{approved:true,phase:'setup-required',waitForInstallation}});
        return {...result,phase:'setup-required'};
      }
    }
    if (message.operation !== 'status') return {error:'INVALID_OPERATION'};
    if (!state?.approved) return {...result,phase:'available'};
    if (state.phase==='setup-required'&&state.waitForInstallation) {
      try {
        const health=await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'health'});
        if(!health.error&&health.health) {
          await chrome.storage.local.set({[origin]:{approved:true,phase:'available'}});
          return {...result,phase:'installation-detected'};
        }
      } catch { /* Keep waiting for installation without reopening Windows prompts. */ }
    }
    if (state.phase !== 'ready') return {...result,phase:state.phase === 'connecting' && Date.now() - (state.started ?? Date.now()) >= 900000 ? 'setup-required' : state.phase};
    // Chrome terminates one-shot host after its response. Host itself enforces a 20s IPC deadline.
    try {
      result.client = await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'health'});
    } catch { /* Missing host, denied OS peer or stopped client remains unavailable. */ }
    if (result.client?.health && state.serverUrl && result.client.serverUrl !== state.serverUrl)
      return {...result,client:null,phase:'gateway-changed'};
    if (!result.client?.error && ['UNENROLLED','PENDING'].includes(result.client?.health?.state)) {
      const enrollment=await chrome.runtime.sendNativeMessage('io.ololabs.toolgate.connect',{operation:'enroll'});
      // A completed installer repair can replace an expired enrollment while this tab remains open.
      const current=(await chrome.storage.local.get(origin))[origin];
      if(!current?.approved||current.phase!=='ready'||current.serverUrl!==state.serverUrl)return {...result,client:null,phase:'cancelled'};
      if(!enrollment.error&&enrollment.challenge) {
        state.userCode=enrollment.challenge.userCode;
        await chrome.storage.local.set({[origin]:state});
      }
    }
    return {...result,phase:state.phase,serverUrl:state.serverUrl,userCode:state.userCode};
  })().then(reply).catch(() => reply({error:'UNAVAILABLE'}));
  return true;
});
