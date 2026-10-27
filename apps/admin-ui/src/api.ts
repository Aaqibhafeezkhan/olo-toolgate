// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import type { FleetReleasePage, FleetRolloutPage, FleetRolloutRequest, FleetRolloutRecord, FleetPackageRelease, EnterpriseApprovalDecisionRequest, EnterpriseApprovalPage, EnterpriseApproval, ControlUser, ErrorEnvelope, EndpointEnrollmentReview, EndpointEnrollmentDecision } from '@olo-labs/toolgate-contracts';
import { listOperations, getOperations, createOperations, updateOperations, deleteOperations, operations, type DirectoryKind, type DirectoryPages, type DirectoryRecords } from './operations.generated';
import type {SdkBuilderDraft,SdkBuilderDraftPage,SdkBuilderSave,SdkBuilderBuild,SdkBuilderBuildPage,SdkBuilderBuildRequest,SdkBuilderStatus,SdkBuilderSource} from '@olo-labs/toolgate-contracts';
import type { AdminSession, ControlAuditPage,ConsoleDeviceLookup,ConsoleDeviceChallenge,ConsoleDeviceProof,ConsoleDeviceVerified } from '@olo-labs/toolgate-contracts';
import type {MarketplaceStatus,MarketplaceImportRequest,PackageVersion,PackageVersionPage,PackageDeployment,PackageDeploymentPage,PackageDeploymentRequest,ProvisioningStatusPage,McpApproval,McpApprovalPage,McpApprovalRequest,McpScanJob,McpServerRegistration,McpServerRegistrationPage,McpServerRegistrationRequest,McpSnapshot} from '@olo-labs/toolgate-contracts/v2';
import type {RemoteToolPage,RemoteToolInspection,GroupMembership,ControlSnapshot,SignedPolicyBundle,BundlePublishRequest,EnterpriseEvaluation,EnterpriseDecision} from '@olo-labs/toolgate-contracts';
import {pendingOperations,type ConfigurationFile,type EnrollmentLookup,type ImportIdentityOptions,type PendingServerSettings,type PendingServerSettingsPatch,type ReauthorizedWorkload,type ReviewPolicy} from './pendingContracts';

/** Marketplace display records as Control passes them through (marketplace docs/api.md), with Control's verdict. */
export type TenantAnalyticsOverview = {
  tenantId:string; mode:'NONE'|'BASIC'|'DETAILED';
  series:import('@olo-labs/toolgate-contracts/v2').InsightsSeries;
  tools:import('@olo-labs/toolgate-contracts/v2').InsightsSeries;
  invocations?:import('@olo-labs/toolgate-contracts/v2').InsightsInvocationPage|null;
};
export type MarketplaceVerdict = { readonly compatible: boolean; readonly reasons: readonly string[] };
export type MarketplacePublisherSummary = { readonly slug: string; readonly displayName: string; readonly organization?: string | null; readonly avatarMediaId?: string | null; readonly profileUrl?: string | null };
export type MarketplaceItem = { readonly packageId: string; readonly version: string; readonly title: string; readonly summary: string; readonly category?: string; readonly tags?: readonly string[]; readonly runtime?: string; readonly iconMediaId?: string | null; readonly publisher: MarketplacePublisherSummary; readonly control?: MarketplaceVerdict };
export type MarketplaceCatalog = { readonly page: number; readonly limit: number; readonly total: number; readonly items: readonly MarketplaceItem[] };
export type MarketplaceVersions = { readonly packageId: string; readonly latestVersion: string | null; readonly installVersion: string | null; readonly versions: readonly { readonly version: string; readonly delisted: boolean; readonly control?: MarketplaceVerdict }[] };
export type MarketplaceDetail = MarketplaceItem & { readonly description: string; readonly repositoryUrl?: string | null; readonly documentationUrl?: string | null; readonly license?: string | null; readonly screenshots?: readonly string[];
  readonly tools?: readonly { readonly name: string; readonly title?: string; readonly description?: string; readonly effect?: string }[];
  readonly requiredSettings?: readonly { readonly kind: string; readonly name: string; readonly type?: string | null; readonly default?: string | null; readonly description?: string | null }[];
  readonly packageDigest: string; readonly archiveSha256: string };
export type MarketplacePublisher = MarketplacePublisherSummary & { readonly bio?: string | null; readonly websiteUrl?: string | null; readonly githubUrl?: string | null; readonly linkedinUrl?: string | null; readonly country?: string | null; readonly publicContactEmail?: string | null; readonly packages?: readonly MarketplaceItem[] };

/** Human-safe messages never render server text, exception bodies or credentials. */
export class ApiError extends Error {
  /** Identifiers the server listed with the failure (for example UNRESOLVED_IDENTITIES), already validated. */
  readonly ids: readonly string[];
  constructor(readonly status: number, readonly code: string, readonly requestId?: string, ids: readonly string[] = []) {
    super(status === 401 ? 'Your session ended. Connect again with a valid access token.'
      : status === 403 ? 'Your account does not have permission for this action.'
      : status === 409 ? 'This record changed or conflicts with another record. Refresh before trying again.'
      : status === 400 ? 'The server rejected these values. Check the fields and references.'
      : status === 404 ? 'This record is no longer available.'
      : status === 413 ? 'This request is too large.'
      : status === 429 ? 'Too many attempts. Wait a few minutes before trying again.'
      : status === 422 ? 'Control refused this package: it failed a verification or compatibility check.'
      : code === 'TIMEOUT' ? 'The request timed out. You can try again.'
      : status === 0 ? 'Unable to reach Control. Check your connection and try again.'
      : 'Control could not complete this request. Try again shortly.');
    this.ids = ids;
  }
}

type Operation = { readonly method: string; readonly path: string };
/** An operation with its named path parameters filled in, each encoded as one segment. */
function at(operation: Operation, values: Record<string, string>): Operation {
  return { method: operation.method, path: operation.path.replace(/\{(\w+)\}/g, (_, name: string) => encodeURIComponent(values[name] ?? '')) };
}
const MAX_RESPONSE = 2 * 1024 * 1024;

async function readBody(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) return undefined;
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_RESPONSE) throw new ApiError(502, 'RESPONSE_LIMIT');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  if (!size) return undefined;
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new ApiError(502, 'INVALID_RESPONSE'); }
}

/** Same-origin transport. Backend exclusively owns auth, validation and concurrency.
 * A session disposal clears credentials and aborts all outstanding requests.
 * Mutations are never automatically retried; callers retain a key for an exact retry.
 */
export class ConfigurationPending extends Error {constructor(readonly changeId:string){super('Draft created. Review its impact, submit it for independent approval, then apply the reviewed change.');}}
export class ControlClient {
  invocations(after?:string,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EnterpriseInvocationPage>{const query=new URLSearchParams({limit:'50'});if(after)query.set('after',after);return this.send(operations.listEnterpriseInvocations,{query,signal});}
  proposeOutcome(id:string,body:import('@olo-labs/toolgate-contracts').EnterpriseReconciliationRequest,key:string):Promise<import('@olo-labs/toolgate-contracts').EnterpriseReconciliation>{return this.send(operations.proposeOutcomeReconciliation,{id,body,key});}
  outcomeReconciliation(id:string):Promise<import('@olo-labs/toolgate-contracts').EnterpriseReconciliation>{return this.send(operations.getOutcomeReconciliation,{id});}
  decideOutcome(id:string,body:import('@olo-labs/toolgate-contracts').EnterpriseReconciliationDecision,key?:string):Promise<import('@olo-labs/toolgate-contracts').EnterpriseReconciliation>{return this.send(operations.decideOutcomeReconciliation,{id,body,key});}
  authorityStatus(signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EnterpriseAuthorityStatus>{return this.send(operations.accessAuthorityStatus,{signal});}
  effectiveAccess(entity:'users'|'agents'|'tools'|'devices',id:string,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EnterpriseEffectiveAccess>{const op={users:operations.effectiveUsers,agents:operations.effectiveAgents,tools:operations.effectiveTools,devices:operations.effectiveDevices}[entity];return this.send(op,{id,signal});}
  humanToolResult(id:string):Promise<import('@olo-labs/toolgate-contracts').RemoteToolResponse>{return this.send(operations.humanToolResult,{id});}
  /** Tools the chosen target reports for this binding, each matching its verified definition. */
  humanCatalog(body:import('@olo-labs/toolgate-contracts').EnterpriseHumanTarget,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').LocalToolCatalog>{return this.send(operations.humanToolCatalog,{body,signal});}
  /** The person's own governed invocation. The key is kept by the caller for an exact retry; nothing retries automatically. */
  invokeHumanTool(body:import('@olo-labs/toolgate-contracts').EnterpriseHumanRequest,key:string):Promise<import('@olo-labs/toolgate-contracts').EnterpriseHumanOutcome>{return this.send(operations.invokeHumanTool,{body,key,timeoutMs:45000});}
  /** Retained run-response events after a cursor, authorized on every page. */
  runEvents(id:string,after:number,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts/v2').RunEventPage>{return this.send(operations.invocationRunEvents,{id,query:new URLSearchParams({after:String(after),limit:'200'}),signal});}
  remoteRequests(signal?:AbortSignal):Promise<RemoteToolPage>{return this.send(operations.listLocalMcpRequests,{signal});}
  remoteRequest(id:string,signal?:AbortSignal):Promise<RemoteToolInspection>{return this.send(operations.inspectLocalMcpRequest,{id,signal});}
  private token: string;
  private readonly lifetime = new AbortController();
  constructor(token: string, private readonly onUnauthorized: () => void, private readonly transport: typeof fetch = (input, init) => fetch(input, init)) {
    this.token = token;
  }
  quickstart<T>(path: 'vault', body?: unknown, key?:string): Promise<T> {
    return this.send<T>({method: body === undefined ? 'GET' : 'POST', path: `/api/quickstart/v1/${path}`}, body === undefined ? {} : {body,key});
  }
  dispose(): void { this.token = ''; this.lifetime.abort(); }

  private async send<T>(operation: Operation, options: { id?: string; params?: Record<string, string>; query?: URLSearchParams; body?: unknown; raw?: Blob; revision?: number; key?: string; signal?: AbortSignal; contentType?: string; timeoutMs?: number } = {}): Promise<T> {
    if (this.lifetime.signal.aborted) throw new DOMException('Session ended', 'AbortError');
    const timeout = AbortSignal.timeout(options.timeoutMs ?? 15000);
    const signal = AbortSignal.any([this.lifetime.signal, timeout, ...(options.signal ? [options.signal] : [])]);
    const path = operation.path.replace(/\{(\w+)\}/g, (_, name: string) => encodeURIComponent(name === 'id' ? options.id ?? '' : options.params?.[name] ?? ''));
    const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${this.token}` });
    if (options.body !== undefined || options.raw) headers.set('Content-Type', options.contentType ?? 'application/json');
    if (options.key) headers.set('Idempotency-Key', options.key);
    if (options.revision !== undefined) headers.set('If-Match', `"${options.revision}"`);
    let response: Response;
    try {
      response = await this.transport(path + (options.query ? `?${options.query}` : ''), {
        method: operation.method, headers, body: options.raw ?? (options.body === undefined ? undefined : JSON.stringify(options.body)),
        signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      });
      if (response.status === 401) { this.dispose(); this.onUnauthorized(); }
      const body = await readBody(response);
      if (!response.ok) {
        const envelope = body as (Partial<ErrorEnvelope> & {error?: unknown; ids?: unknown}) | undefined;
        const named = envelope?.code ?? envelope?.error;
        const code = typeof named === 'string' && /^[A-Z_]{1,64}$/.test(named) ? named : 'HTTP_ERROR';
        const requestId = response.headers.get('X-Request-ID') ?? envelope?.requestId;
        const ids = Array.isArray(envelope?.ids) ? envelope.ids.filter((id): id is string => typeof id === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._:@-]{0,127}$/.test(id)).slice(0, 512) : [];
        throw new ApiError(response.status, code, typeof requestId === 'string' && /^[a-zA-Z0-9._:/-]{1,128}$/.test(requestId) ? requestId : undefined, ids);
      }
      if(response.status===202&&body&&typeof body==='object'&&'state' in body&&typeof body.state==='string'&&'id' in body&&typeof body.id==='string'&&/^config-[a-f0-9]{40}$/.test(body.id))throw new ConfigurationPending(body.id);
      return body as T;
    } catch (error) {
      if (error instanceof ApiError || error instanceof ConfigurationPending) throw error;
      if (this.lifetime.signal.aborted || options.signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
      throw new ApiError(0, timeout.aborted ? 'TIMEOUT' : 'NETWORK');
    }
  }

  serverPackages(signal?:AbortSignal):Promise<PackageVersionPage>{return this.send(operations.listServerPackageVersions,{signal});}
  serverDeployments(signal?:AbortSignal):Promise<PackageDeploymentPage>{return this.send(operations.listServerPackageDeployments,{signal});}
  /** The exact .tgpkg bytes; Control verifies them before storing anything. */
  uploadServerPackage(file:Blob):Promise<PackageVersion>{return this.send(operations.uploadServerPackage,{raw:file,contentType:'application/vnd.ololabs.toolgate.package+zip',timeoutMs:120000});}
  deployServerPackage(body:PackageDeploymentRequest):Promise<PackageDeployment>{return this.send(operations.deployServerPackage,{body});}
  mcpServers(signal?:AbortSignal):Promise<McpServerRegistrationPage>{return this.send(operations.listMcpServers,{signal});}
  mcpServer(slug:string,signal?:AbortSignal):Promise<McpServerRegistration>{return this.send(operations.getMcpServer,{params:{slug},signal});}
  registerMcpServer(body:McpServerRegistrationRequest,key:string):Promise<McpServerRegistration>{return this.send(operations.registerMcpServer,{body,key});}
  scanMcpServer(slug:string,key:string):Promise<McpScanJob>{return this.send(operations.scanMcpServer,{params:{slug},key});}
  mcpSnapshot(slug:string,revision:number,signal?:AbortSignal):Promise<McpSnapshot>{return this.send(operations.getMcpSnapshot,{params:{slug,revision:String(revision)},signal});}
  mcpApprovals(slug:string,signal?:AbortSignal):Promise<McpApprovalPage>{return this.send(operations.listMcpApprovals,{params:{slug},signal});}
  approveMcpServer(slug:string,body:McpApprovalRequest,key:string):Promise<McpApproval>{return this.send(operations.approveMcpServer,{params:{slug},body,key,timeoutMs:45000});}
  unregisterMcpServer(slug:string,key:string):Promise<void>{return this.send(operations.deleteMcpServer,{params:{slug},key});}
  /** The latest status of every applied deployment profile, with its package changes. */
  provisioningStatuses(signal?:AbortSignal):Promise<ProvisioningStatusPage>{return this.send(operations.listProvisioningStatuses,{signal});}
  marketplaceStatus(signal?:AbortSignal):Promise<MarketplaceStatus>{return this.send(operations.getMarketplaceStatus,{signal});}
  marketplaceCatalog(q:string,signal?:AbortSignal):Promise<MarketplaceCatalog>{const query=new URLSearchParams({limit:'50'});if(q)query.set('q',q);return this.send(operations.listMarketplaceCatalog,{query,signal});}
  marketplaceVersions(packageId:string,signal?:AbortSignal):Promise<MarketplaceVersions>{return this.send(at(operations.listMarketplacePackageVersions,{packageId}),{signal});}
  marketplaceDetail(packageId:string,version:string,signal?:AbortSignal):Promise<MarketplaceDetail>{return this.send(at(operations.getMarketplacePackageVersion,{packageId,version}),{signal});}
  marketplacePublisher(slug:string,signal?:AbortSignal):Promise<MarketplacePublisher>{return this.send(at(operations.getMarketplacePublisher,{slug}),{signal});}
  /** Control fetches, verifies and stores the version; a large package can take a while. */
  importMarketplacePackage(body:MarketplaceImportRequest):Promise<PackageVersion>{return this.send(operations.importMarketplacePackage,{body,timeoutMs:120000});}
  configurationChanges(after?:string,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EnterpriseConfigurationPage>{const query=new URLSearchParams();if(after)query.set('after',after);return this.send(operations.listConfigurationChanges,{query,signal});}
  configurationChange(id:string,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EnterpriseConfigurationChange>{return this.send(operations.getConfigurationChange,{id,signal});}
  transitionConfiguration(id:string,body:import('@olo-labs/toolgate-contracts').EnterpriseConfigurationTransition,key:string):Promise<import('@olo-labs/toolgate-contracts').EnterpriseConfigurationChange>{return this.send(operations.transitionConfigurationChange,{id,body,key});}
  fleetReleases(cursor?:string,signal?:AbortSignal):Promise<FleetReleasePage>{const query=new URLSearchParams();if(cursor)query.set('cursor',cursor);return this.send(operations.listFleetReleases,{query,signal});}
  builderDrafts(cursor?:string,signal?:AbortSignal):Promise<SdkBuilderDraftPage>{const query=new URLSearchParams();if(cursor)query.set('cursor',cursor);return this.send(operations.listBuilderDrafts,{query,signal});}
  sdkBuilderStatus(signal?:AbortSignal):Promise<SdkBuilderStatus>{return this.send(operations.getSdkBuilderStatus,{signal});}
  builderBuilds(cursor?:string,signal?:AbortSignal):Promise<SdkBuilderBuildPage>{const query=new URLSearchParams();if(cursor)query.set('cursor',cursor);return this.send(operations.listBuilderBuilds,{query,signal});}
  saveSdkDraft(body:SdkBuilderSave,key:string):Promise<SdkBuilderDraft>{return this.send(operations.saveBuilderDraft,{body,key});}
  buildSdkDraft(body:SdkBuilderBuildRequest,key:string):Promise<SdkBuilderBuild>{return this.send(operations.createBuilderBuild,{body,key});}
  /** The project in a source ZIP, validated and canonical; nothing is stored until it is saved as a draft. */
  importSdkSource(file:Blob):Promise<SdkBuilderSource>{return this.send(operations.importBuilderSource,{raw:file,contentType:'application/zip'});}
  async downloadSdkBuild(id:string,source:boolean):Promise<Blob>{
    if(this.lifetime.signal.aborted)throw new DOMException('Session ended','AbortError');
    const timeout=AbortSignal.timeout(120000),signal=AbortSignal.any([this.lifetime.signal,timeout]);
    const operation=source?operations.downloadBuilderSource:operations.downloadBuilderPackage;
    const mediaType=source?'application/zip':'application/vnd.ololabs.toolgate.package+zip';
    try{
      const response=await this.transport(operation.path.replace('{id}',encodeURIComponent(id)),{headers:{Authorization:`Bearer ${this.token}`,Accept:mediaType},signal,credentials:'omit',cache:'no-store',redirect:'error'});
      if(response.status===401){this.dispose();this.onUnauthorized();}
      if(!response.ok){await readBody(response);throw new ApiError(response.status,'HTTP_ERROR',response.headers.get('X-Request-ID')??undefined);}
      if(!response.headers.get('content-type')?.startsWith(mediaType))throw new ApiError(502,'INVALID_RESPONSE');
      const reader=response.body?.getReader();if(!reader)throw new ApiError(502,'INVALID_RESPONSE');
      const chunks:Uint8Array<ArrayBuffer>[]=[];let size=0;
      try{while(true){const next=await reader.read();if(next.done)break;size+=next.value.byteLength;if(size>64*1024*1024)throw new ApiError(502,'RESPONSE_LIMIT');chunks.push(new Uint8Array(next.value));}}
      finally{await reader.cancel();}
      return new Blob(chunks,{type:mediaType});
    }catch(error){if(error instanceof ApiError)throw error;if(this.lifetime.signal.aborted)throw new DOMException('Session ended','AbortError');throw new ApiError(0,timeout.aborted?'TIMEOUT':'NETWORK');}
  }
  fleetRollouts(cursor?:string,signal?:AbortSignal):Promise<FleetRolloutPage>{const query=new URLSearchParams();if(cursor)query.set('cursor',cursor);return this.send(operations.listFleetRollouts,{query,signal});}
  publishRelease(release:FleetPackageRelease,key:string):Promise<FleetPackageRelease>{return this.send(operations.publishFleetRelease,{body:release,key});}
  assignPackage(request:FleetRolloutRequest,key:string):Promise<FleetRolloutRecord>{return this.send(operations.createFleetRollout,{body:request,key});}
  advanceRollout(id:string,expectedRevision:number,percentage:number,key:string):Promise<FleetRolloutRecord>{return this.send(operations.advanceFleetRollout,{id,key,body:{expectedRevision,percentage}});}
  list<K extends DirectoryKind>(kind: K, cursor?: string, signal?: AbortSignal): Promise<DirectoryPages[K]> {
    const query = new URLSearchParams({ limit: '50' }); if (cursor) query.set('cursor', cursor);
    return this.send(listOperations[kind], { query, signal });
  }
  bootstrapAdminUserId?:string;
  private permissionSet=new Set<string>();
  private permissionVersion=0;
  private permissionListeners=new Set<()=>void>();
  can(permission:string):boolean{return this.permissionSet.has(permission);}
  permissionSnapshot=()=>this.permissionVersion;
  subscribePermissions=(listener:()=>void)=>{this.permissionListeners.add(listener);return()=>{this.permissionListeners.delete(listener);};};
  async adminSession():Promise<AdminSession>{const session=await this.send<AdminSession>(operations.getAdminSession);this.bootstrapAdminUserId=session.bootstrapAdminUserId;this.permissionSet=new Set(session.permissions??[]);this.permissionVersion++;this.permissionListeners.forEach(listener=>listener());return session;}
  analytics(from:number,to:number,signal?:AbortSignal,tool?:string,after?:string):Promise<TenantAnalyticsOverview>{
    const query=new URLSearchParams({from:String(from),to:String(to)});
    if(tool)query.set('tool',tool);if(after)query.set('after',after);
    return this.send({method:'GET',path:'/api/control/v1/analytics'},{query,signal});
  }
  audit(cursor:string,signal?:AbortSignal):Promise<ControlAuditPage>{return this.send(operations.listAudit,{query:new URLSearchParams({cursor,limit:'50'}),signal});}
  record<K extends DirectoryKind>(kind:K,id:string):Promise<DirectoryRecords[K]> {return this.send(getOperations[kind],{id});}
  async all<K extends DirectoryKind>(kind:K,signal?:AbortSignal):Promise<DirectoryRecords[K][]> {
    const rows:DirectoryRecords[K][]=[];const seen=new Set<string>();let cursor:string|undefined;
    do {const page=await this.list(kind,cursor,signal);rows.push(...page.items as DirectoryRecords[K][]);cursor=page.nextCursor;
      if(rows.length>512 || cursor&&seen.has(cursor))throw new ApiError(502,'RESPONSE_LIMIT');if(cursor)seen.add(cursor);
    } while(cursor);return rows;
  }
  memberships(entity:'users'|'agents'|'tools'|'devices',id:string,signal?:AbortSignal):Promise<GroupMembership>{const operation={users:operations.getUserGroupMembership,agents:operations.getAgentGroupMembership,tools:operations.getToolGroupMembership,devices:operations.getDeviceGroupMembership}[entity];return this.send(operation,{id,signal});}
  saveMemberships(entity:'users'|'agents'|'tools'|'devices',body:GroupMembership,key:string):Promise<GroupMembership>{const operation={users:operations.updateUserGroupMembership,agents:operations.updateAgentGroupMembership,tools:operations.updateToolGroupMembership,devices:operations.updateDeviceGroupMembership}[entity];return this.send(operation,{id:body.entityId,body,revision:body.revision,key});}
  simulateAccess(body:EnterpriseEvaluation):Promise<EnterpriseDecision>{return this.send(operations.simulateEnterpriseAccess,{body});}
  serverSettings(signal?:AbortSignal,section?:'server'|'device'):Promise<PendingServerSettings>{return this.send(operations.getServerSettings,{signal,...(section?{query:new URLSearchParams({section})}:{})});}
  /** Every signed-in console user may read the configuration review threshold. */
  reviewPolicy(signal?:AbortSignal):Promise<ReviewPolicy>{return this.send(pendingOperations.getReviewPolicy,{signal});}
  /** Saves only the groups one Configuration page owns. */
  patchServerSettings(groups:PendingServerSettingsPatch,revision:number,key:string):Promise<PendingServerSettings>{return this.send(operations.patchServerSettings,{body:groups,revision,key,contentType:'application/merge-patch+json'});}
  exportConfig():Promise<ControlSnapshot>{return this.send(operations.exportConfig);}
  installPresets(snapshot:ControlSnapshot,key:string):Promise<unknown>{return this.send(operations.importConfig,{body:{snapshot,mode:'MERGE',dryRun:false},revision:snapshot.revision,key});}
  /** The configuration file as exported: no users, plus settings and required identities. */
  exportConfiguration():Promise<ConfigurationFile>{return this.send(operations.exportConfig);}
  previewConfiguration(snapshot:ConfigurationFile,mode:'MERGE'|'REPLACE',identities?:ImportIdentityOptions):Promise<{changes:readonly {kind:string;id:string;operation:string}[]}>{return this.send(operations.importConfig,{body:{snapshot,mode,dryRun:true,...identities},revision:snapshot.revision,key:crypto.randomUUID()});}
  importConfiguration(snapshot:ConfigurationFile,mode:'MERGE'|'REPLACE',key:string,identities?:ImportIdentityOptions):Promise<unknown>{return this.send(operations.importConfig,{body:{snapshot,mode,dryRun:false,...identities},revision:snapshot.revision,key});}
  currentBundle():Promise<SignedPolicyBundle>{return this.send(operations.getCurrentPolicyBundle);}
  publishAccess(body:BundlePublishRequest,key:string):Promise<SignedPolicyBundle>{return this.send(operations.publishPolicyBundle,{body,key});}
  saveRecord<K extends DirectoryKind>(kind:K,record:DirectoryRecords[K],existing:boolean,key:string):Promise<DirectoryRecords[K]> {
    return this.send(existing?updateOperations[kind]:createOperations[kind],{id:record.id,body:record,key,revision:existing?record.revision:undefined});
  }
  deleteRecord<K extends DirectoryKind>(kind:K,record:DirectoryRecords[K],key:string):Promise<void> {return this.send(deleteOperations[kind],{id:record.id,revision:record.revision,key});}
  user(id: string, signal?: AbortSignal): Promise<ControlUser> { return this.send(operations.getControlUser, { id, signal }); }
  saveUser(user: ControlUser, existing: boolean, key: string): Promise<ControlUser> {
    return this.send(existing ? operations.updateControlUser : operations.createControlUser,
      { id: user.id, body: user, key, revision: existing ? user.revision : undefined });
  }
  deleteUser(user: ControlUser, key: string): Promise<void> {
    return this.send(operations.deleteControlUser, { id: user.id, revision: user.revision, key });
  }
  invocation(id:string,signal?:AbortSignal):Promise<import("@olo-labs/toolgate-contracts").EnterpriseInvocation>{return this.send(operations.getEnterpriseInvocation,{id,signal});}
  cancelInvocation(id:string,revision:number):Promise<import("@olo-labs/toolgate-contracts").EnterpriseInvocation>{return this.send(operations.cancelEnterpriseInvocation,{id,revision});}
  approvals(cursor?: string, signal?: AbortSignal): Promise<EnterpriseApprovalPage> {
    const query = new URLSearchParams({ limit: '50' }); if (cursor) query.set('cursor', cursor);
    return this.send(operations.listApprovals, { query, signal });
  }
  approval(id: string, signal?: AbortSignal): Promise<EnterpriseApproval> { return this.send(operations.getApproval, { id, signal }); }
  decideApproval(id: string, decision: EnterpriseApprovalDecisionRequest, key: string): Promise<EnterpriseApproval> {
    return this.send(operations.decideApproval, { id, body: decision, key });
  }
  pendingEnrollments(signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EndpointEnrollmentPage>{return this.send(operations.listEndpointEnrollments,{signal});}
  managedDevices(signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EndpointManagedDevicePage>{return this.send(operations.listEndpointDevices,{signal});}
  managedDeviceDeployments(signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EndpointManagedDeviceDeploymentPage>{return this.send(operations.listEndpointDevices,{signal,query:new URLSearchParams({deployment:'true'})});}
  setDeviceApproval(id:string,body:import('@olo-labs/toolgate-contracts').EndpointApprovalRequest,key:string):Promise<import('@olo-labs/toolgate-contracts').EndpointDeviceRecord>{return this.send(operations.setEndpointApproval,{id,body,key});}
  requestDeviceApproval(id:string,body:import('@olo-labs/toolgate-contracts').EndpointRevokeRequest,key:string):Promise<import('@olo-labs/toolgate-contracts').EndpointDeviceRecord>{return this.send(operations.requestEndpointDeviceApproval,{id,body,key});}
  setDeviceEnabled(id:string,body:import('@olo-labs/toolgate-contracts').EndpointEnabledRequest,key:string):Promise<import('@olo-labs/toolgate-contracts').ControlDevice>{return this.send(operations.setEndpointEnabled,{id,body,key});}
  enrollment(code: string, signal?: AbortSignal): Promise<EndpointEnrollmentReview> {
    return this.send(operations.reviewEndpointEnrollment, { query: new URLSearchParams({ code }), signal });
  }
  /** Rate-limited: Control answers 404 with no detail for an unknown code and 429 after repeated failures. */
  lookupEnrollment(userCode:string,signal?:AbortSignal):Promise<EnrollmentLookup>{return this.send(pendingOperations.lookupEndpointEnrollment,{body:{userCode},signal});}
  consoleDeviceChallenge(body:ConsoleDeviceLookup,signal?:AbortSignal):Promise<ConsoleDeviceChallenge>{return this.send(operations.challengeConsoleDevice,{body,signal});}
  verifyConsoleDevice(body:ConsoleDeviceProof,signal?:AbortSignal):Promise<ConsoleDeviceVerified>{return this.send(operations.verifyConsoleDevice,{body,signal});}
  /** Quickstart rotates the delegated binding credential, waits for the Gateway, and returns the new agent token once. */
  reauthorizeWorkload(id:string,key:string):Promise<ReauthorizedWorkload>{return this.send(pendingOperations.reauthorizeQuickstartWorkload,{id,body:{},key,timeoutMs:60000});}
  endpointDevice(id:string,signal?:AbortSignal):Promise<import('@olo-labs/toolgate-contracts').EndpointDeviceRecord>{return this.send(operations.getEndpointDevice,{id,signal});}
  decideEnrollment(decision: EndpointEnrollmentDecision, key: string): Promise<EndpointEnrollmentReview> {
    return this.send(operations.decideEndpointEnrollment, { body: decision, key });
  }
}
