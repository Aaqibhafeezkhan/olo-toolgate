# Master Codex Implementation Prompt

For current maintenance, preserve the device registry flow and uniform execution gates. Treat pending enrollment, enablement, connection approval and tool filters as separate checks. Support explicit unlimited approval with short-lived certificates, reversible HTTP 423 suspension, stable approval revisions and exact-key recovery. Verify tenant/owner binding and real effect denial; do not infer access from installation, green status or a registry row.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

You are implementing **OLO ToolGate**:

```text
https://github.com/olo-labs/olo-toolgate
```

You must implement the assigned module completely and production-quality.

The project is architecture-first, security-first, test-first at boundaries, and designed for future repository separation.

---

# 1. Mandatory Reading

Read before modifying code:

```text
README.md
ARCHITECTURE.md
CONTRACTS.md
SECURITY.md
CONTRIBUTING.md
DEVELOPMENT.md
VISION.md
ROADMAP.md

docs/README.md
docs/INDEX.md
docs/control-plane/device-registry.md

docs/architecture/overview.md
docs/architecture/component-map.md
docs/architecture/trust-boundaries.md
docs/architecture/data-flows.md
docs/architecture/package-model.md
docs/architecture/shared-contracts.md
docs/architecture/desired-vs-reported-state.md
docs/architecture/scalability.md
docs/architecture/compatibility.md

docs/security/security-invariants.md
docs/security/authorization.md
docs/security/credentials-and-vaults.md
docs/security/endpoint-enforcement.md
docs/security/supply-chain.md
docs/security/sandboxing.md
docs/security/key-management.md

docs/codex/01-ENGINEERING-PRINCIPLES.md
docs/codex/03-TESTING-AND-QUALITY-GATES.md
docs/codex/04-RELEASE-AND-PUBLISHING.md
docs/codex/05-KUBERNETES-AND-HELM.md
docs/codex/06-SECURITY-IMPLEMENTATION-RULES.md
docs/codex/07-CODE-STYLE-COMMENTS-LICENSE.md
docs/codex/08-DEFINITION-OF-DONE.md
docs/codex/09-REQUIREMENTS-TRACEABILITY.md
docs/codex/10-CI-CD.md
docs/codex/11-OBSERVABILITY-OPERATIONS.md
docs/codex/12-DATA-PERSISTENCE.md
docs/codex/13-API-CONTRACTS.md
docs/codex/14-DOCUMENTATION-RULES.md
docs/codex/15-SUPPLY-CHAIN-DEPENDENCIES.md
docs/codex/16-CONFIG-SECRETS.md
docs/codex/17-PERFORMANCE-SCALABILITY.md
docs/codex/18-VERSIONING-COMPATIBILITY.md
docs/codex/19-SPLIT-REPO-READINESS.md
```

Then read the assigned module prompt.

---

# 2. First Action: Requirements Coverage Plan

Before editing files, produce a concise plan mapping:

```text
Requirement ID
Applicable? yes/no
Implementation location
Test location
Documentation location
Release/deployment impact
```

Use the requirement IDs in:

```text
docs/codex/09-REQUIREMENTS-TRACEABILITY.md
```

Do not begin implementation until the module has a clear coverage plan.

---

# 3. Scope

Implement only the assigned module plus the smallest required shared changes.

Do not:

- redesign unrelated modules;
- copy contracts into services;
- bypass existing abstractions;
- create hidden coupling;
- make optional infrastructure mandatory without ADR;
- introduce a second framework for an already-solved problem;
- start the next module.

If an architectural decision is missing:

```text
docs/adr/
```

must receive an ADR.

---

# 4. Shared Contracts

Cross-component contracts are a product.

Canonical source:

```text
packages/contracts/schemas/
packages/contracts/openapi/
packages/contracts/events/
```

Publishable bindings:

```text
packages/contracts/rust/
packages/contracts/java/
packages/contracts/typescript/
packages/contracts/php/
```

Stable Java artifact:

```text
io.github.olo-labs:toolgate-contracts
```

Within monorepo:

```text
local workspace/project dependency
```

After split:

```text
published semantic version
```

Application imports must remain unchanged.

---

# 5. Build Systems

## Java

```text
Java 21+
Gradle Kotlin DSL
```

Required:

```bash
./gradlew projects
./gradlew javaCheck
./gradlew build
```

## Rust

Stable Rust/Cargo.

## Admin UI

React + TypeScript.

## Drupal

Composer-managed Drupal project.

---

# 6. Maintainability

Use:

```text
high cohesion
low coupling
dependency inversion
ports/adapters at real boundaries
small interfaces
composition over inheritance
typed configuration
typed errors
immutable value objects where helpful
explicit state machines
idempotent mutation APIs
versioned contracts
```

Avoid:

```text
god classes
service locator
global mutable state
framework leakage into domain
business logic in HTTP controllers
authorization in persistence repositories
copy/pasted schemas
hidden I/O
catch-all swallowing
```

Use patterns only for actual needs.

Expected useful patterns:

```text
Adapter
Strategy
Factory
Repository
State Machine
Command
Specification
Policy Object
Builder
Decorator
Circuit Breaker
Outbox
Lease
```

---

# 7. Security

Default deny.

Never convert failure into ALLOW.

Never log secrets.

Never run untrusted code inside:

```text
Control Plane
Marketplace API
Drupal
Marketplace Worker host process/namespace
```

Use sandbox/test-client boundaries.

Never skip signature verification.

Never accept Marketplace trust as organization deployment authorization.

Never accept organization deployment authorization as runtime authorization.

---

# 8. Testing

Tests are implementation.

Every applicable module must add:

```text
unit tests
component tests
contract tests
integration tests
negative-path tests
security tests
E2E coverage where cross-component
performance tests where critical
```

No module is complete with manual testing only.

---

# 9. Documentation / Comments

Human-authored source files should include:

```text
Copyright 2026 OLO Labs
SPDX-License-Identifier: Apache-2.0
```

unless language/ecosystem convention or generated source makes it inappropriate.

Generated code:

```text
GENERATED FILE — DO NOT EDIT DIRECTLY
```

Comments explain WHY, not syntax.

Public APIs and security-sensitive code require docs.

---

# 10. Docker

Deployable services need:

```text
multi-stage build
minimal runtime image
non-root execution
immutable image
no embedded secrets
OCI labels
version metadata
SBOM support
health/readiness integration
graceful shutdown
resource-conscious defaults
```

---

# 11. Kubernetes / Helm

Production services must integrate with:

```text
deploy/helm/olo-toolgate/
```

or an ADR-approved chart layout.

Required where applicable:

```text
Deployment
Service
ServiceAccount
ConfigMap
Secret references
Ingress optional
NetworkPolicy
PodDisruptionBudget
HPA
securityContext
containerSecurityContext
resources
startup/readiness/liveness
topologySpreadConstraints
affinity/tolerations/nodeSelector
metrics/ServiceMonitor option
```

Charts require:

```text
helm lint
helm template tests
Kubernetes schema validation
upgrade rendering tests
```

---

# 12. Release Engineering

Support publishing:

```text
Java shared contract artifact
Rust contract crate when configured
npm contract package when configured
Composer contract package when configured
raw contract bundle
Docker images
Endpoint-client binaries/packages
Helm OCI chart
GitHub release assets
checksums
SBOMs
provenance
signatures where configured
```

---

# 13. Database / State

Production schema changes require migrations.

No automatic destructive schema mutation.

Test:

```text
fresh install
upgrade path
concurrent mutation where relevant
idempotency
transaction boundaries
```

---

# 14. APIs

External APIs must:

```text
be versioned
validate input
use stable error codes
not expose internal exceptions
support idempotency where specified
emit request IDs
publish OpenAPI
```

Controllers remain thin.

---

# 15. Observability

Long-running components need:

```text
health
readiness
metrics
structured logs
trace propagation
correlation IDs
graceful shutdown
```

Asynchronous workflows expose explicit status, not only logs.

---

# 16. No Fake Completion

Primary paths must not contain:

```text
TODO
UnsupportedOperationException
hardcoded ALLOW
fake signature verification
production mocks
sleep-based synchronization
temporary insecure bypass
```

If something is intentionally unsupported, return an explicit unsupported capability and document it.

---

# 17. Completion Report

Before finishing the module, provide:

1. module summary;
2. requirement IDs completed;
3. files changed;
4. design choices/patterns;
5. tests created;
6. commands executed;
7. results;
8. security analysis;
9. Docker changes;
10. Helm/Kubernetes changes;
11. release/publishing changes;
12. observability changes;
13. documentation changes;
14. remaining limitations;
15. suggested commit message.

Do not start the next module.
