# Contract set v2 schemas (frozen at design gate D1)

## Purpose

Canonical v2 wire definitions for the Tool SDK and governed tool platform. They are **frozen design artifacts**: nothing consumes them until milestone M1, and after D1 they change only through an amendment to the ADR that owns them, which reopens D1. ADR 016 amended the set at gate D2 with additive definitions and optional members only (`InvocationSubmission`, `InvocationAdmission`, `TaskCapability`, `TaskView`, `TaskInputSubmission`, `PermitReservationRequest`, `LeaseRenewal`, plus `LeaseHeartbeat.progress`, `LeaseHeartbeat.inputRequest` and `InvocationResultReport.notStarted`).

The v2 set is self-contained: no `$ref` points into `v1`. Bindings for v2 are not generated yet; [docs/contracts/v2-generation.md](../../../../docs/contracts/v2-generation.md) is the generator design M1 implements. Fixtures are in [tests/fixtures/contracts/v2](../../../../tests/fixtures/contracts/v2/) and are checked by `tests/contracts/test_contracts_v2.py`.

| File | Main definitions | Owning decision |
|---|---|---|
| `identifiers.schema.json` | `ToolId`, `PackageId`, `Digest` (`sha256:` prefixed), `VariableName`, `Slug` | ADR 014, ADR 019 |
| `common.schema.json` | `RequestContext` v2 (no device), `ContractSet` | ADR 015 |
| `descriptor.schema.json` | `ToolDescriptor` (semantic descriptor, generation 1) | ADR 019, [tool-sdk/spec/descriptor](../../../../tool-sdk/spec/descriptor/README.md) |
| `package.schema.json` | `PackageManifest` v2, `ToolDefinition` v2, `PackageVersion`, `PackageReview` | ADR 014 |
| `catalog.schema.json` | `CatalogScope` | ADR 015 |
| `permit.schema.json` | `InvocationPermitClaims` v2, `PermitConsumption`, `LeaseHeartbeat` | ADR 013 (D2) |
| `invocation.schema.json` | `InvocationTask`, `TaskState`, business-key reservation | ADR 016 (D2) |
| `kill.schema.json` | `KillEvent`, `RecoveryEvent`, `KillPush`, `BreakGlassRequest` | ADR 017 (D2) |
| `service-profile.schema.json` | `ServiceProfile` | ADR 013 (D2) |
| `deployment-binding.schema.json` | `DeploymentBinding`, `EffectiveDeployment` | ADR 020 |
| `protocol.schema.json` | Tool protocol v2 frames; `Dispatch`, `AdmissionResponse`, `ResultMessage` | ADR 014, ADR 013 (D2) |
| `settings.schema.json` | `ControlServerSettings` format version 2, `ClientCheckIn` | ADR 021 |

Definitions owned by a D2 ADR are written here because D1 freezes the wire shapes; D2 reviews their trust and persistence semantics and may amend them before D2 passes.
