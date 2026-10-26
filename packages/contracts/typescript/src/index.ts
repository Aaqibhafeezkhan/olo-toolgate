// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
import type * as V2 from "./v2/index.js";
/** Server-verified administrator portal session. */
export interface AdminSession {
  readonly permissions?: ReadonlyArray<string>;
  readonly role: UserRole;
  readonly bootstrapAdminUserId?: string;
  readonly tenantId?: string;
  readonly tenantName?: string;
}
/** Immutable artifact identity; digest must be verified by consumers. */
export interface ArtifactDescriptor {
  readonly uri: string;
  readonly sha256: string;
  readonly sizeBytes: number;
}
/** Runtime request; principal context and resource identity are derived by the gateway, never asserted by the caller. */
export interface AuthorizationRequest {
  readonly toolId: string;
  readonly action: string;
  readonly arguments: Record<string, unknown>;
}
/** Strict JWS protected header; no remote or embedded keys and no algorithm negotiation. */
export interface BundleHeader {
  readonly alg: string;
  readonly typ: string;
  readonly kid: string;
}
/** Publish a consistent directory snapshot or roll back into a new sequence. Requires Idempotency-Key. */
export interface BundlePublishRequest {
  readonly directoryRevision: number;
  readonly expectedSequence: number;
  readonly lifetimeMs: number;
  readonly graceMs: number;
  readonly gracePolicyIds?: ReadonlyArray<string>;
  readonly rollbackOf?: number;
}
/** Durable caller-scoped cancellation acknowledgement. */
export interface CancellationAccepted {
  readonly cancelled: boolean;
}
/** Bounded redacted device activity for OS-authenticated local status inspection. */
export interface ClientActivity {
  readonly active: ReadonlyArray<ClientCommandActivity>;
  readonly lastCommand?: ClientCommandActivity;
  readonly events: ReadonlyArray<ClientActivityEvent>;
  readonly logAvailable: boolean;
}
/** Bounded redacted device activity for OS-authenticated local status inspection. */
export interface ClientActivityEvent {
  readonly timestampUnixMs: number;
  readonly name: string;
  readonly state: string;
}
/** Bounded redacted device activity for OS-authenticated local status inspection. */
export interface ClientCommandActivity {
  readonly id: string;
  readonly name: string;
  readonly startedAtUnixMs: number;
  readonly finishedAtUnixMs?: number;
  readonly state: ClientCommandState;
  readonly progressPercent?: number;
}
export type ClientCommandState = "RUNNING" | "SUCCEEDED" | "FAILED" | "INTERRUPTED";
/** Endpoint identity foundation wire model. */
export interface ClientDiscovery {
  readonly protocolVersion: number;
  readonly serverId: string;
  readonly organization: string;
  readonly tenantId: string;
  readonly controlUrl: string;
  readonly gatewayUrl: string;
  readonly verificationUri: string;
  readonly minimumClientVersion: string;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly issuerCertificatePem: string;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface ClientDownloadArtifact {
  readonly platform: ClientPlatform;
  readonly target: string;
  readonly filename: string;
  readonly sha256: string;
  readonly bytes: number;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface ClientDownloadManifest {
  readonly version: string;
  readonly artifacts: ReadonlyArray<ClientDownloadArtifact>;
}
/** Device identity and capabilities. Enrollment credentials travel separately. */
export interface ClientEnrollmentRequest {
  readonly deviceId: string;
  readonly clientVersion: string;
  readonly capabilities: ReadonlyArray<string>;
}
/** One gateway connection held by the device service; the focused connection serves local tool commands. */
export interface ClientGatewayConnection {
  readonly serverUrl: string;
  readonly serverName?: string;
  readonly focused: boolean;
  readonly health: ClientHealth;
  readonly activity?: ClientActivity;
}
/** Endpoint identity foundation wire model. */
export interface ClientHealth {
  readonly state: EndpointState;
  readonly ready: boolean;
  readonly uptimeSeconds: number;
  readonly successfulCheckIns: number;
  readonly failedCheckIns: number;
  readonly reportSequence: number;
  readonly lastSuccessUnixMs?: number;
}
/** Unsigned interactive system-service installer; signing is a separate release gate. */
export interface ClientInstallerArtifact {
  readonly platform: ClientPlatform;
  readonly target: string;
  readonly filename: string;
  readonly sha256: string;
  readonly bytes: number;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface ClientInstallerManifest {
  readonly version: string;
  readonly artifacts: ReadonlyArray<ClientInstallerArtifact>;
}
export type ClientIpcOperation = "HEALTH" | "ENROLL" | "CHECK_IN" | "ACTIVITY" | "POWER_ON" | "POWER_OFF";
/** Endpoint identity foundation wire model. */
export interface ClientIpcRequest {
  readonly protocolVersion: number;
  readonly requestId: string;
  readonly operation: ClientIpcOperation;
}
/** Endpoint identity foundation wire model. */
export interface ClientIpcResponse {
  readonly requestId: string;
  readonly health?: ClientHealth;
  readonly challenge?: EndpointEnrollmentPrompt;
  readonly error?: ErrorCode;
  readonly activity?: ClientActivity;
  readonly connections?: ReadonlyArray<ClientGatewayConnection>;
  readonly power?: DevicePower;
}
export type ClientPlatform = "WINDOWS" | "LINUX" | "MACOS";
/** Batched report of device inventory and applied desired revision. */
export interface ClientReport {
  readonly deviceId: string;
  readonly clientVersion: string;
  readonly appliedRevision: number;
  readonly packages: ReadonlyArray<ReportedPackage>;
  readonly power?: DevicePower;
}
export type ClientSocketOperation = "CHECK_IN" | "AUTHORIZE" | "RESULT";
/** Correlated status and response; body is validated again as the expected response contract. */
export interface ClientSocketReply {
  readonly requestId: string;
  readonly status: number;
  readonly body: Record<string, unknown>;
}
/** Correlated bounded request; body is validated again as the selected operation contract. */
export interface ClientSocketRequest {
  readonly requestId: string;
  readonly operation: ClientSocketOperation;
  readonly body?: Record<string, unknown>;
}
/** A short-lived server challenge bound to a verified console session and device key. */
export interface ConsoleDeviceChallenge {
  readonly nonce: string;
  readonly deviceId: string;
  readonly keyFingerprint: string;
  readonly expiresAtUnixMs: number;
}
/** Exactly one enrollment code or existing device identifier for establishing console device context. */
export interface ConsoleDeviceLookup {
  readonly userCode?: string;
  readonly deviceId?: string;
}
/** Device-key signature over the domain-separated console challenge nonce. */
export interface ConsoleDeviceProof {
  readonly nonce: string;
  readonly deviceId: string;
  readonly keyFingerprint: string;
  readonly signature: string;
}
/** Bounded local native-client proof request for the selected console origin. */
export interface ConsoleDeviceProofRequest {
  readonly kind: string;
  readonly consoleUrl: string;
  readonly nonce: string;
}
/** Current-device context accepted for the authenticated console session. */
export interface ConsoleDeviceVerified {
  readonly deviceId: string;
  readonly expiresAtUnixMs: number;
}
/** Identifies the shared contract set, independently of product versions. */
export interface ContractSet {
  readonly name: string;
  readonly version: string;
}
/** Group/typed-role sourced complete runtime capability; never an individual ACL. */
export interface ControlAccessGrant {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly sourceType: EnterpriseSourceType;
  readonly sourceId: string;
  readonly purpose: EnterpriseGrantPurpose;
  readonly scope: EnterpriseScope;
}
/** Bounded canonical directory cursor page. */
export interface ControlAccessGrantPage {
  readonly items: ReadonlyArray<ControlAccessGrant>;
  readonly nextCursor?: string;
}
/** Tenant-scoped agents configuration record. Not a runtime credential or policy grant. */
export interface ControlAgent {
  readonly id: string;
  readonly name: string;
  readonly slug?: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly ownerUserId: string;
}
/** Bounded downstream Agent Group delegation; every hop narrows capability. */
export interface ControlAgentDelegation {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly fromAgentGroupId: string;
  readonly toAgentGroupId: string;
  readonly scope: EnterpriseScope;
  readonly maximumDepth: number;
}
/** Bounded canonical directory cursor page. */
export interface ControlAgentDelegationPage {
  readonly items: ReadonlyArray<ControlAgentDelegation>;
  readonly nextCursor?: string;
}
/** Tenant-scoped Agent membership and compatible actor/management role assignments. */
export interface ControlAgentGroup {
  readonly id: string;
  readonly name: string;
  readonly slug?: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly agentIds: ReadonlyArray<string>;
  readonly roleIds: ReadonlyArray<string>;
}
/** Bounded canonical directory cursor page. */
export interface ControlAgentGroupPage {
  readonly items: ReadonlyArray<ControlAgentGroup>;
  readonly nextCursor?: string;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlAgentPage {
  readonly items: ReadonlyArray<ControlAgent>;
  readonly nextCursor?: string;
}
/** Append-only mutation metadata; payloads and credentials are excluded. */
export interface ControlAudit {
  readonly sequence: number;
  readonly tenantId: string;
  readonly actorId: string;
  readonly operation: string;
  readonly target: string;
  readonly revision: number;
  readonly requestId: string;
  readonly occurredAt: string;
  readonly requestDigest: string;
  readonly evidence?: ControlAuditEvidence;
}
/** Authorization facts of an invocation event as separate fields (OLO M4.4). Never arguments, payloads or credentials. */
export interface ControlAuditEvidence {
  readonly invocationId: string;
  readonly mode: string;
  readonly subjectUserId?: string;
  readonly actorAgentId?: string;
  readonly delegationChain?: ReadonlyArray<string>;
  readonly workloadBindingId?: string;
  readonly credentialKind?: string;
  readonly toolId: string;
  readonly operation: string;
  readonly resources?: ReadonlyArray<string>;
  readonly resourceCount: number;
  readonly resourcesDigest: string;
  readonly decision?: Decision;
  readonly state: string;
  readonly authorizationEpoch: number;
  readonly policyRevision: number;
}
/** Cursor page of audit mutation metadata. */
export interface ControlAuditPage {
  readonly items: ReadonlyArray<ControlAudit>;
  readonly nextCursor?: string;
}
/** Deterministic import diff without payload or credential material. */
export interface ControlChange {
  readonly kind: ControlEntityKind;
  readonly id: string;
  readonly operation: ControlChangeKind;
}
export type ControlChangeKind = "CREATE" | "UPDATE" | "DELETE";
/** Delegated human operation requires grant and delegation through this same Team and capability through this same Agent Group. */
export interface ControlDelegation {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly teamId: string;
  readonly agentGroupId: string;
  readonly scope: EnterpriseScope;
}
/** Bounded canonical directory cursor page. */
export interface ControlDelegationPage {
  readonly items: ReadonlyArray<ControlDelegation>;
  readonly nextCursor?: string;
}
/** Tenant-scoped devices configuration record. Not a runtime credential or policy grant. */
export interface ControlDevice {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly ownerUserId: string;
}
/** Administrator/attestation sourced facts; callers cannot supply authoritative evidence. */
export interface ControlDeviceEvidence {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly deviceId: string;
  readonly posture: ReadonlyArray<string>;
  readonly region: string;
  readonly verifiedNetworkAddress: string;
  readonly verifiedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
}
/** Bounded canonical directory cursor page. */
export interface ControlDeviceEvidencePage {
  readonly items: ReadonlyArray<ControlDeviceEvidence>;
  readonly nextCursor?: string;
}
/** Named device group. Membership alone grants no tool execution. */
export interface ControlDeviceGroup {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly deviceIds: ReadonlyArray<string>;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlDeviceGroupPage {
  readonly items: ReadonlyArray<ControlDeviceGroup>;
  readonly nextCursor?: string;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlDevicePage {
  readonly items: ReadonlyArray<ControlDevice>;
  readonly nextCursor?: string;
}
export type ControlEntityKind = "USER" | "TEAM" | "AGENT" | "TOOL" | "POLICY" | "DEVICE" | "ROLE" | "DEVICE_GROUP" | "AGENT_GROUP" | "TOOL_GROUP" | "GRANT" | "DELEGATION" | "AGENT_DELEGATION" | "BINDING" | "EXTRACTOR" | "WORKLOAD_BINDING" | "IDENTITY_BINDING" | "DEVICE_EVIDENCE" | "SETTINGS";
/** Error that names what the caller must resolve; IDs only, never payloads. */
export interface ControlErrorDetail {
  readonly error: ControlErrorDetailCode;
  readonly ids?: ReadonlyArray<string>;
  readonly requestId?: string;
}
export type ControlErrorDetailCode = "UNRESOLVED_IDENTITIES" | "REPLACE_CONFLICTS" | "ONE_DEVICE_PER_ACCOUNT" | "REQUIRED_REVIEWS_CHANGE" | "RATE_LIMITED";
/** Explicit Tool Group to Device Group binding; one binding is selected per invocation. */
export interface ControlExecutionBinding {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly toolGroupId: string;
  readonly deviceGroupId: string;
  readonly actions: ReadonlyArray<string>;
  readonly allowedPackageDigests: ReadonlyArray<string>;
  readonly requireOnline: boolean;
  readonly ownerDependency: boolean;
}
/** Bounded canonical directory cursor page. */
export interface ControlExecutionBindingPage {
  readonly items: ReadonlyArray<ControlExecutionBinding>;
  readonly nextCursor?: string;
}
/** Stable verified tenant/issuer/subject identity; email never determines identity. */
export interface ControlIdentityBinding {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly userId: string;
  readonly issuer: string;
  readonly subject: string;
  readonly sessionEpoch: number;
  readonly firstSeenUnixMs: number;
  readonly lastAttemptUnixMs: number;
  readonly attemptCount: number;
  readonly registrationReason: string;
  readonly sessionsValidAfterUnixMs: number;
}
/** Bounded canonical directory cursor page. */
export interface ControlIdentityBindingPage {
  readonly items: ReadonlyArray<ControlIdentityBinding>;
  readonly nextCursor?: string;
}
/** How one required identity is resolved on import. MAP needs targetUserId; teamIds are added, never replaced. */
export interface ControlIdentityResolution {
  readonly action: ControlIdentityResolutionAction;
  readonly targetUserId?: string;
  readonly teamIds?: ReadonlyArray<string>;
}
export type ControlIdentityResolutionAction = "EXISTING" | "MAP" | "PLACEHOLDER";
export type ControlImportMode = "MERGE" | "REPLACE";
/** Validate and diff before applying a bounded configuration transaction. */
export interface ControlImportRequest {
  readonly snapshot: ControlSnapshot;
  readonly mode: ControlImportMode;
  readonly dryRun: boolean;
  readonly identityResolution?: Record<string, unknown>;
  readonly removeUnlistedMemberships?: boolean;
}
/** Import validation/diff result with the current tenant revision. */
export interface ControlImportResult {
  readonly applied: boolean;
  readonly revision: number;
  readonly changes: ReadonlyArray<ControlChange>;
}
/** Group-scoped guardrails. ALLOW does not create a grant; BLOCK overrides and ASK accumulates. */
export interface ControlPolicy {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly decision: Decision;
  readonly scope: EnterpriseScope;
  readonly teams: GroupSelection;
  readonly agentGroups: GroupSelection;
  readonly approverTeams: GroupSelection;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlPolicyPage {
  readonly items: ReadonlyArray<ControlPolicy>;
  readonly nextCursor?: string;
}
/** A user ID that exported records reference, with the exported teams it belonged to. IDs only. */
export interface ControlRequiredIdentity {
  readonly id: string;
  readonly teamIds: ReadonlyArray<string>;
}
/** Reviewed immutable versioned extraction definition; changing it invalidates operation bindings. */
export interface ControlResourceExtractor {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly extractorKind: EnterpriseExtractorKind;
  readonly version: string;
  readonly fields: ReadonlyArray<ExtractorField>;
  readonly fixedResources: ReadonlyArray<ResourceDescriptor>;
  readonly maxResources: number;
  readonly amountPointer?: string;
  readonly operationPointer?: string;
  readonly locatorTemplates?: ReadonlyArray<string>;
}
/** Bounded canonical directory cursor page. */
export interface ControlResourceExtractorPage {
  readonly items: ReadonlyArray<ControlResourceExtractor>;
  readonly nextCursor?: string;
}
/** Configuration review threshold in force for new changes. */
export interface ControlReviewPolicy {
  readonly requiredReviews: number;
}
/** Typed role assigned only to compatible groups. Management rules cannot confer runtime access. */
export interface ControlRole {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly portalRole: UserRole;
  readonly roleType: EnterpriseRoleType;
  readonly managementRules: ReadonlyArray<EnterpriseManagementRule>;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlRolePage {
  readonly items: ReadonlyArray<ControlRole>;
  readonly nextCursor?: string;
}
/** Enterprise group graph. Individual ACLs and retired snapshot formats are rejected. */
export interface ControlSnapshot {
  readonly formatVersion: number;
  readonly tenantId: string;
  readonly revision: number;
  readonly users: ReadonlyArray<ControlUser>;
  readonly teams: ReadonlyArray<ControlTeam>;
  readonly agents: ReadonlyArray<ControlAgent>;
  readonly tools: ReadonlyArray<ControlTool>;
  readonly policies: ReadonlyArray<ControlPolicy>;
  readonly devices: ReadonlyArray<ControlDevice>;
  readonly roles: ReadonlyArray<ControlRole>;
  readonly deviceGroups: ReadonlyArray<ControlDeviceGroup>;
  readonly agentGroups: ReadonlyArray<ControlAgentGroup>;
  readonly toolGroups: ReadonlyArray<ControlToolGroup>;
  readonly grants: ReadonlyArray<ControlAccessGrant>;
  readonly delegations: ReadonlyArray<ControlDelegation>;
  readonly agentDelegations: ReadonlyArray<ControlAgentDelegation>;
  readonly bindings: ReadonlyArray<ControlExecutionBinding>;
  readonly extractors: ReadonlyArray<ControlResourceExtractor>;
  readonly workloadBindings: ReadonlyArray<ControlWorkloadBinding>;
  readonly identityBindings: ReadonlyArray<ControlIdentityBinding>;
  readonly deviceEvidence: ReadonlyArray<ControlDeviceEvidence>;
  readonly settings?: V2.ControlServerSettingsPatch;
  readonly requiredIdentities?: ReadonlyArray<ControlRequiredIdentity>;
  readonly visitorPool?: ControlVisitorPool;
  readonly reservedRecords?: number;
}
/** Tenant-scoped teams configuration record. Not a runtime credential or policy grant. */
export interface ControlTeam {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly userIds: ReadonlyArray<string>;
  readonly roleIds: ReadonlyArray<string>;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlTeamPage {
  readonly items: ReadonlyArray<ControlTeam>;
  readonly nextCursor?: string;
}
/** Tenant-scoped tools configuration record. Not a runtime credential or policy grant. */
export interface ControlTool {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly definition: ToolDefinition;
  readonly extractorId: string;
  readonly version: string;
  readonly packageDigest: string;
}
/** Exactly one primary membership for each Tool; execution bindings are group-level. */
export interface ControlToolGroup {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly toolIds: ReadonlyArray<string>;
}
/** Bounded canonical directory cursor page. */
export interface ControlToolGroupPage {
  readonly items: ReadonlyArray<ControlToolGroup>;
  readonly nextCursor?: string;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlToolPage {
  readonly items: ReadonlyArray<ControlTool>;
  readonly nextCursor?: string;
}
/** Tenant-scoped users configuration record. Not a runtime credential or policy grant. */
export interface ControlUser {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly email?: string;
  readonly showOnLogin?: boolean;
  readonly claims?: Record<string, unknown>;
}
/** Cursor page; nextCursor is absent after the last item. */
export interface ControlUserPage {
  readonly items: ReadonlyArray<ControlUser>;
  readonly nextCursor?: string;
}
/** Quickstart demo account pool, read only at install. Never stored as directory records. */
export interface ControlVisitorPool {
  readonly prefix: string;
  readonly teamId: string;
  readonly agentGroupId: string;
  readonly delegationId?: string;
  readonly claims?: Record<string, unknown>;
  readonly passClaims?: Record<string, unknown>;
}
/** Individual identity binding is authentication only; permissions derive from Agent Groups. */
export interface ControlWorkloadBinding {
  readonly id: string;
  readonly name: string;
  readonly enabled: boolean;
  readonly revision: number;
  readonly agentId: string;
  readonly mode: V2.RequestMode;
  readonly issuer: string;
  readonly subject: string;
  readonly audience: string;
  readonly credentialSha256: string;
  readonly credentialEpoch: number;
  readonly expiresAtUnixMs: number;
  readonly delegatedUserId?: string;
  readonly parentBindingId?: string;
  readonly delegatedSessionEpoch?: number;
  readonly passClaims?: Record<string, unknown>;
  readonly needsReauthorization?: boolean;
}
/** Bounded canonical directory cursor page. */
export interface ControlWorkloadBindingPage {
  readonly items: ReadonlyArray<ControlWorkloadBinding>;
  readonly nextCursor?: string;
}
/** Updated binding and the Gateway request context to install with the token; bindingId is the execution binding the agent's grants select, when exactly one does. */
export interface ControlWorkloadCredentialResult {
  readonly binding: ControlWorkloadBinding;
  readonly context: V2.RequestContext;
}
/** Installs a new static token hash; delegated bindings are rebound to their user's current session epoch. */
export interface ControlWorkloadCredentialRotation {
  readonly credentialSha256: string;
  readonly enable: boolean;
  readonly bindingId?: string;
}
export type Decision = "ALLOW" | "ASK" | "BLOCK";
/** Organization assignment with independent Marketplace evidence; grants no runtime permission. */
export interface DeploymentAssignment {
  readonly assignmentId: string;
  readonly deviceId: string;
  readonly release: MarketplaceRelease;
  readonly organizationKeyId: string;
  readonly organizationSignature: string;
  readonly desiredPresence: boolean;
}
/** Versioned desired package assignments reconciled by an endpoint. */
export interface DesiredState {
  readonly deviceId: string;
  readonly revision: number;
  readonly assignments: ReadonlyArray<DeploymentAssignment>;
}
/** Operator-collected Docker deployment details for display only; never enrollment, permission or trusted network evidence. */
export interface DeviceDeploymentMetadata {
  readonly executionKind: DeviceExecutionKind;
  readonly containerId: string;
  readonly containerName: string;
  readonly systemName: string;
  readonly networkId: string;
  readonly networkName: string;
  readonly ipAddress: string;
}
/** Public deployment details attached only to an already visible enrolled device. */
export interface DeviceDeploymentRecord {
  readonly deviceId: string;
  readonly deployment: DeviceDeploymentMetadata;
}
export type DeviceExecutionKind = "ENDPOINT_CLIENT" | "TOOL_HOST_DIRECT" | "TOOL_HOST_PULL";
/** Endpoint identity foundation wire model. */
export interface DeviceIdentity {
  readonly deviceId: string;
  readonly tenantId: string;
  readonly userId: string;
  readonly serverId: string;
  readonly certificatePem: string;
  readonly issuerCertificatePem: string;
  readonly expiresAtUnixMs: number;
}
export type DevicePower = "ON" | "OFF";
/** Authenticated device adoption metadata. No owner or per-agent permission cache; discovery and effects require current online authority. */
export interface EndpointAdoption {
  readonly serverId: string;
  readonly deviceId: string;
  readonly revision: number;
  readonly authorizationEpoch: number;
  readonly digest: string;
}
/** Reversible approval decision for an already enrolled key, preserving its owner and directory status. */
export interface EndpointApprovalRequest {
  readonly expectedApprovalRevision: number;
  readonly approved: boolean;
  readonly connectionExpiresAtUnixMs?: number;
  readonly unlimitedConnection?: boolean;
}
/** Endpoint identity foundation wire model. */
export interface EndpointCheckIn {
  readonly sequence: number;
  readonly report: ClientReport;
  readonly adoptionDigest?: string;
  readonly localTools?: ReadonlyArray<LocalToolInfo>;
}
/** Endpoint identity foundation wire model. */
export interface EndpointCheckInAck {
  readonly deviceId: string;
  readonly sequence: number;
  readonly serverTimeUnixMs: number;
  readonly nextIntervalMs: number;
  readonly identity?: DeviceIdentity;
  readonly adoption?: EndpointAdoption;
  readonly task?: RemoteToolTask;
  readonly serverName?: string;
}
export type EndpointConnectionAction = "ENROLL" | "CHECK_IN" | "WAIT";
/** Server-authoritative response to a device presence ping; presence never grants tool access. */
export interface EndpointConnectionDirective {
  readonly deviceId: string;
  readonly state: EndpointState;
  readonly action: EndpointConnectionAction;
  readonly reportSequence: number;
  readonly nextIntervalMs: number;
}
/** Endpoint identity foundation wire model. keyFingerprint is returned only to callers who may approve through a group rule. */
export interface EndpointDeviceRecord {
  readonly deviceId: string;
  readonly tenantId: string;
  readonly userId: string;
  readonly keyFingerprint?: string;
  readonly state: EndpointState;
  readonly revision: number;
  readonly lastSeenUnixMs: number;
  readonly reportSequence: number;
  readonly report?: ClientReport;
  readonly connectionExpiresAtUnixMs?: number;
  readonly connectionApproved?: boolean;
  readonly approvalRevision?: number;
  readonly systemName?: string;
  readonly ipAddress?: string;
}
/** Toggle device directory activation, including a pending device. Zero expects no directory record yet. */
export interface EndpointEnabledRequest {
  readonly expectedRevision: number;
  readonly enabled: boolean;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentChallenge {
  readonly enrollmentId: string;
  readonly deviceCode: string;
  readonly userCode: string;
  readonly verificationUri: string;
  readonly expiresAtUnixMs: number;
  readonly pollIntervalSeconds: number;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentDecision {
  readonly userCode: string;
  readonly keyFingerprint: string;
  readonly choice: EnrollmentChoice;
  readonly connectionExpiresAtUnixMs?: number;
  readonly unlimitedConnection?: boolean;
}
/** Look up one pending enrollment by the code its client shows. */
export interface EndpointEnrollmentLookup {
  readonly userCode: string;
}
/** The pending enrollment a valid code names, for comparison with the client before approval. */
export interface EndpointEnrollmentLookupResult {
  readonly enrollmentId: string;
  readonly deviceId: string;
  readonly platform: ClientPlatform;
  readonly keyFingerprint: string;
  readonly expiresAtUnixMs: number;
}
/** All unexpired pending requests in the authenticated tenant; enrollment quota is 32. */
export interface EndpointEnrollmentPage {
  readonly items: ReadonlyArray<EndpointEnrollmentReview>;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentPoll {
  readonly enrollmentId: string;
  readonly deviceCode: string;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentPrompt {
  readonly enrollmentId: string;
  readonly userCode: string;
  readonly verificationUri: string;
  readonly expiresAtUnixMs: number;
  readonly pollIntervalSeconds: number;
  readonly keyFingerprint: string;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentResult {
  readonly state: EnrollmentState;
  readonly identity?: DeviceIdentity;
  readonly serverName?: string;
}
/** Endpoint identity foundation wire model. userCode and keyFingerprint are returned only to callers who may approve through a group rule. */
export interface EndpointEnrollmentReview {
  readonly enrollmentId: string;
  readonly userCode?: string;
  readonly deviceId: string;
  readonly platform: ClientPlatform;
  readonly keyFingerprint?: string;
  readonly state: EnrollmentState;
  readonly expiresAtUnixMs: number;
  readonly connectionExpiresAtUnixMs?: number;
  readonly unlimitedConnection?: boolean;
}
/** Endpoint identity foundation wire model. */
export interface EndpointEnrollmentStart {
  readonly deviceId: string;
  readonly clientVersion: string;
  readonly platform: ClientPlatform;
  readonly csrPem: string;
  readonly capabilities: ReadonlyArray<string>;
}
/** Directory identity, enrolled approval and pending request with server-resolved target permissions. */
export interface EndpointManagedDevice {
  readonly deviceId: string;
  readonly directoryDevice?: ControlDevice;
  readonly endpointDevice?: EndpointDeviceRecord;
  readonly enrollment?: EndpointEnrollmentReview;
  readonly registeredUser?: ControlUser;
  readonly systemName?: string;
  readonly ipAddress?: string;
  readonly permissions?: ReadonlyArray<string>;
}
/** Opt-in administrative deployment view. Default device responses retain their existing closed schema. */
export interface EndpointManagedDeviceDeploymentPage {
  readonly items: ReadonlyArray<EndpointManagedDevice>;
  readonly deployments?: ReadonlyArray<DeviceDeploymentRecord>;
}
/** All directory devices plus pending requests within the existing directory and enrollment quotas. */
export interface EndpointManagedDevicePage {
  readonly items: ReadonlyArray<EndpointManagedDevice>;
}
/** Key-proved device presence with the client's current local UTC offset for hourly revoked-device scheduling. */
export interface EndpointPresencePing {
  readonly announcement: EndpointEnrollmentStart;
  readonly utcOffset: string;
}
/** Endpoint identity foundation wire model. */
export interface EndpointRevokeRequest {
  readonly expectedRevision: number;
}
export type EndpointState = "UNENROLLED" | "PENDING" | "ACTIVE" | "OFFLINE" | "REVOKED";
export type EnrollmentChoice = "APPROVE" | "DENY";
export type EnrollmentState = "PENDING" | "APPROVED" | "DENIED" | "EXPIRED" | "CONSUMED";
/** Certificate-authenticated current device acknowledgement. Acknowledgement is metadata, never an execution capability. */
export interface EnterpriseAdoptionStatus {
  readonly deviceId: string;
  readonly directoryRevision: number;
  readonly authorizationEpoch: number;
  readonly graphDigest: string;
  readonly observedAtUnixMs: number;
}
/** Separate exact-operation and configuration approval records. Reviewers cannot expand the approved binding. */
export interface EnterpriseApproval {
  readonly id: string;
  readonly approvalType: EnterpriseApprovalType;
  readonly invocationId: string;
  readonly requestDigest: string;
  readonly authorizationEpoch: number;
  readonly directoryRevision: number;
  readonly obligationIds: ReadonlyArray<string>;
  readonly reviews: ReadonlyArray<EnterpriseApprovalReview>;
  readonly state: EnterpriseApprovalState;
  readonly revision: number;
  readonly expiresAtUnixMs: number;
}
/** Independent decision for one exact operation obligation and approval revision. */
export interface EnterpriseApprovalDecisionRequest {
  readonly expectedRevision: number;
  readonly obligationId: string;
  readonly decision: EnterpriseReviewDecision;
}
/** Current scoped operation approval queue. */
export interface EnterpriseApprovalPage {
  readonly items: ReadonlyArray<EnterpriseApproval>;
  readonly nextCursor?: string;
}
/** Audited independent review of one bound obligation. */
export interface EnterpriseApprovalReview {
  readonly obligationId: string;
  readonly reviewerUserId: string;
  readonly decision: EnterpriseReviewDecision;
  readonly decidedAtUnixMs: number;
}
export type EnterpriseApprovalState = "PENDING" | "APPROVED" | "DENIED" | "CANCELLED" | "EXPIRED" | "REVOKED";
export type EnterpriseApprovalType = "OPERATION" | "CONFIGURATION";
/** Saved authority, explicitly published signed snapshot and independently acknowledged device adoption are distinct states. */
export interface EnterpriseAuthorityStatus {
  readonly directoryRevision: number;
  readonly authorizationEpoch: number;
  readonly snapshotSequence: number;
  readonly publishedRevision?: number;
  readonly adoptions: ReadonlyArray<EnterpriseAdoptionStatus>;
}
/** Online authoritative invocation status. Only a reserved signed permit can proceed to device-authenticated consumption. */
export interface EnterpriseAuthorizationOutcome {
  readonly invocation: EnterpriseInvocation;
  readonly reservation?: EnterpriseReservation;
}
/** All present conditions accumulate. Missing trusted evidence denies. */
export interface EnterpriseConditions {
  readonly notBeforeUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly networkCidrs: ReadonlyArray<string>;
  readonly devicePosture: ReadonlyArray<string>;
  readonly regions: ReadonlyArray<string>;
  readonly hoursUtc: ReadonlyArray<EnterpriseHours>;
  readonly requireOnline: boolean;
  readonly highRisk: boolean;
  readonly maxAmountMinorUnits?: number;
  readonly maxInvocationsPerMinute?: number;
}
export type EnterpriseConfigurationAction = "SUBMIT" | "APPROVE" | "DENY" | "REVOKE" | "CANCEL" | "APPLY";
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationChange {
  readonly id: string;
  readonly requesterUserId: string;
  readonly command: EnterpriseConfigurationCommand;
  readonly requestDigest: string;
  readonly directoryRevision: number;
  readonly authorizationEpoch: number;
  readonly state: EnterpriseConfigurationState;
  readonly revision: number;
  readonly createdAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly requiredReviews: number;
  readonly impact: ReadonlyArray<EnterpriseConfigurationImpact>;
  readonly affectedGroups: ReadonlyArray<string>;
  readonly affectedIndividuals: ReadonlyArray<string>;
  readonly reviews: ReadonlyArray<EnterpriseConfigurationReview>;
}
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationCommand {
  readonly operation: EnterpriseConfigurationOperation;
  readonly kind: ControlEntityKind;
  readonly entityId: string;
  readonly document: string;
  readonly expectedRevision: number;
}
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationImpact {
  readonly kind: ControlEntityKind;
  readonly entityId: string;
  readonly operation: EnterpriseConfigurationImpactOperation;
  readonly beforeDigest: string;
  readonly afterDigest: string;
}
export type EnterpriseConfigurationImpactOperation = "CREATE" | "UPDATE" | "DELETE";
export type EnterpriseConfigurationOperation = "CREATE" | "UPDATE" | "DELETE" | "MEMBERSHIPS" | "IMPORT";
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationPage {
  readonly items: ReadonlyArray<EnterpriseConfigurationChange>;
}
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationReview {
  readonly reviewerUserId: string;
  readonly decision: EnterpriseReviewDecision;
  readonly reviewedAtUnixMs: number;
}
export type EnterpriseConfigurationState = "DRAFT" | "PENDING" | "APPROVED" | "DENIED" | "CANCELLED" | "EXPIRED" | "REVOKED" | "APPLIED" | "STALE";
/** Reviewed configuration workflow bound to the exact group graph revision. */
export interface EnterpriseConfigurationTransition {
  readonly expectedRevision: number;
  readonly action: EnterpriseConfigurationAction;
}
/** Trusted complete runtime identity. Authentication adapters construct it; ordinary arguments cannot change it. deviceId is the device Control placed the call on: the one online device of the execution binding device group (the user's own first), or the device a human chose. */
export interface EnterpriseContext {
  readonly requestId: string;
  readonly tenantId: string;
  readonly mode: V2.RequestMode;
  readonly userId?: string;
  readonly agentId?: string;
  readonly workloadBindingId?: string;
  readonly chain: ReadonlyArray<V2.ActorHop>;
  readonly sessionEpoch?: number;
  readonly credentialEpoch?: number;
  readonly bindingId: string;
  readonly deviceId: string;
}
/** Deterministic decision, safe reasons, complete witnesses and accumulated obligations. */
export interface EnterpriseDecision {
  readonly decision: Decision;
  readonly reason: EnterpriseDecisionReason;
  readonly witnesses: ReadonlyArray<EnterpriseWitness>;
  readonly obligations: ReadonlyArray<string>;
  readonly revision: number;
  readonly authorizationEpoch: number;
  readonly validUntilUnixMs: number;
  readonly diagnosticId: string;
}
export type EnterpriseDecisionReason = "MATCHED" | "NO_GRANT" | "IDENTITY_DISABLED" | "INVALID_CONTEXT" | "GROUP_UNAVAILABLE" | "NO_BINDING" | "DEVICE_UNTRUSTED" | "RESOURCE_REJECTED" | "BLOCKED" | "APPROVAL_REQUIRED" | "EVIDENCE_MISSING" | "EXPIRED" | "STALE_AUTHORITY" | "QUOTA_EXCEEDED" | "VERSION_MISMATCH" | "MANAGEMENT_DENIED";
/** Device-bound durable effect outcome and completed resource subset. */
export interface EnterpriseEffectReport {
  readonly invocationId: string;
  readonly expectedRevision: number;
  readonly state: EnterpriseInvocationState;
  readonly completedResources: ReadonlyArray<ResourceDescriptor>;
  readonly resultDigest?: string;
}
/** Read-only inherited access provenance at the current authority revision. No direct individual access mapping is created. Bindings and candidate grants do not constitute execution authorization. */
export interface EnterpriseEffectiveAccess {
  readonly entityId: string;
  readonly kind: ControlEntityKind;
  readonly directoryRevision: number;
  readonly authorizationEpoch: number;
  readonly memberships: ReadonlyArray<EnterpriseEffectiveMembership>;
  readonly bindings: ReadonlyArray<ControlExecutionBinding>;
  readonly managementRoles: ReadonlyArray<ControlRole>;
}
/** Current group membership provenance with intact grants; candidate grants still require the complete request evaluation. */
export interface EnterpriseEffectiveMembership {
  readonly groupType: EnterpriseGroupType;
  readonly groupId: string;
  readonly enabled: boolean;
  readonly roleIds: ReadonlyArray<string>;
  readonly grants: ReadonlyArray<ControlAccessGrant>;
}
/** Side-effect-free complete request for simulation or trusted enforcement. */
export interface EnterpriseEvaluation {
  readonly context: EnterpriseContext;
  readonly toolId: string;
  readonly action: string;
  readonly argumentsDigest: string;
  readonly resources: ReadonlyArray<ResourceDescriptor>;
  readonly toolDigest: string;
  readonly packageDigest: string;
  readonly nowUnixMs: number;
  readonly authorityRevision: number;
  readonly online: boolean;
  readonly amountMinorUnits?: number;
  readonly operation?: string;
}
export type EnterpriseExtractorKind = "FIXED" | "FIELDS" | "FILESYSTEM" | "NETWORK" | "SQL" | "SHELL";
export type EnterpriseGrantPurpose = "HUMAN" | "CAPABILITY" | "SERVICE" | "SECRET";
export type EnterpriseGroupType = "TEAM" | "AGENT_GROUP" | "TOOL_GROUP" | "DEVICE_GROUP";
/** Trusted UTC window; end must exceed start. */
export interface EnterpriseHours {
  readonly dayOfWeek: number;
  readonly startMinute: number;
  readonly endMinute: number;
}
/** Pending approvals carry no queued task or effect capability. Dispatched operations include the durable relay status. */
export interface EnterpriseHumanOutcome {
  readonly invocation: EnterpriseInvocation;
  readonly dispatch?: RemoteToolResponse;
}
/** Verified human selects an exact execution binding and target. User and session facts are always supplied by the authenticated adapter. */
export interface EnterpriseHumanRequest {
  readonly bindingId: string;
  readonly deviceId: string;
  readonly request: AuthorizationRequest;
}
/** Target for current verified-human discovery. */
export interface EnterpriseHumanTarget {
  readonly bindingId: string;
  readonly deviceId: string;
}
/** Durable exact binding and outcome state. OUTCOME_UNKNOWN never authorizes a blind retry. */
export interface EnterpriseInvocation {
  readonly id: string;
  readonly requestDigest: string;
  readonly evaluation: EnterpriseEvaluation;
  readonly state: EnterpriseInvocationState;
  readonly revision: number;
  readonly authorizationEpoch: number;
  readonly reservedNonce?: string;
  readonly expiresAtUnixMs: number;
  readonly completedResources: ReadonlyArray<ResourceDescriptor>;
  readonly downstreamIdempotencyKey?: string;
  readonly resultDigest?: string;
  readonly diagnosticId: string;
  readonly reconciliation?: ReconciliationMethod;
}
/** Scoped durable effect states. A cursor does not disclose hidden rows. */
export interface EnterpriseInvocationPage {
  readonly items: ReadonlyArray<EnterpriseInvocation>;
  readonly nextCursor?: string;
}
/** Exact authenticated invocation with original arguments and installed code digests. */
export interface EnterpriseInvocationRequest {
  readonly context: V2.RequestContext;
  readonly request: AuthorizationRequest;
  readonly toolDigest: string;
  readonly packageDigest: string;
  readonly downstreamIdempotencyKey?: string;
  readonly executionContext?: V2.ExecutionContext;
}
export type EnterpriseInvocationState = "PENDING_APPROVAL" | "QUEUED" | "RESERVED" | "DISPATCHED" | "EXECUTING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "EXPIRED" | "PARTIAL" | "OUTCOME_UNKNOWN";
/** Feature/action management grants belong to group-assigned Management Roles only. Generic legacy action names are rejected. */
export interface EnterpriseManagementRule {
  readonly actions: ReadonlyArray<string>;
  readonly groupType: EnterpriseGroupType;
  readonly groups: GroupSelection;
  readonly grantableScopes: ReadonlyArray<EnterpriseScope>;
  readonly conditions: EnterpriseConditions;
  readonly deviceScope?: string;
}
export type EnterpriseMemberType = "USER" | "AGENT" | "TOOL" | "DEVICE";
/** Scoped operational health and configured hard bounds. Snapshot lag never authorizes a cached effect. */
export interface EnterpriseOperationalStatus {
  readonly pendingSnapshotEvents: number;
  readonly oldestUnpublishedUnixMs: number;
  readonly unknownOutcomes: number;
  readonly expiredRunningEffects: number;
  readonly permitLifetimeMs: number;
  readonly clockSkewMs: number;
  readonly authorityCacheGraceMs: number;
  readonly readyProbeFreshnessMs: number;
}
/** Short-lived audience-bound permit for one exact invocation and target. */
export interface EnterprisePermitClaims {
  readonly issuer: string;
  readonly audience: string;
  readonly nonce: string;
  readonly invocationId: string;
  readonly requestDigest: string;
  readonly evaluationDigest: string;
  readonly authorizationEpoch: number;
  readonly directoryRevision: number;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly toolDigest: string;
  readonly packageDigest: string;
  readonly bindingId: string;
  readonly deviceId: string;
  readonly approvalIds: ReadonlyArray<string>;
}
/** Certificate-bound single-use consumption of an exact effect capability. */
export interface EnterprisePermitConsumption {
  readonly invocationId: string;
  readonly permit: EnterpriseSignedPermit;
  readonly argumentsDigest: string;
  readonly resources: ReadonlyArray<ResourceDescriptor>;
  readonly toolDigest: string;
  readonly packageDigest: string;
}
/** Purpose-specific RS256 effect capability header. */
export interface EnterprisePermitHeader {
  readonly alg: string;
  readonly typ: string;
  readonly kid: string;
}
export type EnterpriseReconciledState = "SUCCEEDED" | "FAILED" | "PARTIAL";
/** Maker and independent eligible checker resolve an unknown effect; evidence stays external. */
export interface EnterpriseReconciliation {
  readonly id: string;
  readonly invocationId: string;
  readonly requesterUserId: string;
  readonly request: EnterpriseReconciliationRequest;
  readonly requestDigest: string;
  readonly revision: number;
  readonly reviewerUserId?: string;
  readonly state: EnterpriseReconciliationState;
}
/** Independent eligible checker decision against the exact durable reconciliation revision. */
export interface EnterpriseReconciliationDecision {
  readonly expectedRevision: number;
  readonly approve: boolean;
}
/** Immutable independently reviewed downstream evidence. Never creates a new permit. */
export interface EnterpriseReconciliationRequest {
  readonly expectedRevision: number;
  readonly state: EnterpriseReconciledState;
  readonly completedResources: ReadonlyArray<ResourceDescriptor>;
  readonly evidenceDigest: string;
  readonly resultDigest?: string;
}
export type EnterpriseReconciliationState = "PENDING" | "APPLIED" | "DENIED";
/** Protected operator review for group-only bootstrap or bounded recovery. */
export interface EnterpriseRecoveryAuthorization {
  readonly formatVersion: number;
  readonly tenantId: string;
  readonly expectedRevision: number;
  readonly snapshot: ControlSnapshot;
  readonly reasonDigest: string;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
}
/** Protected operator review for group-only bootstrap or bounded recovery. */
export interface EnterpriseRecoveryProof {
  readonly keyId: string;
  readonly signature: string;
}
/** Durable invocation reservation and its exact signed capability. */
export interface EnterpriseReservation {
  readonly invocation: EnterpriseInvocation;
  readonly permit: EnterpriseSignedPermit;
}
/** Compare-and-swap reservation for a queued invocation. */
export interface EnterpriseReservationRequest {
  readonly invocationId: string;
  readonly expectedRevision: number;
}
export type EnterpriseResourceMatch = "EXACT" | "PREFIX" | "ANY";
/** Canonical complete resource constraint; ANY is explicit privileged scope. */
export interface EnterpriseResourceRule {
  readonly kind: ResourceKind;
  readonly locator: string;
  readonly match: EnterpriseResourceMatch;
}
export type EnterpriseReviewDecision = "APPROVE" | "DENY" | "REVOKE";
/** Initial tenant installation has one pinned installation authority. Existing tenant recovery requires at least two distinct pinned independent signing authorities. */
export interface EnterpriseReviewedRecovery {
  readonly authorization: EnterpriseRecoveryAuthorization;
  readonly proofs: ReadonlyArray<EnterpriseRecoveryProof>;
}
export type EnterpriseRoleType = "HUMAN" | "ACTOR_SERVICE" | "MANAGEMENT";
/** A complete group/action/device/resource tuple; independent scopes are never multiplied. */
export interface EnterpriseScope {
  readonly toolGroups: GroupSelection;
  readonly deviceGroups: GroupSelection;
  readonly actions: ReadonlyArray<string>;
  readonly allActions: boolean;
  readonly resources: ReadonlyArray<EnterpriseResourceRule>;
  readonly conditions: EnterpriseConditions;
}
/** Runtime-only secret value; never returned by management, export, result or diagnostic routes. */
export interface EnterpriseSecretDelivery {
  readonly value: string;
}
/** Certificate-bound secret delivery to an executing invocation. Original reviewed resource set must contain this exact secret. */
export interface EnterpriseSecretDeliveryRequest {
  readonly invocationId: string;
  readonly name: string;
}
/** Read-only comparison to a captured immutable legacy result; never a second runtime authority. */
export interface EnterpriseShadowRequest {
  readonly snapshot: ControlSnapshot;
  readonly evaluation: EnterpriseEvaluation;
  readonly observedLegacyDecision: Decision;
  readonly legacyEvidenceDigest: string;
}
/** No grant is applied and no approval, quota or permit is consumed. */
export interface EnterpriseShadowResult {
  readonly decision: EnterpriseDecision;
  readonly observedLegacyDecision: Decision;
  readonly legacyEvidenceDigest: string;
  readonly proposedSnapshotDigest: string;
  readonly accessExpansion: boolean;
}
/** Signed enterprise effect permit; claims are bound and consumption is durably atomic. */
export interface EnterpriseSignedPermit {
  readonly jws: string;
}
/** Signed group graph for discovery and adoption. It is never an execution permit. */
export interface EnterpriseSnapshotPayload {
  readonly formatVersion: number;
  readonly issuer: string;
  readonly audience: string;
  readonly tenantId: string;
  readonly sequence: number;
  readonly policyVersion: string;
  readonly directoryRevision: number;
  readonly authorizationEpoch: number;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly graphSha256: string;
  readonly graphBase64: string;
  readonly rollbackOf?: number;
}
export type EnterpriseSourceType = "TEAM" | "AGENT_GROUP" | "ROLE";
/** Only references covered by the current group management authority are returned. */
export interface EnterpriseVaultPage {
  readonly items: ReadonlyArray<EnterpriseVaultReference>;
}
/** Visible secret name and group boundary; no plaintext or verifier bytes. */
export interface EnterpriseVaultReference {
  readonly name: string;
  readonly toolGroupId: string;
  readonly deviceGroupId: string;
}
/** Acknowledgement after authorized encrypted custody and redacted audit commit. */
export interface EnterpriseVaultStored {
  readonly stored: boolean;
}
/** Encrypted secret custody bound to explicit Tool and Device Groups; plaintext never appears in directory export or audit. */
export interface EnterpriseVaultWrite {
  readonly name: string;
  readonly value: string;
  readonly toolGroupId: string;
  readonly deviceGroupId: string;
}
/** One complete path; Team and Agent Group identifiers cannot be mixed between witnesses. */
export interface EnterpriseWitness {
  readonly teamId?: string;
  readonly grantId?: string;
  readonly agentGroupId?: string;
  readonly capabilityId?: string;
  readonly delegationId?: string;
  readonly serviceGrantId?: string;
  readonly bindingId: string;
  readonly provenance: ReadonlyArray<string>;
}
export type ErrorCode = "VALIDATION" | "UNAUTHORIZED" | "FORBIDDEN" | "NOT_FOUND" | "CONFLICT" | "DEPENDENCY_UNAVAILABLE" | "TIMEOUT" | "INTERNAL" | "UNSUPPORTED" | "TENANT_MISMATCH" | "TENANT_UNAVAILABLE" | "MISDIRECTED_REQUEST" | "INVALID_TENANT_ID";
/** Machine-readable error without exception text or caller-controlled detail. */
export interface ErrorEnvelope {
  readonly code: ErrorCode;
  readonly requestId: string;
  readonly retryable: boolean;
}
/** Reviewed JSON pointer to resources, including every batch member or source/destination. */
export interface ExtractorField {
  readonly pointer: string;
  readonly kind: ResourceKind;
  readonly multiple: boolean;
}
export type FleetArchitecture = "x86_64" | "aarch64";
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetArtifactGrantClaims {
  readonly tenantId: string;
  readonly serverId: string;
  readonly deviceId: string;
  readonly generation: number;
  readonly manifestDigest: string;
  readonly sizeBytes: number;
  readonly grantId: string;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetArtifactGrantRequest {
  readonly generation: number;
  readonly manifestDigest: string;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetAssignment {
  readonly release: FleetPackageRelease;
  readonly desiredPresence: boolean;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetClientStatus {
  readonly generation: number;
  readonly ready: boolean;
  readonly packages: ReadonlyArray<ReportedPackage>;
  readonly error?: ErrorCode;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetDesiredDocument {
  readonly formatVersion: number;
  readonly tenantId: string;
  readonly serverId: string;
  readonly deviceId: string;
  readonly generation: number;
  readonly issuedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly assignments: ReadonlyArray<FleetAssignment>;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetDesiredSnapshot {
  readonly deviceId: string;
  readonly generation: number;
  readonly assignments: ReadonlyArray<FleetAssignment>;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetPackageDocument {
  readonly formatVersion: number;
  readonly packageId: string;
  readonly version: string;
  readonly platforms: ReadonlyArray<ClientPlatform>;
  readonly architectures: ReadonlyArray<FleetArchitecture>;
  readonly minimumClientVersion: string;
  readonly runtimes: ReadonlyArray<ManagedRuntime>;
  readonly tools: ReadonlyArray<LocalToolRegistration>;
  readonly selfTests: ReadonlyArray<FleetSelfTest>;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetPackageRelease {
  readonly packageId: string;
  readonly version: string;
  readonly manifestDigest: string;
  readonly sizeBytes: number;
  readonly release: FleetSignedDocument;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetReleasePage {
  readonly items: ReadonlyArray<FleetPackageRelease>;
  readonly nextCursor?: string;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutAdvance {
  readonly expectedRevision: number;
  readonly percentage: number;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutMember {
  readonly deviceId: string;
  readonly generation: number;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutPage {
  readonly items: ReadonlyArray<FleetRolloutStatus>;
  readonly nextCursor?: string;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutRecord {
  readonly id: string;
  readonly packageId: string;
  readonly version: string;
  readonly desiredPresence: boolean;
  readonly percentage: number;
  readonly revision: number;
  readonly createdAtUnixMs: number;
  readonly members: ReadonlyArray<FleetRolloutMember>;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutRequest {
  readonly id: string;
  readonly packageId: string;
  readonly version: string;
  readonly deviceIds: ReadonlyArray<string>;
  readonly desiredPresence: boolean;
  readonly percentage: number;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetRolloutStatus {
  readonly rollout: FleetRolloutRecord;
  readonly ready: number;
  readonly failed: number;
  readonly offline: number;
  readonly waiting: number;
  readonly pending: number;
  readonly superseded: number;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetSelfTest {
  readonly toolId: string;
  readonly arguments: Record<string, unknown>;
  readonly expectedOutput: Record<string, unknown>;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetSignatureHeader {
  readonly alg: string;
  readonly typ: string;
  readonly kid: string;
}
/** Signed fleet lifecycle contract; organization deployment trust never grants runtime permission. */
export interface FleetSignedDocument {
  readonly jws: string;
}
/** Explicit RSA public trust key; no private material or implicit domain sharing. */
export interface FleetTrustKey {
  readonly kid: string;
  readonly n: string;
  readonly e: string;
}
export type GatewayName = string;
/** Atomic complete mandatory membership replacement guarded by directory revision. */
export interface GroupMembership {
  readonly entityType: EnterpriseMemberType;
  readonly entityId: string;
  readonly groupIds: ReadonlyArray<string>;
  readonly revision: number;
}
/** Empty means none; all is explicit, tenant-scoped and requires privileged authoring. */
export interface GroupSelection {
  readonly ids: ReadonlyArray<string>;
  readonly all: boolean;
}
export type Identifier = string;
/** Reviewed deployment metadata, never a grant. Local executors independently verify the installed package and resource extraction definition. */
export interface InstalledAuthorizationProfile {
  readonly tool: ControlTool;
  readonly extractor: ControlResourceExtractor;
}
export type LocalFileAccess = "NONE" | "READ" | "WRITE";
/** Bounded per-service execution counters and sandbox state. */
export interface LocalRuntimeHealth {
  readonly ready: boolean;
  readonly successfulExecutions: number;
  readonly failedExecutions: number;
  readonly runtimes: ReadonlyArray<LocalRuntimeStatus>;
}
/** OS-authorized additive IPC revision; callers cannot select runtime images, paths or launch arguments. */
export interface LocalRuntimeIpcRequest {
  readonly protocolVersion: number;
  readonly requestId: string;
  readonly operation: LocalRuntimeOperation;
  readonly invocation?: LocalToolInput;
}
/** Canonical redacted execution/status response for IPC revision 3. */
export interface LocalRuntimeIpcResponse {
  readonly requestId: string;
  readonly health?: LocalRuntimeHealth;
  readonly result?: LocalToolOutput;
  readonly error?: ErrorCode;
}
export type LocalRuntimeKind = "JAVA_SDK" | "PYTHON_SDK";
/** Bounded local sandbox budget; limits never grant host access. */
export interface LocalRuntimeLimits {
  readonly timeoutMs: number;
  readonly memoryMiB: number;
  readonly maxInputBytes: number;
  readonly maxOutputBytes: number;
}
export type LocalRuntimeOperation = "STATUS" | "PREPARE" | "INVOKE";
export type LocalRuntimeState = "MISSING" | "READY" | "FAILED" | "UNSUPPORTED";
/** Redacted runtime readiness and capability, never engine output or credential contents. */
export interface LocalRuntimeStatus {
  readonly runtimeId: string;
  readonly kind: LocalRuntimeKind;
  readonly state: LocalRuntimeState;
  readonly version: string;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface LocalToolCall {
  readonly toolId: string;
  readonly arguments: Record<string, unknown>;
}
/** Only enabled local definitions applicable to this verified agent and device. */
export interface LocalToolCatalog {
  readonly tools: ReadonlyArray<LocalToolInfo>;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface LocalToolInfo {
  readonly toolId: string;
  readonly action: string;
  readonly description: string;
  readonly enabled: boolean;
  readonly inputSchema: Record<string, unknown>;
  readonly toolDigest: string;
  readonly packageDigest: string;
}
/** One JSON stdin document; arguments never become process command strings. */
export interface LocalToolInput {
  readonly protocolVersion: number;
  readonly requestId: string;
  readonly toolId: string;
  readonly arguments: Record<string, unknown>;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface LocalToolIpcRequest {
  readonly protocolVersion: number;
  readonly requestId: string;
  readonly operation: LocalToolOperation;
  readonly invocation?: LocalToolCall;
  readonly agentId?: string;
}
/** Local tool boundary for signed managed registrations; validate schema before use. */
export interface LocalToolIpcResponse {
  readonly requestId: string;
  readonly tools?: ReadonlyArray<LocalToolInfo>;
  readonly output?: Record<string, unknown>;
  readonly error?: ErrorCode;
}
export type LocalToolOperation = "CATALOG" | "CALL";
/** One bounded JSON stdout document; request binding and output schema are verified. */
export interface LocalToolOutput {
  readonly protocolVersion: number;
  readonly requestId: string;
  readonly output: Record<string, unknown>;
}
/** Protected local organization registration, separate from marketplace trust and online Gateway authorization. */
export interface LocalToolRegistration {
  readonly toolId: string;
  readonly action: string;
  readonly runtimeId: string;
  readonly entryPoint: string;
  readonly inputSchema: Record<string, unknown>;
  readonly outputSchema: Record<string, unknown>;
  readonly limits: LocalRuntimeLimits;
  readonly authorizationProfile: InstalledAuthorizationProfile;
  readonly fileAccess?: LocalFileAccess;
}
/** Administrator-selected immutable tool/runtime image; runtime provisioning is not execution authorization. */
export interface ManagedRuntime {
  readonly id: string;
  readonly kind: LocalRuntimeKind;
  readonly image: string;
  readonly version: string;
}
/** Marketplace trust only; never organization or runtime authorization. */
export interface MarketplaceRelease {
  readonly packageId: string;
  readonly version: string;
  readonly manifestDigest: string;
  readonly marketplaceKeyId: string;
  readonly marketplaceSignature: string;
}
/** Minimum supported contract and client versions. */
export interface PackageCompatibility {
  readonly contractsVersion: string;
  readonly minimumClientVersion: string;
}
/** Portable package metadata. Credential values are forbidden. */
export interface PackageManifest {
  readonly schemaVersion: number;
  readonly id: string;
  readonly version: string;
  readonly tools: ReadonlyArray<ToolDefinition>;
  readonly artifacts: ReadonlyArray<ArtifactDescriptor>;
  readonly credentialReferences: ReadonlyArray<string>;
  readonly compatibility: PackageCompatibility;
}
export type PackageState = "ABSENT" | "STAGING" | "READY" | "FAILED" | "REVOKED";
export type ReconciliationMethod = "DOWNSTREAM_IDEMPOTENCY" | "STATUS_LOOKUP" | "MANUAL";
/** A leased client rechecks the exact pending operation online before execution. */
export interface RemoteToolAuthorization {
  readonly requestId: string;
  readonly leaseId: string;
  readonly request: AuthorizationRequest;
}
/** Fresh remote operation deadline, never a reusable grant. */
export interface RemoteToolAuthorizationAck {
  readonly expiresAtUnixMs: number;
}
/** Same-tenant administrator inspection of one completed response; excludes arguments and private lease credentials. */
export interface RemoteToolInspection {
  readonly record: RemoteToolRecord;
  readonly output?: Record<string, unknown>;
}
/** Bounded recent local-tool request progress. */
export interface RemoteToolPage {
  readonly items: ReadonlyArray<RemoteToolRecord>;
}
/** Request progress visible to an administrator without arguments or results. */
export interface RemoteToolRecord {
  readonly requestId: string;
  readonly deviceId: string;
  readonly agentId?: string;
  readonly toolId: string;
  readonly state: RemoteToolState;
  readonly receivedAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly submittedAtUnixMs?: number;
  readonly responseAtUnixMs?: number;
  readonly completedAtUnixMs?: number;
  readonly error?: ErrorCode;
}
/** Gateway-private queued request/result response. */
export interface RemoteToolResponse {
  readonly record: RemoteToolRecord;
  readonly result?: RemoteToolResult;
}
/** Exact leased execution response. Failed execution carries a sanitized error code. */
export interface RemoteToolResult {
  readonly requestId: string;
  readonly leaseId: string;
  readonly output?: Record<string, unknown>;
  readonly error?: ErrorCode;
}
export type RemoteToolState = "RECEIVED" | "WAITING_FOR_POLL" | "SUBMITTED" | "RESPONSE_RECEIVED" | "DONE" | "FAILED" | "EXPIRED";
/** Dedicated Gateway-authenticated request for one device-local tool. */
export interface RemoteToolSubmission {
  readonly request: AuthorizationRequest;
  readonly expiresAtUnixMs: number;
  readonly context: V2.RequestContext;
  readonly invocationId: string;
  readonly traceparent?: string;
  readonly executionContext?: V2.ExecutionContext;
}
/** Bounded lease delivered only over device-authenticated polling; executable definitions are never accepted from an agent. */
export interface RemoteToolTask {
  readonly requestId: string;
  readonly leaseId: string;
  readonly request: AuthorizationRequest;
  readonly expiresAtUnixMs: number;
  readonly invocation: EnterpriseInvocation;
  readonly permit: EnterpriseSignedPermit;
  readonly traceparent?: string;
  readonly executionContext?: V2.ExecutionContext;
  readonly collectSpans?: boolean;
}
/** Observed package state, distinct from assigned desired state. */
export interface ReportedPackage {
  readonly packageId: string;
  readonly version: string;
  readonly state: PackageState;
}
/** Declared resource identity; does not grant access or validate a path. */
export interface ResourceDescriptor {
  readonly kind: ResourceKind;
  readonly locator: string;
}
export type ResourceKind = "FILE" | "URL" | "DATABASE" | "DEVICE" | "CUSTOM";
/** Pinned package build and fenced worker lease; artifacts confer no runtime authority. */
export interface SdkBuilderBuild {
  readonly id: string;
  readonly draftId: string;
  readonly draftRevision: number;
  readonly toolGroupId: string;
  readonly source: string;
  readonly sourceDigest: string;
  readonly creatorUserId: string;
  readonly state: SdkBuilderState;
  readonly revision: number;
  readonly createdAtUnixMs: number;
  readonly expiresAtUnixMs: number;
  readonly leaseId: string;
  readonly artifactDigest?: string;
  readonly sourceArchiveDigest?: string;
  readonly packageDigest?: string;
  readonly error?: SdkBuilderError;
}
/** Authorized immutable SDK package builds. */
export interface SdkBuilderBuildPage {
  readonly items: ReadonlyArray<SdkBuilderBuild>;
  readonly nextCursor?: string;
}
/** Queue an immutable project revision for the shared SDK worker. */
export interface SdkBuilderBuildRequest {
  readonly id: string;
  readonly draftId: string;
  readonly expectedRevision: number;
}
/** Canonical JSON SDK source project at an optimistic revision; no executable registration. */
export interface SdkBuilderDraft {
  readonly id: string;
  readonly revision: number;
  readonly toolGroupId: string;
  readonly source: string;
  readonly sourceDigest: string;
}
/** Authorized SDK source projects. */
export interface SdkBuilderDraftPage {
  readonly items: ReadonlyArray<SdkBuilderDraft>;
  readonly nextCursor?: string;
}
export type SdkBuilderError = "BUILD_FAILED" | "AUTHORITY_REVOKED" | "EXPIRED" | "VERSION_CONFLICT";
/** Minimal authoring context filtered by current Builder permissions, without Tool Group membership data. */
export interface SdkBuilderGroup {
  readonly id: string;
  readonly name: string;
  readonly canBuild: boolean;
}
/** Save a canonical SDK source revision in an authorized Tool Group. */
export interface SdkBuilderSave {
  readonly id: string;
  readonly expectedRevision: number;
  readonly toolGroupId: string;
  readonly source: string;
}
/** The canonical JSON SDK project read from an uploaded source ZIP; nothing is saved until the project is saved as a draft. */
export interface SdkBuilderSource {
  readonly source: string;
  readonly sourceDigest: string;
}
export type SdkBuilderState = "QUEUED" | "RUNNING" | "READY" | "FAILED";
/** Configured SDK and current authorized authoring contexts; availability grants no authority. */
export interface SdkBuilderStatus {
  readonly sdkVersion: string;
  readonly configured: boolean;
  readonly maxSourceCharacters: number;
  readonly groups: ReadonlyArray<SdkBuilderGroup>;
}
export type SecretReference = string;
export type SemanticVersion = string;
export type ServiceAudience = "olo-memory" | "olo-rag";
/** Coalesced tenant revocation feed snapshot. Services fail closed if polling becomes stale. */
export interface ServiceAuthorizationEpoch {
  readonly tenantId: string;
  readonly authorizationEpoch: number;
  readonly observedAtUnixMs: number;
}
/** Purpose-specific signed downstream authorization. */
export interface ServicePermit {
  readonly jws: string;
}
/** Control-issued exact-request authorization for a downstream service; times are Unix milliseconds. */
export interface ServicePermitClaims {
  readonly iss: string;
  readonly aud: ServiceAudience;
  readonly tenantId: string;
  readonly sub: string;
  readonly act?: string;
  readonly mode: V2.RequestMode;
  readonly operation: string;
  readonly resources: ReadonlyArray<ResourceDescriptor>;
  readonly requestFingerprint: string;
  readonly executorKeySha256: string;
  readonly invocationId: string;
  readonly nonce: string;
  readonly authorizationEpoch: number;
  readonly iat: number;
  readonly exp: number;
  readonly executionNonce: string;
}
/** Single-use downstream write permit was consumed under current authority. */
export interface ServicePermitConsumed {
  readonly authorized: boolean;
}
/** Service compares these request facts before its write, then consumes the nonce online. */
export interface ServicePermitConsumption {
  readonly permit: ServicePermit;
  readonly operation: string;
  readonly resources: ReadonlyArray<ResourceDescriptor>;
  readonly requestFingerprint: string;
  readonly executorKeySha256: string;
}
/** Executor request for downstream authorization; scope comes exclusively from the admitted invocation. */
export interface ServicePermitIssue {
  readonly invocationId: string;
  readonly fencingToken: string;
  readonly audience: ServiceAudience;
}
export type Sha256 = string;
/** Endpoint identity foundation wire model. */
export interface SignedClientDiscovery {
  readonly payload: string;
  readonly signature: string;
}
/** RFC 7515 compact JWS; payload and hash must both verify before adoption. */
export interface SignedPolicyBundle {
  readonly jws: string;
}
/** Named operation and declared resource kinds. */
export interface ToolAction {
  readonly name: string;
  readonly resourceKinds: ReadonlyArray<ResourceKind>;
}
/** Capability definition. JSON schemas are data, never executable code. */
export interface ToolDefinition {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly actions: ReadonlyArray<ToolAction>;
  readonly inputSchema: Record<string, unknown>;
  readonly outputSchema: Record<string, unknown>;
  readonly reconciliation?: ReconciliationMethod;
}
export type UserRole = "BASIC" | "ADMINISTRATOR" | "SUPER_ADMIN";
export const CONTRACT_SET_VERSION = "0.10.0-dev" as const;
export const CONTRACT_SET_NAME = "olo-toolgate-contracts" as const;
