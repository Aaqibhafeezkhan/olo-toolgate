// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {useState} from 'react';
import type {DeploymentProfile,WorkflowProfileStatus} from '@olo-labs/toolgate-contracts/v2';
import {ControlClient} from './api';
import {Failure} from './Failure';
import {EditorDialog} from './EditorDialog';
import {ProfileFields,initialField,profileSchema} from './ProfileFields';

/** Immutable revisions keep draft edits separate from reviewed and published configuration. */
export function WorkflowProfiles({client}:{client:ControlClient}){
  const [open,setOpen]=useState(false),[draft,setDraft]=useState<unknown>(()=>initialField(profileSchema));
  const [id,setId]=useState(''),[digest,setDigest]=useState(''),[status,setStatus]=useState<WorkflowProfileStatus>();
  const [busy,setBusy]=useState(false),[error,setError]=useState<unknown>(),[notice,setNotice]=useState(''),[epoch,setEpoch]=useState(0);
  async function run(action:()=>Promise<void>){if(busy)return;setBusy(true);setError(undefined);setNotice('');try{await action();}catch(e){setError(e);}finally{setBusy(false);}}
  const edit=(value:unknown)=>{setDraft(value);setStatus(undefined);setNotice('Draft changed. Stage it before review or publication.');};
  async function load(){const [document,current]=await Promise.all([client.workflowProfileDocument(id,digest),client.workflowProfileStatus(id,digest)]);setDraft(document);setStatus(current);setEpoch(e=>e+1);setNotice('Loaded exact revision.');}
  return <section className="guidance"><h2>Workflow profiles</h2><p>Manage workflow groups, workflows, agents, models, prompts, context, dispatch bindings, packages and readiness cases. Current Super Admin authority is required by Control.</p>
    <button onClick={()=>setOpen(true)}>Manage workflow profile</button>
    {open&&<EditorDialog title="Manage workflow profile" close={()=>setOpen(false)} busy={busy}><div className="workflow-profile-editor">
      {error!==undefined&&<Failure error={error}/>}<p role="status">{notice}</p>
      <fieldset disabled={busy}><legend>Open an existing revision</legend><label>Profile identifier<input value={id} onChange={e=>setId(e.target.value)}/></label><label>Revision digest<input value={digest} onChange={e=>setDigest(e.target.value)}/></label><button disabled={!id||!digest} onClick={()=>void run(load)}>Load revision</button><p>Use the exact identifier and digest returned when staging. Control currently has no profile catalog listing API.</p></fieldset>
      <form onSubmit={e=>{e.preventDefault();void run(async()=>{const staged=await client.stageWorkflowProfile(draft as DeploymentProfile);setStatus(staged);setId(staged.profileId);setDigest(staged.profileDigest);setNotice('Staged immutable revision. Review this exact content before provisioning or publishing.');});}}>
        <fieldset disabled={busy}><ProfileFields key={epoch} value={draft} change={edit}/><button className="primary">Stage profile revision</button></fieldset>
      </form>
      {status&&<section aria-label="Profile lifecycle"><h3>{status.profileId}: {status.state}</h3><p>Digest: {status.profileDigest}</p><p>Generation: {status.generation}. Dependencies: {status.dependenciesReady?'ready':'not ready'}.</p>
        <p>Review applies to this exact revision. Provisioning applies its packages. Publication activates reviewed configuration when Control verifies current dependencies and generation; it does not grant runtime access.</p>
        <button disabled={busy} onClick={()=>void run(async()=>setStatus(await client.workflowProfileStatus(status.profileId,status.profileDigest)))}>Refresh lifecycle status</button>
        <button disabled={busy||status.state!=='DRAFT'} onClick={()=>void run(async()=>setStatus(await client.reviewWorkflowProfile(status.profileId,status.profileDigest)))}>Review exact revision</button>
        <button disabled={busy||status.state==='DRAFT'} onClick={()=>void run(async()=>{const result=await client.provisionWorkflowProfile(status.profileId,status.profileDigest);setStatus(await client.workflowProfileStatus(status.profileId,status.profileDigest));setNotice(`Package provisioning: ${result.state}`);})}>Provision reviewed packages</button>
        <button disabled={busy||status.state!=='REVIEWED'||!status.dependenciesReady} onClick={()=>void run(async()=>setStatus(await client.publishWorkflowProfile(status.profileId,status.profileDigest,status.generation)))}>Publish reviewed revision</button>
      </section>}
    </div></EditorDialog>}
  </section>;
}
