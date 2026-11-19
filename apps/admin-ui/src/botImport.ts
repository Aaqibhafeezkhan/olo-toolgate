// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import type {DeploymentProfile,TriggerAssignment,TriggerSubscription,WorkflowProfileStatus} from '@olo-labs/toolgate-contracts/v2';
import {ApiError,ConfigurationPending,ControlClient} from './api';
import {applyWorkflow,workflowAssignmentMatches,type WorkflowBundle} from './workflowBundle';
import {mergeReferences,validateReferences} from './workflowReferences';
import {sha256} from './workflowPackages';

export type ImportStep='bundle'|'references'|'packages'|'identities'|'provisioning'|'connections'|'graph';
const advice:Record<ImportStep,string>={
  bundle:'Choose a complete .olo bundle rebuilt with the current bot builder; raw JSON and obsolete trigger connection fields are not accepted.',
  references:'Check existing bot owners and conflicting referenced configuration.',
  packages:'Check verification of the embedded tool and trigger packages.',
  identities:'Check the destination service bindings for the configured agents.',
  provisioning:'Check an enabled device in the declared groups and package provisioning status.',
  connections:'Check the declared trigger assignments and subscriptions.',
  graph:'Check the configured runtime graph package and graph service readiness.',
};
/** Own bounded messages only; provider/server bodies and arbitrary exceptions are never shown. */
export class BotImportError extends Error {
  readonly requestId?:string;
  constructor(readonly step:ImportStep,cause?:unknown){
    super(`Bot import stopped during ${step}. ${advice[step]}${cause instanceof ApiError?' '+cause.message:''}`);
    this.requestId=cause instanceof ApiError?cause.requestId:undefined;
  }
}

export async function triggerCatalog(client:ControlClient){
  const assignments:TriggerAssignment[]=[];let token:string|undefined;
  do{const page=await client.triggerAssignments(token);assignments.push(...page.items);token=page.nextPageToken;if(assignments.length>1000)throw new BotImportError('connections');}while(token);
  const subscriptions:readonly TriggerSubscription[]=(await client.triggerSubscriptions()).items;
  return {assignments,subscriptions};
}

/** One bundle prepares its configuration closure through current Admin APIs.
 * Device approval/membership, secrets, file content and native qualification are installation authority.
 * Partial preparation stays staged; this never publishes or retries a tool effect.
 */
export async function prepareBotImport(client:ControlClient,bundle:WorkflowBundle,base:DeploymentProfile,targetId:string|undefined,
  options:{progress?:(step:ImportStep)=>void;stored?:(profile:DeploymentProfile,status:WorkflowProfileStatus)=>Promise<void>;owners?:Readonly<Record<string,string>>}={}){
  let step:ImportStep='references';
  const at=(value:ImportStep)=>{step=value;options.progress?.(value);};
  try{
    validateReferences(bundle.references);
    const references=structuredClone(bundle.references);
    const ownerMap=options.owners??{};
    for(const owner of references.requiredIdentities??[]){
      const id=ownerMap[owner.id]||owner.id;
      if(!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(id)||!(await client.user(id)).enabled)throw new BotImportError(step);
    }
    const incoming={...references,
      agents:references.agents.map(agent=>({...agent,ownerUserId:ownerMap[agent.ownerUserId]||agent.ownerUserId})),
      requiredIdentities:(references.requiredIdentities??[]).map(owner=>({...owner,id:ownerMap[owner.id]||owner.id}))};
    const snapshot=mergeReferences(incoming,await client.exportConfiguration());
    const identities={removeUnlistedMemberships:false,identityResolution:Object.fromEntries((snapshot.requiredIdentities??[]).map(owner=>[owner.id,{action:'EXISTING' as const,teamIds:[]}]))};
    let sources=await triggerCatalog(client);
    const candidate=applyWorkflow(base,bundle,targetId,sources.assignments,sources.subscriptions,true);
    // Conflict/owner checks precede package uploads or configuration writes.
    const preview=await client.previewConfiguration(snapshot,'MERGE',identities);
    at('packages');
    for(const item of bundle.packages){
      const stored=await client.uploadServerPackage(new File([new Uint8Array(item.bytes).buffer],item.metadata.packageId+'.olotool'));
      if(stored.state!=='VERIFIED'||stored.packageId!==item.metadata.packageId||stored.version!==item.metadata.version||stored.packageDigest!==item.metadata.packageDigest)throw new BotImportError(step);
    }
    at('references');
    if(preview.changes.length)await client.importConfiguration(snapshot,'MERGE',crypto.randomUUID(),identities);
    at('identities');
    const bindings=await client.all('workloadBindings');
    for(const agent of candidate.workflows!.agentDefinitions){
      const matches=bindings.filter(row=>row.agentId===agent.agentId&&row.mode==='SERVICE'&&row.enabled&&!row.parentBindingId);
      if(matches.length){if(matches.length!==1||matches[0].expiresAtUnixMs<=Date.now())throw new BotImportError(step);continue;}
      const id='workflow-'+(await sha256(new TextEncoder().encode(agent.agentId))).slice(7,39);
      await client.saveRecord('workloadBindings',{id,name:'Bot '+agent.agentId,enabled:true,revision:1,agentId:agent.agentId,
        mode:'SERVICE',issuer:'gateway',subject:id,audience:'gateway',credentialSha256:(await sha256(crypto.getRandomValues(new Uint8Array(32)))).slice(7),
        credentialEpoch:1,expiresAtUnixMs:Date.now()+86400000},false,crypto.randomUUID());
    }
    async function stage(profile:DeploymentProfile){
      const status=await client.stageWorkflowProfile(profile);
      await options.stored?.(profile,status);
      await client.reviewWorkflowProfile(status.profileId,status.profileDigest);
      return status;
    }
    async function provision(status:WorkflowProfileStatus){
      at('provisioning');
      const result=await client.provisionWorkflowProfile(status.profileId,status.profileDigest);
      if(result.state==='FAILED')throw new BotImportError(step);
      return result.state==='READY';
    }
    async function pending(profile:DeploymentProfile,staged:WorkflowProfileStatus){
      const status=await client.workflowProfileStatus(staged.profileId,staged.profileDigest);
      await options.stored?.(profile,status);
      return {profile,status,pending:true};
    }
    const preparing={...candidate,workflows:{...candidate.workflows!,dispatchBindings:[]}};
    const initial=await stage(preparing);
    if(!await provision(initial))return pending(preparing,initial);
    at('connections');sources=await triggerCatalog(client);
    for(const connection of bundle.connections){
      const assignment=sources.assignments.find(item=>item.metadata.name===connection.assignment);
      // Managed placement comes from Control provisioning, never a browser-wide broadcast.
      if(!assignment||!workflowAssignmentMatches(candidate,assignment,connection.assignmentSpec))throw new BotImportError(step);
      const subscription=sources.subscriptions.find(item=>item.metadata.name===connection.subscription);
      if(subscription){
        const normalize=(value:unknown)=>JSON.stringify(value);
        if(subscription.spec.expiresAtUnixMs<=Date.now()||subscription.spec.filter!==connection.subscriptionSpec.filter||normalize([...subscription.spec.sources.assignments].sort())!==normalize([...connection.subscriptionSpec.sources.assignments].sort()))throw new BotImportError(step);
      }else{
        await client.createTriggerSubscription({name:connection.subscription,spec:{...connection.subscriptionSpec,
          expiresAtUnixMs:Math.min(connection.subscriptionSpec.expiresAtUnixMs,Date.now()+30*86400000)}},crypto.randomUUID());
      }
    }
    sources=await triggerCatalog(client);
    const profile=applyWorkflow(candidate,bundle,targetId||bundle.profile.workflows!.workflows[0].id,sources.assignments,sources.subscriptions);
    const staged=await stage(profile);if(!await provision(staged))return pending(profile,staged);
    at('graph');const results=await client.prepareWorkflowGraphs(staged.profileId,staged.profileDigest,staged.generation);
    if(results.some(result=>result.state!=='READY'))throw new BotImportError(step);
    const status=await client.workflowProfileStatus(staged.profileId,staged.profileDigest);
    await options.stored?.(profile,status);
    return {profile,status,pending:false};
  }catch(error){if(error instanceof BotImportError||error instanceof ConfigurationPending)throw error;throw new BotImportError(step,error);}
}
