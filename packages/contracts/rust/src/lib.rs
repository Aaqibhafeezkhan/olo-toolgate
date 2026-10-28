//! Shared structural contracts. Validate canonical schemas at input boundaries.
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
use serde::{Deserialize, Serialize};

/// Contract set v2 models, side by side with v1 during migration.
pub mod v2;
/// Server-verified administrator portal session.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AdminSession {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub permissions: Option<Vec<String>>,
    pub role: UserRole,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bootstrap_admin_user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tenant_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tenant_name: Option<String>,
}
/// Immutable artifact identity; digest must be verified by consumers.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ArtifactDescriptor {
    pub uri: String,
    pub sha256: String,
    pub size_bytes: u64,
}
/// Runtime request; principal context and resource identity are derived by the gateway, never asserted by the caller.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AuthorizationRequest {
    pub tool_id: String,
    pub action: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Strict JWS protected header; no remote or embedded keys and no algorithm negotiation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BundleHeader {
    pub alg: String,
    pub typ: String,
    pub kid: String,
}
/// Publish a consistent directory snapshot or roll back into a new sequence. Requires Idempotency-Key.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BundlePublishRequest {
    pub directory_revision: u64,
    pub expected_sequence: u64,
    pub lifetime_ms: u64,
    pub grace_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grace_policy_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rollback_of: Option<u64>,
}
/// Durable caller-scoped cancellation acknowledgement.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CancellationAccepted {
    pub cancelled: bool,
}
/// Bounded redacted device activity for OS-authenticated local status inspection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientActivity {
    pub active: Vec<ClientCommandActivity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_command: Option<ClientCommandActivity>,
    pub events: Vec<ClientActivityEvent>,
    pub log_available: bool,
}
/// Bounded redacted device activity for OS-authenticated local status inspection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientActivityEvent {
    pub timestamp_unix_ms: u64,
    pub name: String,
    pub state: String,
}
/// Bounded redacted device activity for OS-authenticated local status inspection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientCommandActivity {
    pub id: String,
    pub name: String,
    pub started_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub finished_at_unix_ms: Option<u64>,
    pub state: ClientCommandState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress_percent: Option<u64>,
}
/// Canonical ClientCommandState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClientCommandState {
    #[serde(rename = "RUNNING")]
    Running,
    #[serde(rename = "SUCCEEDED")]
    Succeeded,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "INTERRUPTED")]
    Interrupted,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientDiscovery {
    pub protocol_version: u64,
    pub server_id: String,
    pub organization: String,
    pub tenant_id: String,
    pub control_url: String,
    pub gateway_url: String,
    pub verification_uri: String,
    pub minimum_client_version: String,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub issuer_certificate_pem: String,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientDownloadArtifact {
    pub platform: ClientPlatform,
    pub target: String,
    pub filename: String,
    pub sha256: String,
    pub bytes: u64,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientDownloadManifest {
    pub version: String,
    pub artifacts: Vec<ClientDownloadArtifact>,
}
/// Device identity and capabilities. Enrollment credentials travel separately.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientEnrollmentRequest {
    pub device_id: String,
    pub client_version: String,
    pub capabilities: Vec<String>,
}
/// One gateway connection held by the device service; the focused connection serves local tool commands.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientGatewayConnection {
    pub server_url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_name: Option<String>,
    pub focused: bool,
    pub health: ClientHealth,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub activity: Option<ClientActivity>,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientHealth {
    pub state: EndpointState,
    pub ready: bool,
    pub uptime_seconds: u64,
    pub successful_check_ins: u64,
    pub failed_check_ins: u64,
    pub report_sequence: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_success_unix_ms: Option<u64>,
}
/// Unsigned interactive system-service installer; signing is a separate release gate.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientInstallerArtifact {
    pub platform: ClientPlatform,
    pub target: String,
    pub filename: String,
    pub sha256: String,
    pub bytes: u64,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientInstallerManifest {
    pub version: String,
    pub artifacts: Vec<ClientInstallerArtifact>,
}
/// Canonical ClientIpcOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClientIpcOperation {
    #[serde(rename = "HEALTH")]
    Health,
    #[serde(rename = "ENROLL")]
    Enroll,
    #[serde(rename = "CHECK_IN")]
    CheckIn,
    #[serde(rename = "ACTIVITY")]
    Activity,
    #[serde(rename = "POWER_ON")]
    PowerOn,
    #[serde(rename = "POWER_OFF")]
    PowerOff,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientIpcRequest {
    pub protocol_version: u64,
    pub request_id: String,
    pub operation: ClientIpcOperation,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientIpcResponse {
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub health: Option<ClientHealth>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub challenge: Option<EndpointEnrollmentPrompt>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub activity: Option<ClientActivity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connections: Option<Vec<ClientGatewayConnection>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub power: Option<DevicePower>,
}
/// Canonical ClientPlatform wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClientPlatform {
    #[serde(rename = "WINDOWS")]
    Windows,
    #[serde(rename = "LINUX")]
    Linux,
    #[serde(rename = "MACOS")]
    Macos,
}
/// Batched report of device inventory and applied desired revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientReport {
    pub device_id: String,
    pub client_version: String,
    pub applied_revision: u64,
    pub packages: Vec<ReportedPackage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub power: Option<DevicePower>,
}
/// Canonical ClientSocketOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClientSocketOperation {
    #[serde(rename = "CHECK_IN")]
    CheckIn,
    #[serde(rename = "AUTHORIZE")]
    Authorize,
    #[serde(rename = "RESULT")]
    Result,
}
/// Correlated status and response; body is validated again as the expected response contract.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientSocketReply {
    pub request_id: String,
    pub status: u64,
    pub body: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Correlated bounded request; body is validated again as the selected operation contract.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientSocketRequest {
    pub request_id: String,
    pub operation: ClientSocketOperation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// A short-lived server challenge bound to a verified console session and device key.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleDeviceChallenge {
    pub nonce: String,
    pub device_id: String,
    pub key_fingerprint: String,
    pub expires_at_unix_ms: u64,
}
/// Exactly one enrollment code or existing device identifier for establishing console device context.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleDeviceLookup {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
}
/// Device-key signature over the domain-separated console challenge nonce.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleDeviceProof {
    pub nonce: String,
    pub device_id: String,
    pub key_fingerprint: String,
    pub signature: String,
}
/// Bounded local native-client proof request for the selected console origin.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleDeviceProofRequest {
    pub kind: String,
    pub console_url: String,
    pub nonce: String,
}
/// Current-device context accepted for the authenticated console session.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleDeviceVerified {
    pub device_id: String,
    pub expires_at_unix_ms: u64,
}
/// Identifies the shared contract set, independently of product versions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ContractSet {
    pub name: String,
    pub version: String,
}
/// Group/typed-role sourced complete runtime capability; never an individual ACL.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAccessGrant {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub source_type: EnterpriseSourceType,
    pub source_id: String,
    pub purpose: EnterpriseGrantPurpose,
    pub scope: EnterpriseScope,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAccessGrantPage {
    pub items: Vec<ControlAccessGrant>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Tenant-scoped agents configuration record. Not a runtime credential or policy grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgent {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub slug: Option<String>,
    pub enabled: bool,
    pub revision: u64,
    pub owner_user_id: String,
}
/// Bounded downstream Agent Group delegation; every hop narrows capability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgentDelegation {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub from_agent_group_id: String,
    pub to_agent_group_id: String,
    pub scope: EnterpriseScope,
    pub maximum_depth: u64,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgentDelegationPage {
    pub items: Vec<ControlAgentDelegation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Tenant-scoped Agent membership and compatible actor/management role assignments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgentGroup {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub slug: Option<String>,
    pub enabled: bool,
    pub revision: u64,
    pub agent_ids: Vec<String>,
    pub role_ids: Vec<String>,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgentGroupPage {
    pub items: Vec<ControlAgentGroup>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAgentPage {
    pub items: Vec<ControlAgent>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Append-only mutation metadata; payloads and credentials are excluded.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAudit {
    pub sequence: u64,
    pub tenant_id: String,
    pub actor_id: String,
    pub operation: String,
    pub target: String,
    pub revision: u64,
    pub request_id: String,
    pub occurred_at: String,
    pub request_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub evidence: Option<ControlAuditEvidence>,
}
/// Authorization facts of an invocation event as separate fields (OLO M4.4). Never arguments, payloads or credentials.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAuditEvidence {
    pub invocation_id: String,
    pub mode: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subject_user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub actor_agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegation_chain: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workload_binding_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_kind: Option<String>,
    pub tool_id: String,
    pub operation: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resources: Option<Vec<String>>,
    pub resource_count: u64,
    pub resources_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub decision: Option<Decision>,
    pub state: String,
    pub authorization_epoch: u64,
    pub policy_revision: u64,
}
/// Cursor page of audit mutation metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlAuditPage {
    pub items: Vec<ControlAudit>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Deterministic import diff without payload or credential material.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlChange {
    pub kind: ControlEntityKind,
    pub id: String,
    pub operation: ControlChangeKind,
}
/// Canonical ControlChangeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlChangeKind {
    #[serde(rename = "CREATE")]
    Create,
    #[serde(rename = "UPDATE")]
    Update,
    #[serde(rename = "DELETE")]
    Delete,
}
/// Delegated human operation requires grant and delegation through this same Team and capability through this same Agent Group.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDelegation {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub team_id: String,
    pub agent_group_id: String,
    pub scope: EnterpriseScope,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDelegationPage {
    pub items: Vec<ControlDelegation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Tenant-scoped devices configuration record. Not a runtime credential or policy grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDevice {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub owner_user_id: String,
}
/// Administrator/attestation sourced facts; callers cannot supply authoritative evidence.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDeviceEvidence {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub device_id: String,
    pub posture: Vec<String>,
    pub region: String,
    pub verified_network_address: String,
    pub verified_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDeviceEvidencePage {
    pub items: Vec<ControlDeviceEvidence>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Named device group. Membership alone grants no tool execution.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDeviceGroup {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub device_ids: Vec<String>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDeviceGroupPage {
    pub items: Vec<ControlDeviceGroup>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlDevicePage {
    pub items: Vec<ControlDevice>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Canonical ControlEntityKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlEntityKind {
    #[serde(rename = "USER")]
    User,
    #[serde(rename = "TEAM")]
    Team,
    #[serde(rename = "AGENT")]
    Agent,
    #[serde(rename = "TOOL")]
    Tool,
    #[serde(rename = "POLICY")]
    Policy,
    #[serde(rename = "DEVICE")]
    Device,
    #[serde(rename = "ROLE")]
    Role,
    #[serde(rename = "DEVICE_GROUP")]
    DeviceGroup,
    #[serde(rename = "AGENT_GROUP")]
    AgentGroup,
    #[serde(rename = "TOOL_GROUP")]
    ToolGroup,
    #[serde(rename = "GRANT")]
    Grant,
    #[serde(rename = "DELEGATION")]
    Delegation,
    #[serde(rename = "AGENT_DELEGATION")]
    AgentDelegation,
    #[serde(rename = "BINDING")]
    Binding,
    #[serde(rename = "EXTRACTOR")]
    Extractor,
    #[serde(rename = "WORKLOAD_BINDING")]
    WorkloadBinding,
    #[serde(rename = "IDENTITY_BINDING")]
    IdentityBinding,
    #[serde(rename = "DEVICE_EVIDENCE")]
    DeviceEvidence,
    #[serde(rename = "SETTINGS")]
    Settings,
}
/// Error that names what the caller must resolve; IDs only, never payloads.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlErrorDetail {
    pub error: ControlErrorDetailCode,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
}
/// Canonical ControlErrorDetailCode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlErrorDetailCode {
    #[serde(rename = "UNRESOLVED_IDENTITIES")]
    UnresolvedIdentities,
    #[serde(rename = "REPLACE_CONFLICTS")]
    ReplaceConflicts,
    #[serde(rename = "ONE_DEVICE_PER_ACCOUNT")]
    OneDevicePerAccount,
    #[serde(rename = "REQUIRED_REVIEWS_CHANGE")]
    RequiredReviewsChange,
    #[serde(rename = "RATE_LIMITED")]
    RateLimited,
}
/// Explicit Tool Group to Device Group binding; one binding is selected per invocation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlExecutionBinding {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub tool_group_id: String,
    pub device_group_id: String,
    pub actions: Vec<String>,
    pub allowed_package_digests: Vec<String>,
    pub require_online: bool,
    pub owner_dependency: bool,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlExecutionBindingPage {
    pub items: Vec<ControlExecutionBinding>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Stable verified tenant/issuer/subject identity; email never determines identity.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlIdentityBinding {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub user_id: String,
    pub issuer: String,
    pub subject: String,
    pub session_epoch: u64,
    pub first_seen_unix_ms: u64,
    pub last_attempt_unix_ms: u64,
    pub attempt_count: u64,
    pub registration_reason: String,
    pub sessions_valid_after_unix_ms: u64,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlIdentityBindingPage {
    pub items: Vec<ControlIdentityBinding>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// How one required identity is resolved on import. MAP needs targetUserId; teamIds are added, never replaced.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlIdentityResolution {
    pub action: ControlIdentityResolutionAction,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub team_ids: Option<Vec<String>>,
}
/// Canonical ControlIdentityResolutionAction wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlIdentityResolutionAction {
    #[serde(rename = "EXISTING")]
    Existing,
    #[serde(rename = "MAP")]
    Map,
    #[serde(rename = "PLACEHOLDER")]
    Placeholder,
}
/// Canonical ControlImportMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlImportMode {
    #[serde(rename = "MERGE")]
    Merge,
    #[serde(rename = "REPLACE")]
    Replace,
}
/// Validate and diff before applying a bounded configuration transaction.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlImportRequest {
    pub snapshot: ControlSnapshot,
    pub mode: ControlImportMode,
    pub dry_run: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity_resolution: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remove_unlisted_memberships: Option<bool>,
}
/// Import validation/diff result with the current tenant revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlImportResult {
    pub applied: bool,
    pub revision: u64,
    pub changes: Vec<ControlChange>,
}
/// Group-scoped guardrails. ALLOW does not create a grant; BLOCK overrides and ASK accumulates.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlPolicy {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub decision: Decision,
    pub scope: EnterpriseScope,
    pub teams: GroupSelection,
    pub agent_groups: GroupSelection,
    pub approver_teams: GroupSelection,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlPolicyPage {
    pub items: Vec<ControlPolicy>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// A user ID that exported records reference, with the exported teams it belonged to. IDs only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlRequiredIdentity {
    pub id: String,
    pub team_ids: Vec<String>,
}
/// Reviewed immutable versioned extraction definition; changing it invalidates operation bindings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlResourceExtractor {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub extractor_kind: EnterpriseExtractorKind,
    pub version: String,
    pub fields: Vec<ExtractorField>,
    pub fixed_resources: Vec<ResourceDescriptor>,
    pub max_resources: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub amount_pointer: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation_pointer: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locator_templates: Option<Vec<String>>,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlResourceExtractorPage {
    pub items: Vec<ControlResourceExtractor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Configuration review threshold in force for new changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlReviewPolicy {
    pub required_reviews: u64,
}
/// Typed role assigned only to compatible groups. Management rules cannot confer runtime access.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlRole {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub portal_role: UserRole,
    pub role_type: EnterpriseRoleType,
    pub management_rules: Vec<EnterpriseManagementRule>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlRolePage {
    pub items: Vec<ControlRole>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Enterprise group graph. Individual ACLs and retired snapshot formats are rejected.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlSnapshot {
    pub format_version: u64,
    pub tenant_id: String,
    pub revision: u64,
    pub users: Vec<ControlUser>,
    pub teams: Vec<ControlTeam>,
    pub agents: Vec<ControlAgent>,
    pub tools: Vec<ControlTool>,
    pub policies: Vec<ControlPolicy>,
    pub devices: Vec<ControlDevice>,
    pub roles: Vec<ControlRole>,
    pub device_groups: Vec<ControlDeviceGroup>,
    pub agent_groups: Vec<ControlAgentGroup>,
    pub tool_groups: Vec<ControlToolGroup>,
    pub grants: Vec<ControlAccessGrant>,
    pub delegations: Vec<ControlDelegation>,
    pub agent_delegations: Vec<ControlAgentDelegation>,
    pub bindings: Vec<ControlExecutionBinding>,
    pub extractors: Vec<ControlResourceExtractor>,
    pub workload_bindings: Vec<ControlWorkloadBinding>,
    pub identity_bindings: Vec<ControlIdentityBinding>,
    pub device_evidence: Vec<ControlDeviceEvidence>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub settings: Option<v2::ControlServerSettingsPatch>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub required_identities: Option<Vec<ControlRequiredIdentity>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub visitor_pool: Option<ControlVisitorPool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reserved_records: Option<u64>,
}
/// Tenant-scoped teams configuration record. Not a runtime credential or policy grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlTeam {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub user_ids: Vec<String>,
    pub role_ids: Vec<String>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlTeamPage {
    pub items: Vec<ControlTeam>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Tenant-scoped tools configuration record. Not a runtime credential or policy grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlTool {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub definition: ToolDefinition,
    pub extractor_id: String,
    pub version: String,
    pub package_digest: String,
}
/// Exactly one primary membership for each Tool; execution bindings are group-level.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlToolGroup {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub tool_ids: Vec<String>,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlToolGroupPage {
    pub items: Vec<ControlToolGroup>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlToolPage {
    pub items: Vec<ControlTool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Tenant-scoped users configuration record. Not a runtime credential or policy grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlUser {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub show_on_login: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claims: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Cursor page; nextCursor is absent after the last item.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlUserPage {
    pub items: Vec<ControlUser>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Quickstart demo account pool, read only at install. Never stored as directory records.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlVisitorPool {
    pub prefix: String,
    pub team_id: String,
    pub agent_group_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claims: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pass_claims: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Individual identity binding is authentication only; permissions derive from Agent Groups.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlWorkloadBinding {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub revision: u64,
    pub agent_id: String,
    pub mode: v2::RequestMode,
    pub issuer: String,
    pub subject: String,
    pub audience: String,
    pub credential_sha256: String,
    pub credential_epoch: u64,
    pub expires_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegated_user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_binding_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegated_session_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pass_claims: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub needs_reauthorization: Option<bool>,
}
/// Bounded canonical directory cursor page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlWorkloadBindingPage {
    pub items: Vec<ControlWorkloadBinding>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Updated binding and the Gateway request context to install with the token; bindingId is the execution binding the agent's grants select, when exactly one does.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlWorkloadCredentialResult {
    pub binding: ControlWorkloadBinding,
    pub context: v2::RequestContext,
}
/// Installs a new static token hash; delegated bindings are rebound to their user's current session epoch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlWorkloadCredentialRotation {
    pub credential_sha256: String,
    pub enable: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding_id: Option<String>,
}
/// Canonical Decision wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Decision {
    #[serde(rename = "ALLOW")]
    Allow,
    #[serde(rename = "ASK")]
    Ask,
    #[serde(rename = "BLOCK")]
    Block,
}
/// Organization assignment with independent Marketplace evidence; grants no runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeploymentAssignment {
    pub assignment_id: String,
    pub device_id: String,
    pub release: MarketplaceRelease,
    pub organization_key_id: String,
    pub organization_signature: String,
    pub desired_presence: bool,
}
/// Versioned desired package assignments reconciled by an endpoint.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DesiredState {
    pub device_id: String,
    pub revision: u64,
    pub assignments: Vec<DeploymentAssignment>,
}
/// Operator-collected Docker deployment details for display only; never enrollment, permission or trusted network evidence.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeviceDeploymentMetadata {
    pub execution_kind: DeviceExecutionKind,
    pub container_id: String,
    pub container_name: String,
    pub system_name: String,
    pub network_id: String,
    pub network_name: String,
    pub ip_address: String,
}
/// Public deployment details attached only to an already visible enrolled device.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeviceDeploymentRecord {
    pub device_id: String,
    pub deployment: DeviceDeploymentMetadata,
}
/// Canonical DeviceExecutionKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DeviceExecutionKind {
    #[serde(rename = "ENDPOINT_CLIENT")]
    EndpointClient,
    #[serde(rename = "TOOL_HOST_DIRECT")]
    ToolHostDirect,
    #[serde(rename = "TOOL_HOST_PULL")]
    ToolHostPull,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeviceIdentity {
    pub device_id: String,
    pub tenant_id: String,
    pub user_id: String,
    pub server_id: String,
    pub certificate_pem: String,
    pub issuer_certificate_pem: String,
    pub expires_at_unix_ms: u64,
}
/// Canonical DevicePower wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DevicePower {
    #[serde(rename = "ON")]
    On,
    #[serde(rename = "OFF")]
    Off,
}
/// Authenticated device adoption metadata. No owner or per-agent permission cache; discovery and effects require current online authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointAdoption {
    pub server_id: String,
    pub device_id: String,
    pub revision: u64,
    pub authorization_epoch: u64,
    pub digest: String,
}
/// Reversible approval decision for an already enrolled key, preserving its owner and directory status.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointApprovalRequest {
    pub expected_approval_revision: u64,
    pub approved: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_expires_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlimited_connection: Option<bool>,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointCheckIn {
    pub sequence: u64,
    pub report: ClientReport,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub adoption_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_tools: Option<Vec<LocalToolInfo>>,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointCheckInAck {
    pub device_id: String,
    pub sequence: u64,
    pub server_time_unix_ms: u64,
    pub next_interval_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity: Option<DeviceIdentity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub adoption: Option<EndpointAdoption>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task: Option<RemoteToolTask>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_name: Option<String>,
}
/// Canonical EndpointConnectionAction wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EndpointConnectionAction {
    #[serde(rename = "ENROLL")]
    Enroll,
    #[serde(rename = "CHECK_IN")]
    CheckIn,
    #[serde(rename = "WAIT")]
    Wait,
}
/// Server-authoritative response to a device presence ping; presence never grants tool access.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointConnectionDirective {
    pub device_id: String,
    pub state: EndpointState,
    pub action: EndpointConnectionAction,
    pub report_sequence: u64,
    pub next_interval_ms: u64,
}
/// Endpoint identity foundation wire model. keyFingerprint is returned only to callers who may approve through a group rule.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointDeviceRecord {
    pub device_id: String,
    pub tenant_id: String,
    pub user_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key_fingerprint: Option<String>,
    pub state: EndpointState,
    pub revision: u64,
    pub last_seen_unix_ms: u64,
    pub report_sequence: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub report: Option<ClientReport>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_expires_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_approved: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_revision: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub system_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ip_address: Option<String>,
}
/// Toggle device directory activation, including a pending device. Zero expects no directory record yet.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnabledRequest {
    pub expected_revision: u64,
    pub enabled: bool,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentChallenge {
    pub enrollment_id: String,
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_at_unix_ms: u64,
    pub poll_interval_seconds: u64,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentDecision {
    pub user_code: String,
    pub key_fingerprint: String,
    pub choice: EnrollmentChoice,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_expires_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlimited_connection: Option<bool>,
}
/// Look up one pending enrollment by the code its client shows.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentLookup {
    pub user_code: String,
}
/// The pending enrollment a valid code names, for comparison with the client before approval.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentLookupResult {
    pub enrollment_id: String,
    pub device_id: String,
    pub platform: ClientPlatform,
    pub key_fingerprint: String,
    pub expires_at_unix_ms: u64,
}
/// All unexpired pending requests in the authenticated tenant; enrollment quota is 32.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentPage {
    pub items: Vec<EndpointEnrollmentReview>,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentPoll {
    pub enrollment_id: String,
    pub device_code: String,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentPrompt {
    pub enrollment_id: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_at_unix_ms: u64,
    pub poll_interval_seconds: u64,
    pub key_fingerprint: String,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentResult {
    pub state: EnrollmentState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity: Option<DeviceIdentity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_name: Option<String>,
}
/// Endpoint identity foundation wire model. userCode and keyFingerprint are returned only to callers who may approve through a group rule.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentReview {
    pub enrollment_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_code: Option<String>,
    pub device_id: String,
    pub platform: ClientPlatform,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key_fingerprint: Option<String>,
    pub state: EnrollmentState,
    pub expires_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_expires_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlimited_connection: Option<bool>,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointEnrollmentStart {
    pub device_id: String,
    pub client_version: String,
    pub platform: ClientPlatform,
    pub csr_pem: String,
    pub capabilities: Vec<String>,
}
/// Directory identity, enrolled approval and pending request with server-resolved target permissions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointManagedDevice {
    pub device_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub directory_device: Option<ControlDevice>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub endpoint_device: Option<EndpointDeviceRecord>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enrollment: Option<EndpointEnrollmentReview>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub registered_user: Option<ControlUser>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub system_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ip_address: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub permissions: Option<Vec<String>>,
}
/// Opt-in administrative deployment view. Default device responses retain their existing closed schema.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointManagedDeviceDeploymentPage {
    pub items: Vec<EndpointManagedDevice>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deployments: Option<Vec<DeviceDeploymentRecord>>,
}
/// All directory devices plus pending requests within the existing directory and enrollment quotas.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointManagedDevicePage {
    pub items: Vec<EndpointManagedDevice>,
}
/// Key-proved device presence with the client's current local UTC offset for hourly revoked-device scheduling.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointPresencePing {
    pub announcement: EndpointEnrollmentStart,
    pub utc_offset: String,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EndpointRevokeRequest {
    pub expected_revision: u64,
}
/// Canonical EndpointState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EndpointState {
    #[serde(rename = "UNENROLLED")]
    Unenrolled,
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "ACTIVE")]
    Active,
    #[serde(rename = "OFFLINE")]
    Offline,
    #[serde(rename = "REVOKED")]
    Revoked,
}
/// Canonical EnrollmentChoice wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnrollmentChoice {
    #[serde(rename = "APPROVE")]
    Approve,
    #[serde(rename = "DENY")]
    Deny,
}
/// Canonical EnrollmentState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnrollmentState {
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "DENIED")]
    Denied,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "CONSUMED")]
    Consumed,
}
/// Certificate-authenticated current device acknowledgement. Acknowledgement is metadata, never an execution capability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseAdoptionStatus {
    pub device_id: String,
    pub directory_revision: u64,
    pub authorization_epoch: u64,
    pub graph_digest: String,
    pub observed_at_unix_ms: u64,
}
/// Separate exact-operation and configuration approval records. Reviewers cannot expand the approved binding.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseApproval {
    pub id: String,
    pub approval_type: EnterpriseApprovalType,
    pub invocation_id: String,
    pub request_digest: String,
    pub authorization_epoch: u64,
    pub directory_revision: u64,
    pub obligation_ids: Vec<String>,
    pub reviews: Vec<EnterpriseApprovalReview>,
    pub state: EnterpriseApprovalState,
    pub revision: u64,
    pub expires_at_unix_ms: u64,
}
/// Independent decision for one exact operation obligation and approval revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseApprovalDecisionRequest {
    pub expected_revision: u64,
    pub obligation_id: String,
    pub decision: EnterpriseReviewDecision,
}
/// Current scoped operation approval queue.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseApprovalPage {
    pub items: Vec<EnterpriseApproval>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Audited independent review of one bound obligation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseApprovalReview {
    pub obligation_id: String,
    pub reviewer_user_id: String,
    pub decision: EnterpriseReviewDecision,
    pub decided_at_unix_ms: u64,
}
/// Canonical EnterpriseApprovalState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseApprovalState {
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "DENIED")]
    Denied,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "REVOKED")]
    Revoked,
}
/// Canonical EnterpriseApprovalType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseApprovalType {
    #[serde(rename = "OPERATION")]
    Operation,
    #[serde(rename = "CONFIGURATION")]
    Configuration,
}
/// Saved authority, explicitly published signed snapshot and independently acknowledged device adoption are distinct states.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseAuthorityStatus {
    pub directory_revision: u64,
    pub authorization_epoch: u64,
    pub snapshot_sequence: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_revision: Option<u64>,
    pub adoptions: Vec<EnterpriseAdoptionStatus>,
}
/// Online authoritative invocation status. Only a reserved signed permit can proceed to device-authenticated consumption.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseAuthorizationOutcome {
    pub invocation: EnterpriseInvocation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reservation: Option<EnterpriseReservation>,
}
/// All present conditions accumulate. Missing trusted evidence denies.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConditions {
    pub not_before_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub network_cidrs: Vec<String>,
    pub device_posture: Vec<String>,
    pub regions: Vec<String>,
    pub hours_utc: Vec<EnterpriseHours>,
    pub require_online: bool,
    pub high_risk: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_amount_minor_units: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_invocations_per_minute: Option<u64>,
}
/// Canonical EnterpriseConfigurationAction wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseConfigurationAction {
    #[serde(rename = "SUBMIT")]
    Submit,
    #[serde(rename = "APPROVE")]
    Approve,
    #[serde(rename = "DENY")]
    Deny,
    #[serde(rename = "REVOKE")]
    Revoke,
    #[serde(rename = "CANCEL")]
    Cancel,
    #[serde(rename = "APPLY")]
    Apply,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationChange {
    pub id: String,
    pub requester_user_id: String,
    pub command: EnterpriseConfigurationCommand,
    pub request_digest: String,
    pub directory_revision: u64,
    pub authorization_epoch: u64,
    pub state: EnterpriseConfigurationState,
    pub revision: u64,
    pub created_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub required_reviews: u64,
    pub impact: Vec<EnterpriseConfigurationImpact>,
    pub affected_groups: Vec<String>,
    pub affected_individuals: Vec<String>,
    pub reviews: Vec<EnterpriseConfigurationReview>,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationCommand {
    pub operation: EnterpriseConfigurationOperation,
    pub kind: ControlEntityKind,
    pub entity_id: String,
    pub document: String,
    pub expected_revision: u64,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationImpact {
    pub kind: ControlEntityKind,
    pub entity_id: String,
    pub operation: EnterpriseConfigurationImpactOperation,
    pub before_digest: String,
    pub after_digest: String,
}
/// Canonical EnterpriseConfigurationImpactOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseConfigurationImpactOperation {
    #[serde(rename = "CREATE")]
    Create,
    #[serde(rename = "UPDATE")]
    Update,
    #[serde(rename = "DELETE")]
    Delete,
}
/// Canonical EnterpriseConfigurationOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseConfigurationOperation {
    #[serde(rename = "CREATE")]
    Create,
    #[serde(rename = "UPDATE")]
    Update,
    #[serde(rename = "DELETE")]
    Delete,
    #[serde(rename = "MEMBERSHIPS")]
    Memberships,
    #[serde(rename = "IMPORT")]
    Import,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationPage {
    pub items: Vec<EnterpriseConfigurationChange>,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationReview {
    pub reviewer_user_id: String,
    pub decision: EnterpriseReviewDecision,
    pub reviewed_at_unix_ms: u64,
}
/// Canonical EnterpriseConfigurationState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseConfigurationState {
    #[serde(rename = "DRAFT")]
    Draft,
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "DENIED")]
    Denied,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "REVOKED")]
    Revoked,
    #[serde(rename = "APPLIED")]
    Applied,
    #[serde(rename = "STALE")]
    Stale,
}
/// Reviewed configuration workflow bound to the exact group graph revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseConfigurationTransition {
    pub expected_revision: u64,
    pub action: EnterpriseConfigurationAction,
}
/// Trusted complete runtime identity. Authentication adapters construct it; ordinary arguments cannot change it. deviceId is the device Control placed the call on: the one online device of the execution binding device group (the user's own first), or the device a human chose.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseContext {
    pub request_id: String,
    pub tenant_id: String,
    pub mode: v2::RequestMode,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workload_binding_id: Option<String>,
    pub chain: Vec<v2::ActorHop>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_epoch: Option<u64>,
    pub binding_id: String,
    pub device_id: String,
}
/// Deterministic decision, safe reasons, complete witnesses and accumulated obligations.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseDecision {
    pub decision: Decision,
    pub reason: EnterpriseDecisionReason,
    pub witnesses: Vec<EnterpriseWitness>,
    pub obligations: Vec<String>,
    pub revision: u64,
    pub authorization_epoch: u64,
    pub valid_until_unix_ms: u64,
    pub diagnostic_id: String,
}
/// Canonical EnterpriseDecisionReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseDecisionReason {
    #[serde(rename = "MATCHED")]
    Matched,
    #[serde(rename = "NO_GRANT")]
    NoGrant,
    #[serde(rename = "IDENTITY_DISABLED")]
    IdentityDisabled,
    #[serde(rename = "INVALID_CONTEXT")]
    InvalidContext,
    #[serde(rename = "GROUP_UNAVAILABLE")]
    GroupUnavailable,
    #[serde(rename = "NO_BINDING")]
    NoBinding,
    #[serde(rename = "DEVICE_UNTRUSTED")]
    DeviceUntrusted,
    #[serde(rename = "RESOURCE_REJECTED")]
    ResourceRejected,
    #[serde(rename = "BLOCKED")]
    Blocked,
    #[serde(rename = "APPROVAL_REQUIRED")]
    ApprovalRequired,
    #[serde(rename = "EVIDENCE_MISSING")]
    EvidenceMissing,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "STALE_AUTHORITY")]
    StaleAuthority,
    #[serde(rename = "QUOTA_EXCEEDED")]
    QuotaExceeded,
    #[serde(rename = "VERSION_MISMATCH")]
    VersionMismatch,
    #[serde(rename = "MANAGEMENT_DENIED")]
    ManagementDenied,
}
/// Device-bound durable effect outcome and completed resource subset.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseEffectReport {
    pub invocation_id: String,
    pub expected_revision: u64,
    pub state: EnterpriseInvocationState,
    pub completed_resources: Vec<ResourceDescriptor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_digest: Option<String>,
}
/// Read-only inherited access provenance at the current authority revision. No direct individual access mapping is created. Bindings and candidate grants do not constitute execution authorization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseEffectiveAccess {
    pub entity_id: String,
    pub kind: ControlEntityKind,
    pub directory_revision: u64,
    pub authorization_epoch: u64,
    pub memberships: Vec<EnterpriseEffectiveMembership>,
    pub bindings: Vec<ControlExecutionBinding>,
    pub management_roles: Vec<ControlRole>,
}
/// Current group membership provenance with intact grants; candidate grants still require the complete request evaluation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseEffectiveMembership {
    pub group_type: EnterpriseGroupType,
    pub group_id: String,
    pub enabled: bool,
    pub role_ids: Vec<String>,
    pub grants: Vec<ControlAccessGrant>,
}
/// Side-effect-free complete request for simulation or trusted enforcement.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseEvaluation {
    pub context: EnterpriseContext,
    pub tool_id: String,
    pub action: String,
    pub arguments_digest: String,
    pub resources: Vec<ResourceDescriptor>,
    pub tool_digest: String,
    pub package_digest: String,
    pub now_unix_ms: u64,
    pub authority_revision: u64,
    pub online: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub amount_minor_units: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation: Option<String>,
}
/// Canonical EnterpriseExtractorKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseExtractorKind {
    #[serde(rename = "FIXED")]
    Fixed,
    #[serde(rename = "FIELDS")]
    Fields,
    #[serde(rename = "FILESYSTEM")]
    Filesystem,
    #[serde(rename = "NETWORK")]
    Network,
    #[serde(rename = "SQL")]
    Sql,
    #[serde(rename = "SHELL")]
    Shell,
}
/// Canonical EnterpriseGrantPurpose wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseGrantPurpose {
    #[serde(rename = "HUMAN")]
    Human,
    #[serde(rename = "CAPABILITY")]
    Capability,
    #[serde(rename = "SERVICE")]
    Service,
    #[serde(rename = "SECRET")]
    Secret,
}
/// Canonical EnterpriseGroupType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseGroupType {
    #[serde(rename = "TEAM")]
    Team,
    #[serde(rename = "AGENT_GROUP")]
    AgentGroup,
    #[serde(rename = "TOOL_GROUP")]
    ToolGroup,
    #[serde(rename = "DEVICE_GROUP")]
    DeviceGroup,
}
/// Trusted UTC window; end must exceed start.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseHours {
    pub day_of_week: u64,
    pub start_minute: u64,
    pub end_minute: u64,
}
/// Pending approvals carry no queued task or effect capability. Dispatched operations include the durable relay status.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseHumanOutcome {
    pub invocation: EnterpriseInvocation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dispatch: Option<RemoteToolResponse>,
}
/// Verified human selects an exact execution binding and target. User and session facts are always supplied by the authenticated adapter.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseHumanRequest {
    pub binding_id: String,
    pub device_id: String,
    pub request: AuthorizationRequest,
}
/// Target for current verified-human discovery.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseHumanTarget {
    pub binding_id: String,
    pub device_id: String,
}
/// Durable exact binding and outcome state. OUTCOME_UNKNOWN never authorizes a blind retry.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseInvocation {
    pub id: String,
    pub request_digest: String,
    pub evaluation: EnterpriseEvaluation,
    pub state: EnterpriseInvocationState,
    pub revision: u64,
    pub authorization_epoch: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reserved_nonce: Option<String>,
    pub expires_at_unix_ms: u64,
    pub completed_resources: Vec<ResourceDescriptor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downstream_idempotency_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_digest: Option<String>,
    pub diagnostic_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reconciliation: Option<ReconciliationMethod>,
}
/// Scoped durable effect states. A cursor does not disclose hidden rows.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseInvocationPage {
    pub items: Vec<EnterpriseInvocation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Exact authenticated invocation with original arguments and installed code digests.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseInvocationRequest {
    pub context: v2::RequestContext,
    pub request: AuthorizationRequest,
    pub tool_digest: String,
    pub package_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downstream_idempotency_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_context: Option<v2::ExecutionContext>,
}
/// Canonical EnterpriseInvocationState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseInvocationState {
    #[serde(rename = "PENDING_APPROVAL")]
    PendingApproval,
    #[serde(rename = "QUEUED")]
    Queued,
    #[serde(rename = "RESERVED")]
    Reserved,
    #[serde(rename = "DISPATCHED")]
    Dispatched,
    #[serde(rename = "EXECUTING")]
    Executing,
    #[serde(rename = "SUCCEEDED")]
    Succeeded,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "PARTIAL")]
    Partial,
    #[serde(rename = "OUTCOME_UNKNOWN")]
    OutcomeUnknown,
}
/// Feature/action management grants belong to group-assigned Management Roles only. Generic legacy action names are rejected.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseManagementRule {
    pub actions: Vec<String>,
    pub group_type: EnterpriseGroupType,
    pub groups: GroupSelection,
    pub grantable_scopes: Vec<EnterpriseScope>,
    pub conditions: EnterpriseConditions,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_scope: Option<String>,
}
/// Canonical EnterpriseMemberType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseMemberType {
    #[serde(rename = "USER")]
    User,
    #[serde(rename = "AGENT")]
    Agent,
    #[serde(rename = "TOOL")]
    Tool,
    #[serde(rename = "DEVICE")]
    Device,
}
/// Scoped operational health and configured hard bounds. Snapshot lag never authorizes a cached effect.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseOperationalStatus {
    pub pending_snapshot_events: u64,
    pub oldest_unpublished_unix_ms: u64,
    pub unknown_outcomes: u64,
    pub expired_running_effects: u64,
    pub permit_lifetime_ms: u64,
    pub clock_skew_ms: u64,
    pub authority_cache_grace_ms: u64,
    pub ready_probe_freshness_ms: u64,
}
/// Short-lived audience-bound permit for one exact invocation and target.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterprisePermitClaims {
    pub issuer: String,
    pub audience: String,
    pub nonce: String,
    pub invocation_id: String,
    pub request_digest: String,
    pub evaluation_digest: String,
    pub authorization_epoch: u64,
    pub directory_revision: u64,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub tool_digest: String,
    pub package_digest: String,
    pub binding_id: String,
    pub device_id: String,
    pub approval_ids: Vec<String>,
}
/// Certificate-bound single-use consumption of an exact effect capability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterprisePermitConsumption {
    pub invocation_id: String,
    pub permit: EnterpriseSignedPermit,
    pub arguments_digest: String,
    pub resources: Vec<ResourceDescriptor>,
    pub tool_digest: String,
    pub package_digest: String,
}
/// Purpose-specific RS256 effect capability header.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterprisePermitHeader {
    pub alg: String,
    pub typ: String,
    pub kid: String,
}
/// Canonical EnterpriseReconciledState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseReconciledState {
    #[serde(rename = "SUCCEEDED")]
    Succeeded,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "PARTIAL")]
    Partial,
}
/// Maker and independent eligible checker resolve an unknown effect; evidence stays external.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReconciliation {
    pub id: String,
    pub invocation_id: String,
    pub requester_user_id: String,
    pub request: EnterpriseReconciliationRequest,
    pub request_digest: String,
    pub revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reviewer_user_id: Option<String>,
    pub state: EnterpriseReconciliationState,
}
/// Independent eligible checker decision against the exact durable reconciliation revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReconciliationDecision {
    pub expected_revision: u64,
    pub approve: bool,
}
/// Immutable independently reviewed downstream evidence. Never creates a new permit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReconciliationRequest {
    pub expected_revision: u64,
    pub state: EnterpriseReconciledState,
    pub completed_resources: Vec<ResourceDescriptor>,
    pub evidence_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_digest: Option<String>,
}
/// Canonical EnterpriseReconciliationState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseReconciliationState {
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPLIED")]
    Applied,
    #[serde(rename = "DENIED")]
    Denied,
}
/// Protected operator review for group-only bootstrap or bounded recovery.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseRecoveryAuthorization {
    pub format_version: u64,
    pub tenant_id: String,
    pub expected_revision: u64,
    pub snapshot: ControlSnapshot,
    pub reason_digest: String,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
}
/// Protected operator review for group-only bootstrap or bounded recovery.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseRecoveryProof {
    pub key_id: String,
    pub signature: String,
}
/// Durable invocation reservation and its exact signed capability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReservation {
    pub invocation: EnterpriseInvocation,
    pub permit: EnterpriseSignedPermit,
}
/// Compare-and-swap reservation for a queued invocation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReservationRequest {
    pub invocation_id: String,
    pub expected_revision: u64,
}
/// Canonical EnterpriseResourceMatch wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseResourceMatch {
    #[serde(rename = "EXACT")]
    Exact,
    #[serde(rename = "PREFIX")]
    Prefix,
    #[serde(rename = "ANY")]
    Any,
}
/// Canonical complete resource constraint; ANY is explicit privileged scope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseResourceRule {
    pub kind: ResourceKind,
    pub locator: String,
    pub r#match: EnterpriseResourceMatch,
}
/// Canonical EnterpriseReviewDecision wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseReviewDecision {
    #[serde(rename = "APPROVE")]
    Approve,
    #[serde(rename = "DENY")]
    Deny,
    #[serde(rename = "REVOKE")]
    Revoke,
}
/// Initial tenant installation has one pinned installation authority. Existing tenant recovery requires at least two distinct pinned independent signing authorities.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseReviewedRecovery {
    pub authorization: EnterpriseRecoveryAuthorization,
    pub proofs: Vec<EnterpriseRecoveryProof>,
}
/// Canonical EnterpriseRoleType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseRoleType {
    #[serde(rename = "HUMAN")]
    Human,
    #[serde(rename = "ACTOR_SERVICE")]
    ActorService,
    #[serde(rename = "MANAGEMENT")]
    Management,
}
/// A complete group/action/device/resource tuple; independent scopes are never multiplied.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseScope {
    pub tool_groups: GroupSelection,
    pub device_groups: GroupSelection,
    pub actions: Vec<String>,
    pub all_actions: bool,
    pub resources: Vec<EnterpriseResourceRule>,
    pub conditions: EnterpriseConditions,
}
/// Runtime-only secret value; never returned by management, export, result or diagnostic routes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseSecretDelivery {
    pub value: String,
}
/// Certificate-bound secret delivery to an executing invocation. Original reviewed resource set must contain this exact secret.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseSecretDeliveryRequest {
    pub invocation_id: String,
    pub name: String,
}
/// Read-only comparison to a captured immutable legacy result; never a second runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseShadowRequest {
    pub snapshot: ControlSnapshot,
    pub evaluation: EnterpriseEvaluation,
    pub observed_legacy_decision: Decision,
    pub legacy_evidence_digest: String,
}
/// No grant is applied and no approval, quota or permit is consumed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseShadowResult {
    pub decision: EnterpriseDecision,
    pub observed_legacy_decision: Decision,
    pub legacy_evidence_digest: String,
    pub proposed_snapshot_digest: String,
    pub access_expansion: bool,
}
/// Signed enterprise effect permit; claims are bound and consumption is durably atomic.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseSignedPermit {
    pub jws: String,
}
/// Signed group graph for discovery and adoption. It is never an execution permit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseSnapshotPayload {
    pub format_version: u64,
    pub issuer: String,
    pub audience: String,
    pub tenant_id: String,
    pub sequence: u64,
    pub policy_version: String,
    pub directory_revision: u64,
    pub authorization_epoch: u64,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub graph_sha256: String,
    pub graph_base64: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rollback_of: Option<u64>,
}
/// Canonical EnterpriseSourceType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnterpriseSourceType {
    #[serde(rename = "TEAM")]
    Team,
    #[serde(rename = "AGENT_GROUP")]
    AgentGroup,
    #[serde(rename = "ROLE")]
    Role,
}
/// Only references covered by the current group management authority are returned.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseVaultPage {
    pub items: Vec<EnterpriseVaultReference>,
}
/// Visible secret name and group boundary; no plaintext or verifier bytes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseVaultReference {
    pub name: String,
    pub tool_group_id: String,
    pub device_group_id: String,
}
/// Acknowledgement after authorized encrypted custody and redacted audit commit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseVaultStored {
    pub stored: bool,
}
/// Encrypted secret custody bound to explicit Tool and Device Groups; plaintext never appears in directory export or audit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseVaultWrite {
    pub name: String,
    pub value: String,
    pub tool_group_id: String,
    pub device_group_id: String,
}
/// One complete path; Team and Agent Group identifiers cannot be mixed between witnesses.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EnterpriseWitness {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub team_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grant_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_group_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capability_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub service_grant_id: Option<String>,
    pub binding_id: String,
    pub provenance: Vec<String>,
}
/// Canonical ErrorCode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ErrorCode {
    #[serde(rename = "VALIDATION")]
    Validation,
    #[serde(rename = "UNAUTHORIZED")]
    Unauthorized,
    #[serde(rename = "FORBIDDEN")]
    Forbidden,
    #[serde(rename = "NOT_FOUND")]
    NotFound,
    #[serde(rename = "CONFLICT")]
    Conflict,
    #[serde(rename = "DEPENDENCY_UNAVAILABLE")]
    DependencyUnavailable,
    #[serde(rename = "TIMEOUT")]
    Timeout,
    #[serde(rename = "INTERNAL")]
    Internal,
    #[serde(rename = "UNSUPPORTED")]
    Unsupported,
    #[serde(rename = "TENANT_MISMATCH")]
    TenantMismatch,
    #[serde(rename = "TENANT_UNAVAILABLE")]
    TenantUnavailable,
    #[serde(rename = "MISDIRECTED_REQUEST")]
    MisdirectedRequest,
    #[serde(rename = "INVALID_TENANT_ID")]
    InvalidTenantId,
}
/// Machine-readable error without exception text or caller-controlled detail.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ErrorEnvelope {
    pub code: ErrorCode,
    pub request_id: String,
    pub retryable: bool,
}
/// Reviewed JSON pointer to resources, including every batch member or source/destination.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExtractorField {
    pub pointer: String,
    pub kind: ResourceKind,
    pub multiple: bool,
}
/// Canonical FleetArchitecture wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum FleetArchitecture {
    #[serde(rename = "x86_64")]
    X8664,
    #[serde(rename = "aarch64")]
    Aarch64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetArtifactGrantClaims {
    pub tenant_id: String,
    pub server_id: String,
    pub device_id: String,
    pub generation: u64,
    pub manifest_digest: String,
    pub size_bytes: u64,
    pub grant_id: String,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetArtifactGrantRequest {
    pub generation: u64,
    pub manifest_digest: String,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetAssignment {
    pub release: FleetPackageRelease,
    pub desired_presence: bool,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetClientStatus {
    pub generation: u64,
    pub ready: bool,
    pub packages: Vec<ReportedPackage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetDesiredDocument {
    pub format_version: u64,
    pub tenant_id: String,
    pub server_id: String,
    pub device_id: String,
    pub generation: u64,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub assignments: Vec<FleetAssignment>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetDesiredSnapshot {
    pub device_id: String,
    pub generation: u64,
    pub assignments: Vec<FleetAssignment>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetPackageDocument {
    pub format_version: u64,
    pub package_id: String,
    pub version: String,
    pub platforms: Vec<ClientPlatform>,
    pub architectures: Vec<FleetArchitecture>,
    pub minimum_client_version: String,
    pub runtimes: Vec<ManagedRuntime>,
    pub tools: Vec<LocalToolRegistration>,
    pub self_tests: Vec<FleetSelfTest>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetPackageRelease {
    pub package_id: String,
    pub version: String,
    pub manifest_digest: String,
    pub size_bytes: u64,
    pub release: FleetSignedDocument,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetReleasePage {
    pub items: Vec<FleetPackageRelease>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutAdvance {
    pub expected_revision: u64,
    pub percentage: u64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutMember {
    pub device_id: String,
    pub generation: u64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutPage {
    pub items: Vec<FleetRolloutStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutRecord {
    pub id: String,
    pub package_id: String,
    pub version: String,
    pub desired_presence: bool,
    pub percentage: u64,
    pub revision: u64,
    pub created_at_unix_ms: u64,
    pub members: Vec<FleetRolloutMember>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutRequest {
    pub id: String,
    pub package_id: String,
    pub version: String,
    pub device_ids: Vec<String>,
    pub desired_presence: bool,
    pub percentage: u64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetRolloutStatus {
    pub rollout: FleetRolloutRecord,
    pub ready: u64,
    pub failed: u64,
    pub offline: u64,
    pub waiting: u64,
    pub pending: u64,
    pub superseded: u64,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetSelfTest {
    pub tool_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    pub expected_output: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetSignatureHeader {
    pub alg: String,
    pub typ: String,
    pub kid: String,
}
/// Signed fleet lifecycle contract; organization deployment trust never grants runtime permission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetSignedDocument {
    pub jws: String,
}
/// Explicit RSA public trust key; no private material or implicit domain sharing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FleetTrustKey {
    pub kid: String,
    pub n: String,
    pub e: String,
}
/// Atomic complete mandatory membership replacement guarded by directory revision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GroupMembership {
    pub entity_type: EnterpriseMemberType,
    pub entity_id: String,
    pub group_ids: Vec<String>,
    pub revision: u64,
}
/// Empty means none; all is explicit, tenant-scoped and requires privileged authoring.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GroupSelection {
    pub ids: Vec<String>,
    pub all: bool,
}
/// Reviewed deployment metadata, never a grant. Local executors independently verify the installed package and resource extraction definition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InstalledAuthorizationProfile {
    pub tool: ControlTool,
    pub extractor: ControlResourceExtractor,
}
/// Canonical LocalFileAccess wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LocalFileAccess {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "READ")]
    Read,
    #[serde(rename = "WRITE")]
    Write,
}
/// Bounded per-service execution counters and sandbox state.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRuntimeHealth {
    pub ready: bool,
    pub successful_executions: u64,
    pub failed_executions: u64,
    pub runtimes: Vec<LocalRuntimeStatus>,
}
/// OS-authorized additive IPC revision; callers cannot select runtime images, paths or launch arguments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRuntimeIpcRequest {
    pub protocol_version: u64,
    pub request_id: String,
    pub operation: LocalRuntimeOperation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub invocation: Option<LocalToolInput>,
}
/// Canonical redacted execution/status response for IPC revision 3.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRuntimeIpcResponse {
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub health: Option<LocalRuntimeHealth>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<LocalToolOutput>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
}
/// Canonical LocalRuntimeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LocalRuntimeKind {
    #[serde(rename = "JAVA_SDK")]
    JavaSdk,
    #[serde(rename = "PYTHON_SDK")]
    PythonSdk,
}
/// Bounded local sandbox budget; limits never grant host access.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRuntimeLimits {
    pub timeout_ms: u64,
    pub memory_mi_b: u64,
    pub max_input_bytes: u64,
    pub max_output_bytes: u64,
}
/// Canonical LocalRuntimeOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LocalRuntimeOperation {
    #[serde(rename = "STATUS")]
    Status,
    #[serde(rename = "PREPARE")]
    Prepare,
    #[serde(rename = "INVOKE")]
    Invoke,
}
/// Canonical LocalRuntimeState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LocalRuntimeState {
    #[serde(rename = "MISSING")]
    Missing,
    #[serde(rename = "READY")]
    Ready,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "UNSUPPORTED")]
    Unsupported,
}
/// Redacted runtime readiness and capability, never engine output or credential contents.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalRuntimeStatus {
    pub runtime_id: String,
    pub kind: LocalRuntimeKind,
    pub state: LocalRuntimeState,
    pub version: String,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolCall {
    pub tool_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Only enabled local definitions applicable to this verified agent and device.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolCatalog {
    pub tools: Vec<LocalToolInfo>,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolInfo {
    pub tool_id: String,
    pub action: String,
    pub description: String,
    pub enabled: bool,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub tool_digest: String,
    pub package_digest: String,
}
/// One JSON stdin document; arguments never become process command strings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolInput {
    pub protocol_version: u64,
    pub request_id: String,
    pub tool_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolIpcRequest {
    pub protocol_version: u64,
    pub request_id: String,
    pub operation: LocalToolOperation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub invocation: Option<LocalToolCall>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
}
/// Local tool boundary for signed managed registrations; validate schema before use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolIpcResponse {
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<LocalToolInfo>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
}
/// Canonical LocalToolOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LocalToolOperation {
    #[serde(rename = "CATALOG")]
    Catalog,
    #[serde(rename = "CALL")]
    Call,
}
/// One bounded JSON stdout document; request binding and output schema are verified.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolOutput {
    pub protocol_version: u64,
    pub request_id: String,
    pub output: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Protected local organization registration, separate from marketplace trust and online Gateway authorization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LocalToolRegistration {
    pub tool_id: String,
    pub action: String,
    pub runtime_id: String,
    pub entry_point: String,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub output_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub limits: LocalRuntimeLimits,
    pub authorization_profile: InstalledAuthorizationProfile,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file_access: Option<LocalFileAccess>,
}
/// Administrator-selected immutable tool/runtime image; runtime provisioning is not execution authorization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ManagedRuntime {
    pub id: String,
    pub kind: LocalRuntimeKind,
    pub image: String,
    pub version: String,
}
/// Marketplace trust only; never organization or runtime authorization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MarketplaceRelease {
    pub package_id: String,
    pub version: String,
    pub manifest_digest: String,
    pub marketplace_key_id: String,
    pub marketplace_signature: String,
}
/// Minimum supported contract and client versions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageCompatibility {
    pub contracts_version: String,
    pub minimum_client_version: String,
}
/// Portable package metadata. Credential values are forbidden.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageManifest {
    pub schema_version: u64,
    pub id: String,
    pub version: String,
    pub tools: Vec<ToolDefinition>,
    pub artifacts: Vec<ArtifactDescriptor>,
    pub credential_references: Vec<String>,
    pub compatibility: PackageCompatibility,
}
/// Canonical PackageState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PackageState {
    #[serde(rename = "ABSENT")]
    Absent,
    #[serde(rename = "STAGING")]
    Staging,
    #[serde(rename = "READY")]
    Ready,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "REVOKED")]
    Revoked,
}
/// Canonical ReconciliationMethod wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ReconciliationMethod {
    #[serde(rename = "DOWNSTREAM_IDEMPOTENCY")]
    DownstreamIdempotency,
    #[serde(rename = "STATUS_LOOKUP")]
    StatusLookup,
    #[serde(rename = "MANUAL")]
    Manual,
}
/// A leased client rechecks the exact pending operation online before execution.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolAuthorization {
    pub request_id: String,
    pub lease_id: String,
    pub request: AuthorizationRequest,
}
/// Fresh remote operation deadline, never a reusable grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolAuthorizationAck {
    pub expires_at_unix_ms: u64,
}
/// Same-tenant administrator inspection of one completed response; excludes arguments and private lease credentials.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolInspection {
    pub record: RemoteToolRecord,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Bounded recent local-tool request progress.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolPage {
    pub items: Vec<RemoteToolRecord>,
}
/// Request progress visible to an administrator without arguments or results.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolRecord {
    pub request_id: String,
    pub device_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    pub tool_id: String,
    pub state: RemoteToolState,
    pub received_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub submitted_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub response_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub completed_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
}
/// Gateway-private queued request/result response.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolResponse {
    pub record: RemoteToolRecord,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<RemoteToolResult>,
}
/// Exact leased execution response. Failed execution carries a sanitized error code.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolResult {
    pub request_id: String,
    pub lease_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorCode>,
}
/// Canonical RemoteToolState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RemoteToolState {
    #[serde(rename = "RECEIVED")]
    Received,
    #[serde(rename = "WAITING_FOR_POLL")]
    WaitingForPoll,
    #[serde(rename = "SUBMITTED")]
    Submitted,
    #[serde(rename = "RESPONSE_RECEIVED")]
    ResponseReceived,
    #[serde(rename = "DONE")]
    Done,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "EXPIRED")]
    Expired,
}
/// Dedicated Gateway-authenticated request for one device-local tool.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolSubmission {
    pub request: AuthorizationRequest,
    pub expires_at_unix_ms: u64,
    pub context: v2::RequestContext,
    pub invocation_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub traceparent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_context: Option<v2::ExecutionContext>,
}
/// Bounded lease delivered only over device-authenticated polling; executable definitions are never accepted from an agent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RemoteToolTask {
    pub request_id: String,
    pub lease_id: String,
    pub request: AuthorizationRequest,
    pub expires_at_unix_ms: u64,
    pub invocation: EnterpriseInvocation,
    pub permit: EnterpriseSignedPermit,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub traceparent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_context: Option<v2::ExecutionContext>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub collect_spans: Option<bool>,
}
/// Observed package state, distinct from assigned desired state.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReportedPackage {
    pub package_id: String,
    pub version: String,
    pub state: PackageState,
}
/// Declared resource identity; does not grant access or validate a path.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResourceDescriptor {
    pub kind: ResourceKind,
    pub locator: String,
}
/// Canonical ResourceKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ResourceKind {
    #[serde(rename = "FILE")]
    File,
    #[serde(rename = "URL")]
    Url,
    #[serde(rename = "DATABASE")]
    Database,
    #[serde(rename = "DEVICE")]
    Device,
    #[serde(rename = "CUSTOM")]
    Custom,
}
/// Pinned package build and fenced worker lease; artifacts confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderBuild {
    pub id: String,
    pub draft_id: String,
    pub draft_revision: u64,
    pub tool_group_id: String,
    pub source: String,
    pub source_digest: String,
    pub creator_user_id: String,
    pub state: SdkBuilderState,
    pub revision: u64,
    pub created_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub lease_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artifact_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_archive_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<SdkBuilderError>,
}
/// Authorized immutable SDK package builds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderBuildPage {
    pub items: Vec<SdkBuilderBuild>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Queue an immutable project revision for the shared SDK worker.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderBuildRequest {
    pub id: String,
    pub draft_id: String,
    pub expected_revision: u64,
}
/// Canonical JSON SDK source project at an optimistic revision; no executable registration.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderDraft {
    pub id: String,
    pub revision: u64,
    pub tool_group_id: String,
    pub source: String,
    pub source_digest: String,
}
/// Authorized SDK source projects.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderDraftPage {
    pub items: Vec<SdkBuilderDraft>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Canonical SdkBuilderError wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SdkBuilderError {
    #[serde(rename = "BUILD_FAILED")]
    BuildFailed,
    #[serde(rename = "AUTHORITY_REVOKED")]
    AuthorityRevoked,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "VERSION_CONFLICT")]
    VersionConflict,
}
/// Minimal authoring context filtered by current Builder permissions, without Tool Group membership data.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderGroup {
    pub id: String,
    pub name: String,
    pub can_build: bool,
}
/// Save a canonical SDK source revision in an authorized Tool Group.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderSave {
    pub id: String,
    pub expected_revision: u64,
    pub tool_group_id: String,
    pub source: String,
}
/// The canonical JSON SDK project read from an uploaded source ZIP; nothing is saved until the project is saved as a draft.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderSource {
    pub source: String,
    pub source_digest: String,
}
/// Canonical SdkBuilderState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SdkBuilderState {
    #[serde(rename = "QUEUED")]
    Queued,
    #[serde(rename = "RUNNING")]
    Running,
    #[serde(rename = "READY")]
    Ready,
    #[serde(rename = "FAILED")]
    Failed,
}
/// Configured SDK and current authorized authoring contexts; availability grants no authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkBuilderStatus {
    pub sdk_version: String,
    pub configured: bool,
    pub max_source_characters: u64,
    pub groups: Vec<SdkBuilderGroup>,
}
/// Canonical ServiceAudience wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ServiceAudience {
    #[serde(rename = "olo-memory")]
    OloMemory,
    #[serde(rename = "olo-rag")]
    OloRag,
}
/// Coalesced tenant revocation feed snapshot. Services fail closed if polling becomes stale.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServiceAuthorizationEpoch {
    pub tenant_id: String,
    pub authorization_epoch: u64,
    pub observed_at_unix_ms: u64,
}
/// Purpose-specific signed downstream authorization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServicePermit {
    pub jws: String,
}
/// Control-issued exact-request authorization for a downstream service; times are Unix milliseconds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServicePermitClaims {
    pub iss: String,
    pub aud: ServiceAudience,
    pub tenant_id: String,
    pub sub: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub act: Option<String>,
    pub mode: v2::RequestMode,
    pub operation: String,
    pub resources: Vec<ResourceDescriptor>,
    pub request_fingerprint: String,
    pub executor_key_sha256: String,
    pub invocation_id: String,
    pub nonce: String,
    pub authorization_epoch: u64,
    pub iat: u64,
    pub exp: u64,
    pub execution_nonce: String,
}
/// Single-use downstream write permit was consumed under current authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServicePermitConsumed {
    pub authorized: bool,
}
/// Service compares these request facts before its write, then consumes the nonce online.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServicePermitConsumption {
    pub permit: ServicePermit,
    pub operation: String,
    pub resources: Vec<ResourceDescriptor>,
    pub request_fingerprint: String,
    pub executor_key_sha256: String,
}
/// Executor request for downstream authorization; scope comes exclusively from the admitted invocation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServicePermitIssue {
    pub invocation_id: String,
    pub fencing_token: String,
    pub audience: ServiceAudience,
}
/// Endpoint identity foundation wire model.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedClientDiscovery {
    pub payload: String,
    pub signature: String,
}
/// RFC 7515 compact JWS; payload and hash must both verify before adoption.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedPolicyBundle {
    pub jws: String,
}
/// Named operation and declared resource kinds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolAction {
    pub name: String,
    pub resource_kinds: Vec<ResourceKind>,
}
/// Capability definition. JSON schemas are data, never executable code.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolDefinition {
    pub id: String,
    pub name: String,
    pub description: String,
    pub actions: Vec<ToolAction>,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub output_schema: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reconciliation: Option<ReconciliationMethod>,
}
/// Canonical UserRole wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum UserRole {
    #[serde(rename = "BASIC")]
    Basic,
    #[serde(rename = "ADMINISTRATOR")]
    Administrator,
    #[serde(rename = "SUPER_ADMIN")]
    SuperAdmin,
}
impl ContractSet {
    /// Current canonical contract set.
    pub fn current() -> Self {
        Self {
            name: "olo-toolgate-contracts".into(),
            version: "1.0.0-dev.1".into(),
        }
    }
}
/// Embedded canonical schemas for offline boundary validation.
pub const CANONICAL_SCHEMAS: &[(&str, &str)] = &[
    (
        "https://schemas.ololabs.io/toolgate/v1/bundle.schema.json",
        include_str!("../schemas/v1/bundle.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/client.schema.json",
        include_str!("../schemas/v1/client.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/common.schema.json",
        include_str!("../schemas/v1/common.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/control.schema.json",
        include_str!("../schemas/v1/control.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/deployment.schema.json",
        include_str!("../schemas/v1/deployment.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/endpoint.schema.json",
        include_str!("../schemas/v1/endpoint.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/enterprise.schema.json",
        include_str!("../schemas/v1/enterprise.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/error.schema.json",
        include_str!("../schemas/v1/error.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/execution.schema.json",
        include_str!("../schemas/v1/execution.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/fleet.schema.json",
        include_str!("../schemas/v1/fleet.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/identifiers.schema.json",
        include_str!("../schemas/v1/identifiers.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/local-tools.schema.json",
        include_str!("../schemas/v1/local-tools.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/package.schema.json",
        include_str!("../schemas/v1/package.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/policy.schema.json",
        include_str!("../schemas/v1/policy.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/resource.schema.json",
        include_str!("../schemas/v1/resource.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/runtime.schema.json",
        include_str!("../schemas/v1/runtime.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/sdk-builder.schema.json",
        include_str!("../schemas/v1/sdk-builder.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v1/tool.schema.json",
        include_str!("../schemas/v1/tool.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/catalog.schema.json",
        include_str!("../schemas/v2/catalog.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/common.schema.json",
        include_str!("../schemas/v2/common.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/deployment-binding.schema.json",
        include_str!("../schemas/v2/deployment-binding.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/descriptor.schema.json",
        include_str!("../schemas/v2/descriptor.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/discovery.schema.json",
        include_str!("../schemas/v2/discovery.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/identifiers.schema.json",
        include_str!("../schemas/v2/identifiers.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/insights.schema.json",
        include_str!("../schemas/v2/insights.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/invocation.schema.json",
        include_str!("../schemas/v2/invocation.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/kill.schema.json",
        include_str!("../schemas/v2/kill.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/mcp-proxy.schema.json",
        include_str!("../schemas/v2/mcp-proxy.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/package.schema.json",
        include_str!("../schemas/v2/package.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/permit.schema.json",
        include_str!("../schemas/v2/permit.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/profile.schema.json",
        include_str!("../schemas/v2/profile.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/protocol.schema.json",
        include_str!("../schemas/v2/protocol.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/service-profile.schema.json",
        include_str!("../schemas/v2/service-profile.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/settings.schema.json",
        include_str!("../schemas/v2/settings.schema.json"),
    ),
];
