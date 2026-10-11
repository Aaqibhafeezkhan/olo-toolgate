// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useCallback,useEffect,useRef,useState} from 'react';
import type {ControlServerSettings,ControlServerSettingsPatch} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ControlClient} from './api';
import {Failure} from './Failure';

export const DEFAULT_AUTO_APPROVE_DAYS=30;
const DAY_MS=86400000;
export const CHANGED_ELSEWHERE='These settings changed elsewhere. The current values are loaded; review them and retry.';
const days=(value:string)=>/^[0-9]{1,4}$/.test(value)&&Number(value)>=1&&Number(value)<=3650?Number(value):undefined;
const identifier=(value:string)=>/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/.test(value);
const gatewayName=(value:string)=>/^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,63}$/.test(value);
const durationDays=(settings:ControlServerSettings)=>String(Math.round((settings.deviceApproval.durationMs??DEFAULT_AUTO_APPROVE_DAYS*DAY_MS)/DAY_MS));

/** Parses an exported settings file (formatVersion 2). Its revision and clientCheckIn are ignored on restore. */
export function importedSettings(value:unknown):ControlServerSettings{
  if(!value||typeof value!=='object')throw new Error('Invalid settings');
  const settings=value as Partial<ControlServerSettings>;const approval=settings.deviceApproval;
  if(settings.formatVersion!==2||!settings.gateway||typeof settings.gateway.gatewayName!=='string'||!gatewayName(settings.gateway.gatewayName)
    ||!approval||typeof approval.autoApprove!=='boolean'
    ||(approval.durationMs!==undefined&&(!Number.isSafeInteger(approval.durationMs)||approval.durationMs<DAY_MS||approval.durationMs>3650*DAY_MS))
    ||(approval.ownerUserId!==undefined&&(typeof approval.ownerUserId!=='string'||!identifier(approval.ownerUserId)))
    ||(approval.autoApprove&&approval.ownerUserId===undefined))throw new Error('Invalid settings');
  return settings as ControlServerSettings;
}

/** Loads the settings record and saves one page's groups with the loaded revision. A 409 reloads the current values. */
function useSettings(client:ControlClient){
  const [saved,setSaved]=useState<ControlServerSettings>();const [error,setError]=useState<unknown>();
  const [busy,setBusy]=useState(false);const [notice,setNotice]=useState('');const [attempt,setAttempt]=useState(0);
  const locked=useRef(false);const pending=useRef<{body:string;key:string}|undefined>(undefined);
  useEffect(()=>{const abort=new AbortController();setError(undefined);
    client.serverSettings(abort.signal).then(settings=>{if(!abort.signal.aborted)setSaved(settings);},failure=>{if(!abort.signal.aborted)setError(failure);});
    return()=>abort.abort();},[client,attempt]);
  const reload=useCallback(()=>setAttempt(value=>value+1),[]);
  const run=useCallback(async(encoded:string,request:(key:string)=>Promise<ControlServerSettings>,done:string):Promise<boolean>=>{
    if(locked.current)return false;if(pending.current?.body!==encoded)pending.current={body:encoded,key:crypto.randomUUID()};
    locked.current=true;setBusy(true);setError(undefined);setNotice('');
    try{const result=await request(pending.current.key);pending.current=undefined;setSaved(result);setNotice(done);return true;}
    catch(failure){if(failure instanceof ApiError&&failure.status===409){pending.current=undefined;setNotice(CHANGED_ELSEWHERE);reload();}else setError(failure);return false;}
    finally{locked.current=false;setBusy(false);}},[reload]);
  const patch=useCallback((groups:ControlServerSettingsPatch,done:string)=>saved?run(JSON.stringify(groups),key=>client.patchServerSettings(groups,saved.revision,key),done):Promise.resolve(false),[client,run,saved]);
  const restore=useCallback((settings:ControlServerSettings,done:string)=>saved?run(JSON.stringify(settings),key=>client.restoreServerSettings({...settings,revision:saved.revision},saved.revision,key),done):Promise.resolve(false),[client,run,saved]);
  const status=<>{notice&&<p role="status">{notice}</p>}{error!==undefined&&<><Failure error={error}/><button disabled={busy} onClick={reload}>Reload settings</button></>}</>;
  return {saved,error,busy,status,patch,restore,setNotice,setError};
}

/** Configuration > Server: the gateway name devices show. */
export function ServerConfiguration({client}:{client:ControlClient}){
  const {saved,error,busy,status,patch,setNotice}=useSettings(client);const [name,setName]=useState<string>();
  useEffect(()=>{if(saved)setName(saved.gateway.gatewayName);},[saved]);
  const trimmed=name?.trim()??'';const valid=gatewayName(trimmed);const changed=Boolean(saved)&&trimmed!==saved!.gateway.gatewayName;
  return <><div className="page-heading"><h1>Server</h1></div>
    <p className="intro">Settings for this ToolGate server. Saving changes only the fields on this page.</p>
    <section className="guidance" aria-label="Server settings">
      {!saved&&!error&&<p role="status">Loading server settings…</p>}
      {saved&&name!==undefined&&<fieldset disabled={busy}><legend>Gateway</legend>
        <label htmlFor="gateway-name">Gateway name</label>
        <input id="gateway-name" value={name} maxLength={64} onChange={event=>{setName(event.target.value);setNotice('');}} aria-describedby="gateway-name-help"/>
        <p id="gateway-name-help" className="hint">Devices show this name with the gateway URL on their Status and Activity log tabs, and pick up a rename on their next check-in. It starts as TOOLGATE_GATEWAY_NAME. Use up to 64 letters, numbers, spaces and . _ ( ) -</p>
        {!valid&&<p role="alert">Enter a gateway name that starts with a letter or number.</p>}
        <button className="primary" disabled={!valid||!changed} onClick={()=>void patch({gateway:{gatewayName:trimmed}},'Server settings saved.')}>Save server settings</button>
      </fieldset>}
      {status}
    </section></>;
}

type ApprovalDraft={autoApprove:boolean;days:string;owner:string};
const approvalDraft=(settings:ControlServerSettings):ApprovalDraft=>({autoApprove:settings.deviceApproval.autoApprove,days:durationDays(settings),owner:settings.deviceApproval.ownerUserId??''});

/** Configuration > Device: bounded device auto-approval and the deployment's read-only check-in values. */
export function DeviceConfiguration({client}:{client:ControlClient}){
  const {saved,error,busy,status,patch,setNotice}=useSettings(client);const [draft,setDraft]=useState<ApprovalDraft>();
  useEffect(()=>{if(saved)setDraft(approvalDraft(saved));},[saved]);
  const duration=draft&&days(draft.days);const owner=draft?.owner.trim()??'';
  const valid=Boolean(draft)&&duration!==undefined&&(owner===''?!draft?.autoApprove:identifier(owner));
  const changed=Boolean(saved&&draft)&&JSON.stringify(approvalDraft(saved!))!==JSON.stringify({...draft!,owner});
  const update=(change:Partial<ApprovalDraft>)=>{setDraft(current=>current&&{...current,...change});setNotice('');};
  const checkIn=saved?.clientCheckIn;
  return <><div className="page-heading"><h1>Device</h1></div>
    <p className="intro">How new devices are approved and how often connected devices check in. Saving changes only device approval.</p>
    <section className="guidance" aria-label="Device settings">
      {!saved&&!error&&<p role="status">Loading device settings…</p>}
      {draft&&<fieldset disabled={busy}><legend>Device approval</legend>
        <label className="checkbox"><input type="checkbox" checked={draft.autoApprove} onChange={event=>update({autoApprove:event.target.checked})}/> Auto approve devices</label>
        <p className="hint">New devices that start enrollment are approved immediately, without an administrator decision, for the duration below. Existing devices are not changed. Leave this off unless every device that can reach this server should get access.</p>
        <label htmlFor="auto-approve-duration">Auto approve duration (days)</label>
        <input id="auto-approve-duration" type="number" inputMode="numeric" min={1} max={3650} required value={draft.days} onChange={event=>update({days:event.target.value})}/>
        {duration===undefined&&<p role="alert">Enter between 1 and 3650 days. Default: {DEFAULT_AUTO_APPROVE_DAYS} days.</p>}
        <label htmlFor="auto-approve-owner">Owner user ID for auto-approved devices</label>
        <input id="auto-approve-owner" value={draft.owner} maxLength={128} onChange={event=>update({owner:event.target.value})} aria-describedby="auto-approve-owner-help"/>
        <p id="auto-approve-owner-help" className="hint">Required while auto-approval is on. The user must exist and be enabled; devices fall back to manual approval if it is later disabled.</p>
        {draft.autoApprove&&!owner&&<p role="alert">Enter the owner user ID to turn on auto-approval.</p>}
        <button className="primary" disabled={!valid||!changed} onClick={()=>void patch({deviceApproval:{autoApprove:draft.autoApprove,durationMs:duration!*DAY_MS,...(owner?{ownerUserId:owner}:{})}},'Device settings saved.')}>Save device settings</button>
      </fieldset>}
      {checkIn&&<fieldset aria-describedby="check-in-help"><legend>Check-in</legend>
        <p id="check-in-help" className="hint">Set by the deployment ({checkIn.source==='DEFAULT'?'built-in defaults':'environment variables'}) and read-only here. Change TOOLGATE_CONTROL_CLIENT_CHECKIN_MS, TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT or TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS, or the Helm values under control.clientCheckIn, then restart Control.</p>
        <dl className="policy-details"><dt>Check-in interval</dt><dd>{checkIn.checkInMs} ms</dd><dt>Jitter</dt><dd>{checkIn.jitterPct} %</dd><dt>Offline after</dt><dd>{checkIn.offlineAfterMs} ms</dd></dl>
      </fieldset>}
      {status}
    </section></>;
}

/** Configuration > Backup/Restore: the server settings file. Restore replaces the editable groups after review. */
export function SettingsTransfer({client}:{client:ControlClient}){
  const {saved,busy,status,restore,setNotice,setError}=useSettings(client);const [loaded,setLoaded]=useState<ControlServerSettings>();
  function exportSettings(){if(!saved)return;
    const url=URL.createObjectURL(new Blob([JSON.stringify(saved,null,2)+'\n'],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download=`toolgate-server-settings-r${saved.revision}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),0);
    setNotice('Server settings exported. Mount the file as server-settings.json in the configuration import folder to apply it during bring-up.');}
  async function importSettings(file:File|undefined){if(!file)return;setError(undefined);setLoaded(undefined);
    if(file.size>16384){setNotice('Choose a settings file no larger than 16 KiB.');return;}
    try{setLoaded(importedSettings(JSON.parse(await file.text()) as unknown));setNotice('Settings file loaded. Review it, then restore.');}
    catch{setNotice('The file is not an exported ToolGate server settings document (format version 2).');}}
  return <section className="guidance" aria-label="Server settings file"><h2>Server settings file</h2>
    <button disabled={busy||!saved} onClick={exportSettings}>Export server settings</button>
    <label>Server settings JSON file<input type="file" accept=".json,application/json" disabled={busy||!saved} onChange={event=>{void importSettings(event.target.files?.[0]);event.target.value='';}}/></label>
    {loaded&&<><dl className="policy-details"><dt>Gateway name</dt><dd>{loaded.gateway.gatewayName}</dd><dt>Auto approve devices</dt><dd>{loaded.deviceApproval.autoApprove?'On':'Off'}</dd>
      <dt>Auto approve duration</dt><dd>{durationDays(loaded)} days</dd><dt>Owner user ID</dt><dd>{loaded.deviceApproval.ownerUserId??'None'}</dd></dl>
      <button className="primary" disabled={busy} onClick={()=>void restore(loaded,'Server settings restored.').then(ok=>{if(ok)setLoaded(undefined);})}>Restore server settings</button></>}
    <p className="hint">Check-in values in a settings file are ignored; they always come from the deployment. At bring-up, the server also imports server-settings.json from the folder in TOOLGATE_CONFIG_IMPORT_DIRECTORY, then applies TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES, TOOLGATE_SETTINGS_AUTO_APPROVE_DURATION_DAYS and TOOLGATE_SETTINGS_AUTO_APPROVE_OWNER. Startup import only seeds settings once unless TOOLGATE_CONFIG_IMPORT_OVERWRITE is true.</p>
    {status}
  </section>;
}
