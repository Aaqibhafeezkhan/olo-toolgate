# Contract set v2 schemas (frozen at design gate D1)

## Purpose

Canonical v2 wire definitions for the Tool SDK and governed tool platform. They are **frozen design artifacts**: after D1 they change only through an amendment to the ADR that owns them, which reopens D1. [Invocation state v2: tasks, ledger, business keys](../../../../docs/control-plane/invocation-state-v2.md) amended the set at gate D2 with additive definitions and optional members only (`InvocationSubmission`, `InvocationAdmission`, `TaskCapability`, `TaskView`, `TaskInputSubmission`, `PermitReservationRequest`, `LeaseRenewal`, plus `LeaseHeartbeat.progress`, `LeaseHeartbeat.inputRequest` and `InvocationResultReport.notStarted`).

The v2 set is self-contained: no `$ref` points into `v1`. Since contract set `1.0.0` the v1 schemas reference the v2 request context (`RequestContext`, `CredentialKind`, `RequestMode`, `ActorHop`) instead of defining their own, and the Gateway and Control use those v2 types. Bindings for v2 are generated side by side with v1 as M1 part 1 implemented them from [docs/contracts/v2-generation.md](../../../../docs/development/schema-workflow.md): Rust module `olo_toolgate_contracts::v2`, Java package `io.ololabs.toolgate.contracts.v2`, TypeScript subpath `@olo-labs/toolgate-contracts/v2`, PHP namespace `OloLabs\ToolGate\Contracts\V2`. Fixtures are in [tests/fixtures/contracts/v2](../../../../tests/fixtures/contracts/v2/) and are checked by `tests/contracts/test_contracts_v2.py`.

| File | Main definitions | Owning decision |
|---|---|---|
| `identifiers.schema.json` | `ToolId`, `PackageId`, `Digest` (`sha256:` prefixed), `VariableName`, `Slug` | [Semantic tool descriptor, generation 1](../../../../tool-sdk/spec/descriptor/README.md), [Authoring contract (language-neutral)](../../../../tool-sdk/spec/authoring/README.md) |
| `common.schema.json` | `RequestContext` v2 (no device), `ContractSet` | [OLO user credentials](../../../../docs/control-plane/olo-user-credentials.md) |
| `descriptor.schema.json` | `ToolDescriptor` (semantic descriptor, generation 1) | [Authoring contract (language-neutral)](../../../../tool-sdk/spec/authoring/README.md), [tool-sdk/spec/descriptor](../../../../tool-sdk/spec/descriptor/README.md) |
| `package.schema.json` | `PackageManifest` v2, `ToolDefinition` v2, `PackageVersion`, `PackageReview` | [Semantic tool descriptor, generation 1](../../../../tool-sdk/spec/descriptor/README.md) |
| `catalog.schema.json` | `CatalogScope` | [OLO user credentials](../../../../docs/control-plane/olo-user-credentials.md) |
| `permit.schema.json` | `InvocationPermitClaims` v2, `PermitConsumption`, `LeaseHeartbeat` | [Tool Host](../../../../apps/tool-host/README.md) (D2) |
| `invocation.schema.json` | `InvocationTask`, `TaskState`, business-key reservation | [Invocation state v2: tasks, ledger, business keys](../../../../docs/control-plane/invocation-state-v2.md) (D2) |
| `kill.schema.json` | `KillEvent`, `RecoveryEvent`, `KillPush`, `BreakGlassRequest` | [Emergency kill switch](../../../../docs/control-plane/kill-switch.md) (D2) |
| `service-profile.schema.json` | `ServiceProfile` | [Tool Host](../../../../apps/tool-host/README.md) (D2) |
| `deployment-binding.schema.json` | `DeploymentBinding`, `EffectiveDeployment` | [Runtime modes](../../../../tool-sdk/spec/runtime-modes.md) |
| `protocol.schema.json` | Tool protocol v2 frames; `Dispatch`, `AdmissionResponse`, `ResultMessage` | [Semantic tool descriptor, generation 1](../../../../tool-sdk/spec/descriptor/README.md), [Tool Host](../../../../apps/tool-host/README.md) (D2) |
| `settings.schema.json` | `ControlServerSettings` format version 2, `ClientCheckIn` | [Console Administration menu](../../../../docs/control-plane/configuration-menu.md) |
| `profile.schema.json` | `DeploymentProfile`, `ProvisioningStatus`: pinned deployment-time package provisioning | [Deployment profiles](../../../../docs/deployment/profiles.md) |
| `discovery.schema.json` | `SignedTenantDiscovery`, `TenantDiscovery`, protected JWS header, public key ring, tenant device authorities and console-scoped native requests | [Tenant discovery](../../../../docs/control-plane/discovery-v2.md) |
| `mcp-proxy.schema.json` | `McpServerRegistration`, `McpSnapshot`, `McpScanClaims`, `McpApproval`, `McpProxyDocument`, `McpQuarantineReport`: upstream MCP servers governed as generated `MCP_PROXY` packages | [Governing existing MCP servers](../../../../docs/gateway/mcp-proxy.md) |
| `insights.schema.json` | `InsightsAdmissionBatch` (Gateway to Control) and the operator query API: `InsightsStatus`, `InsightsSeries`, `InsightsInvocationPage`, `InsightsInvocationDetail`, `InsightsRebuildRequest` | [Operator Insights](../../../../docs/control-plane/insights.md) |
| `trigger.schema.json` | `TriggerDescriptor`, trigger-mode protocol frames, `TriggerCloudEvent`, `TriggerDesiredDocument`, `TriggerSync`, `TriggerSyncAck`, checkpoint transfer | [Trigger architecture](../../../../docs/architecture/triggers.md) |

Definitions owned by a D2 ADR are written here because D1 freezes the wire shapes; D2 reviews their trust and persistence semantics and may amend them before D2 passes.
