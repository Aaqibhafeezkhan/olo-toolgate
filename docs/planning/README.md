# Pending and planned features

Consolidated on October 15, 2026 against this checkout. This is the single backlog
for unfinished product, contributor and distribution work. It replaces the old
module prompts, SDK implementation plans and root roadmap. There are no delivery-date
commitments. Update a row when its implementation or acceptance evidence changes.

**Partial** means some working code exists but the stated scope is incomplete.
**Planned** means a design or scaffold exists without a complete supported workflow.
**Deferred** means an additional design decision and implementation are needed.
Priority records the intended order, not a production-readiness guarantee.

## Implemented baseline

Do not reopen these as new features: group-only access control and independent
review (with the documented Super Admin exception), device enrollment and presence,
Quickstart, configuration transfer, managed endpoint packages, the Java SDK and CLI,
server Java tool upload/placement, direct or queued Tool Host dispatch, and
import from a configured external OLO Orgs Marketplace are present.
The Administration menu and configurable client check-in are also implemented.
See [enterprise evidence](../enterprise-access-control/implementation-status.md),
[Java SDK](../../tool-sdk/java-sdk/README.md) and [Tool Host](../../apps/tool-host/README.md).
Historical test counts are evidence for their recorded revision, not a fresh run.

## Execution and reliability

| Priority | Feature | Status and remaining scope | Completion evidence |
|---|---|---|---|
| P0 | Tool Host isolation | Partial: Java dispatch, broker and clean process environment exist; network namespaces, firewall enforcement and container isolation remain. Endpoint OCI isolation is a separate implemented boundary. | Real escape, direct-egress, process/resource-limit and credential-custody tests from the [threat model](../security/threat-models/tool-host.md). |
| P0 | Durable Tool Host result delivery | Planned: persist a result outbox and retry reports to Control. Currently failed reports are logged. | Crash/restart, unavailable Control, duplicate report and stale-fence tests; never blindly retry an unknown external effect. |
| P0 | Emergency kill delivery | Partial design/contracts: complete runtime integration of kill events, host/client pushes, acknowledgements and termination; validate the 2-of-3 break-glass path. | [Kill specification](../control-plane/kill-switch.md), migration checks and running-effect failure injection all pass. |
| P1 | Remote direct dispatch | Planned: authenticated mTLS Tool Host listener for remote Gateways. Enrolled hosts already support pull dispatch through Control. | Cross-host identity, audience, revocation and transport-failure tests. |
| P1 | Tasks and recovery | Partial: Java standalone tasks and invocation persistence exist; complete governed task lifecycle, UI, abandonment cancellation and worker recovery. | Ownership, expiry, cancellation, lease and unknown-outcome tests through Gateway, Control and a real host. |
| P1 | Fleet delivery improvements | Partial: signed generations and polling exist; add persistent delivery, per-runtime concurrency and adoption deltas where justified. | Ordering, reconnect, stale-generation, backpressure and concurrency tests; polling remains a correctness path. |

## SDKs and integrations

| Priority | Feature | Status and remaining scope | Completion evidence |
|---|---|---|---|
| P0 | Python SDK conformance | Authoring, descriptors, runtime, identity, testing, starter, packaging and host/device launch paths are implemented against Java. Shared vectors and end-to-end package tests run in CI; the full official protocol/conformance record remains to be completed. | Record passing evidence for every applicable Python conformance case. |
| P0 | TypeScript and .NET SDKs | Planned: implement the specified language APIs against the Java reference. | Each language runs a first tool, passes its conformance cases and produces compatible descriptors. |
| P1 | Governed language parity | Partial: Java governed invocation exists; finish isolation and add the other language launchers/runtimes. | Same tool semantics in standalone and governed mode; explicit support matrix and no implicit permission increase. |
| P1 | Broker transports and authentication | Partial: brokered HTTP(S) with API key/basic auth exists; add approved CONNECT/TCP, OAuth, token exchange, connection delivery and streaming. | Denial, TLS, secret-redaction, timeout-after-send and revocation tests for each supported transport. |
| P1 | Existing MCP server proxy | Planned: reviewed upstream catalog snapshots, schema checks, approved credential mapping and drift handling. | An unmodified server works behind ToolGate; schema or permission widening requires review. |
| P1 | OpenAPI/API-to-MCP import | Planned: deterministic transformation, source digest/report, serialization, content negotiation, pagination and authentication alternatives. | A real REST API runs under policy; unsupported constructs fail explicitly and effects default to UNKNOWN. |
| P1 | SaaS connections | Deferred delivery: reviewed connection identities, credential ownership, account selection and revocation. | Isolation between callers/tenants and current-authority checks for every credential delivery. |

## Distribution and community

| Priority | Feature | Status and remaining scope | Completion evidence |
|---|---|---|---|
| P1 | Marketplace platform | Planned: API, worker and Drupal are scaffolds. Build catalog/publisher/version authority, quarantine, isolated scan/build, SBOM, signing, moderation, advisories and storage/queue recovery. | Publish, review, sign, import, deploy and revoke a package end to end; no customer credentials reach the Marketplace. |
| P1 | Private registries and organization import | Partial: the OLO Orgs registry import client is implemented; additional registry adapters, private mirrors and generalized registry lifecycle remain planned. | Verified immutable artifacts and separate organization deployment authority; outage cannot create access. |
| P1 | SDK public publication | Partial: repository release tooling exists; validate intended Maven Central/PyPI/npm/NuGet destinations, namespace ownership and registry install smoke tests per SDK. | Signed artifacts, notices, SBOM/provenance, approved publication and a clean-machine installation. No reserved name is assumed available. |
| P1 | Versioned SDK documentation | Planned: language quickstarts, generated reference, tested snippets, compatibility matrix and standalone-to-governed tutorials. | New author reaches a working tool in under ten minutes; examples and links verified in CI. |
| P2 | Community tool gallery and conformance kit | Planned: publish reusable fixtures/spec versions and contribution criteria for third-party SDKs and examples. | Published kit identifies the exact conformance version and reproducible test results. |
| P2 | Distribution channels and air-gapped bundles | Planned: OS package channels, cohort rollout and offline artifact delivery. | Verified install/update/rollback, mirrored trust and revocation handling; offline delivery does not authorize offline execution. |

## Enterprise adoption and contributor experience

| Priority | Feature | Status and remaining scope | Completion evidence |
|---|---|---|---|
| P0 | Deployment qualification | Installation-specific: configure the real IdP, reviewed migration, external custody, separate DB roles and recovery trust. | Customer-specific restore, outage, revocation and migration rehearsals; [operations runbook](../enterprise-access-control/operations.md). |
| P1 | Capacity and high availability | Partial: stateless service/Helm support and recorded small-fixture checks exist; establish realistic capacity, soak, multi-host failure and recovery results. | Reproducible workload, hardware, topology and p50/p95/p99 results. No enterprise capacity claim from a small fixture. |
| P1 | Security and failure qualification | Partial: boundary tests and scans exist; expand fuzzing, authorization mutation/replay load, fleet scale, Marketplace queue load and dependency-outage coverage as those features ship. | Reproducible threat-to-test mapping, retained reports and no unresolved release-blocking findings; schedule expensive checks where appropriate. |
| P1 | Endpoint release qualification | Partial: installer/build tooling exists; finish required native OS/architecture, boot/logout, signing and upgrade qualification. | Native evidence for each advertised platform; [installer limitations](../client/installers.md). |
| P1 | Stable release qualification | Planned acceptance: qualify the advertised feature/platform matrix, real cluster install/disruption/upgrade, supported migration paths and release artifacts before promoting an RC to stable. | Complete CI, fresh install, recovery/rollback, customer-runtime E2E, supported client matrix, signed artifacts and explicit compatibility notes. Apply N/N-1 checks only where supported version history exists. |
| P1 | Contributor bootstrap | Planned: replace the `make dev`/`make stop` scaffolds with a reliable full-stack lifecycle; wire a supported top-level client build command. | Fresh checkout starts documented services and fixtures, prints usable URLs, and stops only its owned resources. |
| P2 | Enterprise integrations | Planned: external vault adapters, SCIM/directory synchronization, private registries, SIEM export and policy GitOps. | Reviewed trust/ownership contracts, current-authority and outage tests, and operator documentation per integration. |
| P2 | Infrastructure automation | Planned: complete supported Terraform modules and Marketplace deployment topology; qualify multi-region recovery before advertising it. | Reproducible deployment, upgrade, failure and restore evidence. |

## Deferred design decisions

- WASI and host-native runtime tiers require isolation design, ADR changes and per-OS certification.
- Client egress and client-held secrets require a device confinement contract; existing client tools do not gain these by SDK adoption.
- Brokered database adapters, gRPC, arbitrary container integrations, Kubernetes workload identity, A2A, event triggers and GraphQL need scoped designs and conformance coverage.
- Nested/dynamic groups and offline protected effects remain unsupported; neither is implied by future synchronization or air-gapped distribution.

## Completing a backlog item

Keep the relevant [ADR](../adr/README.md), canonical contracts and
[conformance cases](../../tool-sdk/conformance/README.md) consistent. A reviewed
design is not implementation evidence. Before marking work complete, link its
passing tests, supported configurations, limitations, upgrade path and user guide.
Move shipped instructions to their component guide and remove the finished row
here. Release history belongs in [CHANGELOG.md](../../CHANGELOG.md).
