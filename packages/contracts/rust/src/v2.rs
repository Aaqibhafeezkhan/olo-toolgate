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
}
/// Effective and superseded events.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KillEventPage {
    pub kills: Vec<KillEvent>,
    pub recoveries: Vec<RecoveryEvent>,
    pub authorization_epoch: u64,
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
/// Package manifest v2. v1 manifests stay readable and are upgraded on read.
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
/// Successful consumption.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitConsumed {
    pub invocation_id: String,
    pub fencing_token: String,
    pub lease_expires_at_unix_ms: u64,
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
/// One package the profile requires, pinned to an exact version. A MARKETPLACE entry must also pin its package digest; a PREBUILT_CORE entry resolves its digest from the sealed image lock, and a declared digest must equal it. hostIds names the Tool Hosts that receive its eligible ServerTool exports; ClientTool exports never deploy to a Tool Host. deviceGroupIds names the device groups whose active, approved devices receive its device-runnable exports as a signed client release (ADR 026), including devices that join later. An entry that activates exports needs hostIds or deviceGroupIds. bindingIds have their allowed package digests set to exactly the profile packages that name them.
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
}
/// Same shape as the declared route.
pub type ServiceRoute = RouteDeclaration;
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
const_field!(const_deployment_profile_profile_version, u64, 1);
const_field!(const_discovery_header_alg, String, "RS256");
const_field!(const_discovery_header_typ, String, "toolgate-discovery+jws");
const_field!(const_discovery_public_key_kty, String, "RSA");
const_field!(const_discovery_public_key_use, String, "sig");
const_field!(const_discovery_public_key_alg, String, "RS256");
const_field!(const_discovery_public_key_e, String, "AQAB");
const_field!(const_input_request_frame_kind, String, "INPUT_REQUEST");
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
const_field!(const_result_frame_kind, String, "RESULT");
const_field!(const_tenant_discovery_protocol_version, u64, 2);
const_field!(const_tool_descriptor_descriptor_generation, u64, 1);
const_field!(const_tool_input_protocol_version, u64, 2);
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
        "https://schemas.ololabs.io/toolgate/v2/identifiers.schema.json",
        include_str!("../schemas/v2/identifiers.schema.json"),
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
