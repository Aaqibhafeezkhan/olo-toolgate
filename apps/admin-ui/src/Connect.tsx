// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {usePermissions} from './Permissions';
import {useEffect, useRef, useState} from 'react';
import type {ClientInstallerManifest} from '@olo-labs/toolgate-contracts';
import {ControlClient} from './api';
import {extensionStatus,type ConnectStatus} from './ChromeBridge';
import {verifyCurrentDevice} from './ClientDeviceProof';
export {extensionStatus,type ConnectStatus} from './ChromeBridge';

type ExtensionRelease = {protocol:number;version:string;chromeVersion:string;extensionId:string;storeUrl:string;filename:string;sha256:string;bytes:number};

export function Connect({onCode,onCancel,client}:{onCode:(code:string)=>void;onCancel?:()=>void;client?:ControlClient}) {
  const permissions=usePermissions(client);const canConnect=!client||permissions.can('device.enroll');
  const [message,setMessage]=useState('Install the combined setup and enable the Chrome extension, then Connect to configure this gateway and enroll the client.');
  const [busy,setBusy]=useState(false),[release,setRelease]=useState<ExtensionRelease>();
  const [installer,setInstaller]=useState<string>();
  const active=useRef<AbortController|undefined>(undefined);
  useEffect(()=>()=>active.current?.abort(),[]);
  const supported=/Chrome\//.test(navigator.userAgent)&&/Windows/.test(navigator.userAgent)&&!/Edg\/|OPR\//.test(navigator.userAgent);
  async function start() {
    if(!canConnect)return;
    active.current?.abort();const controller=new AbortController();active.current=controller;
    setBusy(true);setRelease(undefined);setInstaller(undefined);setMessage('Checking Chrome Connect release…');
    try {
      if(!supported)throw Error('Currently supported: Chrome on Windows. Use the client downloads below for other browsers and systems.');
      const response=await fetch('/api/public/v1/clients/extension',{credentials:'omit',redirect:'error',cache:'no-store',signal:AbortSignal.any([controller.signal,AbortSignal.timeout(10000)])});
      if(!response.ok)throw Error('Connect extension release is unavailable. Use the client downloads below or contact your administrator.');
      const text=await response.text();if(text.length>65536)throw Error('Invalid extension release.');
      const value=JSON.parse(text) as ExtensionRelease;
      if(value.protocol!==1||value.extensionId!=='emmemldedebhbloibichmmdlbpjakfkf'||typeof value.version!=='string'
        ||!/^\d{1,5}(\.\d{1,5}){3}$/.test(value.chromeVersion)||!/^olo-toolgate-chrome-[0-9A-Za-z._-]+\.zip$/.test(value.filename)
        ||!/^[a-f0-9]{64}$/.test(value.sha256)||!['','https://chromewebstore.google.com/detail/'+value.extensionId].includes(value.storeUrl))throw Error('Invalid extension release.');
      setRelease(value);
      try {
        const setupResponse=await fetch('/api/public/v1/installers',{credentials:'omit',redirect:'error',cache:'no-store',signal:AbortSignal.any([controller.signal,AbortSignal.timeout(10000)])});
        if(setupResponse.ok) {
          const document=await setupResponse.text();
          if(document.length<=65536) {
            const setup=JSON.parse(document) as ClientInstallerManifest;
            const targets=/ARM|aarch64/i.test(navigator.userAgent)?['aarch64-pc-windows-msvc']:['x86_64-pc-windows-msvc','x86_64-pc-windows-gnu'];
            const asset=Array.isArray(setup.artifacts)?targets.map(target=>setup.artifacts.find(a=>a.platform==='WINDOWS'&&a.target===target)).find(Boolean):undefined;
            if(setup.version===value.version&&asset&&asset.filename===`olo-toolgate-client-${value.version}-${asset.target}.setup.exe`
              &&/^[a-f0-9]{64}$/.test(asset.sha256)&&Number.isSafeInteger(asset.bytes)&&asset.bytes>0&&asset.bytes<=104857600)
              setInstaller(asset.target);
          }
        }
      } catch { /* Missing setup must not turn an extension ZIP into an installer. */ }
      const deadline=Date.now()+900000;let detected=false;let requested=false;let previousCode='';let retried=false;let lastProof=0;let proofError='';
      while(!controller.signal.aborted&&Date.now()<deadline) {
        let status:ConnectStatus|undefined;
        try { status=await extensionStatus(controller.signal,detected?'status':'hello'); } catch { if(controller.signal.aborted)break;detected=false; }
        if(controller.signal.aborted)break;
        if(!status) setMessage('Chrome extension not detected. Complete the Chrome setup steps below. This page will detect it automatically and continue client setup.');
        else if(status.protocol!==1||status.chromeVersion!==value.chromeVersion||status.version!==value.version)
          setMessage('Upgrade the Chrome extension below. This page will detect the new version automatically.');
        else {
          detected=true;
          if(status.phase==='installation-detected')requested=false;
          // This page is trying to connect: a client on another gateway, or one this gateway no
          // longer accepts after this page's Connect, is a wrong configuration. Retry once from scratch; an already
          // approved device reconnects without a new approval.
          const health=status.client?.health;
          if(client&&status.phase==='ready'&&health&&!status.client?.error&&Date.now()-lastProof>=60000){
            lastProof=Date.now();setMessage('Verifying this device for the signed-in console session…');
            try{await verifyCurrentDevice(client,controller.signal,['UNENROLLED','PENDING'].includes(health.state??'')?status.userCode:undefined);proofError='';}
            catch(error){if(controller.signal.aborted)break;proofError=error instanceof Error?error.message:'Current-device verification failed. Retry Connect.';}
          }
          if(requested&&!retried&&(status.phase==='gateway-changed'||(status.phase==='ready'&&!status.client?.error&&health&&!health.ready&&['OFFLINE','REVOKED'].includes(health.state??'')))){retried=true;requested=false;}
          if(!requested){requested=true;void extensionStatus(controller.signal,'connect').catch(()=>{});}
          if(status.phase==='gateway-changed'&&previousCode){previousCode='';onCancel?.();}
          if(['UNENROLLED','PENDING'].includes(health?.state??'')&&typeof status.userCode==='string'&&/^[A-F0-9]{16}$/.test(status.userCode)&&status.userCode!==previousCode){previousCode=status.userCode;onCode(status.userCode);}
          setMessage(proofError|| (status.client?.health&&!status.client.error
            ? 'Client detected'+(status.serverUrl?' at '+status.serverUrl:'')+'. '+(status.client.health.ready
              ? 'The client reports successful gateway check-ins. Open Clients to view its current server status.'
              : status.client.health.state==='REVOKED' ? 'This gateway no longer accepts the device. Click Retry Connect to request a fresh enrollment for approval.'
              : ['UNENROLLED','PENDING'].includes(status.client.health.state??'') ? 'Complete enrollment using the code and fingerprint below. Connection will be confirmed after the first successful check-in.'
              : 'The client is offline: no recent successful gateway check-in. Click Retry Connect: it reconnects, or requests a fresh enrollment if this gateway no longer accepts the device.')
            : status.phase==='connecting' ? 'Installing the verified client or updating its gateway URL. Approve Windows administrator permission if shown…'
            : status.phase==='gateway-changed' ? (retried ? 'The client was connected to another gateway. Reconnecting it to this gateway…' : 'The client was connected to another gateway. Click Retry Connect to use this gateway again.')
            : status.phase==='setup-required' ? 'Install the combined client and Chrome extension setup below, then click Retry Connect. If setup is installed, check the gateway connection and Windows permissions.'
            : 'Chrome extension detected. Configuring the client for this gateway…'));
        }
        await new Promise<void>(resolve=>{
          const finish=()=>{clearTimeout(timer);controller.signal.removeEventListener('abort',finish);resolve();};
          const timer=setTimeout(finish,2000);controller.signal.addEventListener('abort',finish,{once:true});
        });
      }
      if(!controller.signal.aborted)setMessage('Connect timed out. Retry when the extension and client are ready.');
    } catch(error) { if(!controller.signal.aborted)setMessage(error instanceof Error?error.message:'Connect unavailable. Retry.'); }
    finally {if(active.current===controller)setBusy(false);}
  }
  function cancel(){active.current?.abort();onCancel?.();setBusy(false);setMessage('Connect cancelled. Close any open Windows installer separately.');const controller=new AbortController();void extensionStatus(controller.signal,'cancel').catch(()=>{});}
  return <section aria-labelledby="connect-title"><h2 id="connect-title">Connect this computer</h2>
    <button disabled={!canConnect} onClick={()=>void start()}>{busy?'Retry Connect':'Connect'}</button>{busy&&<button onClick={cancel}>Cancel Connect</button>}
    <p role="status">{message}</p>
    {release&&<><p>Chrome extension {release.chromeVersion} · Client {release.version}</p>
      {installer?<a href={`/api/public/v1/clients/setup/${installer}`} download>Install Chrome extension and client</a>:<p>Windows setup is not published for this client release.</p>}
      <p>One EXE installs the client, Chrome extension files, native bridge and OLO tray icon. The download selects this gateway and setup configures local certificate trust. The optional Gateway URL field lets you change the address; a local gateway uses https://localhost:18450. Windows may request administrator permission. Once Chrome approves the extension, this page detects the client and starts enrollment if needed.</p>
      {release.storeUrl?<a href={release.storeUrl} target="_blank" rel="noopener noreferrer">Install or upgrade Chrome extension</a>
        : <div className="guidance"><h3>Enable the Chrome extension for this local build</h3>
          <p>A Chrome Web Store listing is not configured yet. The EXE installs extension files; Chrome requires you to enable them before this page can detect the client.</p>
          <ol><li>Install the combined setup above.</li><li>Open <code>chrome://extensions</code> in Chrome and enable <strong>Developer mode</strong>.</li>
            <li>Click <strong>Load unpacked</strong> and select <code>C:\Program Files\OLO\ToolGateSetup\chrome-extension</code>.</li>
            <li>Return to this page. Detection continues automatically while Connect is running; otherwise click <strong>Connect</strong>.</li></ol></div>}
      <details><summary>Extension SHA-256</summary><code>{release.sha256}</code></details></>}
  </section>;
}
