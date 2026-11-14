//! Contract set v2 structural models. Validate canonical schemas at input boundaries.
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
use serde::{Deserialize, Serialize};

macro_rules! const_field {
    ($name:ident, $ty:ty, $value:expr) => {
        fn $name<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<$ty, D::Error> {
            let value = <$ty as Deserialize>::deserialize(deserializer)?;
            if value == $value {
                Ok(value)
            } else {
                Err(serde::de::Error::custom(concat!(
                    "expected constant ",
                    stringify!($value)
                )))
            }
        }
    };
}
/// Verified delegation chain entry; never caller-asserted.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ActorHop {
    pub agent_id: String,
    pub workload_binding_id: String,
}
/// Canonical AdmissionKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum AdmissionKind {
    #[serde(rename = "ADMITTED")]
    Admitted,
    #[serde(rename = "REFUSED")]
    Refused,
}
/// Tool Host to Gateway, sent before any sandbox starts.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AdmissionResponse {
    pub kind: AdmissionKind,
    pub invocation_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fencing_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<RefusalReason>,
}
/// Canonical ApiChange wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ApiChange {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "PATCH")]
    Patch,
    #[serde(rename = "MINOR")]
    Minor,
    #[serde(rename = "MAJOR")]
    Major,
}
/// Canonical AuthType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum AuthType {
    #[serde(rename = "API_KEY")]
    ApiKey,
    #[serde(rename = "BASIC")]
    Basic,
    #[serde(rename = "OAUTH2_CLIENT_CREDENTIALS")]
    Oauth2ClientCredentials,
    #[serde(rename = "TOKEN_EXCHANGE")]
    TokenExchange,
    #[serde(rename = "CONNECTION")]
    Connection,
    #[serde(rename = "CUSTOM")]
    Custom,
}
/// Exact JCS offline approval payload. Issued-to-expiry window is at most ten minutes; two or three distinct current operator keys must approve identical claims.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BreakGlassApprovalClaims {
    pub tenant_id: String,
    pub request_id: String,
    pub scope: KillScope,
    pub mode: KillMode,
    pub reason: String,
    pub issued_at_unix_ms: u64,
    pub not_after_unix_ms: u64,
}
/// Break-glass kill: 2-of-3 operator signatures, adds kills only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BreakGlassRequest {
    pub scope: KillScope,
    pub mode: KillMode,
    pub reason: String,
    pub operator_signatures: Vec<String>,
}
/// Canonical BrokerOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BrokerOperation {
    #[serde(rename = "ROUTE_CALL")]
    RouteCall,
    #[serde(rename = "SECRET_REDEEM")]
    SecretRedeem,
    #[serde(rename = "FILES_LIST")]
    FilesList,
    #[serde(rename = "FILES_READ")]
    FilesRead,
    #[serde(rename = "FILES_INFO")]
    FilesInfo,
    #[serde(rename = "FILES_WRITE")]
    FilesWrite,
    #[serde(rename = "FILES_APPEND")]
    FilesAppend,
    #[serde(rename = "FILES_MKDIR")]
    FilesMkdir,
    #[serde(rename = "FILES_RENAME")]
    FilesRename,
}
/// Tool to executor on stdout: one brokered operation. request is the operation payload: the recorded-exchange route call shape for ROUTE_CALL, {handle} for SECRET_REDEEM, {path, destination, text} for FILES_*. One request is outstanding at a time.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BrokerRequestFrame {
    #[serde(deserialize_with = "const_broker_request_frame_kind")]
    pub kind: String,
    pub request_id: String,
    pub operation: BrokerOperation,
    pub request: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Executor to tool on stdin: the result of the outstanding brokered operation, or a canonical error. Nothing else is written to the tool after its ToolInput.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BrokerResponseFrame {
    #[serde(deserialize_with = "const_broker_response_frame_kind")]
    pub kind: String,
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ToolErrorBody>,
}
/// reserveBusinessOperation input, carried on POST /access/invocations.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BusinessKeyReservationRequest {
    pub tool_id: String,
    pub namespace: KeyNamespace,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub namespace_identity: Option<String>,
    pub business_key_hash: String,
    pub source: BusinessKeySource,
    pub fingerprint: String,
    pub caller: CallerIdentity,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dedupe_window_ms: Option<u64>,
}
/// Reservation result. IDEMPOTENCY_KEY_IN_USE carries nothing else.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BusinessKeyReservationResult {
    pub outcome: ReservationOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub invocation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<TaskState>,
}
/// Business-key settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BusinessKeySettings {
    pub required: bool,
    pub namespace: KeyNamespace,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub argument: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idempotency_header: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idempotency_scope: Option<IdempotencyScope>,
}
/// Canonical BusinessKeySource wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BusinessKeySource {
    #[serde(rename = "HEADER")]
    Header,
    #[serde(rename = "ARGUMENT")]
    Argument,
    #[serde(rename = "DEDUPE_WINDOW")]
    DedupeWindow,
}
/// Kept after retention; results purged.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BusinessKeyTombstone {
    pub business_key_hash: String,
    pub fingerprint: String,
    pub outcome: TaskState,
    pub created_at_unix_ms: u64,
    pub purged_at_unix_ms: u64,
}
/// Authenticated caller, never from arguments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CallerIdentity {
    pub agent_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
}
/// Catalog scope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CatalogScope {
    pub kind: CatalogScopeKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_group_id: Option<String>,
    pub toolsets: Vec<String>,
    pub revision: String,
}
/// Canonical CatalogScopeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CatalogScopeKind {
    #[serde(rename = "ALL")]
    All,
    #[serde(rename = "AGENT")]
    Agent,
    #[serde(rename = "AGENT_GROUP")]
    AgentGroup,
    #[serde(rename = "AGENT_IN_GROUP")]
    AgentInGroup,
}
/// Declared identity claim.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClaimDeclaration {
    pub name: String,
    pub claim: String,
    pub r#type: ValueType,
    pub required: bool,
}
/// Effective Phase A check-in values. Env-owned, read-only in the console.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientCheckIn {
    pub check_in_ms: u64,
    pub jitter_pct: u64,
    pub offline_after_ms: u64,
    pub source: ValueSource,
}
/// A client release: the image index pinned by digest, the exact FleetPackageDocument bytes (manifestDigest) and, when Control holds the package-release key, the signed FleetPackageRelease it recorded.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientRelease {
    pub package_id: String,
    pub version: String,
    pub package_digest: String,
    pub image: String,
    pub runtime_version: String,
    pub manifest_digest: String,
    pub signed: bool,
    pub document: std::collections::BTreeMap<String, serde_json::Value>,
    pub document_base64: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub release: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    pub tools: Vec<ClientReleaseTool>,
}
/// Builds the client-tool image for one verified upload and issues its fleet release (ADR 026).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientReleaseRequest {
    pub package_digest: String,
}
/// One export and whether a client device can run it; an unsupported export is never weakened to fit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClientReleaseTool {
    pub tool_id: String,
    pub supported: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}
/// Canonical CompatibilityProfile wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CompatibilityProfile {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "LLM_STRICT")]
    LlmStrict,
}
/// One applied library mapping.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CompositionMapping {
    pub kind: MappingKind,
    pub from: String,
    pub to: String,
}
/// A library merged into the package (composition.md §2).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CompositionRecord {
    pub library_id: String,
    pub version: String,
    pub fragment_digest: String,
    pub namespace_prefix: String,
    pub mappings: Vec<CompositionMapping>,
    pub excluded: Vec<String>,
    pub narrowed: Vec<String>,
}
/// Canonical ConfigLayer wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConfigLayer {
    #[serde(rename = "PLATFORM_CEILING")]
    PlatformCeiling,
    #[serde(rename = "ENVIRONMENT")]
    Environment,
    #[serde(rename = "TENANT_POLICY")]
    TenantPolicy,
    #[serde(rename = "POOL_OR_DEVICE_GROUP")]
    PoolOrDeviceGroup,
    #[serde(rename = "DEPLOYMENT_BINDING")]
    DeploymentBinding,
    #[serde(rename = "PACKAGE_DEFAULT")]
    PackageDefault,
}
/// Configuration review settings. requiredReviews 0 applies changes immediately.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConfigurationSettings {
    pub required_reviews: u64,
}
/// SaaS connection the package needs (platform-requirements.md §14.3).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConnectionRequirement {
    pub id: String,
    pub provider: String,
    pub scopes: Vec<String>,
}
/// Canonical ConsoleClientOperation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConsoleClientOperation {
    #[serde(rename = "HEALTH")]
    Health,
    #[serde(rename = "ENROLL")]
    Enroll,
}
/// OS-authenticated native request scoped to a console origin and optional expected UUID. The service validates protected local tenant/origin binding before reading health or starting enrollment.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleClientRequest {
    #[serde(deserialize_with = "const_console_client_request_kind")]
    pub kind: String,
    pub request_id: String,
    pub operation: ConsoleClientOperation,
    pub console_url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expected_tenant_id: Option<String>,
}
/// Requester-scoped device state and optional verified tenant UUID. Errors carry no installation or device metadata. The service enforces the error-only response invariant before serialization.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConsoleClientResponse {
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tenant_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub health: Option<crate::ClientHealth>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub challenge: Option<crate::EndpointEnrollmentPrompt>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<crate::ErrorCode>,
}
/// Identifies the shared contract set.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ContractSet {
    #[serde(deserialize_with = "const_contract_set_name")]
    pub name: String,
    pub version: String,
}
/// Settings record. revision is compared with If-Match.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlServerSettings {
    #[serde(deserialize_with = "const_control_server_settings_format_version")]
    pub format_version: u64,
    pub revision: u64,
    pub gateway: GatewaySettings,
    pub device_approval: DeviceApproval,
    pub client_check_in: ClientCheckIn,
    pub configuration: ConfigurationSettings,
}
/// application/merge-patch+json body, and the settings of a configuration snapshot. Env-owned clientCheckIn is not patchable.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlServerSettingsPatch {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gateway: Option<GatewaySettings>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_approval: Option<DeviceApproval>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub configuration: Option<ConfigurationSettings>,
}
/// Settings visible to the current actor. Unreadable feature groups are omitted.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlSettingsView {
    #[serde(deserialize_with = "const_control_settings_view_format_version")]
    pub format_version: u64,
    pub revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gateway: Option<GatewaySettings>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_approval: Option<DeviceApproval>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_check_in: Option<ClientCheckIn>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub configuration: Option<ConfigurationSettings>,
}
/// Canonical CredentialKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum CredentialKind {
    #[serde(rename = "STATIC_TOKEN")]
    StaticToken,
    #[serde(rename = "JWT")]
    Jwt,
    #[serde(rename = "INTROSPECTION")]
    Introspection,
}
/// Deployment binding.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeploymentBinding {
    #[serde(deserialize_with = "const_deployment_binding_binding_format_version")]
    pub binding_format_version: u64,
    pub binding_id: String,
    pub revision: u64,
    pub tenant_id: String,
    pub package_id: String,
    pub package_digest: String,
    pub config_revision: u64,
    pub service_profile_digest: String,
    pub service_profile_revision: u64,
    pub target: DeploymentTarget,
    pub grants: Vec<DeploymentGrant>,
    pub limit_overrides: Vec<LimitOverride>,
    pub rollout_plan: RolloutPlan,
    pub effects: Vec<EffectApproval>,
}
/// Agent Group grant and catalog scope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeploymentGrant {
    pub agent_group_id: String,
    pub tool_ids: Vec<String>,
}
/// Deployment-time package provisioning for one profile (docs/contracts/platform-requirements.md). Applied by Control at deployment; removing an entry never uninstalls a package.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeploymentProfile {
    #[serde(deserialize_with = "const_deployment_profile_profile_version")]
    pub profile_version: u64,
    pub profile_id: String,
    pub packages: Vec<ProfilePackage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub readiness: Option<Vec<ProfileReadinessCase>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workflows: Option<WorkflowConfiguration>,
}
/// Where the package runs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeploymentTarget {
    pub kind: TargetKind,
    pub ids: Vec<String>,
    pub allowed_tiers: Vec<RuntimeTier>,
}
/// Declared destination; the host is a reviewed deployment setting.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DestinationDeclaration {
    pub id: String,
    pub host_setting: String,
    pub port: u64,
    pub spki_pins: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ca_bundle: Option<String>,
}
/// Canonical DestinationScheme wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DestinationScheme {
    #[serde(rename = "https")]
    Https,
    #[serde(rename = "tcp")]
    Tcp,
}
/// Device approval settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeviceApproval {
    pub auto_approve: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner_user_id: Option<String>,
}
/// Canonical DiscoveryAuthorityState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DiscoveryAuthorityState {
    #[serde(rename = "ACTIVE")]
    Active,
    #[serde(rename = "READ_ONLY")]
    ReadOnly,
}
/// Tenant device CA. keyId is SHA-256 of certificate DER; its subject binds the same tenant UUID. It cannot reuse a discovery signing key.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DiscoveryDeviceAuthority {
    pub key_id: String,
    pub state: DiscoveryAuthorityState,
    pub certificate_pem: String,
}
/// Protected JWS header. Algorithm, message purpose and key selection are authenticated; no unprotected or remote-key headers.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DiscoveryHeader {
    #[serde(deserialize_with = "const_discovery_header_alg")]
    pub alg: String,
    #[serde(deserialize_with = "const_discovery_header_typ")]
    pub typ: String,
    pub kid: String,
}
/// Bounded tenant discovery JWK Set. Application validation requires unique key IDs and moduli.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DiscoveryKeySet {
    pub keys: Vec<DiscoveryPublicKey>,
}
/// Public-only RSA JWK, restricted to 3072/4096-bit RS256 keys with exponent 65537. No private parameters or key URLs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DiscoveryPublicKey {
    #[serde(deserialize_with = "const_discovery_public_key_kty")]
    pub kty: String,
    #[serde(deserialize_with = "const_discovery_public_key_use")]
    pub r#use: String,
    #[serde(deserialize_with = "const_discovery_public_key_alg")]
    pub alg: String,
    pub kid: String,
    pub n: String,
    #[serde(deserialize_with = "const_discovery_public_key_e")]
    pub e: String,
}
/// Gateway to Tool Host.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Dispatch {
    pub permit: SignedInvocationPermit,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub traceparent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_context: Option<ExecutionContext>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub collect_spans: Option<bool>,
}
/// Supervisor-only broker-injected key. Persisted once per effect; tools cannot select its value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DownstreamEffectKey {
    pub header: String,
    pub value: String,
}
/// Reviewed effect class per tool.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectApproval {
    pub tool_id: String,
    pub effect_safety: EffectSafety,
    pub business_key_namespace: KeyNamespace,
    pub missing_key_policy: MissingKeyPolicy,
    pub approval_id: String,
}
/// Canonical EffectBodyMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EffectBodyMode {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "CANONICAL_JSON")]
    CanonicalJson,
}
/// Canonical EffectClassification wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EffectClassification {
    #[serde(rename = "READ")]
    Read,
    #[serde(rename = "WRITE")]
    Write,
    #[serde(rename = "DESTRUCTIVE")]
    Destructive,
    #[serde(rename = "FINANCIAL")]
    Financial,
}
/// Authenticated explicit approval of one immutable preview and revision. No worker-provided replacement intent or validity window is accepted.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentApprovalRequest {
    pub preview_digest: String,
    pub expected_revision: u64,
}
/// Signed admission reference to exact approved consent; never substitutes for current Control authority at the effect boundary.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentBinding {
    pub consent_id: String,
    pub preview_digest: String,
}
/// Control-reconstructed outbound business request. Contains resolved mapped values, never credentials. Native MCP excludes handler ID and opaque session/protocol headers; these remain bound by the execution grant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentMappedRequest {
    pub kind: EffectConsentMappingKind,
    pub route_id: String,
    pub classification: EffectClassification,
    #[serde(deserialize_with = "const_effect_consent_mapped_request_scheme")]
    pub scheme: String,
    pub host: String,
    pub port: u64,
    pub method: String,
    pub path: String,
    pub query: String,
    pub headers: std::collections::BTreeMap<String, serde_json::Value>,
    pub content_type: String,
    pub body_mode: EffectBodyMode,
    pub body: serde_json::Value,
}
/// Canonical EffectConsentMappingKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EffectConsentMappingKind {
    #[serde(rename = "MAPPED_HTTP")]
    MappedHttp,
    #[serde(rename = "NATIVE_MCP")]
    NativeMcp,
}
/// Immutable exact-effect preview created by Control from authenticated original arguments and pinned reviewed mappings. Its canonical digest must be explicitly approved; ordinary invocation approval is not exact consent. Validity never exceeds original operation and credential deadlines.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentPreview {
    pub consent_id: String,
    pub tenant_id: String,
    pub operation_id: String,
    pub run_id: String,
    pub tool_id: String,
    pub action: String,
    pub arguments_digest: String,
    pub resources: Vec<crate::ResourceDescriptor>,
    pub profile_digest: String,
    pub not_before_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub slots: Vec<EffectConsentSlot>,
}
/// Explicit bounded route allowance. Each distinct effect identity reserves one use atomically with its authorization; another generation of the same identity reuses that use.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentSlot {
    pub slot_index: u64,
    pub request: EffectConsentMappedRequest,
    pub permitted_count: u64,
}
/// Canonical EffectConsentState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EffectConsentState {
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "REVOKED")]
    Revoked,
}
/// Scoped exact-effect review presentation, with a revision that changes on approval or revocation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectConsentView {
    pub preview: EffectConsentPreview,
    pub preview_digest: String,
    pub state: EffectConsentState,
    pub revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reviewed_by: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approved_at_unix_ms: Option<u64>,
}
/// Issued authority and conservative monotonic send budget, measured from before the issuance request.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectGrant {
    pub grant: SignedEffectGrant,
    pub valid_for_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downstream_key: Option<DownstreamEffectKey>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fenced_target: Option<FencedTarget>,
}
/// Single-owner exact-request authorization. Target consumption is the ordering point for explicitly fenced HTTP targets; unfenced dispatch remains bounded to two seconds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectGrantClaims {
    pub iss: String,
    pub aud: String,
    pub iat: u64,
    pub nbf: u64,
    pub exp: u64,
    pub tenant_id: String,
    pub invocation_id: String,
    pub fencing_token: String,
    pub authorization_epoch: u64,
    pub effect_id: String,
    pub generation: u64,
    pub auth_nonce: String,
    pub broker_instance_id: String,
    pub handler_id: String,
    pub http_request_digest: String,
    pub profile_digest: String,
    pub route_id: String,
    pub classification: EffectClassification,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub downstream_key: Option<DownstreamEffectKey>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fenced_target: Option<FencedTarget>,
    pub executor_key_sha256: String,
}
/// One owner may claim the SENDING transition once.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectGrantDispatch {
    pub grant: SignedEffectGrant,
    pub broker_instance_id: String,
    pub handler_id: String,
}
/// Only explicit true permits the single transport send.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectGrantDispatchAllowed {
    pub authorized: bool,
}
/// Request for fresh owner-bound authority. Control independently verifies the signed argument preimage and reconstructs the reviewed request.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectGrantIssue {
    pub invocation_id: String,
    pub fencing_token: String,
    pub invocation_permit: SignedInvocationPermit,
    pub broker_instance_id: String,
    pub handler_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    pub http_request_digest: String,
    pub http_request_manifest: crate::PreparedHttpRequestManifest,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect_key: Option<String>,
}
/// Reviewed request blueprint. Values are constants or closed $arg JSON-pointer references; resolved argument data is never interpreted as a template.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectIntentBlueprint {
    pub path: serde_json::Value,
    pub query: std::collections::BTreeMap<String, serde_json::Value>,
    pub headers: std::collections::BTreeMap<String, serde_json::Value>,
    pub content_type: String,
    pub body_mode: EffectBodyMode,
    pub body: serde_json::Value,
}
/// Canonical EffectSafety wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EffectSafety {
    #[serde(rename = "READ_ONLY")]
    ReadOnly,
    #[serde(rename = "IDEMPOTENT")]
    Idempotent,
    #[serde(rename = "NON_IDEMPOTENT")]
    NonIdempotent,
    #[serde(rename = "UNKNOWN")]
    Unknown,
}
/// GET /api/control/v1/deployments/{bindingId}/effective.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectiveDeployment {
    pub binding_id: String,
    pub revision: u64,
    pub values: Vec<EffectiveValue>,
}
/// One effective field and the layer it came from.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectiveValue {
    pub field: String,
    pub value: serde_json::Value,
    pub layer: ConfigLayer,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capped_by: Option<ConfigLayer>,
}
/// Canonical EgressMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum EgressMode {
    #[serde(rename = "BROKERED")]
    Brokered,
    #[serde(rename = "TUNNEL")]
    Tunnel,
}
/// Declared business error.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ErrorDeclaration {
    pub code: String,
    pub description: String,
    pub retryable: bool,
}
/// Correlation context of one execution attempt (ADR 031). executionId and attemptNumber are set by the executor from its permit; the other members are declared by the caller, are never trusted and never affect authorization, routing or policy.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExecutionContext {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt_number: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_run_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_invocation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tracestate: Option<String>,
}
/// Execution settings with defaults resolved.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExecutionSettings {
    pub r#async: bool,
    pub max_duration_ms: u64,
    pub stateless: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_concurrency: Option<u64>,
    pub cancel_if_abandoned: bool,
}
/// Independently reviewed target identity and qualified target implementation. This declaration grants no authority by itself.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FencedTarget {
    pub target_id: String,
    pub target_class: FencedTargetClass,
}
/// Canonical FencedTargetClass wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum FencedTargetClass {
    #[serde(rename = "SHARED_SQL")]
    SharedSql,
    #[serde(rename = "SQL_WITH_EXTERNAL_ACTION")]
    SqlWithExternalAction,
}
/// Canonical FrameKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum FrameKind {
    #[serde(rename = "RESULT")]
    Result,
    #[serde(rename = "PROGRESS")]
    Progress,
    #[serde(rename = "INPUT_REQUEST")]
    InputRequest,
    #[serde(rename = "LOG")]
    Log,
    #[serde(rename = "BROKER_REQUEST")]
    BrokerRequest,
    #[serde(rename = "BROKER_RESPONSE")]
    BrokerResponse,
}
/// Gateway settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GatewaySettings {
    pub gateway_name: String,
}
/// Declared global.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GlobalDeclaration {
    pub name: String,
    pub r#type: ValueType,
    pub required: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub default: Option<serde_json::Value>,
    pub sensitive: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minimum: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub maximum: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub min_length: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_length: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pattern: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub r#enum: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub format: Option<String>,
}
/// Declared HTTP auth.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HttpAuthDeclaration {
    pub id: String,
    pub r#type: AuthType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub header: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secret: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection: Option<String>,
    pub scopes: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub audience: Option<String>,
}
/// Canonical HttpMethod wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum HttpMethod {
    #[serde(rename = "GET")]
    Get,
    #[serde(rename = "HEAD")]
    Head,
    #[serde(rename = "POST")]
    Post,
    #[serde(rename = "PUT")]
    Put,
    #[serde(rename = "PATCH")]
    Patch,
    #[serde(rename = "DELETE")]
    Delete,
}
/// Canonical IdempotencyScope wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum IdempotencyScope {
    #[serde(rename = "BUSINESS_KEY")]
    BusinessKey,
    #[serde(rename = "PER_INVOCATION")]
    PerInvocation,
}
/// Tool asks for input (MRTR or tasks/update).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InputRequestFrame {
    #[serde(deserialize_with = "const_input_request_frame_kind")]
    pub kind: String,
    pub request_id: String,
    pub request: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Control's acknowledgement of one admission batch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsAdmissionAck {
    pub sequence: u64,
    pub duplicate: bool,
}
/// Gateway to Control: admission counts since the previous batch of this process epoch. Best-effort; Control ignores a repeated (gatewayId, epoch, sequence) and reports missing sequences as source gaps.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsAdmissionBatch {
    pub gateway_id: String,
    pub epoch: String,
    pub sequence: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_batch: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dropped_requests: Option<u64>,
    pub counts: Vec<InsightsAdmissionCount>,
}
/// Canonical InsightsAdmissionCategory wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsAdmissionCategory {
    #[serde(rename = "UNAUTHENTICATED")]
    Unauthenticated,
    #[serde(rename = "POLICY_DENIED")]
    PolicyDenied,
    #[serde(rename = "APPROVAL_REQUIRED")]
    ApprovalRequired,
    #[serde(rename = "RATE_LIMITED")]
    RateLimited,
    #[serde(rename = "KILL_SWITCH")]
    KillSwitch,
    #[serde(rename = "INVALID_REQUEST")]
    InvalidRequest,
}
/// Requests of one category for one tool in one closed minute bucket.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsAdmissionCount {
    pub bucket_start_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_id: Option<String>,
    pub category: InsightsAdmissionCategory,
    pub count: u64,
}
/// Admission decisions in one bucket.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsAdmissionPoint {
    pub bucket_start_unix_ms: u64,
    pub category: InsightsAdmissionCategory,
    pub count: u64,
}
/// One execution attempt: one consumed permit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsAttempt {
    pub attempt_number: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub executor_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub started_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ended_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<InsightsOutcome>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
}
/// Canonical InsightsErrorCategory wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsErrorCategory {
    #[serde(rename = "POLICY")]
    Policy,
    #[serde(rename = "TIMEOUT")]
    Timeout,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "TOOL_ERROR")]
    ToolError,
    #[serde(rename = "INFRASTRUCTURE")]
    Infrastructure,
    #[serde(rename = "INVALID_REQUEST")]
    InvalidRequest,
    #[serde(rename = "PARTIAL")]
    Partial,
    #[serde(rename = "UNKNOWN")]
    Unknown,
}
/// Canonical InsightsGranularity wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsGranularity {
    #[serde(rename = "MINUTE")]
    Minute,
    #[serde(rename = "HOUR")]
    Hour,
    #[serde(rename = "DAY")]
    Day,
}
/// One invocation's summary, projected from its transition facts. Durations are absent when not measured.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsInvocation {
    pub tenant_id: String,
    pub invocation_id: String,
    pub tool_id: String,
    pub action: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_run_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_invocation_id: Option<String>,
    pub outcome: InsightsOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_category: Option<InsightsErrorCategory>,
    pub attempts: u64,
    pub accepted_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_requested_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_resolved_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub queued_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub first_started_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub terminal_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub queue_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub end_to_end_ms: Option<u64>,
    pub partial_coverage: bool,
}
/// The detail window: summary, attempts and, while facts are retained, the transitions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsInvocationDetail {
    pub invocation: InsightsInvocation,
    pub attempts: Vec<InsightsAttempt>,
    pub transitions: Vec<InsightsTransition>,
    pub transitions_retained: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub spans: Option<Vec<InsightsSpan>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub span_state: Option<InsightsSpanState>,
}
/// Explorer results, newest first. next continues the search.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsInvocationPage {
    pub items: Vec<InsightsInvocation>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next: Option<String>,
}
/// Canonical InsightsMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsMode {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "BASIC")]
    Basic,
    #[serde(rename = "DETAILED")]
    Detailed,
}
/// Canonical InsightsOutcome wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsOutcome {
    #[serde(rename = "PENDING_APPROVAL")]
    PendingApproval,
    #[serde(rename = "IN_PROGRESS")]
    InProgress,
    #[serde(rename = "SUCCEEDED")]
    Succeeded,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "PARTIAL")]
    Partial,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "OUTCOME_UNKNOWN")]
    OutcomeUnknown,
}
/// Recompute one tenant's aggregate cells for a range from its summaries. dryRun only compares.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsRebuildRequest {
    pub tenant_id: String,
    pub from_unix_ms: u64,
    pub to_unix_ms: u64,
    pub dry_run: bool,
}
/// How many cells the recomputation compared and how many differed from the live projection.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsRebuildResult {
    pub tenant_id: String,
    pub from_unix_ms: u64,
    pub to_unix_ms: u64,
    pub cells_compared: u64,
    pub cells_differing: u64,
    pub applied: bool,
}
/// Aggregates for a range. Data before unavailableBeforeUnixMs has expired at this granularity and is absent, not zero.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSeries {
    pub granularity: InsightsGranularity,
    pub from_unix_ms: u64,
    pub to_unix_ms: u64,
    pub group_by: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unavailable_before_unix_ms: Option<u64>,
    pub histogram_bounds_ms: Vec<u64>,
    pub points: Vec<InsightsSeriesPoint>,
    pub admissions: Vec<InsightsAdmissionPoint>,
}
/// One bucket and outcome. histogram holds counts per InsightsSeries.histogramBoundsMs bucket (last is overflow).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSeriesPoint {
    pub bucket_start_unix_ms: u64,
    pub key: String,
    pub outcome: InsightsOutcome,
    pub count: u64,
    pub duration_count: u64,
    pub duration_sum_ms: u64,
    pub histogram: Vec<u64>,
}
/// One caller-declared session in a range: its calls by acceptance time. Session IDs are declared by the caller and not verified.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSession {
    pub tenant_id: String,
    pub session_id: String,
    pub first_accepted_at_unix_ms: u64,
    pub last_activity_unix_ms: u64,
    pub calls: u64,
    pub succeeded: u64,
    pub failed: u64,
    pub unfinished: u64,
    pub agent_runs: u64,
    pub tools: u64,
}
/// A tenant's sessions with calls accepted in the range, most recent activity first. next continues the list.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSessionPage {
    pub items: Vec<InsightsSession>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next: Option<String>,
    pub calls_without_session: u64,
}
/// Every retained call of one session, oldest first, plus calls that declared one of them as parent (nested work), to a bounded depth.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSessionTimeline {
    pub tenant_id: String,
    pub session_id: String,
    pub invocations: Vec<InsightsInvocation>,
    pub truncated: bool,
}
/// Admission counts that may be missing: a skipped sequence, or an epoch that stopped without a final batch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSourceGap {
    pub tenant_id: String,
    pub gateway_id: String,
    pub epoch: String,
    pub last_sequence: u64,
    pub missing_sequences: u64,
    pub last_seen_unix_ms: u64,
    pub dropped_requests: u64,
}
/// One allowlisted span. Metadata only: never arguments, outputs, URLs, headers or secrets.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSpan {
    pub trace_id: String,
    pub span_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_span_id: Option<String>,
    pub name: InsightsSpanName,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<InsightsSpanSource>,
    pub invocation_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_id: Option<String>,
    pub start_unix_ms: u64,
    pub duration_ms: u64,
    pub status: InsightsSpanStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub route_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub http_status: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_code: Option<String>,
}
/// How many spans Control buffered and how many it refused (unknown invocation, wrong executor, over a bound).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSpanAck {
    pub accepted: u64,
    pub refused: u64,
}
/// Spans from one producer. Best-effort: droppedSpans counts what the producer could not keep.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsSpanBatch {
    pub spans: Vec<InsightsSpan>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dropped_spans: Option<u64>,
}
/// Canonical InsightsSpanName wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsSpanName {
    #[serde(rename = "GATEWAY_REQUEST")]
    GatewayRequest,
    #[serde(rename = "GATEWAY_AUTHORIZE")]
    GatewayAuthorize,
    #[serde(rename = "GATEWAY_DISPATCH")]
    GatewayDispatch,
    #[serde(rename = "HOST_ADMISSION")]
    HostAdmission,
    #[serde(rename = "HOST_EXECUTE")]
    HostExecute,
    #[serde(rename = "HOST_EGRESS")]
    HostEgress,
    #[serde(rename = "CLIENT_EXECUTE")]
    ClientExecute,
}
/// Canonical InsightsSpanSource wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsSpanSource {
    #[serde(rename = "GATEWAY")]
    Gateway,
    #[serde(rename = "TOOL_HOST")]
    ToolHost,
    #[serde(rename = "CLIENT")]
    Client,
}
/// Canonical InsightsSpanState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsSpanState {
    #[serde(rename = "NOT_COLLECTED")]
    NotCollected,
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "RETAINED")]
    Retained,
    #[serde(rename = "SAMPLED_OUT")]
    SampledOut,
    #[serde(rename = "DROPPED")]
    Dropped,
}
/// Canonical InsightsSpanStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InsightsSpanStatus {
    #[serde(rename = "OK")]
    Ok,
    #[serde(rename = "ERROR")]
    Error,
}
/// Read first by the operator page: what is collected and how current it is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsStatus {
    pub mode: InsightsMode,
    pub generated_at_unix_ms: u64,
    pub tenants: u64,
    pub unprojected_facts: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub oldest_unprojected_unix_ms: Option<u64>,
    pub summaries_suspended_tenants: u64,
    pub failed_tenants: u64,
    pub source_gaps: Vec<InsightsSourceGap>,
}
/// One retained transition fact, for the stage timeline.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InsightsTransition {
    pub fact_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from_state: Option<String>,
    pub to_state: String,
    pub occurred_at_unix_ms: u64,
}
/// Response to a v2 POST /access/invocations for NEW and EXISTING reservations. IDEMPOTENCY_CONFLICT, IDEMPOTENCY_KEY_IN_USE and EXPIRED_KEY are 409 errors with that code and carry nothing else. requestState is present with an ASK outcome for an MRTR client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InvocationAdmission {
    pub invocation_id: String,
    pub state: TaskState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reservation: Option<ReservationOutcome>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task: Option<InvocationTask>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_state: Option<String>,
}
/// Canonical InvocationOutcome wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum InvocationOutcome {
    #[serde(rename = "COMPLETED")]
    Completed,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "OUTCOME_UNKNOWN")]
    OutcomeUnknown,
}
/// Permit claims. exp is the short admission window, not the execution deadline.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InvocationPermitClaims {
    pub jti: String,
    pub iss: String,
    pub aud: String,
    pub iat: u64,
    pub nbf: u64,
    pub exp: u64,
    pub nonce: String,
    pub invocation_id: String,
    pub attempt: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub identity: PermitIdentity,
    pub target: PermitTarget,
    pub catalog_revision: String,
    pub arguments_digest: String,
    pub action: String,
    pub resources: Vec<std::collections::BTreeMap<String, serde_json::Value>>,
    pub effect_safety: EffectSafety,
    pub requires_execution_ledger: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_key_hash: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_key_namespace: Option<KeyNamespace>,
    pub approval_ids: Vec<String>,
    pub deadline_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mcp_verification: Option<McpPermitVerification>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect_consent: Option<EffectConsentBinding>,
}
/// Tool Host result to Control (effects/report, v2 body), keyed by invocation and fencing token. notStarted, with outcome FAILED, reports a claim the host abandoned before any sandbox started (invocation-state-v2.md T10).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InvocationResultReport {
    pub invocation_id: String,
    pub fencing_token: String,
    pub outcome: InvocationOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<ResultFrame>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub not_started: Option<RefusalReason>,
    pub execution_tier: RuntimeTier,
}
/// v2 body of POST /access/invocations (invocation-state-v2.md T1). The Gateway generates invocationId; Control evaluates policy, reserves the business key and decides whether a task exists.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InvocationSubmission {
    pub invocation_id: String,
    pub context: RequestContext,
    pub catalog_scope: CatalogScope,
    pub tool_id: String,
    pub tool_descriptor_digest: String,
    pub package_digest: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    pub arguments_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_key: Option<BusinessKeyReservationRequest>,
    pub tasks: TaskCapability,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_state: Option<String>,
}
/// Task fields added to a Control invocation when a task record exists (platform-requirements.md §12.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InvocationTask {
    pub task_id: String,
    pub state: TaskState,
    pub status: McpTaskStatus,
    pub ttl_ms: u64,
    pub poll_interval_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status_message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<TaskProgress>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_deadline_unix_ms: Option<u64>,
    pub cancel_if_abandoned: bool,
    pub transitions: Vec<TaskTransition>,
    pub package_digest: String,
}
/// Canonical KeyNamespace wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KeyNamespace {
    #[serde(rename = "AGENT")]
    Agent,
    #[serde(rename = "DELEGATED_USER")]
    DelegatedUser,
    #[serde(rename = "TENANT")]
    Tenant,
}
/// Acknowledgement from a socket-authenticated component; identity is never caller supplied.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillAcknowledgement {
    pub authorization_epoch: u64,
    pub delivery_path: KillDeliveryPath,
}
/// Current registered consumer and its latest authenticated acknowledgement, if any.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillComponentDelivery {
    pub kind: KillComponentKind,
    pub component_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authorization_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub acknowledged_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delivery_path: Option<KillDeliveryPath>,
}
/// Canonical KillComponentKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillComponentKind {
    #[serde(rename = "TOOL_HOST")]
    ToolHost,
    #[serde(rename = "ENDPOINT")]
    Endpoint,
    #[serde(rename = "GATEWAY")]
    Gateway,
}
/// Canonical KillDeliveryPath wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillDeliveryPath {
    #[serde(rename = "PUSH")]
    Push,
    #[serde(rename = "CHECK_IN")]
    CheckIn,
    #[serde(rename = "POLL")]
    Poll,
}
/// Immutable kill event.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillEvent {
    pub event_id: String,
    pub issuer: KillIssuer,
    pub scope: KillScope,
    pub mode: KillMode,
    pub reason: String,
    pub issued_at_unix_ms: u64,
    pub signature: String,
    pub tenant_id: String,
    pub authorization_epoch: u64,
}
/// Effective and superseded events.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillEventPage {
    pub kills: Vec<KillEvent>,
    pub recoveries: Vec<RecoveryEvent>,
    pub authorization_epoch: u64,
}
/// Immutable per-attempt Control facts returned only after online permit consumption. Absent facts are unknown and match scoped kills conservatively; empty pool/image means known not applicable.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillExecutionScope {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_id: Option<String>,
    pub package_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub publisher: Option<String>,
    pub tool_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pool_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image_digest: Option<String>,
}
/// Canonical KillIssuer wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillIssuer {
    #[serde(rename = "control")]
    Control,
    #[serde(rename = "control-breakglass")]
    ControlBreakglass,
}
/// Canonical KillMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillMode {
    #[serde(rename = "STOP_NEW")]
    StopNew,
    #[serde(rename = "TERMINATE")]
    Terminate,
}
/// Authoritative current kill state, registered delivery status and retained archive counts.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillOverview {
    pub state: KillPush,
    pub components: Vec<KillComponentDelivery>,
    pub retained_event_count: u64,
    pub archived_event_count: u64,
    pub observed_at_unix_ms: u64,
}
/// Immutable request and append-only reviews; no execution authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillProposal {
    pub id: String,
    pub requester_user_id: String,
    pub kind: KillProposalKind,
    pub state: KillProposalState,
    pub revision: u64,
    pub authorization_epoch: u64,
    pub created_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scope: Option<KillScope>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<KillMode>,
    pub reason: String,
    pub supersedes: Vec<String>,
    pub reviews: Vec<KillReview>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub event_id: Option<String>,
}
/// Review the exact retained proposal; reviewer identity is authenticated.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillProposalDecision {
    pub expected_revision: u64,
    pub approve: bool,
}
/// Canonical KillProposalKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillProposalKind {
    #[serde(rename = "KILL")]
    Kill,
    #[serde(rename = "RECOVERY")]
    Recovery,
}
/// Canonical KillProposalState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillProposalState {
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "APPLIED")]
    Applied,
    #[serde(rename = "REJECTED")]
    Rejected,
    #[serde(rename = "EXPIRED")]
    Expired,
}
/// Push to Tool Hosts and clients for running work.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillPush {
    pub tenant_id: String,
    pub authorization_epoch: u64,
    pub kills: Vec<KillEvent>,
    pub recoveries: Vec<RecoveryEvent>,
    pub signature: String,
}
/// Admin kill request; reviewed when the enterprise review workflow is on.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillRequest {
    pub scope: KillScope,
    pub mode: KillMode,
    pub reason: String,
}
/// One authenticated independent review of retained kill or recovery intent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillReview {
    pub reviewer_user_id: String,
    pub approved: bool,
    pub reviewed_at_unix_ms: u64,
}
/// Kill target. value is absent only for TENANT.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillScope {
    pub kind: KillScopeKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub value: Option<String>,
}
/// Canonical KillScopeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum KillScopeKind {
    #[serde(rename = "PACKAGE")]
    Package,
    #[serde(rename = "PACKAGE_DIGEST")]
    PackageDigest,
    #[serde(rename = "PUBLISHER")]
    Publisher,
    #[serde(rename = "TOOL")]
    Tool,
    #[serde(rename = "POOL")]
    Pool,
    #[serde(rename = "RUNTIME_IMAGE")]
    RuntimeImage,
    #[serde(rename = "TENANT")]
    Tenant,
}
/// Direct HTTPS key bootstrap; never accept it from a worker or relayed push.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillTrustDocument {
    pub tenant_id: String,
    pub authorization_epoch: u64,
    pub keys: Vec<KillTrustKey>,
}
/// Organization fleet public key delivered only by the fixed certificate-authenticated Control origin.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillTrustKey {
    pub kid: String,
    pub n: String,
    #[serde(deserialize_with = "const_kill_trust_key_e")]
    pub e: String,
}
/// Lease renewal for a running ledger invocation (every lease/3). Also carries the latest progress and, while the tool waits for input, its input request (invocation-state-v2.md T6, T7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LeaseHeartbeat {
    pub invocation_id: String,
    pub fencing_token: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<TaskProgress>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_request: Option<InputRequestFrame>,
}
/// Heartbeat response: the renewed lease, whether cancellation was requested, and any task input waiting for the tool (invocation-state-v2.md T6–T8).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LeaseRenewal {
    pub invocation_id: String,
    pub fencing_token: String,
    pub lease_expires_at_unix_ms: u64,
    pub cancel_requested: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_responses: Option<serde_json::Value>,
}
/// Per-tool limit override; at most the platform ceiling.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LimitOverride {
    pub tool_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_duration_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_concurrency: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory_bytes: Option<u64>,
}
/// Redacted, size-capped log line.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LogFrame {
    #[serde(deserialize_with = "const_log_frame_kind")]
    pub kind: String,
    pub level: LogLevel,
    pub message: String,
}
/// Canonical LogLevel wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LogLevel {
    #[serde(rename = "debug")]
    Debug,
    #[serde(rename = "info")]
    Info,
    #[serde(rename = "warn")]
    Warn,
    #[serde(rename = "error")]
    Error,
}
/// Canonical MappingKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MappingKind {
    #[serde(rename = "SECRET")]
    Secret,
    #[serde(rename = "GLOBAL")]
    Global,
    #[serde(rename = "CLAIM")]
    Claim,
    #[serde(rename = "DESTINATION")]
    Destination,
}
/// Imports one Marketplace version as an uploaded server package. Control fetches, verifies and stores it; nothing is deployed, enabled or granted.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MarketplaceImportRequest {
    pub package_id: String,
    pub version: String,
}
/// A Marketplace release signing key Control trusts, by id and SHA-256 fingerprint of its raw Ed25519 public key.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MarketplaceKey {
    pub key_id: String,
    pub fingerprint: String,
}
/// Whether this ToolGate reads an OLO Orgs Marketplace. The id, origin and keys are environment-owned settings, absent when disabled.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MarketplaceStatus {
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub marketplace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub keys: Option<Vec<MarketplaceKey>>,
}
/// An accepted approval: one per approval sequence, with its generated package.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpApproval {
    pub sequence: u64,
    pub snapshot_revision: u64,
    pub tools: Vec<McpApprovedTool>,
    pub spki_pins: Vec<String>,
    pub package_id: String,
    pub package_version: String,
    pub package_digest: String,
    pub approved_at_unix_ms: u64,
}
/// Approvals of one registration, newest first.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpApprovalPage {
    pub items: Vec<McpApproval>,
}
/// Approves tools of one snapshot; at most 64, the package and deployment limit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpApprovalRequest {
    pub snapshot_revision: u64,
    pub tools: Vec<McpToolApproval>,
}
/// A tool of an approval.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpApprovedTool {
    pub tool_id: String,
    pub upstream_name: String,
    pub definition_digest: String,
    pub effect_policy: McpEffectPolicy,
}
/// Canonical McpAuthScheme wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpAuthScheme {
    #[serde(rename = "BEARER")]
    Bearer,
    #[serde(rename = "HEADER")]
    Header,
}
/// Canonical McpCheckFailure wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpCheckFailure {
    #[serde(rename = "PIN_MISMATCH")]
    PinMismatch,
    #[serde(rename = "TLS_ERROR")]
    TlsError,
    #[serde(rename = "UPSTREAM_UNREACHABLE")]
    UpstreamUnreachable,
    #[serde(rename = "UPSTREAM_ERROR")]
    UpstreamError,
    #[serde(rename = "PROTOCOL_ERROR")]
    ProtocolError,
    #[serde(rename = "SCAN_LIMIT_EXCEEDED")]
    ScanLimitExceeded,
    #[serde(rename = "DEADLINE_EXCEEDED")]
    DeadlineExceeded,
    #[serde(rename = "DEFINITION_CHANGED")]
    DefinitionChanged,
    #[serde(rename = "TOOL_MISSING")]
    ToolMissing,
    #[serde(rename = "SERVER_CHANGED")]
    ServerChanged,
}
/// Explicit operator review for one native MCP business call. Upstream annotations do not confer effect authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpEffectPolicy {
    pub classification: EffectClassification,
    pub consent_required: bool,
    pub acknowledged_unfenced_risk: bool,
}
/// Freshness evidence an invocation permit carries for an MCP_PROXY tool.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpPermitVerification {
    pub status: McpVerificationStatus,
    pub approval_sequence: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checked_at_unix_ms: Option<u64>,
}
/// mcp-proxy.json, the one artifact of an MCP_PROXY package (media type application/vnd.ololabs.toolgate.mcp-proxy+json).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpProxyDocument {
    #[serde(deserialize_with = "const_mcp_proxy_document_format_version")]
    pub format_version: u64,
    pub server: McpProxyServer,
    pub approval_sequence: u64,
    pub strict: bool,
    pub tools: Vec<McpApprovedTool>,
}
/// The upstream an MCP_PROXY package proxies: its URL and credential reference, bound by the package digest, and the identity and TLS pins it was approved with.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpProxyServer {
    pub slug: String,
    pub url: String,
    pub auth: McpUpstreamAuth,
    pub server_info: McpServerInfo,
    pub spki_pins: Vec<String>,
}
/// A recorded quarantine and the evidence that caused it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpQuarantine {
    pub reason: McpQuarantineReason,
    pub evidence_id: String,
    pub tool_ids: Vec<String>,
    pub server_wide: bool,
    pub at_unix_ms: u64,
    pub approval_sequence: u64,
}
/// Canonical McpQuarantineReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpQuarantineReason {
    #[serde(rename = "OUTPUT_INVALID")]
    OutputInvalid,
    #[serde(rename = "DEFINITION_CHANGED")]
    DefinitionChanged,
    #[serde(rename = "TOOL_MISSING")]
    ToolMissing,
    #[serde(rename = "SERVER_CHANGED")]
    ServerChanged,
    #[serde(rename = "SERVER_VERSION_CHANGED")]
    ServerVersionChanged,
    #[serde(rename = "PIN_MISMATCH")]
    PinMismatch,
}
/// Sent by the lease holder of exactly one invocation (a call) or one scan (a refresh); Control derives the targets from that evidence and applies it only to the current approval.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpQuarantineReport {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub invocation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scan_id: Option<String>,
    pub fencing_token: String,
    pub reason: McpQuarantineReason,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observed_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observed_server_info: Option<McpServerInfo>,
}
/// A Tool Host saw notifications/tools/list_changed during a call it holds and asks Control for an immediate refresh of that call's MCP server. Bound to the invocation and its fencing token, like a quarantine report.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpRefreshRequest {
    pub invocation_id: String,
    pub fencing_token: String,
}
/// Claims of a scan permit (typ toolgate-mcp-scan-permit+jws), signed in the effect-signing domain. exp - iat is at most 10 s (admission); deadlineUnixMs bounds the whole scan. spkiPins is required for REFRESH.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpScanClaims {
    pub jti: String,
    #[serde(deserialize_with = "const_mcp_scan_claims_iss")]
    pub iss: String,
    pub aud: String,
    pub iat: u64,
    pub exp: u64,
    pub scan_id: String,
    pub slug: String,
    pub registration_revision: u64,
    pub binding_id: String,
    pub purpose: McpScanPurpose,
    pub url: String,
    pub auth: McpUpstreamAuth,
    pub spki_pins: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_sequence: Option<u64>,
    pub fencing_token: String,
    pub deadline_unix_ms: u64,
}
/// A scan a Tool Host fetched: the signed scan permit for its admission.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpScanDispatch {
    pub scan_id: String,
    pub permit: String,
}
/// A failed scan, reported by the lease holder before the deadline.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpScanFailureReport {
    pub scan_id: String,
    pub fencing_token: String,
    pub failure: McpCheckFailure,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observed_spki_pin: Option<String>,
}
/// A scan of one registration on one Tool Host of its binding Device Group.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpScanJob {
    pub scan_id: String,
    pub slug: String,
    pub purpose: McpScanPurpose,
    pub state: McpScanState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_id: Option<String>,
    pub created_at_unix_ms: u64,
    pub deadline_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_sequence: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot_revision: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure: Option<McpCheckFailure>,
}
/// Canonical McpScanPurpose wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpScanPurpose {
    #[serde(rename = "DISCOVER")]
    Discover,
    #[serde(rename = "REFRESH")]
    Refresh,
}
/// What the scanning host observed, reported once by the lease holder before the deadline.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpScanResult {
    pub scan_id: String,
    pub fencing_token: String,
    pub server_info: McpServerInfo,
    pub protocol_version: String,
    pub spki_pin: String,
    pub tools: Vec<McpUpstreamTool>,
}
/// Canonical McpScanState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpScanState {
    #[serde(rename = "QUEUED")]
    Queued,
    #[serde(rename = "CLAIMED")]
    Claimed,
    #[serde(rename = "ADMITTED")]
    Admitted,
    #[serde(rename = "FINISHED")]
    Finished,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "EXPIRED")]
    Expired,
}
/// The upstream serverInfo from initialize.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpServerInfo {
    pub name: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
}
/// A registered upstream MCP server of the routed tenant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpServerRegistration {
    pub slug: String,
    pub url: String,
    pub auth: McpUpstreamAuth,
    pub binding_id: String,
    pub strict: bool,
    pub state: McpServerState,
    pub revision: u64,
    pub created_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_snapshot_revision: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_sequence: Option<u64>,
    pub verification: McpVerification,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quarantine: Option<McpQuarantine>,
}
/// Registrations of the routed tenant.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpServerRegistrationPage {
    pub items: Vec<McpServerRegistration>,
}
/// Registers an upstream MCP server in the routed tenant. The pool is the binding Device Group.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpServerRegistrationRequest {
    pub slug: String,
    pub url: String,
    pub auth: McpUpstreamAuth,
    pub binding_id: String,
    pub strict: bool,
}
/// Canonical McpServerState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpServerState {
    #[serde(rename = "REGISTERED")]
    Registered,
    #[serde(rename = "SCANNING")]
    Scanning,
    #[serde(rename = "SCANNED")]
    Scanned,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "QUARANTINED")]
    Quarantined,
}
/// An immutable scan result; Control recomputes every digest.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpSnapshot {
    pub revision: u64,
    pub scan_id: String,
    pub server_info: McpServerInfo,
    pub protocol_version: String,
    pub spki_pin: String,
    pub scanned_at_unix_ms: u64,
    pub tools: Vec<McpToolDefinition>,
}
/// Canonical McpTaskStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpTaskStatus {
    #[serde(rename = "working")]
    Working,
    #[serde(rename = "input_required")]
    InputRequired,
    #[serde(rename = "completed")]
    Completed,
    #[serde(rename = "failed")]
    Failed,
    #[serde(rename = "cancelled")]
    Cancelled,
}
/// One approved upstream tool and the ToolGate local name it gets (mcp.{slug}/{name}).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpToolApproval {
    pub upstream_name: String,
    pub name: String,
    pub effect_policy: McpEffectPolicy,
}
/// A snapshot tool: the upstream definition and its definition digest (tool-sdk/conformance/mcp-proxy).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpToolDefinition {
    pub tool: McpUpstreamTool,
    pub definition_digest: String,
}
/// Upstream credential for scans and calls. The secret is a tenant vault entry whose Tool Group and Device Group equal the registration binding.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpUpstreamAuth {
    pub secret_name: String,
    pub scheme: McpAuthScheme,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub header: Option<String>,
}
/// One tool exactly as the upstream tools/list returned it, within the scan limits.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpUpstreamTool {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_schema: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub annotations: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// The latest check of a registration against its approval; a failure replaces an earlier success.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpVerification {
    pub status: McpVerificationStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub approval_sequence: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checked_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure: Option<McpCheckFailure>,
}
/// Canonical McpVerificationStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum McpVerificationStatus {
    #[serde(rename = "CURRENT")]
    Current,
    #[serde(rename = "UNVERIFIED")]
    Unverified,
}
/// Canonical MissingKeyPolicy wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MissingKeyPolicy {
    #[serde(rename = "REJECT")]
    Reject,
    #[serde(rename = "ASK")]
    Ask,
    #[serde(rename = "ALLOW_WITH_DEDUPE")]
    AllowWithDedupe,
}
/// Control-created stamp for an immutable generated MCP tool policy; not accepted from approval or package authors.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NativeMcpEffectReview {
    pub tool_id: String,
    pub policy: McpEffectPolicy,
    pub reviewed_by: String,
    pub reviewed_at_unix_ms: u64,
}
/// Current native endpoint consumption: accepted invocation, live lease and frozen kill facts from one Control transaction. Not a Tool Host admission response.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NativePermitConsumed {
    pub invocation: crate::EnterpriseInvocation,
    pub fencing_token: String,
    pub lease_expires_at_unix_ms: u64,
    pub authorization_epoch: u64,
    pub kill_scope: KillExecutionScope,
}
/// Version requirements checked at enable.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageCompatibility {
    pub contracts_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minimum_tool_host_version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minimum_client_version: Option<String>,
}
/// The package version Tool Hosts run for one package id, with the service profile and globals Control built for it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageDeployment {
    pub package_id: String,
    pub version: String,
    pub package_digest: String,
    pub revision: u64,
    pub service_profile_digest: String,
    pub service_profile_revision: u64,
    pub globals_digest: String,
    pub tool_ids: Vec<String>,
    pub deployed_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_group_ids: Option<Vec<String>>,
}
/// Current deployments, one per package id.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageDeploymentPage {
    pub items: Vec<PackageDeployment>,
}
/// Deploys an uploaded server package to the Tool Hosts with values for its declared globals. A destination's host is the global its hostSetting names.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageDeploymentRequest {
    pub package_digest: String,
    pub globals: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_group_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub private_ranges: Option<Vec<PrivateRangeApproval>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub destination_tls: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect_policies: Option<Vec<RouteEffectPolicy>>,
}
/// Enable exactly the reviewed digest with this binding.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageEnableRequest {
    pub package_digest: String,
    pub binding: DeploymentBinding,
    pub acknowledged_security_delta: bool,
}
/// Canonical PackageKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PackageKind {
    #[serde(rename = "SDK")]
    Sdk,
    #[serde(rename = "CONTAINER")]
    Container,
    #[serde(rename = "OPENAPI")]
    Openapi,
    #[serde(rename = "MCP_PROXY")]
    McpProxy,
}
/// Package-level ceilings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageLimits {
    pub max_tools: u64,
    pub max_descriptor_bytes: u64,
}
/// Current package manifest. Earlier formats are rejected without conversion.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageManifest {
    #[serde(deserialize_with = "const_package_manifest_schema_version")]
    pub schema_version: u64,
    pub kind: PackageKind,
    pub id: String,
    pub version: String,
    pub publisher: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sdk: Option<SdkInfo>,
    pub compatibility: PackageCompatibility,
    pub runtimes: Vec<RuntimeDeclaration>,
    pub tools: Vec<ToolDefinition>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub triggers: Option<Vec<TriggerDefinition>>,
    pub descriptor_set_digest: String,
    pub artifacts: Vec<PackagedArtifact>,
    pub credential_references: Vec<String>,
    pub connections: Vec<ConnectionRequirement>,
    pub composition: Vec<CompositionRecord>,
    pub limits: PackageLimits,
}
/// Review and enable page data: both diffs and the values to fill.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageReview {
    pub package_id: String,
    pub version: String,
    pub package_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_package_digest: Option<String>,
    pub api_change: ApiChange,
    pub security_delta: Vec<SecurityDeltaItem>,
    pub binding_draft: DeploymentBinding,
}
/// A tenant's uploaded package version.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageVersion {
    pub package_id: String,
    pub version: String,
    pub package_digest: String,
    pub descriptor_set_digest: String,
    pub state: PackageVersionState,
    pub revision: u64,
    pub uploaded_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub manifest: Option<PackageManifest>,
}
/// Package versions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackageVersionPage {
    pub items: Vec<PackageVersion>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
}
/// Canonical PackageVersionState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum PackageVersionState {
    #[serde(rename = "UPLOADED")]
    Uploaded,
    #[serde(rename = "VERIFIED")]
    Verified,
    #[serde(rename = "IN_REVIEW")]
    InReview,
    #[serde(rename = "APPROVED")]
    Approved,
    #[serde(rename = "ROLLING_OUT")]
    RollingOut,
    #[serde(rename = "ACTIVE")]
    Active,
    #[serde(rename = "PAUSED")]
    Paused,
    #[serde(rename = "ROLLED_BACK")]
    RolledBack,
    #[serde(rename = "DEPRECATED")]
    Deprecated,
    #[serde(rename = "DRAINING")]
    Draining,
    #[serde(rename = "RETIRED")]
    Retired,
    #[serde(rename = "REJECTED")]
    Rejected,
    #[serde(rename = "SUSPENDED")]
    Suspended,
}
/// Executable artifact inside the package; artifactDigest = sha256 over its exact bytes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PackagedArtifact {
    pub artifact_id: String,
    pub path: String,
    pub artifact_digest: String,
    pub size_bytes: u64,
    pub media_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_id: Option<String>,
}
/// Canonical Permission wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Permission {
    #[serde(rename = "HTTP_ROUTE")]
    HttpRoute,
    #[serde(rename = "TCP")]
    Tcp,
    #[serde(rename = "CREDENTIALS_IN_SANDBOX")]
    CredentialsInSandbox,
    #[serde(rename = "USER_SECRET")]
    UserSecret,
    #[serde(rename = "TOKEN_EXCHANGE")]
    TokenExchange,
    #[serde(rename = "CONNECTION")]
    Connection,
    #[serde(rename = "DEVICE_FILES_READ")]
    DeviceFilesRead,
    #[serde(rename = "DEVICE_FILES_WRITE")]
    DeviceFilesWrite,
}
/// Successful consumption with current authorization epoch and immutable per-attempt revocation scope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitConsumed {
    pub invocation_id: String,
    pub fencing_token: String,
    pub lease_expires_at_unix_ms: u64,
    pub authorization_epoch: u64,
    pub kill_scope: KillExecutionScope,
}
/// Tool Host or device consumes the single-use nonce; returns the fencing token.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitConsumption {
    pub invocation_id: String,
    pub permit: SignedInvocationPermit,
    pub arguments_digest: String,
    pub tool_descriptor_digest: String,
    pub package_digest: String,
    pub artifact_digest: String,
}
/// JWS protected header.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitHeader {
    #[serde(deserialize_with = "const_permit_header_alg")]
    pub alg: String,
    #[serde(deserialize_with = "const_permit_header_typ")]
    pub typ: String,
    pub kid: String,
}
/// Verified identity and epochs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitIdentity {
    pub tenant_id: String,
    pub mode: RequestMode,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    pub chain: Vec<ActorHop>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_epoch: Option<u64>,
    pub authorization_epoch: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claims: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// v2 body of POST /access/invocations/reserve: one permit for one attempt, addressed to one Tool Host or device (invocation-state-v2.md T4). For a Tool Host the body carries the call's arguments: Control checks them against the invocation and pins their RFC 8785 digest in the permit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitReservationRequest {
    pub invocation_id: String,
    pub audience: String,
    pub attempt: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arguments: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Exact code and configuration the attempt may run.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitTarget {
    pub tool_id: String,
    pub tool_descriptor_digest: String,
    pub package_digest: String,
    pub artifact_digest: String,
    pub runtime_digest: String,
    pub globals_digest: String,
    pub service_profile_digest: String,
    pub service_profile_revision: u64,
    pub deployment_binding_digest: String,
    pub deployment_binding_revision: u64,
    pub config_revision: u64,
    pub routes: Vec<String>,
}
/// Admin-approved private address range.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PrivateRangeApproval {
    pub host: String,
    pub cidr: String,
    pub ports: Vec<u64>,
}
/// One package difference from the previously applied profile, recorded for review.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileChange {
    pub package_id: String,
    pub change: ProfileChangeKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_package_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_digest: Option<String>,
}
/// Canonical ProfileChangeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProfileChangeKind {
    #[serde(rename = "ADDED")]
    Added,
    #[serde(rename = "CHANGED")]
    Changed,
    #[serde(rename = "REMOVED")]
    Removed,
}
/// One package the profile requires, pinned to an exact version. A MARKETPLACE entry must also pin its package digest; a PREBUILT_CORE entry resolves its digest from the sealed image lock, and a declared digest must equal it. hostIds names the Tool Hosts that receive its eligible ServerTool exports; ClientTool exports never deploy to a Tool Host. deviceGroupIds names the device groups whose active, approved devices receive its device-runnable exports as a signed client release (ADR 026), including devices that join later. An entry that activates exports needs hostIds or deviceGroupIds. bindingIds have their allowed package digests set to exactly the profile packages that name them. triggers declares client trigger assignments of its exports for those device groups.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfilePackage {
    pub package_id: String,
    pub source: ProfilePackageSource,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_digest: Option<String>,
    pub required: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_group_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub globals: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<ProfileToolActivation>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub triggers: Option<Vec<ProfileTrigger>>,
}
/// Canonical ProfilePackageSource wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProfilePackageSource {
    #[serde(rename = "PREBUILT_CORE")]
    PrebuiltCore,
    #[serde(rename = "MARKETPLACE")]
    Marketplace,
}
/// A call the deployment makes through the Gateway, as the named agent, to declare the profile ready. Arguments are explicit; expected names fields the structured result must contain with these exact values.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileReadinessCase {
    pub tool_id: String,
    pub agent_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expected: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// One installed export to enable, in the reviewed Tool Group named here. The export must exist in the pinned package; nothing is created to satisfy a reference.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileToolActivation {
    pub tool_id: String,
    pub tool_group_id: String,
}
/// A client trigger assignment the profile declares for one of its package's trigger exports (ADR 058). It targets the entry's deviceGroupIds; a profile that declares any trigger enables triggers for the deployment.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProfileTrigger {
    pub assignment: String,
    pub trigger_id: String,
    pub config: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub desired_state: Option<TriggerDesiredState>,
}
/// Progress frame.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProgressFrame {
    #[serde(deserialize_with = "const_progress_frame_kind")]
    pub kind: String,
    pub fraction: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}
/// Supported tool protocol range, inclusive.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProtocolRange {
    pub min: u64,
    pub max: u64,
}
/// One pinned package: where provisioning stands and why it is not ready. hostIds and globalsDigest record the deployment it applied, so an unchanged repeat does not redeploy.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProvisioningPackageStatus {
    pub package_id: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_digest: Option<String>,
    pub required: bool,
    pub state: ProvisioningState,
    pub step: ProvisioningStep,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_group_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_ids: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub globals_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trigger_assignments: Option<Vec<String>>,
}
/// Canonical ProvisioningState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProvisioningState {
    #[serde(rename = "READY")]
    Ready,
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "FAILED")]
    Failed,
}
/// Persisted outcome of the latest profile application. state is READY only when every required package is READY.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProvisioningStatus {
    pub profile_id: String,
    pub profile_digest: String,
    pub generation: u64,
    pub state: ProvisioningState,
    pub packages: Vec<ProvisioningPackageStatus>,
    pub applied_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous_profile_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub changes: Option<Vec<ProfileChange>>,
}
/// The latest ProvisioningStatus of every applied deployment profile, by profile id.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProvisioningStatusPage {
    pub items: Vec<ProvisioningStatus>,
}
/// Canonical ProvisioningStep wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ProvisioningStep {
    #[serde(rename = "VALIDATE")]
    Validate,
    #[serde(rename = "IMPORT")]
    Import,
    #[serde(rename = "DEPLOY")]
    Deploy,
    #[serde(rename = "RELEASE")]
    Release,
    #[serde(rename = "ROLLOUT")]
    Rollout,
    #[serde(rename = "ACTIVATE")]
    Activate,
    #[serde(rename = "ASSIGN")]
    Assign,
    #[serde(rename = "DONE")]
    Done,
}
/// Supersedes kills after two-person review. Issuer is always control.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RecoveryEvent {
    pub event_id: String,
    #[serde(deserialize_with = "const_recovery_event_issuer")]
    pub issuer: String,
    pub supersedes: Vec<String>,
    pub reviewers: Vec<String>,
    pub issued_at_unix_ms: u64,
    pub signature: String,
    pub tenant_id: String,
    pub authorization_epoch: u64,
}
/// Bounded current-only preflight. Opaque cursors fence a changed tenant directory; observed facts never authorize a resend.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RecoveryPreflightPage {
    pub items: Vec<ToolRecoveryReadiness>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_cursor: Option<String>,
    pub observed_at_unix_ms: u64,
    #[serde(deserialize_with = "const_recovery_preflight_page_build_mode")]
    pub build_mode: String,
}
/// Canonical RecoveryReadinessReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RecoveryReadinessReason {
    #[serde(rename = "ELIGIBLE")]
    Eligible,
    #[serde(rename = "DISABLED")]
    Disabled,
    #[serde(rename = "MISSING_PROFILE")]
    MissingProfile,
    #[serde(rename = "PROFILE_CHANGED")]
    ProfileChanged,
    #[serde(rename = "NO_WRITE_ROUTES")]
    NoWriteRoutes,
    #[serde(rename = "UNREVIEWED_ROUTE")]
    UnreviewedRoute,
    #[serde(rename = "MISSING_GUARANTEE")]
    MissingGuarantee,
    #[serde(rename = "REVIEW_EXPIRED")]
    ReviewExpired,
    #[serde(rename = "INDEPENDENT_REVIEW_REQUIRED")]
    IndependentReviewRequired,
    #[serde(rename = "UNSUPPORTED_CREDENTIAL")]
    UnsupportedCredential,
    #[serde(rename = "UNQUALIFIED_EXECUTOR")]
    UnqualifiedExecutor,
}
/// Request a reviewed recovery event.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RecoveryRequest {
    pub supersedes: Vec<String>,
    pub reason: String,
}
/// Canonical RefusalReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RefusalReason {
    #[serde(rename = "BUSY")]
    Busy,
    #[serde(rename = "DIGEST_NOT_PRESENT")]
    DigestNotPresent,
    #[serde(rename = "STALE_SEQUENCE")]
    StaleSequence,
    #[serde(rename = "PERMIT_INVALID")]
    PermitInvalid,
    #[serde(rename = "PERMIT_EXPIRED")]
    PermitExpired,
    #[serde(rename = "STORE_UNAVAILABLE")]
    StoreUnavailable,
    #[serde(rename = "AUDIT_UNAVAILABLE")]
    AuditUnavailable,
    #[serde(rename = "UNSUPPORTED_TIER")]
    UnsupportedTier,
    #[serde(rename = "KILLED")]
    Killed,
}
/// Authenticated Gateway context v2. Carries no device: server-side agents have none (decision 7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RequestContext {
    pub request_id: String,
    pub tenant_id: String,
    pub mode: RequestMode,
    pub credential_kind: CredentialKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub issuer: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub user_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workload_binding_id: Option<String>,
    pub chain: Vec<ActorHop>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delegated_session_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_sha256: Option<String>,
}
/// Canonical RequestMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RequestMode {
    #[serde(rename = "HUMAN")]
    Human,
    #[serde(rename = "DELEGATED")]
    Delegated,
    #[serde(rename = "SERVICE")]
    Service,
}
/// Canonical ReservationOutcome wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ReservationOutcome {
    #[serde(rename = "NEW")]
    New,
    #[serde(rename = "EXISTING")]
    Existing,
    #[serde(rename = "IDEMPOTENCY_CONFLICT")]
    IdempotencyConflict,
    #[serde(rename = "IDEMPOTENCY_KEY_IN_USE")]
    IdempotencyKeyInUse,
    #[serde(rename = "EXPIRED_KEY")]
    ExpiredKey,
}
/// Terminal output frame.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResultFrame {
    #[serde(deserialize_with = "const_result_frame_kind")]
    pub kind: String,
    pub result_type: ResultType,
    pub content: Vec<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub structured_content: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    pub is_error: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<ToolErrorBody>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provenance: Option<ResultProvenance>,
}
/// Tool Host to Gateway.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResultMessage {
    pub invocation_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fencing_token: Option<String>,
    pub outcome: InvocationOutcome,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<ResultFrame>,
}
/// Canonical ResultProvenance wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ResultProvenance {
    #[serde(rename = "TOOL")]
    Tool,
    #[serde(rename = "UNTRUSTED_UPSTREAM")]
    UntrustedUpstream,
}
/// Canonical ResultType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ResultType {
    #[serde(rename = "complete")]
    Complete,
    #[serde(rename = "input_required")]
    InputRequired,
    #[serde(rename = "task")]
    Task,
}
/// Rollout plan attached at enable.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RolloutPlan {
    pub strategy: RolloutStrategy,
    pub steps: Vec<RolloutStep>,
    pub auto_rollback: bool,
}
/// One exposure step.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RolloutStep {
    pub percent: u64,
    pub soak_seconds: u64,
}
/// Canonical RolloutStrategy wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RolloutStrategy {
    #[serde(rename = "ALL_AT_ONCE")]
    AllAtOnce,
    #[serde(rename = "COHORT")]
    Cohort,
}
/// Declared HTTP route, without host values.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RouteDeclaration {
    pub id: String,
    pub destination: String,
    pub method: HttpMethod,
    pub path: String,
    pub query: Vec<String>,
    pub headers: Vec<String>,
    pub request_content_types: Vec<String>,
    pub max_request_bytes: u64,
    pub max_response_bytes: u64,
    pub streaming: Streaming,
    pub timeout_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auth: Option<String>,
}
/// Tool configuration review binds the exact declaration to an immutable profile/route. Direct or self-reviewed metadata is insufficient.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RouteDedupeConfiguration {
    pub profile_digest: String,
    pub route_id: String,
    pub guarantee: TargetDedupeGuarantee,
}
/// Operator-reviewed generic route intent. Financial/destructive and declared consent-required routes require immutable exact-effect approval and atomic slot authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RouteEffectPolicy {
    pub route_id: String,
    pub classification: EffectClassification,
    pub consent_required: bool,
    pub acknowledged_unfenced_risk: bool,
    pub intent: EffectIntentBlueprint,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_dedupe_guarantee: Option<TargetDedupeGuarantee>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fenced_target: Option<FencedTarget>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authoritative_response: Option<bool>,
}
/// Review stamp created by Control, never accepted from package or worker input.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RouteEffectReview {
    pub policy: RouteEffectPolicy,
    pub reviewed_by: String,
    pub reviewed_at_unix_ms: u64,
}
/// Current reviewed contract facts for one write route; no operation-specific retry authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RouteRecoveryReadiness {
    pub route_id: String,
    pub reason: RecoveryReadinessReason,
    pub lookup_available: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub review_expires_at_unix_ms: Option<u64>,
}
/// One retained run-response event. cursor is assigned by Control and orders replay; timestamps are display data only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEvent {
    pub cursor: u64,
    pub source: RunEventSource,
    pub kind: RunEventKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attempt: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sequence: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub executor_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub emitted_at_unix_ms: Option<u64>,
    pub recorded_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub state: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<ResultFrame>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub level: Option<LogLevel>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fraction: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Control acknowledgement: the highest contiguous sequence stored for the attempt, and how many frames were already present or refused by retention bounds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEventAck {
    pub invocation_id: String,
    pub attempt: u64,
    pub accepted_through: u64,
    pub duplicates: u64,
    pub refused: u64,
}
/// Executor to Control: frames of one admitted attempt, bound to the invocation and the fencing token its permit consumption returned. Redelivery of the same sequence is idempotent. droppedFrames counts frames the executor discarded under its own bounds.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEventBatch {
    pub invocation_id: String,
    pub fencing_token: String,
    pub attempt: u64,
    pub events: Vec<RunEventFrame>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dropped_frames: Option<u64>,
}
/// One redacted non-terminal SDK frame (LOG, PROGRESS or INPUT_REQUEST) as the executor forwards it. sequence is monotonic per invocation attempt; the executor never renumbers a frame it resends.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEventFrame {
    pub sequence: u64,
    pub emitted_at_unix_ms: u64,
    pub kind: RunEventKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub level: Option<LogLevel>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fraction: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// Sequences of an attempt that never arrived; they are reported, never invented.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEventGap {
    pub attempt: u64,
    pub from_sequence: u64,
    pub to_sequence: u64,
}
/// Canonical RunEventKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RunEventKind {
    #[serde(rename = "LOG")]
    Log,
    #[serde(rename = "PROGRESS")]
    Progress,
    #[serde(rename = "INPUT_REQUEST")]
    InputRequest,
    #[serde(rename = "STATE")]
    State,
    #[serde(rename = "RESULT")]
    Result,
}
/// Authorized replay page after a cursor. terminal is the authoritative ledger state; truncated, droppedFrames, gaps and expired say explicitly what is not shown.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunEventPage {
    pub invocation_id: String,
    pub state: String,
    pub terminal: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub executor_id: Option<String>,
    pub events: Vec<RunEvent>,
    pub next_cursor: u64,
    pub more: bool,
    pub truncated: bool,
    pub dropped_frames: u64,
    pub expired: bool,
    pub gaps: Vec<RunEventGap>,
}
/// Canonical RunEventSource wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RunEventSource {
    #[serde(rename = "EXECUTOR")]
    Executor,
    #[serde(rename = "PLATFORM")]
    Platform,
}
/// Language runtime a tool needs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RuntimeDeclaration {
    pub runtime_id: String,
    pub language: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_image_digest: Option<String>,
}
/// Canonical RuntimeTier wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RuntimeTier {
    #[serde(rename = "OCI_SANDBOX")]
    OciSandbox,
    #[serde(rename = "WASI")]
    Wasi,
    #[serde(rename = "HOST_NATIVE")]
    HostNative,
}
/// SDK that built the package.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SdkInfo {
    pub language: SdkLanguage,
    pub version: String,
    pub protocol: ProtocolRange,
    pub descriptor_generation: u64,
}
/// Canonical SdkLanguage wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SdkLanguage {
    #[serde(rename = "JAVA")]
    Java,
    #[serde(rename = "PYTHON")]
    Python,
    #[serde(rename = "TYPESCRIPT")]
    Typescript,
    #[serde(rename = "DOTNET")]
    Dotnet,
}
/// Declared secret; never a value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SecretDeclaration {
    pub name: String,
    pub r#type: ValueType,
    pub scope: SecretScope,
}
/// Secret handle, redeemed per call with the fencing token. Never a value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SecretHandle {
    pub name: String,
    pub handle: String,
}
/// Canonical SecretScope wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SecretScope {
    #[serde(rename = "GROUP")]
    Group,
    #[serde(rename = "USER")]
    User,
}
/// One security delta. UNCLASSIFIED counts as widening.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SecurityDeltaItem {
    pub kind: SecurityDeltaKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_id: Option<String>,
    pub widening: bool,
    pub summary: String,
}
/// Canonical SecurityDeltaKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SecurityDeltaKind {
    #[serde(rename = "PERMISSION")]
    Permission,
    #[serde(rename = "DESTINATION")]
    Destination,
    #[serde(rename = "ROUTE")]
    Route,
    #[serde(rename = "SECRET")]
    Secret,
    #[serde(rename = "CONNECTION")]
    Connection,
    #[serde(rename = "CLAIM")]
    Claim,
    #[serde(rename = "RUNTIME_TIER")]
    RuntimeTier,
    #[serde(rename = "PUBLISHER_KEY")]
    PublisherKey,
    #[serde(rename = "BASE_IMAGE")]
    BaseImage,
    #[serde(rename = "KIND")]
    Kind,
    #[serde(rename = "EFFECT_SAFETY")]
    EffectSafety,
    #[serde(rename = "BUSINESS_KEY_NAMESPACE")]
    BusinessKeyNamespace,
    #[serde(rename = "UNCLASSIFIED")]
    Unclassified,
}
/// Approved destination with its reviewed host.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServiceDestination {
    pub id: String,
    pub scheme: DestinationScheme,
    pub host: String,
    pub port: u64,
    pub tls: TlsSettings,
    pub mode: EgressMode,
}
/// Service profile. Its digest and revision are pinned in the permit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServiceProfile {
    pub profile_id: String,
    pub revision: u64,
    pub package_id: String,
    pub destinations: Vec<ServiceDestination>,
    pub routes: Vec<RouteDeclaration>,
    pub private_ranges: Vec<PrivateRangeApproval>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect_reviews: Option<Vec<RouteEffectReview>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub native_mcp_effects: Option<Vec<NativeMcpEffectReview>>,
}
/// Same shape as the declared route.
pub type ServiceRoute = RouteDeclaration;
/// JWS with the fixed toolgate-effect-grant-v1 type; not interchangeable with admission or service permits.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedEffectGrant {
    pub jws: String,
}
/// Compact JWS over InvocationPermitClaims.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedInvocationPermit {
    pub jws: String,
}
/// RFC 7515 compact RS256 JWS with a protected toolgate-discovery+jws header and TenantDiscovery v2 payload. No v1 or alternate-algorithm fallback.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedTenantDiscovery {
    pub jws: String,
}
/// Response of GET /api/control/v1/endpoint/triggers/desired.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SignedTriggerDesiredDocument {
    pub document: String,
}
/// Canonical Streaming wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Streaming {
    #[serde(rename = "NONE")]
    None,
    #[serde(rename = "RESPONSE")]
    Response,
    #[serde(rename = "BIDIRECTIONAL")]
    Bidirectional,
}
/// Authenticated target consumption of an exact accepted request, after its durable local claim commits. Replays return status only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TargetClaimConsumption {
    pub grant: SignedEffectGrant,
    pub claim_token: String,
    pub http_request_digest: String,
    pub executor_key_sha256: String,
}
/// Canonical TargetDedupeDifferentBody wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TargetDedupeDifferentBody {
    #[serde(rename = "REJECT")]
    Reject,
    #[serde(rename = "REPLAY_FIRST")]
    ReplayFirst,
}
/// Explicit operator-reviewed target contract; its presence alone never authorizes resend.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TargetDedupeGuarantee {
    pub documentation_url: String,
    pub retention_ms: u64,
    pub scope: TargetDedupeScope,
    pub scope_binding_digest: String,
    pub different_body: TargetDedupeDifferentBody,
    pub review_expires_at_unix_ms: u64,
    pub header: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_version: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status_route_id: Option<String>,
}
/// Canonical TargetDedupeScope wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TargetDedupeScope {
    #[serde(rename = "GLOBAL")]
    Global,
    #[serde(rename = "ACCOUNT")]
    Account,
    #[serde(rename = "API_KEY")]
    ApiKey,
}
/// Scoped operator result for one read-only target lookup. Effect status does not imply whole-operation completion; no target key, receipt or credential is exposed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TargetEffectLookupResult {
    pub invocation_id: String,
    pub effect_id: String,
    pub state: TargetEffectStatus,
    pub outcome_revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub evidence_digest: Option<String>,
}
/// Canonical TargetEffectStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TargetEffectStatus {
    #[serde(rename = "UNKNOWN")]
    Unknown,
    #[serde(rename = "PENDING")]
    Pending,
    #[serde(rename = "COMPLETED")]
    Completed,
    #[serde(rename = "FAILED")]
    Failed,
}
/// Current target lookup response. Terminal evidence must match the stored key/body and include a stable business receipt and result digest; unknown/pending never authorize execution. FAILED means a durable target witness that the effect was not applied; an ordinary failed HTTP/business response is insufficient.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TargetEffectStatusReply {
    pub key: String,
    pub state: TargetEffectStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub business_receipt: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result_digest: Option<String>,
}
/// Canonical TargetKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TargetKind {
    #[serde(rename = "TOOL_HOST_POOL")]
    ToolHostPool,
    #[serde(rename = "DEVICE_GROUP")]
    DeviceGroup,
}
/// Whether this request declared the MCP Tasks extension, and the owner digest a task would be bound to (invocation-state-v2.md §7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TaskCapability {
    pub declared: bool,
    pub owner_digest: String,
}
/// tasks/update input, relayed by the Gateway to the waiting tool (invocation-state-v2.md §7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TaskInputSubmission {
    pub input_responses: serde_json::Value,
}
/// Latest progress.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TaskProgress {
    pub fraction: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    pub at_unix_ms: u64,
}
/// Canonical TaskState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskState {
    #[serde(rename = "ACCEPTED")]
    Accepted,
    #[serde(rename = "AWAITING_APPROVAL")]
    AwaitingApproval,
    #[serde(rename = "QUEUED")]
    Queued,
    #[serde(rename = "DISPATCHED")]
    Dispatched,
    #[serde(rename = "RUNNING")]
    Running,
    #[serde(rename = "INPUT_REQUIRED")]
    InputRequired,
    #[serde(rename = "CANCEL_REQUESTED")]
    CancelRequested,
    #[serde(rename = "COMPLETED")]
    Completed,
    #[serde(rename = "FAILED")]
    Failed,
    #[serde(rename = "REJECTED")]
    Rejected,
    #[serde(rename = "EXPIRED")]
    Expired,
    #[serde(rename = "CANCELLED")]
    Cancelled,
    #[serde(rename = "OUTCOME_UNKNOWN")]
    OutcomeUnknown,
}
/// One recorded transition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TaskTransition {
    pub from: TaskState,
    pub to: TaskState,
    pub actor: TransitionActor,
    pub at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}
/// A task as Control returns it to the Gateway for tasks/get (invocation-state-v2.md §7). result is present only in a terminal state.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TaskView {
    pub invocation_id: String,
    pub task: InvocationTask,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<ResultFrame>,
}
/// Declared TCP destination (tunnel mode).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TcpDeclaration {
    pub id: String,
    pub host_setting: String,
    pub port: u64,
}
/// Signed tenant discovery v2. Exact HTTPS origins, configured/pinned UUID, lifetime, key continuity, CA purposes and active issuer are verified before enrollment or local identity access.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TenantDiscovery {
    #[serde(deserialize_with = "const_tenant_discovery_protocol_version")]
    pub protocol_version: u64,
    pub tenant_id: String,
    pub issuer: String,
    pub audiences: Vec<String>,
    pub server_id: String,
    pub organization: String,
    pub control_url: String,
    pub gateway_url: String,
    pub verification_uri: String,
    pub minimum_client_version: String,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub key_set_version: u64,
    pub discovery_keys: DiscoveryKeySet,
    pub device_issuer_key_id: String,
    pub device_authorities: Vec<DiscoveryDeviceAuthority>,
}
/// Upstream TLS verification.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TlsSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ca_bundle: Option<String>,
    pub spki_pins: Vec<String>,
}
/// The tool's single permission and the resources it acts on (OLO M3.7). Absent: action invoke on tool://{toolId}.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolAuthorization {
    pub action: String,
    pub resources: Vec<String>,
}
/// Tool definition v2: the semantic descriptor plus packaging members outside the digest.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolDefinition {
    pub descriptor: ToolDescriptor,
    pub tool_descriptor_digest: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub entry_point: Option<String>,
    pub artifact_ids: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limits: Option<ToolLimits>,
}
/// Semantic tool descriptor, generation 1. Identical in standalone and governed mode.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolDescriptor {
    #[serde(deserialize_with = "const_tool_descriptor_descriptor_generation")]
    pub descriptor_generation: u64,
    pub tool_id: String,
    pub mcp_name: String,
    pub kind: ToolKind,
    pub title: String,
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub use_when: Option<String>,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_schema: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    pub errors: Vec<ErrorDeclaration>,
    pub effect: ToolEffect,
    pub business_key: BusinessKeySettings,
    pub execution: ExecutionSettings,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reconcile_tool: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tiers: Option<Vec<RuntimeTier>>,
    pub routes: Vec<RouteDeclaration>,
    pub destinations: Vec<DestinationDeclaration>,
    pub http_auth: Vec<HttpAuthDeclaration>,
    pub tcp: Vec<TcpDeclaration>,
    pub variables: ToolVariables,
    pub permissions: Vec<Permission>,
    pub toolsets: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub authorization: Option<ToolAuthorization>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reconciliation: Option<ToolReconciliation>,
}
/// Effect settings with defaults resolved.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolEffect {
    pub safety: EffectSafety,
    pub destructive: bool,
    pub open_world: bool,
}
/// Error per tool-sdk/spec/authoring/errors.md.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolErrorBody {
    pub code: String,
    pub message: String,
    pub retryable: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<std::collections::BTreeMap<String, serde_json::Value>>,
}
/// One deployed tool a Tool Host runs. The host reports it in its check-in as a local tool, so Control places calls for it there.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolHostAssignment {
    pub tool_id: String,
    pub action: String,
    pub description: String,
    pub enabled: bool,
    pub input_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub tool_digest: String,
    pub package_digest: String,
}
/// What a registered Tool Host runs: its tools, and the deployments whose package, service profile and globals it fetches by digest.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolHostAssignments {
    pub host_id: String,
    pub tools: Vec<ToolHostAssignment>,
    pub deployments: Vec<PackageDeployment>,
}
/// Canonical ToolHostDispatchState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ToolHostDispatchState {
    #[serde(rename = "QUEUED")]
    Queued,
    #[serde(rename = "CLAIMED")]
    Claimed,
    #[serde(rename = "ADMITTED")]
    Admitted,
    #[serde(rename = "REFUSED")]
    Refused,
    #[serde(rename = "FINISHED")]
    Finished,
    #[serde(rename = "EXPIRED")]
    Expired,
}
/// A dispatch the Gateway handed to Control for a Tool Host that fetches its calls (pull mode), with the host's admission and result once it has sent them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolHostDispatchStatus {
    pub invocation_id: String,
    pub host_id: String,
    pub state: ToolHostDispatchState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub admission: Option<AdmissionResponse>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<ResultMessage>,
}
/// Tool Host registration with its workload identity (mTLS).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolHostRegistration {
    pub host_id: String,
    pub pool_id: String,
    pub version: String,
    pub protocol: ProtocolRange,
    pub launcher_version: String,
    pub tiers: Vec<RuntimeTier>,
}
/// First stdin frame.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolInput {
    #[serde(deserialize_with = "const_tool_input_protocol_version")]
    pub protocol_version: u64,
    pub invocation_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub tool_id: String,
    pub entry_point: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
    pub globals: std::collections::BTreeMap<String, serde_json::Value>,
    pub claims: std::collections::BTreeMap<String, serde_json::Value>,
    pub secret_handles: Vec<SecretHandle>,
    pub deadline_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub idempotency_key: Option<String>,
    pub catalog_revision: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub traceparent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fencing_token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_context: Option<ExecutionContext>,
}
/// Canonical ToolKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ToolKind {
    #[serde(rename = "SERVER")]
    Server,
    #[serde(rename = "CLIENT")]
    Client,
}
/// Tool resource requests; each at most the platform ceiling (platform-requirements.md §13.5).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolLimits {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory_bytes: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_input_bytes: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_output_bytes: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_argument_properties: Option<u64>,
}
/// Canonical ToolReconciliation wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ToolReconciliation {
    #[serde(rename = "DOWNSTREAM_IDEMPOTENCY")]
    DownstreamIdempotency,
    #[serde(rename = "STATUS_LOOKUP")]
    StatusLookup,
    #[serde(rename = "MANUAL")]
    Manual,
}
/// Fresh-build preflight for tools declaring downstream idempotency. Eligibility is configured capability only; each retry independently needs qualified complete history, current policy/consent and its original window. No key, receipt or credential is exposed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolRecoveryReadiness {
    pub tenant_id: String,
    pub tool_id: String,
    pub name: String,
    pub automatic_resend_eligible: bool,
    pub effective_method: crate::ReconciliationMethod,
    pub reason: RecoveryReadinessReason,
    pub routes: Vec<RouteRecoveryReadiness>,
}
/// Declared configuration and identity inputs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ToolVariables {
    pub globals: Vec<GlobalDeclaration>,
    pub secrets: Vec<SecretDeclaration>,
    pub claims: Vec<ClaimDeclaration>,
}
/// Canonical TransitionActor wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransitionActor {
    #[serde(rename = "GATEWAY")]
    Gateway,
    #[serde(rename = "CONTROL")]
    Control,
    #[serde(rename = "TOOL_HOST")]
    ToolHost,
    #[serde(rename = "CLIENT")]
    Client,
    #[serde(rename = "ADMIN")]
    Admin,
}
/// Every emit of this process up to emitSeq is resolved at Control.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAcceptedUpToFrame {
    #[serde(deserialize_with = "const_trigger_accepted_up_to_frame_kind")]
    pub kind: String,
    pub emit_seq: u64,
}
/// Declarative assignment resource (plan 9.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignment {
    #[serde(deserialize_with = "const_trigger_assignment_api_version")]
    pub api_version: String,
    #[serde(deserialize_with = "const_trigger_assignment_kind")]
    pub kind: String,
    pub metadata: TriggerAssignmentMetadata,
    pub spec: TriggerAssignmentSpec,
    pub status: TriggerAssignmentStatus,
}
/// One assignment condition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentCondition {
    pub r#type: TriggerAssignmentConditionType,
    pub status: TriggerConditionStatus,
    pub reason: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    pub last_transition_time: String,
}
/// Canonical TriggerAssignmentConditionType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerAssignmentConditionType {
    #[serde(rename = "Available")]
    Available,
    #[serde(rename = "Progressing")]
    Progressing,
}
/// Body of POST /api/control/v1/triggers/assignments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentCreate {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub labels: Option<Vec<TriggerLabel>>,
    pub spec: TriggerAssignmentSpec,
}
/// Server-assigned assignment metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentMetadata {
    pub name: String,
    pub uid: String,
    pub generation: u64,
    pub config_revision: u64,
    pub resource_version: String,
    pub labels: Vec<TriggerLabel>,
    pub created_at_unix_ms: u64,
    pub updated_at_unix_ms: u64,
}
/// One page of assignments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentPage {
    pub items: Vec<TriggerAssignment>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// Result of an assignment mutation, or of its dry run.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentResult {
    pub assignment: TriggerAssignment,
    pub dry_run: bool,
    pub changes: Vec<TriggerInstanceChange>,
}
/// Desired assignment of one trigger definition (plan 9.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentSpec {
    pub trigger: String,
    pub package_version: String,
    pub target: TriggerTarget,
    pub config: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub globals: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    pub desired_state: TriggerDesiredState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy: Option<TriggerRolloutStrategy>,
}
/// Observed assignment status.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerAssignmentStatus {
    pub observed_generation: u64,
    pub instances: TriggerInstanceCounts,
    pub conditions: Vec<TriggerAssignmentCondition>,
}
/// Barrier advanced to that checkpoint at that level.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointAckFrame {
    #[serde(deserialize_with = "const_trigger_checkpoint_ack_frame_kind")]
    pub kind: String,
    pub checkpoint_id: u64,
    pub level: TriggerCheckpointLevel,
}
/// Explicit checkpoint; subject to the same barrier as position.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointFrame {
    #[serde(deserialize_with = "const_trigger_checkpoint_frame_kind")]
    pub kind: String,
    pub checkpoint_id: u64,
    pub after_emit_seq: u64,
    pub state: String,
}
/// Canonical TriggerCheckpointLevel wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerCheckpointLevel {
    #[serde(rename = "LOCAL")]
    Local,
    #[serde(rename = "ACCEPTED")]
    Accepted,
}
/// Committed checkpoint of one instance.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointMetadata {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub after_outbox_seq: u64,
    pub level: TriggerCheckpointLevel,
    pub version: u64,
    pub digest: String,
    pub size_bytes: u64,
    pub chunk_count: u64,
}
/// Checkpoint metadata changed since the last ack; the body is inline only up to 4 KiB (plan 8.6).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointReport {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub after_outbox_seq: u64,
    pub level: TriggerCheckpointLevel,
    pub version: u64,
    pub digest: String,
    pub size_bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub inline: Option<String>,
}
/// Started upload.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointUpload {
    pub upload_id: String,
    #[serde(deserialize_with = "const_trigger_checkpoint_upload_chunk_bytes")]
    pub chunk_bytes: u64,
    pub expires_at_unix_ms: u64,
}
/// Start a checkpoint upload; idempotent on (instanceId, version).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCheckpointUploadRequest {
    pub version: u64,
    pub lease_epoch: u64,
    pub after_outbox_seq: u64,
    pub level: TriggerCheckpointLevel,
    pub size_bytes: u64,
    pub digest: String,
}
/// Canonical TriggerClassification wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerClassification {
    #[serde(rename = "PUBLIC")]
    Public,
    #[serde(rename = "INTERNAL")]
    Internal,
    #[serde(rename = "CONFIDENTIAL")]
    Confidential,
    #[serde(rename = "RESTRICTED")]
    Restricted,
}
/// CloudEvents 1.0 JSON envelope written by the supervisor; authors never set source, type, dataschema or tg* attributes (plan 6.4).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCloudEvent {
    #[serde(deserialize_with = "const_trigger_cloud_event_specversion")]
    pub specversion: String,
    pub id: String,
    pub source: String,
    pub r#type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub time: Option<String>,
    #[serde(deserialize_with = "const_trigger_cloud_event_datacontenttype")]
    pub datacontenttype: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dataschema: Option<String>,
    pub data: serde_json::Value,
    pub tgleaseepoch: u64,
    pub tgsequence: u64,
    pub tgkind: TriggerEventKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub traceparent: Option<String>,
}
/// One instance condition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCondition {
    pub r#type: TriggerConditionType,
    pub status: TriggerConditionStatus,
    pub reason: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    pub last_transition_time: String,
}
/// Canonical TriggerConditionStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerConditionStatus {
    #[serde(rename = "True")]
    True,
    #[serde(rename = "False")]
    False,
    #[serde(rename = "Unknown")]
    Unknown,
}
/// Canonical TriggerConditionType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerConditionType {
    #[serde(rename = "Installed")]
    Installed,
    #[serde(rename = "Leased")]
    Leased,
    #[serde(rename = "Ready")]
    Ready,
    #[serde(rename = "Degraded")]
    Degraded,
    #[serde(rename = "Reconciled")]
    Reconciled,
    #[serde(rename = "Stale")]
    Stale,
    #[serde(rename = "Unexpected")]
    Unexpected,
}
/// Canonical TriggerConsumerKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerConsumerKind {
    #[serde(rename = "SUBSCRIPTION")]
    Subscription,
    #[serde(rename = "ACTION")]
    Action,
    #[serde(rename = "TEST")]
    Test,
}
/// Monotonic counters for the current process lifetime of one instance.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerCounters {
    pub emitted: u64,
    pub rejected: u64,
    pub dropped: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub emit_timeouts: Option<u64>,
    pub restarts: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub broker_calls: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub possible_loss_on_crash: Option<u64>,
}
/// Tenant fail-closed defaults for unlabelled data, per integration kind (plan 12.3). A default may be raised, never lowered below the shipped value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDefaultClassifications {
    pub sdk_package: TriggerClassification,
    pub mcp_proxy: TriggerClassification,
    pub external: TriggerClassification,
    pub unknown: TriggerClassification,
}
/// Trigger export (ADR 058, plan 5.9): the signed trigger descriptor plus packaging members outside the digest. Trigger names share the package export namespace with tools.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDefinition {
    pub descriptor: TriggerDescriptor,
    pub trigger_descriptor_digest: String,
    pub runtime_id: String,
    pub entry_point: String,
    pub artifact_ids: Vec<String>,
}
/// One page of definitions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDefinitionPage {
    pub items: Vec<TriggerDefinitionRecord>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// A trigger export of an uploaded package version (plan 10.2).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDefinitionRecord {
    pub trigger_id: String,
    pub package_id: String,
    pub package_version: String,
    pub package_digest: String,
    pub trigger_descriptor_digest: String,
    pub descriptor: TriggerDescriptor,
    pub security_delta: Vec<SecurityDeltaItem>,
}
/// One delivery of a routed event to one consumer.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDelivery {
    pub delivery_id: String,
    pub consumer: TriggerDeliveryConsumer,
    pub cursor: String,
    pub event_source: String,
    pub event_id: String,
    pub event_type: String,
    pub assignment: String,
    pub state: TriggerDeliveryState,
    pub attempts: u64,
    pub created_at_unix_ms: u64,
    pub updated_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_attempt_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}
/// The consumer of a delivery.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDeliveryConsumer {
    pub kind: TriggerConsumerKind,
    pub name: String,
}
/// One page of deliveries.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDeliveryPage {
    pub items: Vec<TriggerDelivery>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// Canonical TriggerDeliveryState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerDeliveryState {
    #[serde(rename = "Pending")]
    Pending,
    #[serde(rename = "InFlight")]
    Inflight,
    #[serde(rename = "Delivered")]
    Delivered,
    #[serde(rename = "RetryWait")]
    Retrywait,
    #[serde(rename = "Held")]
    Held,
    #[serde(rename = "Superseded")]
    Superseded,
    #[serde(rename = "DeadLettered")]
    Deadlettered,
    #[serde(rename = "Skipped")]
    Skipped,
}
/// Signed semantic descriptor of one trigger export (plan 5.9).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDescriptor {
    pub kind: TriggerKind,
    pub trigger_id: String,
    pub mode: TriggerMode,
    pub executors: Vec<TriggerExecutor>,
    pub event_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub loss_tolerant: Option<bool>,
    pub config_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub event_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub checkpoint_schema_version: u64,
    pub source_replay: TriggerSourceReplay,
    pub schedule: TriggerSchedule,
    pub limits: TriggerLimits,
    pub overflow: TriggerOverflow,
    pub on_terminal_rejection: TriggerTerminalRejectionPolicy,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot: Option<TriggerSnapshotCapacity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub routes: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tcp_destinations: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secrets: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub permissions: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tiers: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub self_tests: Option<Vec<String>>,
}
/// Signed desired trigger state for one executor; the payload of a compact JWS by the organization deployment key.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDesiredDocument {
    #[serde(deserialize_with = "const_trigger_desired_document_format_version")]
    pub format_version: u64,
    pub tenant_id: String,
    pub executor_id: String,
    pub executor: TriggerExecutor,
    pub generation: u64,
    pub issued_at_unix_ms: u64,
    pub expires_at_unix_ms: u64,
    pub instances: Vec<TriggerDesiredInstance>,
}
/// One resolved instance in the desired document (plan 8.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDesiredInstance {
    pub instance_id: String,
    pub trigger_id: String,
    pub package_digest: String,
    pub trigger_descriptor_digest: String,
    pub config_revision: u64,
    pub config: std::collections::BTreeMap<String, serde_json::Value>,
    pub binding_digest: String,
    pub desired_state: TriggerDesiredState,
}
/// Canonical TriggerDesiredState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerDesiredState {
    #[serde(rename = "Running")]
    Running,
    #[serde(rename = "Paused")]
    Paused,
    #[serde(rename = "Stopped")]
    Stopped,
}
/// Control-side terminal rejection of one record.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDisposition {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub outbox_seq: u64,
    pub id: String,
    pub disposition: TriggerDispositionKind,
    pub reason: String,
}
/// Canonical TriggerDispositionKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerDispositionKind {
    #[serde(rename = "REJECTED_TERMINAL")]
    RejectedTerminal,
}
/// Control's terminal disposition of a stored record: a loss record, or a Control-side rejection on re-validation (plan 6.6).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerDispositionView {
    pub disposition: TriggerDispositionKind,
    pub reason: String,
}
/// Completes or fails one emit() future.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerEmitAckFrame {
    #[serde(deserialize_with = "const_trigger_emit_ack_frame_kind")]
    pub kind: String,
    pub emit_seq: u64,
    pub status: TriggerEmitStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outbox_seq: Option<u64>,
}
/// One event; the supervisor wraps it in the CloudEvents envelope.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerEmitFrame {
    #[serde(deserialize_with = "const_trigger_emit_frame_kind")]
    pub kind: String,
    pub emit_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub time: Option<String>,
    pub data: serde_json::Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub position: Option<String>,
}
/// Canonical TriggerEmitStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerEmitStatus {
    #[serde(rename = "LOCAL_DURABLE")]
    LocalDurable,
    #[serde(rename = "RETRYABLE_REJECTION")]
    RetryableRejection,
    #[serde(rename = "TERMINAL_REJECTION")]
    TerminalRejection,
}
/// Canonical TriggerEventKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerEventKind {
    #[serde(rename = "fired")]
    Fired,
    #[serde(rename = "state")]
    State,
    #[serde(rename = "rejected")]
    Rejected,
}
/// One page of an instance's stored records, in (leaseEpoch, outboxSeq) order.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerEventPage {
    pub items: Vec<TriggerStoredEvent>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// Canonical TriggerExecutor wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerExecutor {
    #[serde(rename = "SERVER")]
    Server,
    #[serde(rename = "CLIENT")]
    Client,
}
/// Last frame on a clean stop.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerExitFrame {
    #[serde(deserialize_with = "const_trigger_exit_frame_kind")]
    pub kind: String,
    pub code: u64,
}
/// Canonical TriggerFrameKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerFrameKind {
    #[serde(rename = "TRIGGER_START")]
    TriggerStart,
    #[serde(rename = "EMIT_ACK")]
    EmitAck,
    #[serde(rename = "ACCEPTED_UPTO")]
    AcceptedUpto,
    #[serde(rename = "CHECKPOINT_ACK")]
    CheckpointAck,
    #[serde(rename = "RECONCILE")]
    Reconcile,
    #[serde(rename = "PAUSE")]
    Pause,
    #[serde(rename = "RESUME")]
    Resume,
    #[serde(rename = "STOP")]
    Stop,
    #[serde(rename = "READY")]
    Ready,
    #[serde(rename = "EMIT")]
    Emit,
    #[serde(rename = "CHECKPOINT")]
    Checkpoint,
    #[serde(rename = "HEALTH")]
    Health,
    #[serde(rename = "HEARTBEAT")]
    Heartbeat,
    #[serde(rename = "EXIT")]
    Exit,
}
/// From reportHealth.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerHealthFrame {
    #[serde(deserialize_with = "const_trigger_health_frame_kind")]
    pub kind: String,
    pub status: TriggerHealthStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}
/// Canonical TriggerHealthStatus wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerHealthStatus {
    #[serde(rename = "HEALTHY")]
    Healthy,
    #[serde(rename = "DEGRADED")]
    Degraded,
    #[serde(rename = "UNHEALTHY")]
    Unhealthy,
}
/// Sent every 10 s by the SDK runtime.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerHeartbeatFrame {
    #[serde(deserialize_with = "const_trigger_heartbeat_frame_kind")]
    pub kind: String,
}
/// One instance a mutation adds, changes or removes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerInstanceChange {
    pub instance_id: String,
    pub executor_id: String,
    pub change: TriggerInstanceChangeKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub old_desired_state: Option<TriggerDesiredState>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub new_desired_state: Option<TriggerDesiredState>,
}
/// Canonical TriggerInstanceChangeKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerInstanceChangeKind {
    #[serde(rename = "Added")]
    Added,
    #[serde(rename = "Updated")]
    Updated,
    #[serde(rename = "Removed")]
    Removed,
    #[serde(rename = "Unchanged")]
    Unchanged,
}
/// Instances of an assignment by phase.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerInstanceCounts {
    pub desired: u64,
    pub pending: u64,
    pub starting: u64,
    pub running: u64,
    pub paused: u64,
    pub stopped: u64,
    pub failed: u64,
    pub unsupported: u64,
    pub stale: u64,
}
/// One page of instances.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerInstancePage {
    pub items: Vec<TriggerInstanceView>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// Executor snapshot entry for one held instance (plan 8.2).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerInstanceReport {
    pub instance_id: String,
    pub config_revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lease_epoch: Option<u64>,
    pub phase: TriggerPhase,
    pub conditions: Vec<TriggerCondition>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_event_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_reconcile_at: Option<String>,
    pub counters: TriggerCounters,
    pub outbox: TriggerOutboxStatus,
}
/// Read-only instance resource (plan 9.2).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerInstanceView {
    pub instance_id: String,
    pub assignment: String,
    pub trigger_id: String,
    pub executor: TriggerExecutor,
    pub executor_id: String,
    pub package_digest: String,
    pub trigger_descriptor_digest: String,
    pub config_revision: u64,
    pub desired_state: TriggerDesiredState,
    pub desired_state_source: String,
    pub phase: TriggerPhase,
    pub conditions: Vec<TriggerCondition>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lease_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_seen_at_unix_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub counters: Option<TriggerCounters>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outbox: Option<TriggerOutboxStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_event_at: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_reconcile_at: Option<String>,
}
/// Canonical TriggerKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerKind {
    #[serde(rename = "SERVER_TRIGGER")]
    ServerTrigger,
    #[serde(rename = "CLIENT_TRIGGER")]
    ClientTrigger,
}
/// One assignment label.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerLabel {
    pub key: String,
    pub value: String,
}
/// Run lease for one instance (plan 7). It authorizes running and reporting only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerLease {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub lease_token: String,
    pub expires_at_unix_ms: u64,
}
/// Canonical TriggerLifecycleAction wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerLifecycleAction {
    #[serde(rename = "Deploy")]
    Deploy,
    #[serde(rename = "Run")]
    Run,
    #[serde(rename = "Pause")]
    Pause,
    #[serde(rename = "Stop")]
    Stop,
    #[serde(rename = "Remove")]
    Remove,
    #[serde(rename = "Clear")]
    Clear,
}
/// Declared per-instance limits.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerLimits {
    pub max_events_per_hour: u64,
    pub max_event_bytes: u64,
    pub max_checkpoint_bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub memory_mi_b: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_broker_calls_per_minute: Option<u64>,
    pub stop_grace_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub emit_timeout_ms: Option<u64>,
}
/// Canonical TriggerMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerMode {
    #[serde(rename = "POLL")]
    Poll,
    #[serde(rename = "PUSH")]
    Push,
}
/// Highest contiguous resolved outboxSeq for one (instance, epoch).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerOutboxAck {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub outbox_seq: u64,
}
/// One outbox record sent to Control, oldest unacknowledged first.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerOutboxRecord {
    pub instance_id: String,
    pub event: TriggerCloudEvent,
}
/// Outbox depth for one instance.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerOutboxStatus {
    pub depth: u64,
    pub oldest_age_ms: u64,
}
/// Canonical TriggerOverflow wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerOverflow {
    #[serde(rename = "BLOCK")]
    Block,
    #[serde(rename = "DROP_OLDEST")]
    DropOldest,
    #[serde(rename = "DROP_NEWEST")]
    DropNewest,
}
/// Pause the instance.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerPauseFrame {
    #[serde(deserialize_with = "const_trigger_pause_frame_kind")]
    pub kind: String,
}
/// Canonical TriggerPhase wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerPhase {
    #[serde(rename = "Pending")]
    Pending,
    #[serde(rename = "Starting")]
    Starting,
    #[serde(rename = "Running")]
    Running,
    #[serde(rename = "Paused")]
    Paused,
    #[serde(rename = "Stopped")]
    Stopped,
    #[serde(rename = "Failed")]
    Failed,
    #[serde(rename = "Unsupported")]
    Unsupported,
    #[serde(rename = "Removed")]
    Removed,
}
/// Producer identity Control attaches from the mTLS certificate, lease and signed package; never from the payload.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerProducer {
    pub kind: TriggerProducerKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub executor_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_groups: Option<Vec<String>>,
    pub assignment: String,
    pub instance_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lease_epoch: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package_digest: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trigger_descriptor_digest: Option<String>,
}
/// Canonical TriggerProducerKind wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerProducerKind {
    #[serde(rename = "DEVICE")]
    Device,
    #[serde(rename = "TOOL_HOST")]
    ToolHost,
    #[serde(rename = "SCHEDULE")]
    Schedule,
}
/// start() completed (push) or config validated (poll).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerReadyFrame {
    #[serde(deserialize_with = "const_trigger_ready_frame_kind")]
    pub kind: String,
}
/// Run reconcile().
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerReconcileFrame {
    #[serde(deserialize_with = "const_trigger_reconcile_frame_kind")]
    pub kind: String,
}
/// Drop the outbox entries of this lease epoch, stop the instance and resync. Records of other epochs are unaffected.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerRejection {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub reason: TriggerRejectionReason,
}
/// Canonical TriggerRejectionReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerRejectionReason {
    #[serde(rename = "UnknownInstance")]
    Unknowninstance,
    #[serde(rename = "NotAssigned")]
    Notassigned,
    #[serde(rename = "StaleLeaseEpoch")]
    Staleleaseepoch,
    #[serde(rename = "StaleConfigRevision")]
    Staleconfigrevision,
}
/// Resume the instance.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerResumeFrame {
    #[serde(deserialize_with = "const_trigger_resume_frame_kind")]
    pub kind: String,
}
/// Retryable rejection; records from fromOutboxSeq are kept and re-sent.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerRetryLater {
    pub instance_id: String,
    pub lease_epoch: u64,
    pub from_outbox_seq: u64,
    pub retry_after_ms: u64,
}
/// Revoked lease.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerRevocation {
    pub instance_id: String,
    pub reason: TriggerRevocationReason,
}
/// Canonical TriggerRevocationReason wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerRevocationReason {
    #[serde(rename = "AssignmentStopped")]
    Assignmentstopped,
    #[serde(rename = "AssignmentRemoved")]
    Assignmentremoved,
    #[serde(rename = "PackageSuspended")]
    Packagesuspended,
    #[serde(rename = "KillSwitch")]
    Killswitch,
    #[serde(rename = "ExecutorDeregistered")]
    Executorderegistered,
    #[serde(rename = "DeviceRevoked")]
    Devicerevoked,
    #[serde(rename = "Compromised")]
    Compromised,
    #[serde(rename = "Reassigned")]
    Reassigned,
}
/// Availability limit for a rolling trigger update.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerRollingUpdate {
    pub max_unavailable: String,
}
/// Client package and configuration rollout strategy.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerRolloutStrategy {
    pub rolling_update: TriggerRollingUpdate,
}
/// POLL interval or PUSH resync interval.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSchedule {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub interval_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resync_interval_ms: Option<u64>,
}
/// Tenant trigger settings (GET and PUT /api/control/v1/triggers/settings).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSettings {
    pub event_retention_days: u64,
    pub default_classification: TriggerDefaultClassifications,
}
/// Declared SnapshotState bounds (plan 5.6).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSnapshotCapacity {
    pub capacity: u64,
    pub max_key_bytes: u64,
}
/// One page of a frozen executor snapshot (plan 8.5). Pages from different generations are never combined.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSnapshotPage {
    pub snapshot_generation: u64,
    pub snapshot_digest: String,
    pub page: u64,
    pub page_count: u64,
    pub page_digest: String,
    pub instances: Vec<TriggerInstanceReport>,
}
/// Acknowledged snapshot page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSnapshotPageAck {
    pub snapshot_generation: u64,
    pub page: u64,
}
/// Canonical TriggerSourceReplay wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerSourceReplay {
    #[serde(rename = "EVENT_LOG")]
    EventLog,
    #[serde(rename = "STATE_SNAPSHOT")]
    StateSnapshot,
    #[serde(rename = "NON_REPLAYABLE")]
    NonReplayable,
}
/// First stdin line, exactly once.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStartFrame {
    #[serde(deserialize_with = "const_trigger_start_frame_kind")]
    pub kind: String,
    pub trigger_id: String,
    pub instance_id: String,
    pub config_revision: u64,
    pub lease_epoch: u64,
    pub config: std::collections::BTreeMap<String, serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub globals: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secret_handles: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checkpoint: Option<String>,
}
/// Graceful stop.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStopFrame {
    #[serde(deserialize_with = "const_trigger_stop_frame_kind")]
    pub kind: String,
    pub reason: String,
    pub grace_ms: u64,
}
/// One record Control stored for an instance (plan 6.4): the event as the executor sent it, the producer Control attached from the certificate, the lease and the signed package, and when Control received it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStoredEvent {
    pub event: TriggerCloudEvent,
    pub producer: TriggerProducer,
    pub received_at_unix_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disposition: Option<TriggerDispositionView>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classification: Option<TriggerClassification>,
}
/// A client message on `toolgate.triggers.v1`. subscribe: subscription, optional resumeAfter. watch: sources, optional filter. ack: subscription, cursor. credit: n. unsubscribe: subscription.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStreamClientMessage {
    pub r#type: TriggerStreamClientType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subscription: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume_after: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sources: Option<TriggerSubscriptionSources>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub filter: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub n: Option<u64>,
}
/// Canonical TriggerStreamClientType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerStreamClientType {
    #[serde(rename = "subscribe")]
    Subscribe,
    #[serde(rename = "watch")]
    Watch,
    #[serde(rename = "ack")]
    Ack,
    #[serde(rename = "credit")]
    Credit,
    #[serde(rename = "unsubscribe")]
    Unsubscribe,
}
/// An error on the stream, with the status and code an HTTP call would get.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStreamError {
    pub status: u64,
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}
/// A server message on `toolgate.triggers.v1`. subscribed: subscription. event: subscription (absent for watch), cursor, event. gap: subscription, fromCursor, toCursor, reason RetentionExpired. revoked: subscription, reason. error: error.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStreamServerMessage {
    pub r#type: TriggerStreamServerType,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subscription: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub event: Option<TriggerViewEvent>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub from_cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub to_cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<TriggerStreamError>,
}
/// Canonical TriggerStreamServerType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerStreamServerType {
    #[serde(rename = "subscribed")]
    Subscribed,
    #[serde(rename = "event")]
    Event,
    #[serde(rename = "gap")]
    Gap,
    #[serde(rename = "revoked")]
    Revoked,
    #[serde(rename = "error")]
    Error,
}
/// A single-use WebSocket ticket (plan 13.2), valid for 30 s, sent as the subprotocol `ticket.` followed by the ticket value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerStreamTicket {
    pub ticket: String,
    pub expires_at_unix_ms: u64,
}
/// Subscription resource.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscription {
    #[serde(deserialize_with = "const_trigger_subscription_api_version")]
    pub api_version: String,
    #[serde(deserialize_with = "const_trigger_subscription_kind")]
    pub kind: String,
    pub metadata: TriggerAssignmentMetadata,
    pub spec: TriggerSubscriptionSpec,
    pub status: TriggerSubscriptionStatus,
}
/// Body of POST /api/control/v1/triggers/subscriptions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscriptionCreate {
    pub name: String,
    pub spec: TriggerSubscriptionSpec,
}
/// One page of subscriptions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscriptionPage {
    pub items: Vec<TriggerSubscription>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
}
/// Canonical TriggerSubscriptionPhase wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerSubscriptionPhase {
    #[serde(rename = "Active")]
    Active,
    #[serde(rename = "Suspended")]
    Suspended,
    #[serde(rename = "Expired")]
    Expired,
}
/// Explicit sources of a subscription (plan 13.1); there is no wildcard. Control refuses a source listed twice.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscriptionSources {
    pub assignments: Vec<String>,
}
/// A durable socket subscription (plan 13.1).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscriptionSpec {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub owner: Option<String>,
    pub sources: TriggerSubscriptionSources,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub filter: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub include_state: Option<bool>,
    pub expires_at_unix_ms: u64,
}
/// Subscription status.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSubscriptionStatus {
    pub phase: TriggerSubscriptionPhase,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    pub clearance: TriggerClassification,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub acked_cursor: Option<String>,
}
/// Trigger block of every executor check-in (plan 8.2, 8.5).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSync {
    pub observed_generation: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot: Option<TriggerSnapshotPage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub checkpoints: Option<Vec<TriggerCheckpointReport>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub events: Option<Vec<TriggerOutboxRecord>>,
    pub pending: u64,
}
/// Trigger block of the check-in ack (plan 8.3, 8.5).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerSyncAck {
    pub generation: u64,
    pub max_check_in_bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub check_in_now: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub acked: Option<Vec<TriggerOutboxAck>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dispositions: Option<Vec<TriggerDisposition>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_later: Option<Vec<TriggerRetryLater>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub leases: Option<Vec<TriggerLease>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub revoked: Option<Vec<TriggerRevocation>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rejected: Option<Vec<TriggerRejection>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot_pages: Option<Vec<TriggerSnapshotPageAck>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub snapshot_requested: Option<bool>,
}
/// Where an assignment runs (plan 9.1). CLIENT: devices and Device Groups, minus excluded devices. Entries are unique; Control refuses repeats.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerTarget {
    pub executor: TriggerExecutor,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub devices: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_groups: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exclude_devices: Option<Vec<String>>,
}
/// Canonical TriggerTerminalRejectionPolicy wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TriggerTerminalRejectionPolicy {
    #[serde(rename = "HALT")]
    Halt,
    #[serde(rename = "SKIP_AND_RECORD")]
    SkipAndRecord,
}
/// A routed event as one reader may see it (plan 12.2 rule 5): the envelope always; data and subject only when the reader's clearance covers the event's label.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TriggerViewEvent {
    #[serde(deserialize_with = "const_trigger_view_event_specversion")]
    pub specversion: String,
    pub id: String,
    pub source: String,
    pub r#type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub time: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<std::collections::BTreeMap<String, serde_json::Value>>,
    pub tgclassification: TriggerClassification,
    pub tgredacted: bool,
}
/// Canonical ValueSource wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ValueSource {
    #[serde(rename = "ENVIRONMENT")]
    Environment,
    #[serde(rename = "DATABASE")]
    Database,
    #[serde(rename = "DEFAULT")]
    Default,
}
/// Canonical ValueType wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ValueType {
    #[serde(rename = "STRING")]
    String,
    #[serde(rename = "INT")]
    Int,
    #[serde(rename = "LONG")]
    Long,
    #[serde(rename = "NUMBER")]
    Number,
    #[serde(rename = "BOOLEAN")]
    Boolean,
    #[serde(rename = "DATE")]
    Date,
    #[serde(rename = "DATE_TIME")]
    DateTime,
    #[serde(rename = "DURATION")]
    Duration,
    #[serde(rename = "ENUM")]
    Enum,
    #[serde(rename = "URI")]
    Uri,
    #[serde(rename = "OBJECT")]
    Object,
    #[serde(rename = "ARRAY")]
    Array,
    #[serde(rename = "BINARY")]
    Binary,
}
/// Profile declaration for WorkflowAgentBinding; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowAgentBinding {
    pub agent_id: String,
    pub agent_group_id: String,
    pub entry_eligible: bool,
    pub delegation_allowed: bool,
}
/// Profile declaration for WorkflowAgentDefinition; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowAgentDefinition {
    pub agent_id: String,
    pub model_group_id: String,
    pub system_prompt: String,
    pub context_sources: Vec<WorkflowContextSource>,
    pub output_schema: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Profile declaration for WorkflowConfiguration; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowConfiguration {
    pub workflow_groups: Vec<WorkflowGroupDefinition>,
    pub model_groups: Vec<WorkflowModelGroupDefinition>,
    pub agent_definitions: Vec<WorkflowAgentDefinition>,
    pub workflows: Vec<WorkflowDefinition>,
    pub dispatch_bindings: Vec<WorkflowDispatchBinding>,
    pub model_deployments: Vec<WorkflowModelDeployment>,
}
/// Profile declaration for WorkflowContextSource; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowContextSource {
    pub tool_id: String,
    pub arguments: std::collections::BTreeMap<String, serde_json::Value>,
}
/// Profile declaration for WorkflowDefinition; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowDefinition {
    pub id: String,
    pub version_id: String,
    pub workflow_group_id: String,
    pub model_group_id: String,
    pub system_prompt: String,
    pub context_sources: Vec<WorkflowContextSource>,
    pub parameter_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub result_schema: std::collections::BTreeMap<String, serde_json::Value>,
    pub limits: WorkflowExecutionLimits,
    pub agents: Vec<WorkflowAgentBinding>,
}
/// Profile declaration for WorkflowDispatchBinding; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowDispatchBinding {
    pub id: String,
    pub subscription_uid: String,
    pub source_assignment_uid: String,
    pub workflow_id: String,
    pub workflow_version_id: String,
    pub service_agent_id: String,
    pub service_agent_group_id: String,
    pub message_pointer: String,
    pub remediation_mode: WorkflowRemediationMode,
}
/// Canonical WorkflowDispatchDisposition wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum WorkflowDispatchDisposition {
    #[serde(rename = "ADMITTED")]
    Admitted,
    #[serde(rename = "DUPLICATE")]
    Duplicate,
    #[serde(rename = "REJECTED")]
    Rejected,
    #[serde(rename = "FILTERED")]
    Filtered,
}
/// Durable admission evidence bound to this subscription UID and delivered cursor. ADMITTED requires runId; DUPLICATE returns its original durable outcome reference and runId when one exists. Terminal rejection/filtering is recorded, never inferred locally.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowDispatchReceipt {
    pub subscription_uid: String,
    pub cursor: String,
    pub admission_id: String,
    pub disposition: WorkflowDispatchDisposition,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub run_id: Option<String>,
}
/// A delivered subscription cursor for server-side workflow admission. Caller/event fields cannot select tenant, workflow, version, identity or prompts.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowDispatchRequest {
    pub subscription_uid: String,
    pub cursor: String,
}
/// Profile declaration for WorkflowExecutionLimits; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowExecutionLimits {
    pub max_steps: u64,
    pub max_handoffs: u64,
    pub max_duration_seconds: u64,
}
/// Profile declaration for WorkflowGroupDefinition; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowGroupDefinition {
    pub id: String,
    pub display_name: String,
}
/// An explicitly assigned model deployment. Only server-owned TLS origin, model name and vault reference are configuration; no provider key reaches a runner.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowModelDeployment {
    pub id: String,
    pub origin: String,
    pub model_id: String,
    pub secret_name: String,
    pub tool_group_id: String,
    pub device_group_id: String,
}
/// Profile declaration for WorkflowModelGroupDefinition; eligibility and configuration confer no runtime authority.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowModelGroupDefinition {
    pub id: String,
    pub deployment_ids: Vec<String>,
}
/// Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowProfilePublishRequest {
    pub profile_digest: String,
    pub expected_generation: u64,
}
/// Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowProfileReviewRequest {
    pub profile_digest: String,
}
/// Canonical WorkflowProfileState wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum WorkflowProfileState {
    #[serde(rename = "DRAFT")]
    Draft,
    #[serde(rename = "REVIEWED")]
    Reviewed,
    #[serde(rename = "PUBLISHED")]
    Published,
}
/// Workflow profile lifecycle metadata; publication requires current authorization, reviewed content and dependency readiness.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkflowProfileStatus {
    pub profile_id: String,
    pub profile_digest: String,
    pub state: WorkflowProfileState,
    pub generation: u64,
    pub dependencies_ready: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reviewed_by: Option<String>,
}
/// Canonical WorkflowRemediationMode wire values.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum WorkflowRemediationMode {
    #[serde(rename = "PREPARE")]
    Prepare,
    #[serde(rename = "APPLY")]
    Apply,
}
const_field!(const_broker_request_frame_kind, String, "BROKER_REQUEST");
const_field!(const_broker_response_frame_kind, String, "BROKER_RESPONSE");
const_field!(
    const_console_client_request_kind,
    String,
    "console-tenant-state"
);
const_field!(const_contract_set_name, String, "olo-toolgate-contracts");
const_field!(const_control_server_settings_format_version, u64, 2);
const_field!(const_control_settings_view_format_version, u64, 2);
const_field!(const_deployment_binding_binding_format_version, u64, 1);
const_field!(const_deployment_profile_profile_version, u64, 2);
const_field!(const_discovery_header_alg, String, "RS256");
const_field!(const_discovery_header_typ, String, "toolgate-discovery+jws");
const_field!(const_discovery_public_key_kty, String, "RSA");
const_field!(const_discovery_public_key_use, String, "sig");
const_field!(const_discovery_public_key_alg, String, "RS256");
const_field!(const_discovery_public_key_e, String, "AQAB");
const_field!(const_effect_consent_mapped_request_scheme, String, "https");
const_field!(const_input_request_frame_kind, String, "INPUT_REQUEST");
const_field!(const_kill_trust_key_e, String, "AQAB");
const_field!(const_log_frame_kind, String, "LOG");
const_field!(const_mcp_proxy_document_format_version, u64, 1);
const_field!(const_mcp_scan_claims_iss, String, "control");
const_field!(const_package_manifest_schema_version, u64, 2);
const_field!(const_permit_header_alg, String, "RS256");
const_field!(
    const_permit_header_typ,
    String,
    "toolgate-invocation-permit+jws"
);
const_field!(const_progress_frame_kind, String, "PROGRESS");
const_field!(const_recovery_event_issuer, String, "control");
const_field!(
    const_recovery_preflight_page_build_mode,
    String,
    "CURRENT_ONLY"
);
const_field!(const_result_frame_kind, String, "RESULT");
const_field!(const_tenant_discovery_protocol_version, u64, 2);
const_field!(const_tool_descriptor_descriptor_generation, u64, 1);
const_field!(const_tool_input_protocol_version, u64, 2);
const_field!(
    const_trigger_accepted_up_to_frame_kind,
    String,
    "ACCEPTED_UPTO"
);
const_field!(
    const_trigger_assignment_api_version,
    String,
    "toolgate.olo/v1"
);
const_field!(const_trigger_assignment_kind, String, "TriggerAssignment");
const_field!(
    const_trigger_checkpoint_ack_frame_kind,
    String,
    "CHECKPOINT_ACK"
);
const_field!(const_trigger_checkpoint_frame_kind, String, "CHECKPOINT");
const_field!(const_trigger_checkpoint_upload_chunk_bytes, u64, 65536);
const_field!(const_trigger_cloud_event_specversion, String, "1.0");
const_field!(
    const_trigger_cloud_event_datacontenttype,
    String,
    "application/json"
);
const_field!(const_trigger_desired_document_format_version, u64, 1);
const_field!(const_trigger_emit_ack_frame_kind, String, "EMIT_ACK");
const_field!(const_trigger_emit_frame_kind, String, "EMIT");
const_field!(const_trigger_exit_frame_kind, String, "EXIT");
const_field!(const_trigger_health_frame_kind, String, "HEALTH");
const_field!(const_trigger_heartbeat_frame_kind, String, "HEARTBEAT");
const_field!(const_trigger_pause_frame_kind, String, "PAUSE");
const_field!(const_trigger_ready_frame_kind, String, "READY");
const_field!(const_trigger_reconcile_frame_kind, String, "RECONCILE");
const_field!(const_trigger_resume_frame_kind, String, "RESUME");
const_field!(const_trigger_start_frame_kind, String, "TRIGGER_START");
const_field!(const_trigger_stop_frame_kind, String, "STOP");
const_field!(
    const_trigger_subscription_api_version,
    String,
    "toolgate.olo/v1"
);
const_field!(
    const_trigger_subscription_kind,
    String,
    "TriggerSubscription"
);
const_field!(const_trigger_view_event_specversion, String, "1.0");
/// Embedded canonical schemas for offline boundary validation.
pub const CANONICAL_SCHEMAS: &[(&str, &str)] = &[
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
        "https://schemas.ololabs.io/toolgate/v2/effect-authorization.schema.json",
        include_str!("../schemas/v2/effect-authorization.schema.json"),
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
    (
        "https://schemas.ololabs.io/toolgate/v2/trigger.schema.json",
        include_str!("../schemas/v2/trigger.schema.json"),
    ),
    (
        "https://schemas.ololabs.io/toolgate/v2/workflow.schema.json",
        include_str!("../schemas/v2/workflow.schema.json"),
    ),
];
