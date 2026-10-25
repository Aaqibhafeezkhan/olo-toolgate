// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
import type * as V1 from "../index.js";
/** Verified delegation chain entry; never caller-asserted. */
export interface ActorHop {
  readonly agentId: string;
  readonly workloadBindingId: string;
}
export type AdmissionKind = "ADMITTED" | "REFUSED";
/** Tool Host to Gateway, sent before any sandbox starts. */
export interface AdmissionResponse {
  readonly kind: AdmissionKind;
  readonly invocationId: string;
  readonly fencingToken?: string;
  readonly reason?: RefusalReason;
}
export type ApiChange = "NONE" | "PATCH" | "MINOR" | "MAJOR";
export type AuthType = "API_KEY" | "BASIC" | "OAUTH2_CLIENT_CREDENTIALS" | "TOKEN_EXCHANGE" | "CONNECTION" | "CUSTOM";
/** Break-glass kill: 2-of-3 operator signatures, adds kills only. */
export interface BreakGlassRequest {
  readonly scope: KillScope;
  readonly mode: KillMode;
  readonly reason: string;
  readonly operatorSignatures: ReadonlyArray<string>;
}
export type BrokerOperation = "ROUTE_CALL" | "SECRET_REDEEM" | "FILES_LIST" | "FILES_READ" | "FILES_INFO" | "FILES_WRITE" | "FILES_APPEND" | "FILES_MKDIR" | "FILES_RENAME";
/** Tool to executor on stdout: one brokered operation. request is the operation payload: the recorded-exchange route call shape for ROUTE_CALL, {handle} for SECRET_REDEEM, {path, destination, text} for FILES_*. One request is outstanding at a time. */
export interface BrokerRequestFrame {
  readonly kind: "BROKER_REQUEST";
  readonly requestId: string;
  readonly operation: BrokerOperation;
  readonly request: Record<string, unknown>;
}
/** Executor to tool on stdin: the result of the outstanding brokered operation, or a canonical error. Nothing else is written to the tool after its ToolInput. */
export interface BrokerResponseFrame {
  readonly kind: "BROKER_RESPONSE";
  readonly requestId: string;
  readonly result?: Record<string, unknown>;
  readonly error?: ToolErrorBody;
}
/** reserveBusinessOperation input, carried on POST /access/invocations. */
export interface BusinessKeyReservationRequest {
  readonly toolId: string;
  readonly namespace: KeyNamespace;
  readonly namespaceIdentity?: string;
  readonly businessKeyHash: string;
  readonly source: BusinessKeySource;
  readonly fingerprint: string;
  readonly caller: CallerIdentity;
  readonly dedupeWindowMs?: number;
}
/** Reservation result. IDEMPOTENCY_KEY_IN_USE carries nothing else. */
export interface BusinessKeyReservationResult {
  readonly outcome: ReservationOutcome;
  readonly invocationId?: string;
  readonly state?: TaskState;
}
/** Business-key settings. */
export interface BusinessKeySettings {
  readonly required: boolean;
  readonly namespace: KeyNamespace;
  readonly argument?: string;
  readonly idempotencyHeader?: string;
  readonly idempotencyScope?: IdempotencyScope;
}
export type BusinessKeySource = "HEADER" | "ARGUMENT" | "DEDUPE_WINDOW";
/** Kept after retention; results purged. */
export interface BusinessKeyTombstone {
  readonly businessKeyHash: string;
  readonly fingerprint: string;
  readonly outcome: TaskState;
  readonly createdAtUnixMs: number;
  readonly purgedAtUnixMs: number;
}
/** Authenticated caller, never from arguments. */
export interface CallerIdentity {
  readonly agentId: string;
  readonly userId?: string;
}
/** Catalog scope. */
export interface CatalogScope {
  readonly kind: CatalogScopeKind;
  readonly agentId?: string;
  readonly agentGroupId?: string;
  readonly toolsets: ReadonlyArray<string>;
  readonly revision: string;
}
export type CatalogScopeKind = "ALL" | "AGENT" | "AGENT_GROUP" | "AGENT_IN_GROUP";
/** Declared identity claim. */
export interface ClaimDeclaration {
  readonly name: string;
  readonly claim: string;
  readonly type: ValueType;
  readonly required: boolean;
}
/** Effective Phase A check-in values. Env-owned, read-only in the console. */
export interface ClientCheckIn {
  readonly checkInMs: number;
  readonly jitterPct: number;
  readonly offlineAfterMs: number;
  readonly source: ValueSource;
}
/** A client release: the image index pinned by digest, the exact FleetPackageDocument bytes (manifestDigest) and, when Control holds the package-release key, the signed FleetPackageRelease it recorded. */
export interface ClientRelease {
  readonly packageId: string;
  readonly version: string;
  readonly packageDigest: string;
  readonly image: string;
  readonly runtimeVersion: string;
  readonly manifestDigest: string;
  readonly signed: boolean;
  readonly document: Record<string, unknown>;
  readonly documentBase64: string;
  readonly release?: Record<string, unknown>;
  readonly tools: ReadonlyArray<ClientReleaseTool>;
}
/** Builds the client-tool image for one verified upload and issues its fleet release (ADR 026). */
export interface ClientReleaseRequest {
  readonly packageDigest: string;
}
/** One export and whether a client device can run it; an unsupported export is never weakened to fit. */
export interface ClientReleaseTool {
  readonly toolId: string;
  readonly supported: boolean;
  readonly reason?: string;
}
export type CompactJws = string;
export type CompatibilityProfile = "NONE" | "LLM_STRICT";
/** One applied library mapping. */
export interface CompositionMapping {
  readonly kind: MappingKind;
  readonly from: string;
  readonly to: string;
}
/** A library merged into the package (composition.md §2). */
export interface CompositionRecord {
  readonly libraryId: string;
  readonly version: string;
  readonly fragmentDigest: string;
  readonly namespacePrefix: string;
  readonly mappings: ReadonlyArray<CompositionMapping>;
  readonly excluded: ReadonlyArray<string>;
  readonly narrowed: ReadonlyArray<string>;
}
export type ConfigLayer = "PLATFORM_CEILING" | "ENVIRONMENT" | "TENANT_POLICY" | "POOL_OR_DEVICE_GROUP" | "DEPLOYMENT_BINDING" | "PACKAGE_DEFAULT";
/** Configuration review settings. requiredReviews 0 applies changes immediately. */
export interface ConfigurationSettings {
  readonly requiredReviews: number;
}
/** SaaS connection the package needs (platform-requirements.md §14.3). */
export interface ConnectionRequirement {
  readonly id: string;
  readonly provider: string;
  readonly scopes: ReadonlyArray<string>;
}
export type ConsoleClientOperation = "HEALTH" | "ENROLL";
/** OS-authenticated native request scoped to a console origin and optional expected UUID. The service validates protected local tenant/origin binding before reading health or starting enrollment. */
export interface ConsoleClientRequest {
  readonly kind: "console-tenant-state";
  readonly requestId: string;
  readonly operation: ConsoleClientOperation;
  readonly consoleUrl: string;
  readonly expectedTenantId?: string;
}
/** Requester-scoped device state and optional verified tenant UUID. Errors carry no installation or device metadata. The service enforces the error-only response invariant before serialization. */
export interface ConsoleClientResponse {
  readonly requestId: string;
  readonly tenantId?: string;
  readonly health?: V1.ClientHealth;
  readonly challenge?: V1.EndpointEnrollmentPrompt;
  readonly error?: V1.ErrorCode;
}
/** Identifies the shared contract set. */
export interface ContractSet {
  readonly name: "olo-toolgate-contracts";
  readonly version: string;
}
/** Settings record. revision is compared with If-Match. */
export interface ControlServerSettings {
  readonly formatVersion: 2;
  readonly revision: number;
  readonly gateway: GatewaySettings;
  readonly deviceApproval: DeviceApproval;
  readonly clientCheckIn: ClientCheckIn;
  readonly configuration: ConfigurationSettings;
}
/** application/merge-patch+json body, and the settings of a configuration snapshot. Env-owned clientCheckIn is not patchable. */
export interface ControlServerSettingsPatch {
  readonly gateway?: GatewaySettings;
  readonly deviceApproval?: DeviceApproval;
  readonly configuration?: ConfigurationSettings;
}
/** Settings visible to the current actor. Unreadable feature groups are omitted. */
export interface ControlSettingsView {
  readonly formatVersion: 2;
  readonly revision: number;
  readonly gateway?: GatewaySettings;
  readonly deviceApproval?: DeviceApproval;
  readonly clientCheckIn?: ClientCheckIn;
  readonly configuration?: ConfigurationSettings;
}
export type CredentialKind = "STATIC_TOKEN" | "JWT" | "INTROSPECTION";
export type DeclarationId = string;
/** Deployment binding. */
export interface DeploymentBinding {
  readonly bindingFormatVersion: 1;
  readonly bindingId: string;
  readonly revision: number;
  readonly tenantId: string;
  readonly packageId: string;
  readonly packageDigest: string;
  readonly configRevision: number;
  readonly serviceProfileDigest: string;
  readonly serviceProfileRevision: number;
  readonly target: DeploymentTarget;
  readonly grants: ReadonlyArray<DeploymentGrant>;
  readonly limitOverrides: ReadonlyArray<LimitOverride>;
  readonly rolloutPlan: RolloutPlan;
  readonly effects: ReadonlyArray<EffectApproval>;
}
/** Agent Group grant and catalog scope. */
export interface DeploymentGrant {
  readonly agentGroupId: string;
  readonly toolIds: ReadonlyArray<string>;
}
/** Deployment-time package provisioning for one profile (docs/contracts/platform-requirements.md). Applied by Control at deployment; removing an entry never uninstalls a package. */
export interface DeploymentProfile {
  readonly profileVersion: 1;
  readonly profileId: string;
  readonly packages: ReadonlyArray<ProfilePackage>;
  readonly readiness?: ReadonlyArray<ProfileReadinessCase>;
}
/** Where the package runs. */
export interface DeploymentTarget {
  readonly kind: TargetKind;
  readonly ids: ReadonlyArray<string>;
  readonly allowedTiers: ReadonlyArray<RuntimeTier>;
}
/** Declared destination; the host is a reviewed deployment setting. */
export interface DestinationDeclaration {
  readonly id: string;
  readonly hostSetting: string;
  readonly port: number;
  readonly spkiPins: ReadonlyArray<string>;
  readonly caBundle?: string;
}
export type DestinationScheme = "https" | "tcp";
/** Device approval settings. */
export interface DeviceApproval {
  readonly autoApprove: boolean;
  readonly durationMs?: number;
  readonly ownerUserId?: string;
}
export type Digest = string;
export type DiscoveryAuthorityState = "ACTIVE" | "READ_ONLY";
/** Tenant device CA. keyId is SHA-256 of certificate DER; its subject binds the same tenant UUID. It cannot reuse a discovery signing key. */
export interface DiscoveryDeviceAuthority {
  readonly keyId: string;
  readonly state: DiscoveryAuthorityState;
  readonly certificatePem: string;
}
/** Protected JWS header. Algorithm, message purpose and key selection are authenticated; no unprotected or remote-key headers. */
export interface DiscoveryHeader {
  readonly alg: "RS256";
  readonly typ: "toolgate-discovery+jws";
  readonly kid: string;
}
export type DiscoveryKeyId = string;
/** Bounded tenant discovery JWK Set. Application validation requires unique key IDs and moduli. */
export interface DiscoveryKeySet {
  readonly keys: ReadonlyArray<DiscoveryPublicKey>;
}
/** Public-only RSA JWK, restricted to 3072/4096-bit RS256 keys with exponent 65537. No private parameters or key URLs. */
export interface DiscoveryPublicKey {
  readonly kty: "RSA";
  readonly use: "sig";
  readonly alg: "RS256";
  readonly kid: string;
  readonly n: string;
  readonly e: "AQAB";
}
/** Gateway to Tool Host. */
export interface Dispatch {
  readonly permit: SignedInvocationPermit;
  readonly arguments: Record<string, unknown>;
}
/** Reviewed effect class per tool. */
export interface EffectApproval {
  readonly toolId: string;
  readonly effectSafety: EffectSafety;
  readonly businessKeyNamespace: KeyNamespace;
  readonly missingKeyPolicy: MissingKeyPolicy;
  readonly approvalId: string;
}
export type EffectSafety = "READ_ONLY" | "IDEMPOTENT" | "NON_IDEMPOTENT" | "UNKNOWN";
/** GET /api/control/v1/deployments/{bindingId}/effective. */
export interface EffectiveDeployment {
  readonly bindingId: string;
  readonly revision: number;
  readonly values: ReadonlyArray<EffectiveValue>;
}
/** One effective field and the layer it came from. */
export interface EffectiveValue {
  readonly field: string;
  readonly value: unknown;
  readonly layer: ConfigLayer;
  readonly cappedBy?: ConfigLayer;
}
export type EgressMode = "BROKERED" | "TUNNEL";
export type ErrorCode = string;
/** Declared business error. */
export interface ErrorDeclaration {
  readonly code: string;
  readonly description: string;
  readonly retryable: boolean;
}
export type EventId = string;
/** Execution settings with defaults resolved. */
export interface ExecutionSettings {
  readonly async: boolean;
  readonly maxDurationMs: number;
  readonly stateless: boolean;
  readonly maxConcurrency?: number;
  readonly cancelIfAbandoned: boolean;
}
export type FrameKind = "RESULT" | "PROGRESS" | "INPUT_REQUEST" | "LOG" | "BROKER_REQUEST" | "BROKER_RESPONSE";
/** Gateway settings. */
export interface GatewaySettings {
  readonly gatewayName: string;
}
/** Declared global. */
export interface GlobalDeclaration {
  readonly name: string;
  readonly type: ValueType;
  readonly required: boolean;
  readonly default?: unknown;
  readonly sensitive: boolean;
  readonly description?: string;
  readonly minimum?: number;
  readonly maximum?: number;
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly pattern?: string;
  readonly enum?: ReadonlyArray<string>;
  readonly format?: string;
}
/** Declared HTTP auth. */
export interface HttpAuthDeclaration {
  readonly id: string;
  readonly type: AuthType;
  readonly header?: string;
  readonly secret?: string;
  readonly connection?: string;
  readonly scopes: ReadonlyArray<string>;
  readonly audience?: string;
}
export type HttpMethod = "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE";
export type IdempotencyScope = "BUSINESS_KEY" | "PER_INVOCATION";
export type Identifier = string;
/** Tool asks for input (MRTR or tasks/update). */
export interface InputRequestFrame {
  readonly kind: "INPUT_REQUEST";
  readonly requestId: string;
  readonly request: Record<string, unknown>;
}
/** Response to a v2 POST /access/invocations for NEW and EXISTING reservations. IDEMPOTENCY_CONFLICT, IDEMPOTENCY_KEY_IN_USE and EXPIRED_KEY are 409 errors with that code and carry nothing else. requestState is present with an ASK outcome for an MRTR client. */
export interface InvocationAdmission {
  readonly invocationId: string;
  readonly state: TaskState;
  readonly reservation?: ReservationOutcome;
  readonly task?: InvocationTask;
  readonly approvalId?: string;
  readonly approvalUrl?: string;
  readonly requestState?: string;
}
export type InvocationOutcome = "COMPLETED" | "FAILED" | "CANCELLED" | "OUTCOME_UNKNOWN";
/** Permit claims. exp is the short admission window, not the execution deadline. */
export interface InvocationPermitClaims {
  readonly jti: string;
  readonly iss: string;
  readonly aud: string;
  readonly iat: number;
  readonly nbf: number;
  readonly exp: number;
  readonly nonce: string;
  readonly invocationId: string;
  readonly attempt: number;
  readonly taskId?: string;
  readonly identity: PermitIdentity;
  readonly target: PermitTarget;
  readonly catalogRevision: string;
  readonly argumentsDigest: string;
  readonly action: string;
  readonly resources: ReadonlyArray<Record<string, unknown>>;
  readonly effectSafety: EffectSafety;
  readonly requiresExecutionLedger: boolean;
  readonly businessKeyHash?: string;
  readonly businessKeyNamespace?: KeyNamespace;
  readonly approvalIds: ReadonlyArray<string>;
  readonly deadlineUnixMs: number;
  readonly mcpVerification?: McpPermitVerification;
}
/** Tool Host result to Control (effects/report, v2 body), keyed by invocation and fencing token. notStarted, with outcome FAILED, reports a claim the host abandoned before any sandbox started (invocation-state-v2.md T10). */
export interface InvocationResultReport {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly outcome: InvocationOutcome;
  readonly result?: ResultFrame;
  readonly notStarted?: RefusalReason;
}
/** v2 body of POST /access/invocations (invocation-state-v2.md T1). The Gateway generates invocationId; Control evaluates policy, reserves the business key and decides whether a task exists. */
export interface InvocationSubmission {
  readonly invocationId: string;
  readonly context: RequestContext;
  readonly catalogScope: CatalogScope;
  readonly toolId: string;
  readonly toolDescriptorDigest: string;
  readonly packageDigest: string;
  readonly arguments: Record<string, unknown>;
  readonly argumentsDigest: string;
  readonly businessKey?: BusinessKeyReservationRequest;
  readonly tasks: TaskCapability;
  readonly requestState?: string;
}
/** Task fields added to a Control invocation when a task record exists (platform-requirements.md §12.1). */
export interface InvocationTask {
  readonly taskId: string;
  readonly state: TaskState;
  readonly status: McpTaskStatus;
  readonly ttlMs: number;
  readonly pollIntervalMs: number;
  readonly statusMessage?: string;
  readonly progress?: TaskProgress;
  readonly inputDeadlineUnixMs?: number;
  readonly cancelIfAbandoned: boolean;
  readonly transitions: ReadonlyArray<TaskTransition>;
  readonly packageDigest: string;
}
export type KeyNamespace = "AGENT" | "DELEGATED_USER" | "TENANT";
/** Immutable kill event. */
export interface KillEvent {
  readonly eventId: string;
  readonly issuer: KillIssuer;
  readonly scope: KillScope;
  readonly mode: KillMode;
  readonly reason: string;
  readonly issuedAtUnixMs: number;
  readonly signature: string;
}
/** Effective and superseded events. */
export interface KillEventPage {
  readonly kills: ReadonlyArray<KillEvent>;
  readonly recoveries: ReadonlyArray<RecoveryEvent>;
  readonly authorizationEpoch: number;
}
export type KillIssuer = "control" | "control-breakglass";
export type KillMode = "STOP_NEW" | "TERMINATE";
/** Push to Tool Hosts and clients for running work. */
export interface KillPush {
  readonly tenantId: string;
  readonly authorizationEpoch: number;
  readonly kills: ReadonlyArray<KillEvent>;
  readonly recoveries: ReadonlyArray<RecoveryEvent>;
  readonly signature: string;
}
/** Admin kill request; reviewed when the enterprise review workflow is on. */
export interface KillRequest {
  readonly scope: KillScope;
  readonly mode: KillMode;
  readonly reason: string;
}
/** Kill target. value is absent only for TENANT. */
export interface KillScope {
  readonly kind: KillScopeKind;
  readonly value?: string;
}
export type KillScopeKind = "PACKAGE" | "PACKAGE_DIGEST" | "PUBLISHER" | "TOOL" | "POOL" | "RUNTIME_IMAGE" | "TENANT";
/** Lease renewal for a running ledger invocation (every lease/3). Also carries the latest progress and, while the tool waits for input, its input request (invocation-state-v2.md T6, T7). */
export interface LeaseHeartbeat {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly progress?: TaskProgress;
  readonly inputRequest?: InputRequestFrame;
}
/** Heartbeat response: the renewed lease, whether cancellation was requested, and any task input waiting for the tool (invocation-state-v2.md T6–T8). */
export interface LeaseRenewal {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly leaseExpiresAtUnixMs: number;
  readonly cancelRequested: boolean;
  readonly inputResponses?: unknown;
}
/** Per-tool limit override; at most the platform ceiling. */
export interface LimitOverride {
  readonly toolId: string;
  readonly maxDurationMs?: number;
  readonly maxConcurrency?: number;
  readonly memoryBytes?: number;
}
/** Redacted, size-capped log line. */
export interface LogFrame {
  readonly kind: "LOG";
  readonly level: LogLevel;
  readonly message: string;
}
export type LogLevel = "debug" | "info" | "warn" | "error";
export type MappingKind = "SECRET" | "GLOBAL" | "CLAIM" | "DESTINATION";
/** Imports one Marketplace version as an uploaded server package. Control fetches, verifies and stores it; nothing is deployed, enabled or granted. */
export interface MarketplaceImportRequest {
  readonly packageId: string;
  readonly version: string;
}
/** A Marketplace release signing key Control trusts, by id and SHA-256 fingerprint of its raw Ed25519 public key. */
export interface MarketplaceKey {
  readonly keyId: string;
  readonly fingerprint: string;
}
/** Whether this ToolGate reads an OLO Orgs Marketplace. The id, origin and keys are environment-owned settings, absent when disabled. */
export interface MarketplaceStatus {
  readonly enabled: boolean;
  readonly marketplaceId?: string;
  readonly baseUrl?: string;
  readonly keys?: ReadonlyArray<MarketplaceKey>;
}
/** An accepted approval: one per approval sequence, with its generated package. */
export interface McpApproval {
  readonly sequence: number;
  readonly snapshotRevision: number;
  readonly tools: ReadonlyArray<McpApprovedTool>;
  readonly spkiPins: ReadonlyArray<string>;
  readonly packageId: string;
  readonly packageVersion: string;
  readonly packageDigest: string;
  readonly approvedAtUnixMs: number;
}
/** Approvals of one registration, newest first. */
export interface McpApprovalPage {
  readonly items: ReadonlyArray<McpApproval>;
}
/** Approves tools of one snapshot; at most 64, the package and deployment limit. */
export interface McpApprovalRequest {
  readonly snapshotRevision: number;
  readonly tools: ReadonlyArray<McpToolApproval>;
}
/** A tool of an approval. */
export interface McpApprovedTool {
  readonly toolId: string;
  readonly upstreamName: string;
  readonly definitionDigest: string;
}
export type McpAuthScheme = "BEARER" | "HEADER";
export type McpCheckFailure = "PIN_MISMATCH" | "TLS_ERROR" | "UPSTREAM_UNREACHABLE" | "UPSTREAM_ERROR" | "PROTOCOL_ERROR" | "SCAN_LIMIT_EXCEEDED" | "DEADLINE_EXCEEDED" | "DEFINITION_CHANGED" | "TOOL_MISSING" | "SERVER_CHANGED";
export type McpName = string;
/** Freshness evidence an invocation permit carries for an MCP_PROXY tool. */
export interface McpPermitVerification {
  readonly status: McpVerificationStatus;
  readonly approvalSequence: number;
  readonly checkedAtUnixMs?: number;
}
/** mcp-proxy.json, the one artifact of an MCP_PROXY package (media type application/vnd.ololabs.toolgate.mcp-proxy+json). */
export interface McpProxyDocument {
  readonly formatVersion: 1;
  readonly server: McpProxyServer;
  readonly approvalSequence: number;
  readonly strict: boolean;
  readonly tools: ReadonlyArray<McpApprovedTool>;
}
/** The upstream an MCP_PROXY package proxies: its URL and credential reference, bound by the package digest, and the identity and TLS pins it was approved with. */
export interface McpProxyServer {
  readonly slug: string;
  readonly url: string;
  readonly auth: McpUpstreamAuth;
  readonly serverInfo: McpServerInfo;
  readonly spkiPins: ReadonlyArray<string>;
}
/** A recorded quarantine and the evidence that caused it. */
export interface McpQuarantine {
  readonly reason: McpQuarantineReason;
  readonly evidenceId: string;
  readonly toolIds: ReadonlyArray<string>;
  readonly serverWide: boolean;
  readonly atUnixMs: number;
  readonly approvalSequence: number;
}
export type McpQuarantineReason = "OUTPUT_INVALID" | "DEFINITION_CHANGED" | "TOOL_MISSING" | "SERVER_CHANGED" | "SERVER_VERSION_CHANGED" | "PIN_MISMATCH";
/** Sent by the lease holder of exactly one invocation (a call) or one scan (a refresh); Control derives the targets from that evidence and applies it only to the current approval. */
export interface McpQuarantineReport {
  readonly invocationId?: string;
  readonly scanId?: string;
  readonly fencingToken: string;
  readonly reason: McpQuarantineReason;
  readonly observedDigest?: string;
  readonly observedServerInfo?: McpServerInfo;
}
/** A Tool Host saw notifications/tools/list_changed during a call it holds and asks Control for an immediate refresh of that call's MCP server. Bound to the invocation and its fencing token, like a quarantine report. */
export interface McpRefreshRequest {
  readonly invocationId: string;
  readonly fencingToken: string;
}
/** Claims of a scan permit (typ toolgate-mcp-scan-permit+jws), signed in the effect-signing domain. exp - iat is at most 10 s (admission); deadlineUnixMs bounds the whole scan. spkiPins is required for REFRESH. */
export interface McpScanClaims {
  readonly jti: string;
  readonly iss: "control";
  readonly aud: string;
  readonly iat: number;
  readonly exp: number;
  readonly scanId: string;
  readonly slug: string;
  readonly registrationRevision: number;
  readonly bindingId: string;
  readonly purpose: McpScanPurpose;
  readonly url: string;
  readonly auth: McpUpstreamAuth;
  readonly spkiPins: ReadonlyArray<string>;
  readonly approvalSequence?: number;
  readonly fencingToken: string;
  readonly deadlineUnixMs: number;
}
/** A scan a Tool Host fetched: the signed scan permit for its admission. */
export interface McpScanDispatch {
  readonly scanId: string;
  readonly permit: string;
}
/** A failed scan, reported by the lease holder before the deadline. */
export interface McpScanFailureReport {
  readonly scanId: string;
  readonly fencingToken: string;
  readonly failure: McpCheckFailure;
  readonly observedSpkiPin?: string;
}
/** A scan of one registration on one Tool Host of its binding Device Group. */
export interface McpScanJob {
  readonly scanId: string;
  readonly slug: string;
  readonly purpose: McpScanPurpose;
  readonly state: McpScanState;
  readonly hostId?: string;
  readonly createdAtUnixMs: number;
  readonly deadlineUnixMs: number;
  readonly approvalSequence?: number;
  readonly snapshotRevision?: number;
  readonly failure?: McpCheckFailure;
}
export type McpScanPurpose = "DISCOVER" | "REFRESH";
/** What the scanning host observed, reported once by the lease holder before the deadline. */
export interface McpScanResult {
  readonly scanId: string;
  readonly fencingToken: string;
  readonly serverInfo: McpServerInfo;
  readonly protocolVersion: string;
  readonly spkiPin: string;
  readonly tools: ReadonlyArray<McpUpstreamTool>;
}
export type McpScanState = "QUEUED" | "CLAIMED" | "ADMITTED" | "FINISHED" | "FAILED" | "EXPIRED";
export type McpSecretName = string;
/** The upstream serverInfo from initialize. */
export interface McpServerInfo {
  readonly name: string;
  readonly version: string;
  readonly title?: string;
}
/** A registered upstream MCP server of the routed tenant. */
export interface McpServerRegistration {
  readonly slug: string;
  readonly url: string;
  readonly auth: McpUpstreamAuth;
  readonly bindingId: string;
  readonly strict: boolean;
  readonly state: McpServerState;
  readonly revision: number;
  readonly createdAtUnixMs: number;
  readonly latestSnapshotRevision?: number;
  readonly approvalSequence?: number;
  readonly verification: McpVerification;
  readonly quarantine?: McpQuarantine;
}
/** Registrations of the routed tenant. */
export interface McpServerRegistrationPage {
  readonly items: ReadonlyArray<McpServerRegistration>;
}
/** Registers an upstream MCP server in the routed tenant. The pool is the binding Device Group. */
export interface McpServerRegistrationRequest {
  readonly slug: string;
  readonly url: string;
  readonly auth: McpUpstreamAuth;
  readonly bindingId: string;
  readonly strict: boolean;
}
export type McpServerSlug = string;
export type McpServerState = "REGISTERED" | "SCANNING" | "SCANNED" | "APPROVED" | "QUARANTINED";
export type McpServerUrl = string;
/** An immutable scan result; Control recomputes every digest. */
export interface McpSnapshot {
  readonly revision: number;
  readonly scanId: string;
  readonly serverInfo: McpServerInfo;
  readonly protocolVersion: string;
  readonly spkiPin: string;
  readonly scannedAtUnixMs: number;
  readonly tools: ReadonlyArray<McpToolDefinition>;
}
export type McpSpkiPin = string;
export type McpTaskStatus = "working" | "input_required" | "completed" | "failed" | "cancelled";
/** One approved upstream tool and the ToolGate local name it gets (mcp.{slug}/{name}). */
export interface McpToolApproval {
  readonly upstreamName: string;
  readonly name: string;
}
/** A snapshot tool: the upstream definition and its definition digest (tool-sdk/conformance/mcp-proxy). */
export interface McpToolDefinition {
  readonly tool: McpUpstreamTool;
  readonly definitionDigest: string;
}
/** Upstream credential for scans and calls. The secret is a tenant vault entry whose Tool Group and Device Group equal the registration binding. */
export interface McpUpstreamAuth {
  readonly secretName: string;
  readonly scheme: McpAuthScheme;
  readonly header?: string;
}
/** One tool exactly as the upstream tools/list returned it, within the scan limits. */
export interface McpUpstreamTool {
  readonly name: string;
  readonly title?: string;
  readonly description?: string;
  readonly inputSchema: Record<string, unknown>;
  readonly outputSchema?: Record<string, unknown>;
  readonly annotations?: Record<string, unknown>;
}
/** The latest check of a registration against its approval; a failure replaces an earlier success. */
export interface McpVerification {
  readonly status: McpVerificationStatus;
  readonly approvalSequence?: number;
  readonly checkedAtUnixMs?: number;
  readonly failure?: McpCheckFailure;
}
export type McpVerificationStatus = "CURRENT" | "UNVERIFIED";
export type MissingKeyPolicy = "REJECT" | "ASK" | "ALLOW_WITH_DEDUPE";
export type NamespacePrefix = string;
/** Version requirements checked at enable. */
export interface PackageCompatibility {
  readonly contractsVersion: string;
  readonly minimumToolHostVersion?: string;
  readonly minimumClientVersion?: string;
}
/** The package version Tool Hosts run for one package id, with the service profile and globals Control built for it. */
export interface PackageDeployment {
  readonly packageId: string;
  readonly version: string;
  readonly packageDigest: string;
  readonly revision: number;
  readonly serviceProfileDigest: string;
  readonly serviceProfileRevision: number;
  readonly globalsDigest: string;
  readonly toolIds: ReadonlyArray<string>;
  readonly deployedAtUnixMs: number;
  readonly hostIds?: ReadonlyArray<string>;
  readonly deviceGroupIds?: ReadonlyArray<string>;
}
/** Current deployments, one per package id. */
export interface PackageDeploymentPage {
  readonly items: ReadonlyArray<PackageDeployment>;
}
/** Deploys an uploaded server package to the Tool Hosts with values for its declared globals. A destination's host is the global its hostSetting names. */
export interface PackageDeploymentRequest {
  readonly packageDigest: string;
  readonly globals: Record<string, unknown>;
  readonly hostIds?: ReadonlyArray<string>;
  readonly deviceGroupIds?: ReadonlyArray<string>;
  readonly privateRanges?: ReadonlyArray<PrivateRangeApproval>;
  readonly destinationTls?: Record<string, unknown>;
}
/** Enable exactly the reviewed digest with this binding. */
export interface PackageEnableRequest {
  readonly packageDigest: string;
  readonly binding: DeploymentBinding;
  readonly acknowledgedSecurityDelta: boolean;
}
export type PackageId = string;
export type PackageKind = "SDK" | "CONTAINER" | "OPENAPI" | "MCP_PROXY";
/** Package-level ceilings. */
export interface PackageLimits {
  readonly maxTools: number;
  readonly maxDescriptorBytes: number;
}
/** Package manifest v2. v1 manifests stay readable and are upgraded on read. */
export interface PackageManifest {
  readonly schemaVersion: 2;
  readonly kind: PackageKind;
  readonly id: string;
  readonly version: string;
  readonly publisher: string;
  readonly sdk?: SdkInfo;
  readonly compatibility: PackageCompatibility;
  readonly runtimes: ReadonlyArray<RuntimeDeclaration>;
  readonly tools: ReadonlyArray<ToolDefinition>;
  readonly descriptorSetDigest: string;
  readonly artifacts: ReadonlyArray<PackagedArtifact>;
  readonly credentialReferences: ReadonlyArray<string>;
  readonly connections: ReadonlyArray<ConnectionRequirement>;
  readonly composition: ReadonlyArray<CompositionRecord>;
  readonly limits: PackageLimits;
}
/** Review and enable page data: both diffs and the values to fill. */
export interface PackageReview {
  readonly packageId: string;
  readonly version: string;
  readonly packageDigest: string;
  readonly previousPackageDigest?: string;
  readonly apiChange: ApiChange;
  readonly securityDelta: ReadonlyArray<SecurityDeltaItem>;
  readonly bindingDraft: DeploymentBinding;
}
/** A tenant's uploaded package version. */
export interface PackageVersion {
  readonly packageId: string;
  readonly version: string;
  readonly packageDigest: string;
  readonly descriptorSetDigest: string;
  readonly state: PackageVersionState;
  readonly revision: number;
  readonly uploadedAtUnixMs: number;
  readonly manifest?: PackageManifest;
}
/** Package versions. */
export interface PackageVersionPage {
  readonly items: ReadonlyArray<PackageVersion>;
  readonly nextCursor?: string;
}
export type PackageVersionState = "UPLOADED" | "VERIFIED" | "IN_REVIEW" | "APPROVED" | "ROLLING_OUT" | "ACTIVE" | "PAUSED" | "ROLLED_BACK" | "DEPRECATED" | "DRAINING" | "RETIRED" | "REJECTED" | "SUSPENDED";
/** Executable artifact inside the package; artifactDigest = sha256 over its exact bytes. */
export interface PackagedArtifact {
  readonly artifactId: string;
  readonly path: string;
  readonly artifactDigest: string;
  readonly sizeBytes: number;
  readonly mediaType: string;
  readonly runtimeId?: string;
}
export type Permission = "HTTP_ROUTE" | "TCP" | "CREDENTIALS_IN_SANDBOX" | "USER_SECRET" | "TOKEN_EXCHANGE" | "CONNECTION" | "DEVICE_FILES_READ" | "DEVICE_FILES_WRITE";
/** Successful consumption. */
export interface PermitConsumed {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly leaseExpiresAtUnixMs: number;
}
/** Tool Host or device consumes the single-use nonce; returns the fencing token. */
export interface PermitConsumption {
  readonly invocationId: string;
  readonly permit: SignedInvocationPermit;
  readonly argumentsDigest: string;
  readonly toolDescriptorDigest: string;
  readonly packageDigest: string;
  readonly artifactDigest: string;
}
/** JWS protected header. */
export interface PermitHeader {
  readonly alg: "RS256";
  readonly typ: "toolgate-invocation-permit+jws";
  readonly kid: string;
}
/** Verified identity and epochs. */
export interface PermitIdentity {
  readonly tenantId: string;
  readonly mode: RequestMode;
  readonly agentId?: string;
  readonly userId?: string;
  readonly chain: ReadonlyArray<ActorHop>;
  readonly credentialEpoch?: number;
  readonly authorizationEpoch: number;
  readonly claims?: Record<string, unknown>;
}
/** v2 body of POST /access/invocations/reserve: one permit for one attempt, addressed to one Tool Host or device (invocation-state-v2.md T4). For a Tool Host the body carries the call's arguments: Control checks them against the invocation and pins their RFC 8785 digest in the permit. */
export interface PermitReservationRequest {
  readonly invocationId: string;
  readonly audience: string;
  readonly attempt: number;
  readonly arguments?: Record<string, unknown>;
}
/** Exact code and configuration the attempt may run. */
export interface PermitTarget {
  readonly toolId: string;
  readonly toolDescriptorDigest: string;
  readonly packageDigest: string;
  readonly artifactDigest: string;
  readonly runtimeDigest: string;
  readonly globalsDigest: string;
  readonly serviceProfileDigest: string;
  readonly serviceProfileRevision: number;
  readonly deploymentBindingDigest: string;
  readonly deploymentBindingRevision: number;
  readonly configRevision: number;
  readonly routes: ReadonlyArray<string>;
}
/** Admin-approved private address range. */
export interface PrivateRangeApproval {
  readonly host: string;
  readonly cidr: string;
  readonly ports: ReadonlyArray<number>;
}
/** One package difference from the previously applied profile, recorded for review. */
export interface ProfileChange {
  readonly packageId: string;
  readonly change: ProfileChangeKind;
  readonly previousVersion?: string;
  readonly previousPackageDigest?: string;
  readonly version?: string;
  readonly packageDigest?: string;
}
export type ProfileChangeKind = "ADDED" | "CHANGED" | "REMOVED";
/** One package the profile requires, pinned to an exact version. A MARKETPLACE entry must also pin its package digest; a PREBUILT_CORE entry resolves its digest from the sealed image lock, and a declared digest must equal it. hostIds names the Tool Hosts that receive its eligible ServerTool exports; ClientTool exports never deploy to a Tool Host. deviceGroupIds names the device groups whose active, approved devices receive its device-runnable exports as a signed client release (ADR 026), including devices that join later. An entry that activates exports needs hostIds or deviceGroupIds. bindingIds have their allowed package digests set to exactly the profile packages that name them. */
export interface ProfilePackage {
  readonly packageId: string;
  readonly source: ProfilePackageSource;
  readonly version: string;
  readonly packageDigest?: string;
  readonly required: boolean;
  readonly hostIds?: ReadonlyArray<string>;
  readonly deviceGroupIds?: ReadonlyArray<string>;
  readonly globals?: Record<string, unknown>;
  readonly tools?: ReadonlyArray<ProfileToolActivation>;
  readonly bindingIds?: ReadonlyArray<string>;
}
export type ProfilePackageSource = "PREBUILT_CORE" | "MARKETPLACE";
/** A call the deployment makes through the Gateway, as the named agent, to declare the profile ready. Arguments are explicit; expected names fields the structured result must contain with these exact values. */
export interface ProfileReadinessCase {
  readonly toolId: string;
  readonly agentId: string;
  readonly arguments: Record<string, unknown>;
  readonly expected?: Record<string, unknown>;
}
/** One installed export to enable, in the reviewed Tool Group named here. The export must exist in the pinned package; nothing is created to satisfy a reference. */
export interface ProfileToolActivation {
  readonly toolId: string;
  readonly toolGroupId: string;
}
/** Progress frame. */
export interface ProgressFrame {
  readonly kind: "PROGRESS";
  readonly fraction: number;
  readonly message?: string;
}
/** Supported tool protocol range, inclusive. */
export interface ProtocolRange {
  readonly min: number;
  readonly max: number;
}
/** One pinned package: where provisioning stands and why it is not ready. hostIds and globalsDigest record the deployment it applied, so an unchanged repeat does not redeploy. */
export interface ProvisioningPackageStatus {
  readonly packageId: string;
  readonly version: string;
  readonly packageDigest?: string;
  readonly required: boolean;
  readonly state: ProvisioningState;
  readonly step: ProvisioningStep;
  readonly reason?: string;
  readonly toolIds?: ReadonlyArray<string>;
  readonly hostIds?: ReadonlyArray<string>;
  readonly deviceGroupIds?: ReadonlyArray<string>;
  readonly deviceIds?: ReadonlyArray<string>;
  readonly globalsDigest?: string;
}
export type ProvisioningState = "READY" | "PENDING" | "FAILED";
/** Persisted outcome of the latest profile application. state is READY only when every required package is READY. */
export interface ProvisioningStatus {
  readonly profileId: string;
  readonly profileDigest: string;
  readonly generation: number;
  readonly state: ProvisioningState;
  readonly packages: ReadonlyArray<ProvisioningPackageStatus>;
  readonly appliedAtUnixMs: number;
  readonly previousProfileDigest?: string;
  readonly changes?: ReadonlyArray<ProfileChange>;
}
/** The latest ProvisioningStatus of every applied deployment profile, by profile id. */
export interface ProvisioningStatusPage {
  readonly items: ReadonlyArray<ProvisioningStatus>;
}
export type ProvisioningStep = "VALIDATE" | "IMPORT" | "DEPLOY" | "RELEASE" | "ROLLOUT" | "ACTIVATE" | "DONE";
/** Supersedes kills after two-person review. Issuer is always control. */
export interface RecoveryEvent {
  readonly eventId: string;
  readonly issuer: "control";
  readonly supersedes: ReadonlyArray<string>;
  readonly reviewers: ReadonlyArray<string>;
  readonly issuedAtUnixMs: number;
  readonly signature: string;
}
/** Request a reviewed recovery event. */
export interface RecoveryRequest {
  readonly supersedes: ReadonlyArray<string>;
  readonly reason: string;
}
export type RefusalReason = "BUSY" | "DIGEST_NOT_PRESENT" | "STALE_SEQUENCE" | "PERMIT_INVALID" | "PERMIT_EXPIRED" | "STORE_UNAVAILABLE" | "AUDIT_UNAVAILABLE" | "UNSUPPORTED_TIER" | "KILLED";
/** Authenticated Gateway context v2. Carries no device: server-side agents have none (decision 7). */
export interface RequestContext {
  readonly requestId: string;
  readonly tenantId: string;
  readonly mode: RequestMode;
  readonly credentialKind: CredentialKind;
  readonly issuer?: string;
  readonly userId?: string;
  readonly agentId?: string;
  readonly workloadBindingId?: string;
  readonly chain: ReadonlyArray<ActorHop>;
  readonly sessionEpoch?: number;
  readonly credentialEpoch?: number;
  readonly delegatedSessionEpoch?: number;
  readonly expiresAtUnixMs?: number;
  readonly bindingId?: string;
  readonly credentialSha256?: string;
}
export type RequestMode = "HUMAN" | "DELEGATED" | "SERVICE";
export type ReservationOutcome = "NEW" | "EXISTING" | "IDEMPOTENCY_CONFLICT" | "IDEMPOTENCY_KEY_IN_USE" | "EXPIRED_KEY";
/** Terminal output frame. */
export interface ResultFrame {
  readonly kind: "RESULT";
  readonly resultType: ResultType;
  readonly content: ReadonlyArray<Record<string, unknown>>;
  readonly structuredContent?: Record<string, unknown>;
  readonly isError: boolean;
  readonly error?: ToolErrorBody;
  readonly provenance?: ResultProvenance;
}
/** Tool Host to Gateway. */
export interface ResultMessage {
  readonly invocationId: string;
  readonly fencingToken?: string;
  readonly outcome: InvocationOutcome;
  readonly result?: ResultFrame;
}
export type ResultProvenance = "TOOL" | "UNTRUSTED_UPSTREAM";
export type ResultType = "complete" | "input_required" | "task";
export type Revision = number;
/** Rollout plan attached at enable. */
export interface RolloutPlan {
  readonly strategy: RolloutStrategy;
  readonly steps: ReadonlyArray<RolloutStep>;
  readonly autoRollback: boolean;
}
/** One exposure step. */
export interface RolloutStep {
  readonly percent: number;
  readonly soakSeconds: number;
}
export type RolloutStrategy = "ALL_AT_ONCE" | "COHORT";
/** Declared HTTP route, without host values. */
export interface RouteDeclaration {
  readonly id: string;
  readonly destination: string;
  readonly method: HttpMethod;
  readonly path: string;
  readonly query: ReadonlyArray<string>;
  readonly headers: ReadonlyArray<string>;
  readonly requestContentTypes: ReadonlyArray<string>;
  readonly maxRequestBytes: number;
  readonly maxResponseBytes: number;
  readonly streaming: Streaming;
  readonly timeoutMs: number;
  readonly auth?: string;
}
/** One retained run-response event. cursor is assigned by Control and orders replay; timestamps are display data only. */
export interface RunEvent {
  readonly cursor: number;
  readonly source: RunEventSource;
  readonly kind: RunEventKind;
  readonly attempt?: number;
  readonly sequence?: number;
  readonly executorId?: string;
  readonly emittedAtUnixMs?: number;
  readonly recordedAtUnixMs: number;
  readonly state?: string;
  readonly result?: ResultFrame;
  readonly level?: LogLevel;
  readonly message?: string;
  readonly fraction?: number;
  readonly requestId?: string;
  readonly request?: Record<string, unknown>;
}
/** Control acknowledgement: the highest contiguous sequence stored for the attempt, and how many frames were already present or refused by retention bounds. */
export interface RunEventAck {
  readonly invocationId: string;
  readonly attempt: number;
  readonly acceptedThrough: number;
  readonly duplicates: number;
  readonly refused: number;
}
/** Executor to Control: frames of one admitted attempt, bound to the invocation and the fencing token its permit consumption returned. Redelivery of the same sequence is idempotent. droppedFrames counts frames the executor discarded under its own bounds. */
export interface RunEventBatch {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly attempt: number;
  readonly events: ReadonlyArray<RunEventFrame>;
  readonly droppedFrames?: number;
}
/** One redacted non-terminal SDK frame (LOG, PROGRESS or INPUT_REQUEST) as the executor forwards it. sequence is monotonic per invocation attempt; the executor never renumbers a frame it resends. */
export interface RunEventFrame {
  readonly sequence: number;
  readonly emittedAtUnixMs: number;
  readonly kind: RunEventKind;
  readonly level?: LogLevel;
  readonly message?: string;
  readonly fraction?: number;
  readonly requestId?: string;
  readonly request?: Record<string, unknown>;
}
/** Sequences of an attempt that never arrived; they are reported, never invented. */
export interface RunEventGap {
  readonly attempt: number;
  readonly fromSequence: number;
  readonly toSequence: number;
}
export type RunEventKind = "LOG" | "PROGRESS" | "INPUT_REQUEST" | "STATE" | "RESULT";
/** Authorized replay page after a cursor. terminal is the authoritative ledger state; truncated, droppedFrames, gaps and expired say explicitly what is not shown. */
export interface RunEventPage {
  readonly invocationId: string;
  readonly state: string;
  readonly terminal: boolean;
  readonly toolId?: string;
  readonly executorId?: string;
  readonly events: ReadonlyArray<RunEvent>;
  readonly nextCursor: number;
  readonly more: boolean;
  readonly truncated: boolean;
  readonly droppedFrames: number;
  readonly expired: boolean;
  readonly gaps: ReadonlyArray<RunEventGap>;
}
export type RunEventSource = "EXECUTOR" | "PLATFORM";
/** Language runtime a tool needs. */
export interface RuntimeDeclaration {
  readonly runtimeId: string;
  readonly language: string;
  readonly version: string;
  readonly baseImageDigest?: string;
}
export type RuntimeTier = "OCI_SANDBOX" | "WASI" | "HOST_NATIVE";
/** SDK that built the package. */
export interface SdkInfo {
  readonly language: SdkLanguage;
  readonly version: string;
  readonly protocol: ProtocolRange;
  readonly descriptorGeneration: number;
}
export type SdkLanguage = "JAVA" | "PYTHON" | "TYPESCRIPT" | "DOTNET";
/** Declared secret; never a value. */
export interface SecretDeclaration {
  readonly name: string;
  readonly type: ValueType;
  readonly scope: SecretScope;
}
/** Secret handle, redeemed per call with the fencing token. Never a value. */
export interface SecretHandle {
  readonly name: string;
  readonly handle: string;
}
export type SecretReference = string;
export type SecretScope = "GROUP" | "USER";
/** One security delta. UNCLASSIFIED counts as widening. */
export interface SecurityDeltaItem {
  readonly kind: SecurityDeltaKind;
  readonly toolId?: string;
  readonly widening: boolean;
  readonly summary: string;
}
export type SecurityDeltaKind = "PERMISSION" | "DESTINATION" | "ROUTE" | "SECRET" | "CONNECTION" | "CLAIM" | "RUNTIME_TIER" | "PUBLISHER_KEY" | "BASE_IMAGE" | "KIND" | "EFFECT_SAFETY" | "BUSINESS_KEY_NAMESPACE" | "UNCLASSIFIED";
export type SemanticVersion = string;
/** Approved destination with its reviewed host. */
export interface ServiceDestination {
  readonly id: string;
  readonly scheme: DestinationScheme;
  readonly host: string;
  readonly port: number;
  readonly tls: TlsSettings;
  readonly mode: EgressMode;
}
/** Service profile. Its digest and revision are pinned in the permit. */
export interface ServiceProfile {
  readonly profileId: string;
  readonly revision: number;
  readonly packageId: string;
  readonly destinations: ReadonlyArray<ServiceDestination>;
  readonly routes: ReadonlyArray<RouteDeclaration>;
  readonly privateRanges: ReadonlyArray<PrivateRangeApproval>;
}
export type ServiceRoute = RouteDeclaration;
export type Sha256 = string;
/** Compact JWS over InvocationPermitClaims. */
export interface SignedInvocationPermit {
  readonly jws: string;
}
/** RFC 7515 compact RS256 JWS with a protected toolgate-discovery+jws header and TenantDiscovery v2 payload. No v1 or alternate-algorithm fallback. */
export interface SignedTenantDiscovery {
  readonly jws: string;
}
export type Slug = string;
export type Streaming = "NONE" | "RESPONSE" | "BIDIRECTIONAL";
export type TargetKind = "TOOL_HOST_POOL" | "DEVICE_GROUP";
/** Whether this request declared the MCP Tasks extension, and the owner digest a task would be bound to (invocation-state-v2.md §7). */
export interface TaskCapability {
  readonly declared: boolean;
  readonly ownerDigest: string;
}
/** tasks/update input, relayed by the Gateway to the waiting tool (invocation-state-v2.md §7). */
export interface TaskInputSubmission {
  readonly inputResponses: unknown;
}
/** Latest progress. */
export interface TaskProgress {
  readonly fraction: number;
  readonly message?: string;
  readonly atUnixMs: number;
}
export type TaskState = "ACCEPTED" | "AWAITING_APPROVAL" | "QUEUED" | "DISPATCHED" | "RUNNING" | "INPUT_REQUIRED" | "CANCEL_REQUESTED" | "COMPLETED" | "FAILED" | "REJECTED" | "EXPIRED" | "CANCELLED" | "OUTCOME_UNKNOWN";
/** One recorded transition. */
export interface TaskTransition {
  readonly from: TaskState;
  readonly to: TaskState;
  readonly actor: TransitionActor;
  readonly atUnixMs: number;
  readonly reason?: string;
}
/** A task as Control returns it to the Gateway for tasks/get (invocation-state-v2.md §7). result is present only in a terminal state. */
export interface TaskView {
  readonly invocationId: string;
  readonly task: InvocationTask;
  readonly result?: ResultFrame;
}
/** Declared TCP destination (tunnel mode). */
export interface TcpDeclaration {
  readonly id: string;
  readonly hostSetting: string;
  readonly port: number;
}
/** Signed tenant discovery v2. Exact HTTPS origins, configured/pinned UUID, lifetime, key continuity, CA purposes and active issuer are verified before enrollment or local identity access. */
export interface TenantDiscovery {
  readonly protocolVersion: 2;
  readonly tenantId: string;
  readonly issuer: string;
  readonly audiences: ReadonlyArray<string>;
  readonly serverId: string;
  readonly organization: string;
  readonly controlUrl: string;
  readonly gatewayUrl: string;
  readonly verificationUri: string;
  readonly minimumClientVersion: string;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly keySetVersion: number;
  readonly discoveryKeys: DiscoveryKeySet;
  readonly deviceIssuerKeyId: string;
  readonly deviceAuthorities: ReadonlyArray<DiscoveryDeviceAuthority>;
}
export type TenantUuid = string;
/** Upstream TLS verification. */
export interface TlsSettings {
  readonly caBundle?: string;
  readonly spkiPins: ReadonlyArray<string>;
}
/** The tool's single permission and the resources it acts on (OLO M3.7). Absent: action invoke on tool://{toolId}. */
export interface ToolAuthorization {
  readonly action: string;
  readonly resources: ReadonlyArray<string>;
}
/** Tool definition v2: the semantic descriptor plus packaging members outside the digest. */
export interface ToolDefinition {
  readonly descriptor: ToolDescriptor;
  readonly toolDescriptorDigest: string;
  readonly runtimeId?: string;
  readonly entryPoint?: string;
  readonly artifactIds: ReadonlyArray<string>;
  readonly limits?: ToolLimits;
}
/** Semantic tool descriptor, generation 1. Identical in standalone and governed mode. */
export interface ToolDescriptor {
  readonly descriptorGeneration: 1;
  readonly toolId: string;
  readonly mcpName: string;
  readonly kind: ToolKind;
  readonly title: string;
  readonly description: string;
  readonly useWhen?: string;
  readonly inputSchema: Record<string, unknown>;
  readonly outputSchema?: Record<string, unknown>;
  readonly errors: ReadonlyArray<ErrorDeclaration>;
  readonly effect: ToolEffect;
  readonly businessKey: BusinessKeySettings;
  readonly execution: ExecutionSettings;
  readonly reconcileTool?: string;
  readonly tiers?: ReadonlyArray<RuntimeTier>;
  readonly routes: ReadonlyArray<RouteDeclaration>;
  readonly destinations: ReadonlyArray<DestinationDeclaration>;
  readonly httpAuth: ReadonlyArray<HttpAuthDeclaration>;
  readonly tcp: ReadonlyArray<TcpDeclaration>;
  readonly variables: ToolVariables;
  readonly permissions: ReadonlyArray<Permission>;
  readonly toolsets: ReadonlyArray<string>;
  readonly authorization?: ToolAuthorization;
  readonly reconciliation?: ToolReconciliation;
}
/** Effect settings with defaults resolved. */
export interface ToolEffect {
  readonly safety: EffectSafety;
  readonly destructive: boolean;
  readonly openWorld: boolean;
}
/** Error per tool-sdk/spec/authoring/errors.md. */
export interface ToolErrorBody {
  readonly code: string;
  readonly message: string;
  readonly retryable: boolean;
  readonly data?: Record<string, unknown>;
}
/** One deployed tool a Tool Host runs. The host reports it in its check-in as a local tool, so Control places calls for it there. */
export interface ToolHostAssignment {
  readonly toolId: string;
  readonly action: string;
  readonly description: string;
  readonly enabled: boolean;
  readonly inputSchema: Record<string, unknown>;
  readonly toolDigest: string;
  readonly packageDigest: string;
}
/** What a registered Tool Host runs: its tools, and the deployments whose package, service profile and globals it fetches by digest. */
export interface ToolHostAssignments {
  readonly hostId: string;
  readonly tools: ReadonlyArray<ToolHostAssignment>;
  readonly deployments: ReadonlyArray<PackageDeployment>;
}
export type ToolHostDispatchState = "QUEUED" | "CLAIMED" | "ADMITTED" | "REFUSED" | "FINISHED" | "EXPIRED";
/** A dispatch the Gateway handed to Control for a Tool Host that fetches its calls (pull mode), with the host's admission and result once it has sent them. */
export interface ToolHostDispatchStatus {
  readonly invocationId: string;
  readonly hostId: string;
  readonly state: ToolHostDispatchState;
  readonly admission?: AdmissionResponse;
  readonly result?: ResultMessage;
}
/** Tool Host registration with its workload identity (mTLS). */
export interface ToolHostRegistration {
  readonly hostId: string;
  readonly poolId: string;
  readonly version: string;
  readonly protocol: ProtocolRange;
  readonly launcherVersion: string;
  readonly tiers: ReadonlyArray<RuntimeTier>;
}
export type ToolId = string;
/** First stdin frame. */
export interface ToolInput {
  readonly protocolVersion: 2;
  readonly invocationId: string;
  readonly taskId?: string;
  readonly toolId: string;
  readonly entryPoint: string;
  readonly arguments: Record<string, unknown>;
  readonly globals: Record<string, unknown>;
  readonly claims: Record<string, unknown>;
  readonly secretHandles: ReadonlyArray<SecretHandle>;
  readonly deadlineUnixMs: number;
  readonly idempotencyKey?: string;
  readonly catalogRevision: string;
  readonly traceparent?: string;
  readonly fencingToken?: string;
}
export type ToolKind = "SERVER" | "CLIENT";
/** Tool resource requests; each at most the platform ceiling (platform-requirements.md §13.5). */
export interface ToolLimits {
  readonly memoryBytes?: number;
  readonly maxInputBytes?: number;
  readonly maxOutputBytes?: number;
  readonly maxArgumentProperties?: number;
}
export type ToolLocalName = string;
export type ToolReconciliation = "DOWNSTREAM_IDEMPOTENCY" | "STATUS_LOOKUP" | "MANUAL";
/** Declared configuration and identity inputs. */
export interface ToolVariables {
  readonly globals: ReadonlyArray<GlobalDeclaration>;
  readonly secrets: ReadonlyArray<SecretDeclaration>;
  readonly claims: ReadonlyArray<ClaimDeclaration>;
}
export type TransitionActor = "GATEWAY" | "CONTROL" | "TOOL_HOST" | "CLIENT" | "ADMIN";
export type UnixMs = number;
export type ValueSource = "ENVIRONMENT" | "DATABASE" | "DEFAULT";
export type ValueType = "STRING" | "INT" | "LONG" | "NUMBER" | "BOOLEAN" | "DATE" | "DATE_TIME" | "DURATION" | "ENUM" | "URI" | "OBJECT" | "ARRAY" | "BINARY";
export type VariableName = string;
