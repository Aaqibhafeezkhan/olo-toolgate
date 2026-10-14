# Requirements Traceability

## Purpose

Prevent requirements from being silently missed.

Every module must mark each applicable requirement as:

```text
COMPLETE
NOT_APPLICABLE — with reason
BLOCKED — module cannot be declared complete
```

## Architecture

| ID | Requirement |
|---|---|
| ARC-001 | Respect documented component ownership. |
| ARC-002 | Maintain low coupling/high cohesion. |
| ARC-003 | Use dependency inversion at external boundaries. |
| ARC-004 | No duplicated cross-component contract models. |
| ARC-005 | Preserve future split-repo readiness. |
| ARC-006 | Use explicit state machines for lifecycle workflows. |
| ARC-007 | Stateless production app containers where architecture requires. |

## Contracts

| ID | Requirement |
|---|---|
| CON-001 | Canonical contract source is language-neutral. |
| CON-002 | Java contracts publish as `io.github.olo-labs:toolgate-contracts`. |
| CON-003 | Workspace and published dependency modes both work. |
| CON-004 | Contract compatibility tests exist. |
| CON-005 | Generated bindings are reproducible and drift-checked. |
| CON-006 | Contract release is SemVer/versioned. |

## Security

| ID | Requirement |
|---|---|
| SEC-001 | Default deny/fail closed. |
| SEC-002 | No secret logging/export. |
| SEC-003 | Signature/hash validation cannot be bypassed. |
| SEC-004 | Marketplace trust != organization deployment trust. |
| SEC-005 | Organization deployment trust != runtime authorization. |
| SEC-006 | Untrusted code is sandboxed. |
| SEC-007 | SSRF/path/shell/archive attacks handled where applicable. |
| SEC-008 | Key domains remain separate. |
| SEC-009 | Security mutation is audited. |
| SEC-010 | Replay/idempotency protections exist where required. |

## Testing

| ID | Requirement |
|---|---|
| TST-001 | Unit tests. |
| TST-002 | Negative/error-path tests. |
| TST-003 | Integration tests at real boundaries. |
| TST-004 | Contract tests. |
| TST-005 | Security tests. |
| TST-006 | E2E coverage updated. |
| TST-007 | Performance test for performance-sensitive path. |
| TST-008 | No flaky timing-based correctness. |

## Code Quality

| ID | Requirement |
|---|---|
| QLT-001 | Apache-2.0 SPDX/copyright headers. |
| QLT-002 | Public/security-sensitive code documented. |
| QLT-003 | Static/lint/type checks pass. |
| QLT-004 | Dependency use justified/centralized. |
| QLT-005 | No god classes/service locator/global mutable correctness state. |
| QLT-006 | Stable typed error model. |

## APIs

| ID | Requirement |
|---|---|
| API-001 | Versioned API. |
| API-002 | OpenAPI updated/generated where applicable. |
| API-003 | Input validation. |
| API-004 | Stable error codes. |
| API-005 | Request/correlation ID. |
| API-006 | Idempotency on documented mutation APIs. |
| API-007 | No stack trace/secret exposure. |

## Data

| ID | Requirement |
|---|---|
| DAT-001 | DB schema via migrations. |
| DAT-002 | Clean install migration tested. |
| DAT-003 | Upgrade migration tested where applicable. |
| DAT-004 | Transaction/concurrency semantics explicit. |
| DAT-005 | Persistent state externalized in production. |

## Observability

| ID | Requirement |
|---|---|
| OBS-001 | Health endpoint/state. |
| OBS-002 | Readiness endpoint/state. |
| OBS-003 | Structured logs. |
| OBS-004 | Metrics. |
| OBS-005 | Trace/correlation propagation. |
| OBS-006 | Async jobs expose explicit durable status. |
| OBS-007 | Sensitive values excluded from telemetry. |

## Docker

| ID | Requirement |
|---|---|
| DKR-001 | Multi-stage image. |
| DKR-002 | Minimal runtime image. |
| DKR-003 | Non-root runtime. |
| DKR-004 | No embedded secrets. |
| DKR-005 | OCI/version labels. |
| DKR-006 | SBOM/image scan pipeline. |
| DKR-007 | Graceful shutdown. |
| DKR-008 | Read-only-root compatibility where practical. |

## Kubernetes / Helm

| ID | Requirement |
|---|---|
| K8S-001 | Helm template exists for deployable service. |
| K8S-002 | Configurable resources. |
| K8S-003 | Security contexts. |
| K8S-004 | Probes. |
| K8S-005 | ServiceAccount. |
| K8S-006 | NetworkPolicy. |
| K8S-007 | PDB where replicated. |
| K8S-008 | HPA where horizontally scalable. |
| K8S-009 | topology/affinity options. |
| K8S-010 | external secret references. |
| K8S-011 | optional ingress. |
| K8S-012 | optional ServiceMonitor/metrics. |
| K8S-013 | `helm lint` passes. |
| K8S-014 | rendered manifests validate. |
| K8S-015 | install/upgrade smoke test in local cluster. |
| K8S-016 | rollback behavior documented/tested where applicable. |

## Release

| ID | Requirement |
|---|---|
| REL-001 | Maven contract publication pipeline. |
| REL-002 | Container publication pipeline. |
| REL-003 | Helm OCI publication pipeline. |
| REL-004 | GitHub Release assets. |
| REL-005 | SHA-256 checksums. |
| REL-006 | SBOM. |
| REL-007 | provenance/attestation. |
| REL-008 | signatures when configured. |
| REL-009 | release notes/changelog. |
| REL-010 | compatibility matrix. |
| REL-011 | upgrade notes. |

## Documentation

| ID | Requirement |
|---|---|
| DOC-001 | Contributor/user docs updated. |
| DOC-002 | Component README updated. |
| DOC-003 | Config documented. |
| DOC-004 | API documented. |
| DOC-005 | Architecture/ADR updated if needed. |
| DOC-006 | Examples contain no real secrets. |
| DOC-007 | Operational/troubleshooting docs updated. |

## Performance / Scale

| ID | Requirement |
|---|---|
| PRF-001 | No DB in normal Gateway authorization path. |
| PRF-002 | Resource use bounded. |
| PRF-003 | Horizontal scalability preserved where required. |
| PRF-004 | Batch/async behavior used for fleet-scale reporting. |
| PRF-005 | Benchmark methodology documented. |
| PRF-006 | Backpressure/limits exist for queues/uploads/jobs. |

## Module Completion Table Template

Copy into the module PR/commit note:

```text
| Requirement | Status | Evidence |
|---|---|---|
| ARC-001 | COMPLETE | ... |
| SEC-001 | COMPLETE | ... |
| K8S-001 | NOT_APPLICABLE | library-only module |
...
```

A `BLOCKED` requirement means the module is not DONE.

## Module evidence

- Module 00: [pre-implementation coverage plan](modules/00-coverage.md) and
  [completion matrix and verification evidence](modules/00-completion.md).
  The foundation classifies all 104 unique IDs: 49 COMPLETE and 55 NOT_APPLICABLE
  with scope reasons. Remote publication is configured for protected CI only.
- Module 01: [pre-implementation coverage plan](modules/01-coverage.md) and
  [completion matrix and verification evidence](modules/01-completion.md).
  Gateway Core classifies all 104 unique IDs: 87 COMPLETE and 17 NOT_APPLICABLE
  with scope reasons. The three template example rows above are not extra
  requirements. Image/Helm publication remains protected CI only.
- Module 02: [pre-implementation coverage plan](modules/02-coverage.md) and
  [completion matrix and verification evidence](modules/02-completion.md).
  Control Plane Backend classifies all 104 unique IDs: 95 COMPLETE and 9
  NOT_APPLICABLE with scope reasons. Real PostgreSQL/HTTP/OTLP, container scan
  and two-replica Kind install/upgrade/rollback pass. Remote publication remains
  protected CI only.
- Module 03: [pre-implementation coverage plan](modules/03-coverage.md) and
  [completion matrix and verification evidence](modules/03-completion.md).
  Admin UI Foundation classifies all 104 unique IDs: 88 COMPLETE and 16
  NOT_APPLICABLE with scope reasons. Real PostgreSQL/Control browser E2E,
  accessibility, production embedding, scans and Kind upgrade/rollback pass.
  Remote publication remains protected CI only.

- Module 04: [pre-implementation coverage plan](modules/04-coverage.md) and
  [completion matrix and verification evidence](modules/04-completion.md).
  Signed policy bundles classify all 104 unique IDs: 101 COMPLETE and 3
  NOT_APPLICABLE with scope reasons. Real JCA/ring/PostgreSQL production-image
  E2E, rotation/outage/expiry/recovery, local Maven proof, scans/SBOMs and
  two-replica signed publication/Helm upgrade/rollback pass. Protected releases
  require the new policy compatibility gate; no remote publication was performed.

- Module 05: [pre-implementation coverage plan](modules/05-coverage.md) and
  [completion matrix and verification evidence](modules/05-completion.md).
  ASK approvals classify all 104 unique IDs: 102 COMPLETE and two NOT_APPLICABLE.
  Real PostgreSQL/production signed-permit E2E, browser accessibility, local Maven,
  scans/SBOMs and two-replica upgrade/rollback pass. Protected release compatibility
  gates include approval behavior; remote publication remains CI only.

- Module 06: [coverage plan](modules/06-coverage.md) and [verification report](modules/06-completion.md). In progress: 101 BLOCKED and 3 NOT_APPLICABLE; commit records current work without claiming definition-of-done completion.

- Module 07: [pre-implementation coverage](modules/07-coverage.md) and
  [verification report](modules/07-completion.md). Implementation includes fixed
  HotFolder/built-ins, fresh Gateway protection, system-service logout independence,
  and anonymous three-OS downloads. There are 99 COMPLETE, two BLOCKED and three
  NOT_APPLICABLE IDs; native service-manager CI must execute before declaring DONE.

- Module 08: [pre-implementation coverage](modules/08-coverage.md) and
  [verification report](modules/08-completion.md). Managed local OCI runtimes and
  first-use provisioning are implemented; Batch/CMD and native OS/ARM runtime
  certification remain BLOCKED. Its historical report records the Module 08 scope.

- Module 09: [pre-implementation coverage](modules/09-coverage.md) and
  [verification report and individual matrix](modules/09-completion.md).
  Signed immutable releases, desired generations, canaries, reconciliation,
  rollback/uninstall, artifact grants and Packages UI are implemented for the
  checked Linux path. Native Windows/macOS/ARM certification and fleet-enabled
  cluster routing remain BLOCKED. Module is NOT DONE; its historical report retains the certification blockers.

- Module 10: [pre-implementation coverage](modules/10-coverage.md) and
  [verification report/individual matrix](modules/10-completion.md). Custom tool
  authoring, signed designated-client sandbox testing, immutable organization
  packages, independent release/deployment and publication preparation pass the
  real Linux path. 84 COMPLETE, 4 BLOCKED and 16 NOT_APPLICABLE IDs; native
  system-engine/logout/boot and ARM certification remain open. NOT DONE; no Module 11.

- Module 11: [pre-implementation coverage](modules/11-coverage.md) and
  [verification report/individual matrix](modules/11-completion.md). Linux/amd64
  non-HA Quickstart passes real Gateway/SQLite/UI/ASK/mTLS/bootstrap, persistence,
  upgrade and backup/restore gates. 84 COMPLETE, 20 NOT_APPLICABLE, zero
  module-scope BLOCKED IDs. Earlier native certification remains open; remote
  publication is protected CI-only. No Module 12 is started.
