// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useCallback,useEffect,useRef,useState} from 'react';
import {ApiError,ControlClient} from './api';
import type {PendingServerSettings as ControlServerSettings,PendingServerSettingsPatch as ControlServerSettingsPatch,RequiredReviews} from './pendingContracts';
import {Failure} from './Failure';

export const DEFAULT_AUTO_APPROVE_DAYS=30;
const DAY_MS=86400000;
export const CHANGED_ELSEWHERE='These settings changed elsewhere. The current values are loaded; review them and retry.';
const days=(value:string)=>/^[0-9]{1,4}$/.test(value)&&Number(value)>=1&&Number(value)<=3650?Number(value):undefined;
const identifier=(value:string)=>/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/.test(value);
const gatewayName=(value:string)=>/^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,63}$/.test(value);
const durationDays=(settings:ControlServerSettings)=>String(Math.round((settings.deviceApproval.durationMs??DEFAULT_AUTO_APPROVE_DAYS*DAY_MS)/DAY_MS));

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
  const status=<>{notice&&<p role="status">{notice}</p>}{error!==undefined&&<><Failure error={error}/><button disabled={busy} onClick={reload}>Reload settings</button></>}</>;
  return {saved,error,busy,status,patch,setNotice};
}

export const REVIEW_POLICY_CHANGE='toolgate:review-policy-change';
const reviewChoices:readonly {value:RequiredReviews;label:string}[]=[{value:0,label:'0 — changes apply immediately'},{value:1,label:'1 independent review'},{value:2,label:'2 independent reviews'}];

/** Administration > Server: the gateway name devices show and the configuration review threshold. */
export function ServerConfiguration({client}:{client:ControlClient}){
  const {saved,error,busy,status,patch,setNotice}=useSettings(client);const [name,setName]=useState<string>();
  const [reviews,setReviews]=useState<RequiredReviews>();
  useEffect(()=>{if(saved){setName(saved.gateway.gatewayName);setReviews(saved.configuration?.requiredReviews);}},[saved]);
  const current=saved?.configuration?.requiredReviews;
  function saveReviews(){if(reviews===undefined)return;
    void patch({configuration:{requiredReviews:reviews}},'Configuration reviews saved.').then(ok=>{if(ok)window.dispatchEvent(new Event(REVIEW_POLICY_CHANGE));});}
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
      {saved&&current!==undefined&&reviews!==undefined&&<fieldset disabled={busy}><legend>Configuration reviews</legend>
        <label htmlFor="required-reviews">Configuration reviews</label>
        <select id="required-reviews" value={reviews} onChange={event=>{setReviews(Number(event.target.value) as RequiredReviews);setNotice('');}} aria-describedby="required-reviews-help">{reviewChoices.map(choice=><option key={choice.value} value={choice.value}>{choice.label}</option>)}</select>
        <p id="required-reviews-help" className="hint">Independent reviews each configuration change needs before it applies. With 0, changes apply immediately and every page shows a banner. A change keeps the count in force when it was created. Changing this value itself needs the current number of reviews{current>0?`, so saving creates a configuration change that ${current===1?'one reviewer':'two reviewers'} must approve`:''}; the Super Admin bypass does not apply. Operation approvals are not affected. It starts as TOOLGATE_CONFIG_REQUIRED_REVIEWS.</p>
        <button className="primary" disabled={reviews===current} onClick={saveReviews}>Save configuration reviews</button>
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
