# ToolGate Tool SDK and governed MCP platform: plan

Status: design, revision 10 (design freeze, reconciled with the code at commit `8ea5091`). Gates D0 and D1 have passed; D2 is in review (§22.1). Nothing is implemented.
Repository: `olo-labs/olo-toolgate` (read at `main`, contracts `0.10.0-dev`).
MCP baseline: specification **2026-07-28** (checked against the published changelog).

## 0. What changed

### Revision 10: reconciled with the current implementation

Revision 10 checks the plan against `olo-labs/olo-toolgate` at commit `8ea5091` (main, 2026-10-10). The plan holds in its goals and most contracts. However, six structural assumptions didn't match the code, and they are corrected here. Each correction moves the design toward what already exists, not away from it. §2.1 lists every finding with file references.

| Priority | Correction | Where |
|---|---|---|
| P0 | **Control stays the authority on every call.** Today the Gateway has no policy engine and no permit signer; Control evaluates each call and issues permits through `/access/invocations` and `/reserve`, and nothing runs without current Control authority (no offline ALLOW). Decision A is reversed: **Control issues Tool Host permits** through the existing reserve path. | §2.1, §11.1, §11.2, §23 |
| P0 | **The state machine already exists in Control** (`control_enterprise_invocations`, approvals, single-use nonces, budgets, reconciliations, outbox; `PENDING_APPROVAL → QUEUED → RESERVED → EXECUTING → …OUTCOME_UNKNOWN`). The plan's "taskstore" is now an extension of those tables, not a second state machine. The Gateway gets **no** database access; it keeps calling Control over HTTPS (ADR 006). | §12.8 |
| P0 | **Kill switch simplified.** Because nothing starts without Control, the Gateway freshness grace and Gateway break-glass are dropped. Kills are enforced at Control (reserve, permit consumption, secret delivery) and pushed to Tool Hosts and clients for running work. | §15.3, §15.4 |
| P0 | **Client delivery keeps today's trust format.** Clients keep the RS256 release JWS over `FleetPackageDocument` and OCI images with code at `/opt/tool`, with no ZIP extraction on devices. `.tgpkg` is the upload format, which Control converts. | §2.1 |
| P1 | **Lifecycle builds on existing fleet releases, the Builder flow and maker/checker configuration changes**, instead of a parallel lifecycle. | §2.1, §8.1 |
| P1 | **Names and paths corrected:** settings endpoint `/api/control/v1/settings` with 409 conflicts; Helm key `control.clientCheckIn.*` with `TOOLGATE_CONTROL_CLIENT_*` variables; OpenAPI under `packages/contracts/openapi/`; npm scope `@olo-labs`; the existing `NATIVE`/`WASM` runtime kinds; "tool protocol v2" (IPC protocol v2 already exists); a duplicate ADR 012 to resolve before numbering 013 onward. | §2.1, §17, §18, §20 |

### Revision 9

Revision 9 closes the four P0 blockers Rahul found in the combined plans.

| Priority | Correction | Where |
|---|---|---|
| P0 | **Gates and milestones are no longer circular.** D1 now produces the complete, reviewed v2 schema files and fixtures as design artifacts; M1 only implements consumers, bindings, migrations and validation. The "Now" exception is removed: the Configuration menu and check-in variables are designed in D1 and built in M1. Every gate lists its artifacts, location and status, and all four are currently **not passed**. | §22 |
| P0 | **Three separate identities:** `toolDescriptorDigest` (equal across modes), `artifactDigest` and `packageDigest` (equal only when the bytes are). | §7.2, §5.8, §11.2 |
| P0 | **Physical transaction model frozen:** one authoritative PostgreSQL state database (SQLite in Quickstart) for approvals, tasks, reservations, ledger and outbox, with per-component roles. Local-file hand-offs are defined as idempotent outboxes, never as atomic. Separate databases are rejected for release 1. | §12.7, §12.8 |
| P0 | **D0 needs test results, not published versions.** The compatibility record format, the acceptance rule and the five adapter contracts are frozen. The first conformance results are in the compatibility record; D0 stays unpassed until every required feature passes or is covered by a reviewed adapter. | §5.10 |

### Revision 8: design freeze

Revision 8 closes the design-freeze blockers from Rahul's sixth review and his review of the publishing plan. After it, nothing in P0 scope is "proposed". Every decision is ACCEPTED, REJECTED or DEFERRED in §23, with its ADR.

| Priority | Correction | Where |
|---|---|---|
| P0 | **One canonical SDK HTTP API.** Named routes (`context.http().route(id)`) are normative in every SDK. URL-style calls are allowed only when the compiler or analyser can prove they map to exactly one declared route; otherwise packaging fails. Destination hosts are reviewed service-profile settings, not globals. All examples are updated. | §5.1, §5.4, §11.8 |
| P0 | **Transport matrix for release 1.** Brokered HTTP(S) and approved TCP over CONNECT with explicit client proxy configuration are the only supported transports. Unmodified database drivers, Unix sockets, UDP and native clients without proxy support are marked unavailable. | §11.9 |
| P0 | **Business-key namespace per tool** (`AGENT`, `DELEGATED_USER` or `TENANT`). The unique constraint, fingerprint, forwarded upstream value and retention all use the same namespace. Cross-identity conflicts reveal nothing about the other caller. Reservation scope and credential ownership are defined. | §11.7, §12.3 |
| P0 | **Persistence transition table.** For approval, reservation, invocation creation, admission, outbox and retention: the durable write, when it is acknowledged, how failure is recovered, and whether execution may proceed. Audit-spool exhaustion while work runs is defined. | §12.7 |
| P0 | **Decision register.** Every earlier decision, A to R included, is closed with an ADR, rationale, alternatives and affected contracts. | §23 |
| P0 | **The complete authoring contract is frozen before any SDK code**, including the error model and execution semantics, not just the subset standalone mode uses. | §5.7, §22 |
| P0 | **Standalone and governed equivalence** is a capability matrix with one invariant: moving modes never silently gains a permission or changes an external effect. A mode-equivalence suite proves it. | §5.8, §21 |
| P0 | **JWT verification is mandatory** in standalone production mode. Fixture claims exist only in an explicitly selected development mode bound to loopback. | §5.9 |
| P0 | **Official MCP SDK versions and features are pinned per language**, with a ToolGate adapter designed for every gap (Java 2026-07-28 core, Tasks extension, `requestState`, bearer verification). | §5.10 |
| P0 | **Library composition** fails on any conflict unless an explicit, reviewed mapping resolves it, and never broadens access. | §7.7 |
| P1 | **Deployment configuration is its own contract** (`DeploymentBinding`), with one precedence order and the env versus database rule. | §8.5 |
| P1 | **Design gates are separate from coding milestones.** D0 to D3 must pass before the matching coding milestone starts. | §22 |

### Revision 7

Revision 7 makes targeted corrections from Rahul's fifth review. None of them is a redesign.

| Priority | Correction | Where |
|---|---|---|
| P0 | The conformance gate no longer demands byte-identity across SDK versions. It is replaced by three tests (reproducibility, upgrade compatibility, generation migration). Semantic equivalence uses a deliberately **limited** rewrite set, and anything uncertain counts as a change that needs review. | §5.6, §21 |
| P0 | Business keys and dedupe use an **atomic durable reservation** (unique constraint, same transaction as invocation creation). A reused key with a different fingerprint gets `IDEMPOTENCY_CONFLICT`. Defined retention and tombstones prevent expired keys from allowing a second high-risk operation. | §11.7, §12.3 |
| P0 | One resolved `requiresExecutionLedger = NON_IDEMPOTENT or UNKNOWN`, fixed at enable and carried in the permit. It controls admission, retry, broker, task store and revocation checkpoints. No default or legacy translation can bypass it. | §11.3 |
| P1 | Kill ordering uses immutable issuer-scoped event ids and a grow-only merge. A kill stays effective until an authorized recovery event supersedes it, and an older snapshot can never remove one. | §15.1, §15.4 |
| P1 | `ServiceProfile` changes are part of the security delta, with explicit widening rules. The permit carries the profile digest, which the broker checks. | §8.3, §11.8 |
| P1 | OpenAPI import defines parameter serialisation, content negotiation, multipart, pagination, status codes and auth alternatives. Imports default to `UNKNOWN`. The source digest and a transformation report are kept. | §14.2 |
| P1 | Conformance is organised into independently runnable suites by cost and trigger, while security-sensitive changes keep their blocking gates. | §21 |

### Revision 6

Revision 6 applies the corrections from Rahul's fourth review. The pasted review was cut off after the fourth item, so a fifth issue may still be outstanding.

| Priority | Correction | Where |
|---|---|---|
| P0 | **At-most-once per invocation is not per business operation.** Adds `effectSafety = READ_ONLY \| IDEMPOTENT \| NON_IDEMPOTENT \| UNKNOWN`, never inferred from the HTTP method, with `UNKNOWN` treated as non-idempotent. High-risk tools require a business idempotency key; a missing key is rejected, sent to ASK, or deduplicated heuristically, per policy. "Effectively once" is claimed only with definitive downstream commit evidence. | §11.7 |
| P0 | **The broker channel is bound to the invocation.** It runs over a per-sandbox Unix socket with an invocation-bound capability, and requests select approved **routes** rather than URLs. Destination and route template are enforced on the server side, along with size, content-type and streaming limits. Client auth headers are stripped. Every decision is audited. | §11.8 |
| P0 | **Roadmap dependencies fixed.** A minimum task store, execution ledger, `KillSnapshot` consumer and revocation enforcement move into Step 2, so Step 2 is secure on its own. Advanced tooling stays in Step 4. | §22 |
| P1 | **SDK upgrade determinism relaxed.** The rule is now semantic descriptor equality. Generated-schema fixes ship behind a `descriptorGeneration` that projects can opt into or pin. Byte changes that don't change meaning never trigger review. | §5.6 |

### Revision 5

Revision 5 closes seven findings from Rahul's third review.

| Priority | Finding | Fix | Where |
|---|---|---|---|
| P0 | The ledger can't make external effects happen only once | Guarantees are restated precisely. ToolGate gives **at-most-once execution** per invocation for non-idempotent tools. Effects are **effectively-once only** when the upstream honours an idempotency key, or when a declared reconcile hook resolves `OUTCOME_UNKNOWN`. The claim of "impossible duplicates" is withdrawn. | §11.7 |
| P0 | Credential injection conflicts with non-intercepted HTTPS | Egress is now split into two explicit modes. **Brokered** mode: the sandbox speaks plain HTTP to a local broker, which originates TLS itself and injects credentials. **Tunnel** mode: CONNECT, host-level policy only, and no injection is possible, so credentials enter the sandbox as a declared security delta. The per-tenant interception CA is removed. | §5.4, §11.6 |
| P0 | Break-glass doesn't reach existing invocations | Break-glass entries are pushed over a persistent Gateway → Tool Host control stream and written to the pull store. Hosts acknowledge them, and hosts that don't acknowledge are fenced. Every component kills running work on receipt. Behaviour for clients and offline hosts is now explicit. Break-glass needs a threshold signature and can only kill. | §15.4–15.6 |
| P1 | SDK runtime placement contradicted §7.4 | §7.4 is corrected. Base image = language runtime + `toolgate-launch`, and the SDK runtime lives in the artifact. The launcher's contract is defined. | §7.4 |
| P1 | MCP proxy checks rely on stale cache | Model clients only ever see ToolGate's **approved** definitions, never the upstream's live ones. Per-call input and output validation is the enforcement; freshness is defence in depth. A strict mode refreshes per call. The remaining race is documented. | §14.1 |
| P1 | Auth profiles too strict for real IdPs | Per-issuer claim-mapping templates (Entra ID, Okta, Auth0, Keycloak, Ping, Google) with typed service discriminators. Delegation accepts auth-code user tokens issued to the agent's client as well as token exchange, and still needs Control's approval. | §9.3 |
| P1 | Rollout lifecycle underspecified | A package version state machine, server-pool and client rollout, health gates, automatic rollback, version coexistence, draining and retirement, global-value changes, and auto-update channels. | §8.4 |

### Revision 4

Revision 4 closes the eight remaining issues from Rahul's second review.

| Priority | Issue | Fix | Where |
|---|---|---|---|
| P0 | Cross-host retry and duplicate execution | One `invocationId` across all attempts. Every non-idempotent execution claims it once in a shared ledger, with a fencing token. Hosts acknowledge admission explicitly. An ambiguous dispatch is never retried on another host. | §11.2–§11.4, §12.3 |
| P0 | Egress sandbox and proxy isolation | Each sandbox gets its own network namespace whose only route is its own proxy listener. Raw DNS, UDP and QUIC are denied. SNI, Host and destination IP are checked together. The proxy itself can't reach cluster networks. Upstream credentials are injected outside the sandbox. | §11.6 |
| P0 | MCP Tasks and fallback | Exact mapping onto the Tasks extension (`resultType: "task"`, `tasks/get`/`update`/`cancel`, `ttlMs`, `pollIntervalMs`). A task is never returned to a client that didn't declare support. Clients without Tasks never start work they can't collect. Task ids are bound to the caller. Orphans are handled by TTL. | §12.4–§12.6 |
| P1 | Auth classification and delegation | The tenant comes from the endpoint, not the token. Each issuer has exactly one token format. Access tokens are typed (RFC 9068). Fixed SERVICE and DELEGATED mapping profiles. Delegation must be allowed in Control as well as by the issuer. | §9.2–§9.3 |
| P1 | Mutable upstream MCP capabilities | Upstream definitions are verified at call time against the approved digest, not only on a schedule. Server identity is pinned. Upstream output is marked untrusted. Upstream input requests and task handles are mediated. | §14.1 |
| P1 | Distributed kill-switch freshness | Three delivery paths, including the permit itself. Signed snapshots with a validity window. A defined grace and fail-closed rule, and break-glass at the Gateway. | §15 |
| P1 | Package digest and artifact verification | Strict zip rules, an exact file set, a canonical checksums format, and verification at every hop into a content-addressed read-only store. Base images are signed. Optional provenance. | §7.6 |
| P1 | SDK compatibility and release conformance | The SDK runtime ships inside the artifact, while the base image is only a launcher. A compatibility matrix, deterministic descriptors across SDK upgrades, wider language baselines, and the CLI distributed through standard registries. | §5.6, §21 |

### Revision 3

Revision 3 applies Rahul's review in full.

- **MCP lifecycle corrected.**
  - There is no `initialize` and no session pinning.
  - `server/discover`, per-request `_meta`, `subscriptions/listen`, the Tasks extension and MRTR are now the model.
  - Older protocol versions go through a separate compatibility adapter (§10).
- **Existing MCP servers and OpenAPI import are P0**, alongside the Java SDK (§14).
- **Direct Gateway → Tool Host dispatch has a full trust contract** (§11): permit issuer, admission, replay, revocation, durable audit and sandbox isolation.
- **Authentication is no longer JWT-only** (§9):
  - credentials are classified without ambiguity;
  - opaque OAuth tokens are checked by introspection;
  - delegation is issuer-approved;
  - a valid but non-JWT token is never sent to the wrong validator.
- **The URL selects a catalog scope, not an identity.** `CatalogScope` is its own type (§9.4).
- **A persisted task state machine** now covers progress, approval, cancellation and idempotency (§12).
- **Runtime isolation tiers are explicit**, with no automatic fallback to a weaker tier (§13.4).
- **Client check-in is split into five separate settings** (§17). Rahul's environment variable ships first.
- **Smaller correctness fixes:**
  - egress is checked at connect time;
  - package signing is no longer circular;
  - one authoritative manifest schema;
  - semver kept separate from security review;
  - warm pools keyed with tenant and identity boundaries;
  - hard limit ceilings;
  - per-invocation schema pinning;
  - idempotency forwarding only through an adapter contract;
  - toolsets limited to visibility;
  - revisioned permission deltas;
  - field-level settings PATCH.
- **New emergency kill switch** (§15).
- **Roadmap reordered**: contracts → foundation with Java → adoption (proxy and OpenAPI) → reliability → other SDKs → distribution (§22).

---

## 1. Goal

ToolGate should govern every tool an enterprise already has or will write. It covers three ways in:

1. **SDK tools.** A developer annotates a method (`@ServerTool` / `@ClientTool`), runs the normal build and gets one zip. Uploading it registers the tools as governed MCP tools. Submitting the same zip to the Marketplace carries the listing.
2. **Existing MCP servers.** An administrator registers the URL (or a packaged stdio server), reviews its capabilities, approves them and exposes them through ToolGate governance without changing the server.
3. **REST APIs.** An administrator imports an OpenAPI document, picks the operations and gets governed tools with no code.

The Gateway then exposes the approved tools over standard MCP. Admins only type what they alone know, such as secret values and tenant URLs, and give the one approval the trust model requires.

## 2. What exists today and how this fits

| Area | What the repo has | Where | How this plan uses it |
|---|---|---|---|
| Tool definition | `ToolDefinition {id, name, description, actions[], inputSchema, outputSchema}` | `packages/contracts/schemas/v1/tool.schema.json` | Becomes `ToolDefinition` v2 (contract change approved) |
| Package | `PackageManifest` v1, `MarketplaceRelease` (detached marketplace signature) | `package.schema.json` | Manifest v2. The detached-signature pattern is kept. |
| Authoring metadata | `BuilderDefinition` (`useWhen`, `doNotUseWhen`, `permissions`, `resource`, `credentialRequirements`, `examples`) | `builder.schema.json` | Same fields, filled from annotations |
| Client execution | Managed OCI sandbox, `LocalToolRegistration`, stdin/stdout protocol v1, limits of 10 s / 1 GiB / 64 KiB | `execution.schema.json`, ADR 009 | The SDK harness speaks the protocol. This sandbox becomes the `OCI_SANDBOX` tier. |
| Fleet | Signed `FleetPackageDocument`, device desired generations | `fleet.schema.json`, ADR 010 | Generated from the package on enable |
| MCP ingress | One `/mcp` route. Already stateless 2026-07-28: `server/discover` returns `supportedVersions:["2026-07-28"]`, `_meta` protocol version and client capabilities are required, `Mcp-Method`/`Mcp-Name` headers are checked, `ttlMs`/`cacheScope` are returned. It still answers `ping`, which 2026-07-28 removed. | `apps/gateway/src/mcp.rs:93-154`, `http.rs:112` | §10 builds the version-aware layer on top. The repo was already ahead of revision 2's mistake. |
| Client relay | Control queue in PostgreSQL, 500 ms client check-in (hard-coded `EndpointService.java:190`), one job at a time per client, 30 s expiry | ADR 012, `docs/client/server-mcp.md` | Kept for client tools. Check-in becomes configurable (§17). |
| Server-side execution | Fixed system executors only: `BUILTINS`, `HOTFOLDER`, `REST_FORWARDING` | `SystemExecutorKind` | Adds `TOOL_HOST` |
| Runtime authorization | Control evaluates every call (`/access/invocations`), owns ASK, issues permits (`/reserve`) and consumes single-use nonces; the Gateway holds no policy (corrected in revision 10, §2.1) | ADR 006 (ADR 005 outdated) | §11 extends Control permits to Tool Host dispatch. No second policy system. |
| Authentication | Opaque bearer tokens: up to 256 mounted SHA-256 hashes, each mapped to a fixed `RequestContext`, which requires `deviceId` and `bindingId` | `apps/gateway/src/auth.rs`, `common.schema.json` | §9 replaces this with classified credentials. Identity no longer carries a device. |
| Identity model | `ControlWorkloadBinding {issuer, subject, audience, agentId, mode, delegatedUserId, parentBindingId}`, `ControlIdentityBinding`, `ControlAgentGroup`, `ControlAgentDelegation` | `enterprise.schema.json` | Reused as-is for token-to-agent mapping and delegation |
| Secrets | `secret://` refs, group-scoped Vault, certificate-bound per-invocation delivery | `enterprise.schema.json` | Used for SDK, proxy and OpenAPI tools |
| API-to-MCP | Stub doc only: "forms, OpenAPI or cURL; credentials become Vault references; SSRF controls mandatory" | `docs/control-plane/api-to-mcp-builder.md` | §14.2 designs it |
| Marketplace | API, Worker, Drupal and Control integration are scaffolds (modules 12–15) | `apps/marketplace-api` | P1/P2. The package format is defined now. |
| Console | Gateway name, device approval and config import/export all sit under Audit > Configuration reviews | `apps/admin-ui/src/ConfigurationRequests.tsx`, `ServerSettings.tsx` | §18 adds a Configuration menu |

Three documented invariants are changed on purpose, and each needs an ADR before code:

- managed tools get no network and no secrets (server tools gain both, under §5.4 and §11);
- upload never grants execution (one-click enable, §8);
- agent tokens never leave the Gateway (still true: tools get verified claims or exchanged tokens).

### 2.1 Verified against the code (commit 8ea5091)

Each finding below was checked in the repository. Where it differs from text elsewhere in this plan, **this table wins**, and the sections named in the last column have been updated.

**Gateway (`apps/gateway`)**

| Area | What the code does | Consequence for the plan |
|---|---|---|
| Authorization | No local policy, snapshot verifier or permit signer. `authorize()` fetches the catalog from Control and POSTs to `/api/control/v1/access/invocations`; permits come from Control's `/reserve` (`application.rs:24-100`, `relay.rs:206-221`; `docs/gateway/configuration.md:3`) | Control issues all permits, including Tool Host permits (§11). ADR 005 and ADR 006's Gateway-signing text is outdated and gets superseded in ADR 013. |
| Caches | None: no catalog or decision cache, 2–3 Control round trips per call, readiness needs a Control response within 5 s (`relay.rs:225-228`) | No introspection or decision caches that would act as offline ALLOW. JWKS key caching is allowed, since keys aren't decisions. |
| Routes | Only `/mcp`, and `guard()`/`parse()` hard-code that path (`http.rs:110-113`, `:201`, `:334`) | Scoped routes (§9.4) need router and guard changes |
| Transport | POST only, HTTP/1 with `keep_alive(false)` (`server.rs:40`); all notifications rejected (`mcp.rs:68-72`); strict top-level keys and `tools/call` params (`mcp.rs:76-78`, `:187-190`); 20 s default and 30 s maximum per request, with a credential-expiry recheck after the handler (`config.rs:74`) | `subscriptions/listen`, SSE and the Tasks fields need server and parser changes. Anything long-running must use tasks. |
| MCP | `server/discover` with 2026-07-28 only; header checks give -32020; -32022 with versions; `ping` answered (`mcp.rs:89-148`); missing `clientCapabilities` gives -32602 | Legacy adapter is new. `ping` is removed for 2026-07-28 requests and kept on the legacy path. |
| Invocation id | Derived from identity plus the JSON-RPC id (`mcp.rs:223`), so a client re-issue is a new invocation | Business keys and dedupe (§11.7) map to Control's existing `downstreamIdempotencyKey` (`application.rs:38`, always `None` today) |
| Tool names | `tools/list` exposes `tool_id` as `name`, no `outputSchema` or annotations (`mcp.rs:154`) | `mcpName` and aliases are new (§10.4) |
| Auth | Opaque bearer tokens, 32–256 URL-safe characters, up to 256 hashes, each mapped to a fixed `RequestContext` that requires `deviceId` and `bindingId` (`auth.rs`); single tenant from the mounted context | JWT, introspection and a token prefix are new; existing mounted tokens keep working unchanged (no prefix needed for them). Removing `deviceId` touches `application.rs:63`, `mcp.rs:277`, `http.rs:410-418` and every Control call. |
| Rate limits | One per-replica fixed window, deliberately with "no identity-indexed memory" (`limits.rs:2-38`) | Per-agent and per-tool quotas use Control's existing execution budgets, not Gateway memory |
| Audit | No audit pipeline or spool; `audit_queue_capacity` is validated but unused (`config.rs:20`, `:79`) | The audit spool (§12.7) is net-new in both Gateway and Tool Host |
| Kill switch | Nothing exists; the config file is fixed with `deny_unknown_fields` (`config.rs:41-50`) | Kill enforcement lives in Control (§15) |
| Execution | The only path is the client relay through Control, polling `/mcp/responses` every 250 ms | Tool Host dispatch is new |

**Control (`apps/control-plane`)**

| Area | What the code does | Consequence |
|---|---|---|
| Invocation state | `control_enterprise_invocations`, `control_enterprise_approvals` (OPERATION and CONFIGURATION), `control_enterprise_nonces` (single-use permit nonces), `control_execution_budgets`, `control_outcome_reconciliations`, `control_authorization_outbox` (migrations V13–V16). States `PENDING_APPROVAL → QUEUED → RESERVED → EXECUTING → SUCCEEDED / FAILED / OUTCOME_UNKNOWN / EXPIRED`, an effect watchdog, operator reconciliation and cancel with If-Match (`EnterpriseOperations.java`) | §11.3's ledger is the existing RESERVED → EXECUTING transition with nonce consumption; §11.7's reconciliation extends the existing reconciliation flow; tasks extend invocations (§12.8) |
| Transactions | Everything in schema `public` with a `control_` prefix; each write takes a per-tenant `pg_advisory_xact_lock` with 5 s statement and 3 s lock timeouts, and the invariants are checked in Java (`PostgresStore.java:39-47`) | No `taskstore` schema and no Gateway or Tool Host database roles. All writes go through Control's API. |
| Roles and migrations | Flyway V1–V17 on PostgreSQL; a hand-written SQLite runner for Quickstart, capped at version 10 (`SqliteState.java:26-35`); separate migration and runtime logins enforced | Every state change ships as a Flyway migration plus a Quickstart SQLite migration, and the SQLite runner's cap is raised |
| Permit consumption | `permits/consume`, `effects/report` and secret delivery authenticate the **device mTLS certificate** (`EnterpriseOperationResource.java:60-66`) | Tool Host gets its own mTLS workload identity and the same three endpoints |
| Secrets | AES-GCM ciphertext in `control_secrets`, keyed by tool group and device group (`VaultService.java`); delivered only to a device with an EXECUTING invocation | Server-tool secrets need a Tool Host identity path, and later the broker's |
| Settings | `PUT /api/control/v1/settings` with If-Match and Idempotency-Key, **409** on a stale revision; `formatVersion` 1; a 16 KiB document; a startup import (`ConfigurationImportBootstrap`) that overwrites settings | §18 uses `PATCH /api/control/v1/settings` and 409; adding fields bumps `formatVersion`; env-owned values are excluded from the startup import |
| Check-in | 500 ms only when the client sends `X-ToolGate-Poll-Interval-Unit: milliseconds`, otherwise 2 s (`EndpointService.java:190`, `:226`); a 250 ms burst guard (`:208`) | The burst guard becomes a fraction of the configured check-in |
| Governance | Maker/checker configuration changes (`control_configuration_changes`); authorization epoch with a no-rollback trigger and an outbox; fleet releases and rollouts (`FleetResource`); Builder seal → publication → release → deploy (ADR 011); per-device disable (423) | Enable submits a configuration change (auto-approved in Quickstart); kill and revocation reuse the epoch and outbox; package lifecycle reuses fleet releases |
| Identity | `ControlWorkloadBinding` also carries `credentialEpoch`, `expiresAtUnixMs`, `delegatedSessionEpoch`; ids match `[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}`, with no slugs | Keep those fields in v2; slugs remain a MAJOR change |
| ADRs | Two files numbered 012 (`012-client-mcp-polling-relay.md`, `012-single-node-quickstart.md`) | Renumber the Quickstart ADR to 012a in D1 before adding ADRs 013 onward |

**Endpoint client and execution**

| Area | What the code does | Consequence |
|---|---|---|
| Protocol v1 | Input `{protocolVersion:1, requestId, toolId, arguments}`, output `{protocolVersion:1, requestId, output}` only (`execution.schema.json:147-195`) | v2 is renamed **tool protocol v2** (IPC protocol v2 already exists, ADR 008); the client keeps a separate v1 codec, with `examples/local-runtime-python` as its fixture |
| Code delivery | Tool code is baked into an OCI image at `/opt/tool`, the entry point must match `^/opt/tool/`, no mounts, no ZIP or TAR extraction (`package-deployment.md:22-23`), images pinned by digest | **Client tools ship as OCI images** built from an approved base, pinned by digest, exactly as today. No `.tgpkg` or mount on devices. Server tools on Tool Host may mount artifacts. |
| Signing | RS256 compact JWS over the exact `FleetPackageDocument` bytes, with release and organization keys (`deployment.rs:100-113`) | That stays the client's only trust format. DSSE over the package digest is the upload-side provenance format; Control re-issues the release JWS on enable. |
| Limits | 10 s, 1 GiB, 64 KiB input and output, 32 argument and output properties, a 32 KiB fleet document; at most 16 runtimes and 32 tools per client; `hotfolder.*` reserved | Client-tool packages are limited to 32 tools; raised ceilings (§13.5) apply to Tool Host and to clients only after a client release |
| Concurrency | One remote job at a time (`service.rs:43`); the socket opens only after a poll delivers a job and closes after 30 s idle | Matches §17; per-runtime concurrency is Phase B |
| Adoption document | `adoption.json` with `revision` and `authorizationEpoch` and rollback rejection; `permissions.json` exists only in docs | §17.4 deltas build on `EndpointAdoption.revision` |
| Runtime kinds | `LocalRuntimeKind` already has `NATIVE` (`/opt/tool/run` inside the image) and `WASM` (unsupported) | Tier names become `OCI_SANDBOX`, `WASI` and `HOST_NATIVE` to avoid the clash; `HOST_NATIVE` stays DEFERRED and needs ADR 009 amended |
| Tool Builder | Signed inline source up to 8 KiB with fixed runners and `def tool(arguments)` (ADR 011), plus up to 8 examples with expected output | Builder stays as the no-SDK path. SDK `@Example`s map onto the same self-test fields. |
| Platforms | Docker-compatible CLI only; Windows named pipes with a Linux engine; only Linux service execution is certified | Unchanged; the plan's tier rules already forbid fallback |

**Contracts, console, deployment, CI**

| Area | What exists | Consequence |
|---|---|---|
| Generator | `tools/contracts/generate.py` hard-codes `schemas/v1`, emits Rust, Java (flat package `io.ololabs.toolgate.contracts`), TypeScript **and PHP**; the Rust crate holds a copied `schemas/v1` with drift (`approval.schema.json` has no canonical source) | D1 adds v2 namespacing (`…contracts.v2`), PHP bindings and a stale-copy check before v2 schemas land |
| Paths | OpenAPI at `packages/contracts/openapi/{control,gateway,quickstart}-v1.yaml`; schema `$id` base `https://schemas.ololabs.io/toolgate/v1/`; fixtures under `tests/fixtures/contracts/` | All plan paths updated; v2 `$id` base `…/toolgate/v2/`; v2 fixtures go in `tests/fixtures/contracts/v2/` |
| DDL | Contracts must not contain database code (`CONTRACTS.md`) | State DDL lives in Control's Flyway and Quickstart migrations, not under `packages/contracts` |
| Console | `App.tsx:29-30` sections and navigation; `routeFromHash` falls back to overview with no redirects | §18 adds sections and a redirect for `#configuration` |
| Helm | Top-level key `control:`, env prefix `TOOLGATE_CONTROL_*`, `values.schema.json` with `additionalProperties: false` | §17.2 uses `control.clientCheckIn.*` and `TOOLGATE_CONTROL_CLIENT_*`, plus a schema update |
| Packages | npm scope `@olo-labs` (`@olo-labs/toolgate-contracts`); Maven `io.ololabs.toolgate:toolgate-contracts`; one root `VERSION` | SDK npm packages use `@olo-labs`; SDKs get their own version files under `tool-sdk/` |
| CI | Release templates `release-foundation.yml`, `gateway.yml`, `control.yml`, `quickstart.yml` (environments, Trivy SBOM, `attest-build-provenance`, GHCR); `admission.yml` is a reusable gate; no public registry publishing | SDK workflows follow `release-foundation.yml`; trusted publishing to Maven Central, npm, PyPI and NuGet is new |
| Roadmap | `ROADMAP.md` Phases 0–8 (Phase 5 Tool Builder, Phase 6 Marketplace, Phase 8 sandboxing) | Rewritten to point at §22 in D1 |

## 3. Integration kinds and priority

Every integration produces the same package manifest (`kind` field) and is governed identically: catalog, policy, permits, audit, kill switch.

| Kind | What it is | Executes on | Priority |
|---|---|---|---|
| `SDK` | Java/Python/TS/.NET annotated code | Tool Host (server) or endpoint client | Java **P0**, others P1 |
| `MCP_PROXY` | Existing MCP server, remote (Streamable HTTP) or packaged stdio | Tool Host proxy executor | **P0** |
| `OPENAPI` | Declarative REST operations from OpenAPI or cURL, no code | Tool Host HTTP executor | **P0** |
| `CONNECTION` | SaaS OAuth connection (per-user accounts) used by the kinds above | Credential broker | **P0 contract**, P1 delivery |
| `CONTAINER` | Existing OCI workload speaking the stdin protocol or HTTP | Tool Host / client | P1 |
| Kubernetes workload identity | Projected service-account tokens as an agent credential | Gateway auth | P1 |
| Air-gapped distribution | Offline bundle of packages, images and a signed index | Control import | P1 |
| GraphQL / gRPC adapters | Declarative like `OPENAPI` | Tool Host | P1/P2 |
| A2A, event triggers (Kafka/webhooks) | Agent-to-agent and event-driven invocation | Gateway / Tool Host | P2 |

## 4. Repository layout

```text
tool-sdk/
  spec/                 # language-neutral: package format, protocol v2, annotation semantics, MCP profile
  conformance/          # descriptor fixtures + runtime cases every SDK must pass
  cli/                  # `toolgate` CLI (Rust crate): validate, pack, sign, upload, publish, dev, import-openapi, add-mcp
  java-sdk/
    annotations/  processor/  runtime/  gradle-plugin/  maven-plugin/  testing/  examples/orders/
  python-sdk/  typescript-sdk/  dotnet-sdk/          # P1
  docs/
```

Canonical schemas stay in `packages/contracts`.

**One packager, thin language front ends.** Each SDK only does what needs the language: it reads annotations and types into an intermediate descriptor, and provides the runtime harness. Validation, canonical JSON, zip layout, digests, signing, upload and publish live in the single `toolgate` CLI. It validates offline because the Rust contracts crate already embeds the canonical schemas. Build plugins download a pinned, checksum-verified CLI.

## 5. Developer experience (Java reference)

### 5.1 Example

```java
@ToolPackage(id = "acme.orders", name = "Acme Orders", publisher = "acme")   // version from the build
@Destination(id = "acme-api", hostSetting = "AcmeApiHost", defaultHost = "api.acme.com", port = 443)
@HttpAuth(id = "acme-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "ApiKey")
@HttpRoute(id = "orders.get", destination = "acme-api", method = HttpMethod.GET,
           path = "/orders/{orderId:[0-9]{6,12}}", query = {"since"},
           headers = {"X-On-Behalf-Of"}, auth = "acme-key", timeoutMs = 10_000)
@Secret(name = "ApiKey")
public class OrderTools {

    @ServerTool(
        name = "orders.lookup",
        title = "Look up an order",                     // "text" accepted as an alias
        description = "Returns status and items for one order.",
        useWhen = "The user asks about a specific order number.",
        effect = EffectSafety.READ_ONLY,
        routes = "orders.get")                          // routes this tool may call
    @Global(name = "TimeoutSeconds", type = ValueType.INT, defaultValue = "10")
    @JwtClaim(name = "UserName", claim = "preferred_username")
    public OrderResult lookup(
            @Param(description = "Order number", pattern = "^[0-9]{6,12}$") String orderId,
            @Param(description = "Only events after this epoch second", required = false) Integer startTime,
            ToolContext context) {

        return context.http()
            .route("orders.get")                        // normative form (§5.4)
            .pathVar("orderId", orderId)
            .query("since", startTime)                  // null values are omitted
            .header("X-On-Behalf-Of", context.jwt().getUserName())
            .timeout(Duration.ofSeconds(context.global().getIntValue("TimeoutSeconds")))  // can only shorten the route timeout
            .send(OrderResult.class);
    }

    @ClientTool(name = "files.checksum", title = "Checksum a file",
                description = "SHA-256 of a file in the protected HotFolder.",
                effect = EffectSafety.READ_ONLY, tiers = RuntimeTier.OCI_SANDBOX)
    @Example(arguments = "{\"path\":\"a.txt\"}", expectedOutput = "{\"sha256\":\"...\"}")
    public ChecksumResult checksum(@Param String path, ToolContext context) { ... }
}
```

`./gradlew toolgatePackage` writes `build/toolgate/acme.orders-1.4.0.tgpkg`.

The destination host is not a global. `AcmeApiHost` is a **service-profile setting**: the admin confirms or changes it at enable, and any change is a security delta (§8.3). Globals hold non-network values only. Building a URL from a global, an argument or a claim fails packaging (§5.4).

The same tool in the other SDKs:

```python
@destination(id="acme-api", host_setting="AcmeApiHost", default_host="api.acme.com")
@http_route(id="orders.get", destination="acme-api", method="GET",
            path="/orders/{orderId:[0-9]{6,12}}", query=["since"], auth="acme-key")
@server_tool(name="orders.lookup", title="Look up an order", effect=EffectSafety.READ_ONLY, routes=["orders.get"])
def lookup(order_id: Annotated[str, Param(pattern=r"^[0-9]{6,12}$")], context: ToolContext) -> OrderResult:
    return context.http.route("orders.get").path_var("orderId", order_id).send(OrderResult)
```

```typescript
export const lookup = serverTool({
  name: "orders.lookup", title: "Look up an order", effect: "READ_ONLY", routes: ["orders.get"],
  input: z.object({ orderId: z.string().regex(/^[0-9]{6,12}$/) }),
  run: (args, ctx) => ctx.http.route("orders.get").pathVar("orderId", args.orderId).send<OrderResult>(),
});
```

```csharp
[ServerTool("orders.lookup", Title = "Look up an order", Effect = EffectSafety.ReadOnly, Routes = new[] { "orders.get" })]
public Task<OrderResult> Lookup([Param(Pattern = "^[0-9]{6,12}$")] string orderId, ToolContext context) =>
    context.Http.Route("orders.get").PathVar("orderId", orderId).SendAsync<OrderResult>();
```

Routes and destinations are declared once per package (in Python and TypeScript, in `toolgate.routes.ts` / `routes.py` or the package decorator; in .NET, assembly attributes).

### 5.2 The grouped `@ToolVariables` form is kept

```java
@ServerTool(name = "orders.list", title = "List orders", description = "...")
@ToolVariables(
    inputs  = { @Variable(name = "StartTime", type = ValueType.INT) },
    globals = { @Global(name = "Region", type = ValueType.STRING),
                @Global(name = "TimeoutSeconds", type = ValueType.INT) },
    jwt     = { @JwtClaim(name = "UserName", claim = "preferred_username", type = ValueType.STRING) },
    secrets = { @Secret(name = "ApiKey", type = ValueType.STRING) })
public OrderResult execute(ToolContext context) { ... }
```

Both forms compile to the same descriptor.

Typed `@Param` parameters are the default because the input schema then can't drift from the code. Class-level declarations apply to every tool in the class.

The annotations also carry:
- `effect = EffectSafety.READ_ONLY | IDEMPOTENT | NON_IDEMPOTENT | UNKNOWN` (default `UNKNOWN`, §11.7), plus `destructive` and `openWorld`. MCP `readOnlyHint`/`idempotentHint` are derived from `effect`. These feed policy defaults; for example, destructive tools default to ASK.
- `async = true` for tools that run as tasks (§12).
- `tiers` for client tools (§13.4).

### 5.3 Context accessors

| Purpose | Java | .NET | Python | TypeScript |
|---|---|---|---|---|
| AI input | `context.getIntValue("StartTime")` | `context.GetIntValue("StartTime")` | `context.get_int("StartTime")` | `context.getInt("StartTime")` |
| Global | `context.global().getStringValue("Region")` | `context.Global.GetStringValue("Region")` | `context.globals.get_str("Region")` | `context.global.getString("Region")` |
| JWT claim | `context.jwt().getUserName()` / `getStringValue("UserName")` | `context.JWT.GetUserName()` | `context.jwt.user_name` | `context.jwt.userName` |
| Secret | `context.secret().getStringValue("ApiKey")` | `context.Secret.GetStringValue("ApiKey")` | `context.secrets.get_str("ApiKey")` | `context.secret.getString("ApiKey")` |
| HTTP (named route, §5.4) | `context.http().route("orders.get")` | `context.Http.Route("orders.get")` | `context.http.route("orders.get")` | `ctx.http.route("orders.get")` |
| Task | `context.progress(0.4, "fetching")`, `context.isCancelled()`, `context.requestInput(...)` | same, PascalCase | same, snake_case | same |
| Invocation | `context.invocation()` (id, tool, agent, catalog revision, deadline, idempotency key) | `context.Invocation` | `context.invocation` | `context.invocation` |

Typed accessors are generated (Java processor, .NET source generator), or inferred (Python, TS). An undeclared name or the wrong type is a compile-time error where the language allows it, and a `ToolConfigurationException` otherwise.

### 5.4 Downstream login and egress

`@HttpAuth` strategies, implemented by `context.http()`:

- `API_KEY`
- `BASIC`
- `OAUTH2_CLIENT_CREDENTIALS`
- `TOKEN_EXCHANGE`: an RFC 8693 token for a downstream audience, minted by the credential broker
- `CONNECTION`: the user's connected SaaS account (§14.3)
- `CUSTOM`

**Where credentials go depends on the egress mode** (§11.6):

| Strategy | Egress mode | Secret enters sandbox? |
|---|---|---|
| `API_KEY`, `BASIC`, `OAUTH2_CLIENT_CREDENTIALS`, `TOKEN_EXCHANGE`, `CONNECTION` | **Brokered**: `context.http()` calls an approved route over the sandbox's broker socket (§11.8). The broker originates TLS and adds the credential. | No |
| `CUSTOM`, or a tool using its own HTTP or database client, only within the transport matrix (§11.9) | **Tunnel** (CONNECT, host-level policy) | Yes. The tool redeems a secret handle. Declared as permission `CREDENTIALS_IN_SANDBOX`, which is always a security delta (§8.3). |

`context.http()` always uses brokered mode, and only for declared routes. Egress control is enforced by the network boundary, never by the SDK.

#### The canonical SDK HTTP API (normative)

There is one HTTP contract across all four SDKs, and it is the broker's: **select a declared route, fill its variables, send.**

| | Java | Python | TypeScript | .NET |
|---|---|---|---|---|
| Normative | `context.http().route("orders.get").pathVar(..).query(..).header(..).body(..).send(T.class)` | `context.http.route("orders.get").path_var(..).send(T)` | `ctx.http.route("orders.get").pathVar(..).send<T>()` | `context.Http.Route("orders.get").PathVar(..).SendAsync<T>()` |
| Convenience | `context.http().get("acme-api", "/orders/{orderId}").pathVar(..)` | `context.http.get("acme-api", "/orders/{orderId}")` | `ctx.http.get("acme-api", "/orders/{orderId}")` | `context.Http.Get("acme-api", "/orders/{orderId}")` |
| Proof at build time | Annotation processor | AST analyser in the packager | TypeScript compiler plugin / AST analyser in the packager | Roslyn analyzer |

**Routes.** Declared with `@HttpRoute` / `@Destination` (or the language equivalent). The packager compiles them into the package's `ServiceProfile` (§11.8). Each tool lists the routes it may call (`routes = ...`). A tool calling a route it didn't list fails packaging, and the broker refuses it at runtime because the permit carries only that tool's route set.

**The convenience form is allowed only when the mapping is proven.** The packager accepts a URL-style call only if all of these hold:
1. the destination id and the path template are **string literals** (no concatenation, formatting, variables, globals, arguments or claims);
2. the method plus destination plus template match **exactly one** declared route, with the same variable names;
3. that route is in the calling tool's `routes`.

The analyser rewrites the call to the route id in the generated descriptor. Any call it can't prove (a non-literal argument, a match against zero or two routes, an unlisted route, or `context.http()` reached through reflection or dynamic dispatch the analyser can't follow) **fails packaging** with the source location and the candidate routes. There is no runtime URL parsing and no "best match".

**Forbidden in every SDK:** a free-form URL or host string, `.url(...)`, a base URL taken from a global, and a destination host taken from any runtime value. These methods don't exist on the API, so the restriction doesn't depend on the analyser.

**Runtime is still the boundary.** The analyser improves developer feedback; it isn't security. The broker enforces the permit's route set and service profile (§11.8) whatever the SDK did.

**Code that doesn't use `context.http()`** (its own HTTP library or a database driver) uses the tunnel transport and is limited to the release 1 transport matrix (§11.9).

### 5.5 Local development

`toolgate dev` / `./gradlew toolgateDev` runs the same artifact as a plain MCP server. It works over stdio and Streamable HTTP, speaks 2026-07-28 and accepts 2025-11-25 clients through the official SDK's compatibility support.

Globals and secrets come from a git-ignored `toolgate.dev.json`. Fixture claims are read from it only when `TOOLGATE_MODE=development` is selected explicitly (§5.9). `toolgate-sdk-testing` provides `ToolHarness` and a mock egress recorder. `@Example` cases run in `check` and become fleet `selfTests`.

### 5.6 SDK compatibility and release policy

**The SDK runtime ships inside the artifact.** The approved base image contains only a small, stable **launcher**. The launcher speaks tool protocol v2 over stdin and loads the SDK runtime bundled in the tool's own artifact. As a result:
- upgrading the SDK never needs a new base image or an admin re-approval of images;
- the base image changes only for the language runtime (JDK, CPython, Node, .NET) and the launcher.

**Version axes** each SDK release declares, and Control checks at enable:

| Axis | Declared by | Checked against |
|---|---|---|
| SDK version (SemVer) | Manifest `sdk.version` | Compatibility matrix |
| Invocation protocol range | Manifest `sdk.protocol` (for example `2..2`) | Tool Host and client supported ranges, advertised on enrollment |
| Contracts / manifest schema | Manifest `schemaVersion`, `compatibility.contractsVersion` | Control |
| Language runtime | Manifest `runtimes[]` | Approved base images |

**Compatibility policy:**
- The annotation API is stable within a major version. Deprecations last at least two minor versions, with compiler warnings.
- **Descriptor stability across SDK upgrades.** Byte-identity is too strict, because it would block legitimate fixes to generated schemas. Instead:
  - **Semantic equality is the default promise, with a deliberately limited definition.** Proving that two JSON Schemas accept the same values is undecidable in general, so ToolGate doesn't try. Two descriptors are *equivalent* only if their RFC 8785 (JCS) canonical bytes are identical after this **fixed, closed set of rewrites**:
    1. inline local, non-recursive `$ref`s;
    2. replace `type: ["x"]` with `type: "x"`;
    3. sort `required` arrays;
    4. remove a short, enumerated list of keywords whose value equals the JSON Schema default (for example `additionalProperties: true` only where the closed-object rule doesn't apply). The list is versioned in `tool-sdk/spec`.

    Anything else that differs, **including descriptions, enum order and any rewrite the list doesn't cover**, is a *change*. It goes to the API-compatibility and security-delta classifiers (§8.3). A difference those classifiers can't confidently classify is treated as **breaking and review-required**. Under this rule, an uncertain change is never waved through as equal.
  - **Corrections are versioned.** A fix that *does* change the generated schema (for example, a nullable field that was emitted as required) is released behind a new **`descriptorGeneration`** number. Each SDK release supports the current generation and the previous one.
    - Projects pin a generation in the build (`toolgate { descriptorGeneration = 3 }`). Upgrading the SDK never changes output until the project moves its pin.
    - Moving the pin regenerates the descriptor. The packager prints a semantic diff, classified as API compatibility (§8.3) or security delta. It then goes through normal review: a schema-only correction is an API change, not a security delta unless it widens permissions.
    - Security fixes in generation (for example, a missing `maxLength` that allowed oversized input) can be marked **mandatory**. The packager then refuses to build with the old generation after a stated date, and the release notes explain the impact.
  - **What the digest and review compare.** The package digest still covers exact bytes, as supply-chain integrity needs. Review and the security delta compare the *semantic* descriptor, so byte-only churn produces "no changes to review" and enable can proceed with a single confirmation.
- A new SDK feature that changes the descriptor is opt-in until the next major.

**Language baselines** (wider than revision 3, for enterprise reach):
- Java 17+ with Kotlin support through KSP / kapt;
- Python 3.10+;
- Node 20+ / TypeScript 5+;
- .NET 8+.

The official MCP SDK dependency is optional and shaded or relocated where a language allows, so it can't clash with the application's own version.

**CLI distribution without ad-hoc downloads.** The `toolgate` CLI is published through each ecosystem's normal registry, so enterprise mirrors and air-gapped builds work:
- Maven artifacts with an OS classifier;
- npm optional platform packages;
- Python wheels;
- a NuGet tool;
- GitHub releases as well.

Every distribution is signed and checksum-verified.

**Release conformance:**
- An SDK release is published only after passing the conformance suite version it declares.
- Each release page states "Conformance x.y, protocol 2, contracts 1.x".
- A public matrix in `tool-sdk/docs` lists which SDK versions work with which Tool Host and client versions.

### 5.7 The complete authoring contract (frozen at D0, before any SDK code)

The public SDK release is phased; the contract is not. Every element below is specified in full before the first SDK line is written (design gate D0, §22.1), including the parts only governed mode exercises. Standalone mode implements the same contract, never a subset with different meaning.

| Element | Normative definition |
|---|---|
| Annotations and their attributes (`@ToolPackage`, `@ServerTool`, `@ClientTool`, `@Param`, `@ToolVariables`, `@Global`, `@Secret`, `@JwtClaim`, `@HttpAuth`, `@Destination`, `@HttpRoute`, `@TcpDestination`, `@Idempotency`, `@Reconcile`, `@Example`) and their equivalents in Python, TypeScript and .NET | §5.1 to §5.4, §11.7, §11.9 |
| `ToolContext` accessors: inputs, globals, claims, secrets, `http()`, `tcp()`, invocation, progress, cancellation, input requests, logging | §5.3, §5.4, below |
| Descriptor schema (`ToolDefinition` v2) and `descriptorGeneration` 1 | §7.1, §5.6 |
| HTTP routes, destinations and the URL convenience rule | §5.4, §11.8 |
| Claims, globals, secrets: types, constraints, required values, scopes | §6 |
| Tasks: `async`, progress, `requestInput`, cancellation, `cancelIfAbandoned` | §12 |
| Effect safety, business keys and namespaces, reconcile | §11.7 |
| **Error model** | below |
| **Execution semantics** | below |
| Runtime modes and their equivalence | §5.8 |
| Identity in standalone mode | §5.9 |
| Official MCP SDK dependencies and ToolGate adapters | §5.10 |
| Library composition | §7.7 |

**Error model.** One hierarchy per SDK (exceptions in Java, Python and .NET; error classes in TypeScript), with identical codes and mapping in every language and both modes.

| Raised as | Code | Returned to the MCP client | Retryable |
|---|---|---|---|
| `ToolInputError` (also raised automatically when arguments fail `inputSchema`) | `INVALID_ARGUMENT` | Tool result, `isError: true` | No |
| `ToolBusinessError(code, message, data)` | Author code matching `^[A-Z][A-Z0-9_]{2,63}$`, declared in the descriptor's `errors[]` (an undeclared code fails packaging where analysable, and is reported as `UNDECLARED_ERROR` otherwise) | Tool result, `isError: true`, `structuredContent.error {code, message, data}` | No |
| `ToolRetryableError(code, retryAfter)` | Author code | Tool result, `isError: true`, `retryable: true` | Only when the permit's `requiresExecutionLedger` is false. For ledger tools it is downgraded to non-retryable, and the downgrade is recorded. |
| `UpstreamError` from `context.http()` / `tcp()` (route, status, upstream request id, `effectUncertain`) | `UPSTREAM_<status class>` | Tool result, `isError: true` | As above. If an uncaught `UpstreamError` with `effectUncertain = true` (timeout or reset after the request was sent) escapes a ledger tool, the invocation ends `OUTCOME_UNKNOWN`, not `FAILED`. |
| Platform errors raised by the SDK: `CANCELLED`, `DEADLINE_EXCEEDED`, `ROUTE_REFUSED`, `AUDIT_UNAVAILABLE`, `CLAIMS_UNAVAILABLE`, `CONFIGURATION` | Fixed | Tool result or task status, per §12.4 | Fixed per code |
| Any other uncaught exception | `INTERNAL_TOOL_ERROR` | Tool result with a generic message; no stack trace reaches the AI | No |

Protocol failures (bad JSON-RPC, unknown tool, missing capability) stay JSON-RPC errors and never reach tool code. Error messages and `data` pass the same redaction as logs: declared secret values and `sensitive` globals are removed.

**Execution semantics:**
- **One handler call per invocation.** The SDK never retries a handler, in either mode.
- **No state across invocations.** Tools must not rely on in-process state between calls. Governed mode uses single-use sandboxes (§11.5); standalone mode may reuse a process, so the rule is stated, tested in the mode-equivalence suite (§21) and assumed by `stateless = true`.
- **Concurrency.** Handlers may run concurrently in standalone mode and must be reentrant. `maxConcurrency` in the descriptor is honoured by both runtimes.
- **Deadline and cancellation.** `context.invocation().deadline()` is the same value in both modes. Cancellation is cooperative through `context.isCancelled()` and a language-native token; after a grace period (default 5 s) the runtime stops the handler (sandbox kill in governed mode; abandoned with the result discarded in standalone). A tool that swallows `CANCELLED` still ends cancelled.
- **Validation.** Inputs are validated against `inputSchema` before the handler runs, and outputs against `outputSchema` after; size ceilings (§13.5) apply in both modes.
- **Logging.** `context.log()` is the only supported log channel. Output is redacted and size-capped, local in standalone mode and part of the governed audit diagnostics.
- **Effects.** The no-retry and no-redirect rules for `NON_IDEMPOTENT` and `UNKNOWN` tools (§11.7) are implemented in the SDK HTTP client as well as the broker, so standalone behaviour already matches governed behaviour.

### 5.8 Runtime modes: standalone and governed

Each SDK ships two runtimes behind the one authoring contract (§5.7). The tool's semantic descriptor, and so each `toolDescriptorDigest`, is identical in both. Artifact and package digests differ whenever the bundled runtime differs (§7.2).

| Capability | Standalone | Governed |
|---|---|---|
| Tool input and output schemas | Identical | Identical |
| Authoring API | Identical | Identical |
| Route definitions | Same descriptor; the SDK HTTP client refuses undeclared routes (developer feedback, not a security boundary) | Same descriptor; enforced by the broker (§11.8) |
| Destination hosts | Local setting, validated against the declared destination | Reviewed service-profile setting (§8.3) |
| Globals | Local provider (env or config file), validated against the same declarations | Approved configuration revision (§8.4) |
| Secrets | Local credential provider (env, OS keychain, file) | Vault and the credential broker; brokered secrets never enter the sandbox |
| JWT claims | Verified identity, or an explicitly selected development fixture (§5.9) | Gateway-verified identity |
| Effect safety, retries, no-redirect | Same rules in the SDK client; at most one execution per invocation id within the process | Ledger, fencing and broker (§11.3) |
| Business keys | Local reservation store (SQLite) with the same namespace and outcome rules | `TaskStore` reservation (§11.7) |
| Tasks | Protocol-compatible local task store (memory or SQLite) implementing the Tasks extension | Durable task infrastructure (§12) |
| Audit | Local diagnostics only; no enterprise guarantee, stated in the startup banner | Governed audit (§12.7) |
| Client tools | **Unavailable** unless the endpoint device services are present; the runtime refuses to expose them and says why | Managed endpoint execution (§13.2) |
| Network isolation | None: the process has the host's network | Per-sandbox namespace, broker and transport matrix (§11.6, §11.9) |

**Required invariant: moving a tool between modes never silently gains a permission or changes an external effect.**
- **Same descriptor digest.** Governed enable shows each tool's `toolDescriptorDigest` beside the value reported by the standalone build, and verifies the package digest for integrity. The governed runtime adds only restrictions approved at review; it can't add routes, secrets, claims, destinations or a weaker effect class that the descriptor doesn't declare.
- **No mode-only API.** No SDK method, annotation or attribute works in only one mode. A capability a runtime can't honour (client tools without device services; claims without a verifier) fails loudly at startup or call time with a fixed code, never by returning empty or default data.
- **Visible differences only.** The values that legitimately differ between modes (destination host, globals, secret source, identity source) are each reviewed at enable, and the Review page shows the standalone defaults beside the governed values.
- **Proved by tests.** The mode-equivalence suite (§21) runs every example and fixture in both modes with the same inputs and compares outputs, errors, route calls (by route id and variables) and effect behaviour under injected failures.

### 5.9 Identity in standalone mode

There is no unverified-claims path outside an explicitly selected development mode.

| Mode (`TOOLGATE_MODE`) | Where claims come from | Restrictions |
|---|---|---|
| `production` (the default) | Only a verified credential: a JWT checked against a configured issuer (JWKS URL or pinned keys, issuer, audience, algorithm allowlist, clock skew at most 60 s, `exp`/`nbf` required), or RFC 7662 introspection | If any tool declares a claim, a user-scoped secret, `TOKEN_EXCHANGE` or the `DELEGATED_USER` namespace, the server **refuses to start** without a verifier. A missing or invalid token returns 401 at the transport. Over stdio there is no bearer token, so those tools return `CLAIMS_UNAVAILABLE`. |
| `development` | Fixture claims from `toolgate.dev.json` | Must be selected explicitly. The server binds only to stdio or loopback, prints a warning banner, and marks every result `_meta["io.ololabs.toolgate/identity"] = "fixture"`. Fixture claims are never accepted in `production`, and `development` refuses to start when the configuration names a production issuer or a non-loopback address. |
| `test` | Fixture claims set by `ToolHarness` | Only inside the testing library |

Claims never come from tool arguments in any mode.

### 5.10 Official MCP SDK dependencies and ToolGate adapters

Standalone runtimes use the official MCP SDKs for transports and protocol types, but **ToolGate doesn't assume the four SDKs implement 2026-07-28 identically**. Language support differs: the Java SDK is Tier 2, and Tasks is an extension that no SDK tier requires.

**Pinned dependencies and tested results.** The first compatibility record is [the 2026-10-10 compatibility record](../../tool-sdk/spec/sdk-compat/2026-10-10/results.md), with raw outputs in `raw/` beside it.
- **Suite:** `@modelcontextprotocol/conformance@0.2.0-alpha.12`. The stable 0.1.16 has no 2026-07-28 content.
- **What was run:** each SDK's own conformance server, built from the git tag of the pinned version, run against 37 required 2026-07-28 server scenarios and 30 required 2025-11-25 scenarios. Stdio was covered by probes against the registry packages.
- **Byte checks:** the PyPI wheel and the npm `dist` are byte-identical to the tag sources.

| Language | Pinned coordinate (commit) | MCP SDK tier | 2026-07-28 required server scenarios |
|---|---|---|---|
| TypeScript | `@modelcontextprotocol/server@2.3.1`, `@modelcontextprotocol/client@2.3.1` (fcef852f) | Tier 1 | **37 / 37** |
| Python | `mcp==2.3.0` (2118f14f) | Tier 1 | **37 / 37** |
| .NET | `ModelContextProtocol` 2.2.0 (6fa38259), stateless or hybrid HTTP mode | Tier 1 | **37 / 37** (stateless endpoint) |
| Java | `io.modelcontextprotocol.sdk:mcp:2.0.1`, BOM `mcp-bom:2.0.1` (c7e1cfe9) | Tier 2 | **0 / 37** (2025-11-25: 30 / 30) |

**Feature results and ownership.** In the result cells, "src" means SUPPORTED-BY-SOURCE (cited in source, not covered by the suite).

| Feature | TS 2.3.1 | Python 2.3.0 | .NET 2.2.0 | Java 2.0.1 | Owner in the ToolGate runtime |
|---|---|---|---|---|---|
| Stateless core, `server/discover`, per-request `_meta` | PASS | PASS | PASS (stateless endpoint; the stateful mode refuses 2026-07-28) | **MISSING** (stateless 7 / 27; 2026 requests get HTTP 400 with a Java stack trace) | Official SDK in TS, Python, .NET (.NET pinned to stateless or hybrid mode); **A1** in Java |
| MRTR transport | PASS (14 scenarios) | PASS (14) | PASS (14) | MISSING | Official SDK, plus **A3** for `requestState` everywhere; A1 in Java |
| Tasks extension | MISSING (9 / 10 fail; on the SDK roadmap) | MISSING (9 / 10; roadmap) | PASS (`ModelContextProtocol.Extensions.Tasks`; no status push over `subscriptions/listen`) | MISSING | **A2 in all four**, so task semantics, ownership and TTL are identical. The .NET Tasks package isn't used. |
| `subscriptions/listen` | PASS | PASS | PASS (list-changed checks skipped by its test server) | MISSING | Official SDK; .NET also needs ToolGate's `toolsListChanged` case; A1 in Java |
| Legacy 2025-11-25 | PASS (30 / 30) | PASS | PASS (separate endpoint) | PASS | Official SDK |
| stdio and Streamable HTTP | HTTP PASS; stdio by probe | Same | Same | 2025-11-25 only (stdio src) | Official SDK; A1 serves 2026-07-28 for Java on both |
| Custom-method hook | src + probe | src + probe | src, **experimental**; probe results lacked `resultType` | MISSING (private handler map) | **A5** in .NET and Java, so no experimental API is used; it adds `resultType`. Official hook in TS and Python. |
| Bearer verification | src | src | src | src (generic header validator only) | **A4 in all four**; no SDK's bearer support is used for verification |

**Known gaps and how each is closed** (all now owned by an adapter contract below):
1. **Java has no 2026-07-28 server support.** Its roadmap puts it in 3.x, which isn't on Maven Central. A1 serves 2026-07-28 for Java, and the official SDK keeps only the legacy path. A1 is retired only when an official Java release passes the same 37 scenarios.
2. **Tasks exist only in .NET**, without status push. A2 provides Tasks everywhere. Push over `subscriptions/listen` is a ToolGate case of its own, because the official suite skips that scenario for every SDK.
3. **.NET serves 2026-07-28 only in stateless or hybrid mode.** The .NET standalone runtime is fixed to that mode and serves both versions on one URL through A5 routing, matching TS and Python.
4. **.NET's custom-method hook is experimental and omitted `resultType`.** A5 handles routing for ToolGate methods instead.
5. **Bearer support is untested by the suite.** A4 is ToolGate's own code with shared fixtures.
6. **Smaller differences:**
   - .NET fails the unscored `json-schema-2020-12` scenario. ToolGate emits schemas from the descriptor, and a ToolGate case checks that `inputSchema` is served exactly as in the descriptor.
   - On stdio, TS and Python lock a connection to the version of its first message, while .NET accepts a later `initialize`. The protocol layer applies the TS and Python rule in every language.

**D0 position on SDKs:** every feature the runtime relies on is now PASS in the official SDK or owned by A1 to A5. D0 still needs those adapter contracts reviewed, and the remaining D0 artifacts.

**The compatibility record (a D0 artifact).** `tool-sdk/spec/sdk-compat/` holds one record per pinned set, frozen at D0:
- exact coordinates and the registry checksum of every resolved artifact (lockfiles committed);
- the conformance suite version and the exact commands run;
- a result per feature and language: **PASS** (test names and counts), **SUPPORTED-BY-SOURCE** (file and version cited, not yet run), **MISSING**, or **UNKNOWN**;
- known gaps, each pointing to the adapter contract that covers it.

**D0 acceptance rule.** For every feature the standalone runtime relies on, the record must show PASS, or the feature must be assigned to a ToolGate adapter whose contract below is reviewed. SUPPORTED-BY-SOURCE and UNKNOWN don't pass D0. Changing a pinned version produces a new record.

**Adapter contracts (normative, language-neutral; each SDK implements them with identical behaviour).**

The **protocol layer** sits between the official SDK and the tool runtime:

```text
RequestEnvelope { jsonrpcId, method, params,
                  meta { protocolVersion, clientCapabilities, extensions },
                  transport { kind: stdio | http, headers?, peer } }

ProtocolLayer.handle(envelope) ->
    Complete(result) | InputRequired(inputRequests, requestState) | Task(CreateTaskResult) | ProtocolError(code, data)
```

The official SDK owns framing, transports, JSON-RPC and schema types, and the legacy session path. The ToolGate layer owns tool dispatch, capability gating per request, identity verification before dispatch, `requestState`, the Tasks methods and the error mapping (§5.7).

| Adapter | Contract | Acceptance |
|---|---|---|
| **A1 Protocol core** (used where the official SDK lacks the 2026-07-28 server core; Java today) | Implements `server/discover`, `tools/list`, `tools/call` with `resultType`, per-request `_meta` validation, errors -32020 / -32021 / -32022, and `subscriptions/listen` for `toolsListChanged`. Routes requests by version: a 2026-07-28 request goes to A1; `initialize` goes to the official SDK's legacy path. Reuses the official schema types where they match. | The official conformance scenarios for 2026-07-28 servers that apply to tools, at the same pass level a Tier 1 SDK must reach, plus the ToolGate protocol cases |
| **A2 Tasks** (all languages) | `CreateTaskResult`, `tasks/get`, `tasks/update`, `tasks/cancel`, `notifications/tasks`; state mapping §12.4; ownership and TTL §12.6; never returns a task to a request that didn't declare the extension. Uses a `TaskStore` port (`create`, `get`, `transition`, `submitInput`, `requestCancel`, `purgeExpired`) with memory and SQLite implementations in standalone mode and §12 in governed mode. | ToolGate Tasks cases (capability per request, foreign identity not-found, TTL purge, state mapping) |
| **A3 `requestState`** (all languages) | Compact JWS (HS256 with a per-process key in standalone; in governed mode minted and verified by Control, which holds the key, ADR 013) over `{toolId, argsHash, identity, scope, approvalId?, exp, nonce}`; verified before any re-dispatch; single use per nonce within its lifetime | ToolGate MRTR cases (tampering, expiry, other identity, replay) |
| **A4 Identity** (all languages) | `verify(bearer) -> VerifiedIdentity {issuer, subject, claims, expiresAt} \| Reject(reason)`: JWKS caching with refresh on unknown `kid` and rate limits, issuer, audience, algorithm allowlist, `exp`/`nbf` with at most 60 s skew, RFC 7662 introspection with caching bounded by `exp`; runs before dispatch (§5.9) | Shared token fixtures (good, expired, wrong audience, `alg: none`, unknown `kid`, revoked by introspection) identical in every language |
| **A5 Method routing** (where the official SDK has no custom-method hook) | The ToolGate transport handler sees each request first, routes `tasks/*` and other ToolGate-owned methods to A2 to A4, and passes the rest to the official SDK unchanged | The same protocol compatibility cases pass with and without the hook |

**Upgrade rule.** An official SDK version changes only through a PR that re-runs the protocol compatibility suite (§21) and updates this table. The Java dependency is relocated (shaded), and the others are isolated where the ecosystem allows, so a tool's own MCP SDK version can't clash.

## 6. Variable semantics

| Kind | Declared by | Supplied by | Travels in | Visible to AI |
|---|---|---|---|---|
| Input | `@Param` / `@Variable` | AI, per call | `tools/call` arguments, validated against `inputSchema` | Yes |
| Global | `@Global` | Package default, overridden per tenant, Tool Group or execution pool | The invocation permit (digest) and the invocation (values) | No |
| Claim | `@JwtClaim` | Gateway, from the verified credential (JWT claims or introspection response) | The permit's identity section, declared claims only | No |
| Secret | `@Secret(scope = GROUP \| USER)` | Vault binding (GROUP) or connected account (USER) | Handle in the invocation, redeemed per call | No |

- **Required values.** A required global, secret or claim with no value blocks enable (globals and secrets) or fails the call before dispatch (claims).
- **Claim names.** `@JwtClaim` keeps its name for familiarity. It reads from whichever verified credential the Gateway accepted, so with an opaque introspected token it uses the introspection response.
- **Value types:** `STRING, INT, LONG, NUMBER, BOOLEAN, DATE, DATE_TIME, DURATION, ENUM, URI, OBJECT, ARRAY, BINARY`.
- **Constraints:** `required`, `defaultValue`, `min`/`max`, `minLength`/`maxLength`, `pattern`, `enumValues`, `format`, `sensitive`.

## 7. Package format (`.tgpkg`)

### 7.1 One authoritative schema

- `packages/contracts/schemas/v2/package.schema.json` (new) defines `PackageManifest` v2 and `ToolDefinition` v2.
- Every example in docs and in `tool-sdk/spec` is **generated from fixtures and validated against the schema in CI**, so prose and examples can't drift apart. Revision 2 had two different shapes; that is fixed this way.
- The plan doesn't freeze field names. P0 step 1 does, in the schema.

Shape, normative only through the schema:
- manifest: `schemaVersion: 2`, `kind`, `id`, `version`, `publisher`, `sdk`, `compatibility`, `runtimes[]`, `tools[]`, `artifacts[]`, `credentialReferences[]`, `connections[]`, `limits`;
- each tool: `toolId`, `mcpName`, `title`, `description`, `annotations`, `inputSchema`, `outputSchema`, `target`, `runtimeId`, `entryPoint`, `tiers`, `variables {globals, claims, secrets}`, `permissions`, `egress`, `httpAuth`, `idempotency`, `execution {async, deadlineMs}`, `resource`, `limits`, `toolsets`.

v1 manifests stay readable and are upgraded on read. The contract set takes a MAJOR bump.

### 7.2 Zip layout and digests

```text
acme.orders-1.4.0.tgpkg
  toolgate-package.json
  tools/<toolId>.json
  marketplace/listing.json, README.md, CHANGELOG.md, icon.png
  tests/self-tests.json
  artifacts/...
  sbom/cyclonedx.json
  CHECKSUMS.sha256          # sha256 of every file above; excludes signatures/
  signatures/               # optional, see 7.3
```

- **The package digest** is `SHA-256(canonical bytes of CHECKSUMS.sha256)`. It is *not* the digest of the zip file.
- **Effect:** adding or removing signatures never changes the digest. `ControlTool.packageDigest` and `allowedPackageDigests` pin it.
- **Determinism:** the zip uses sorted entries and fixed timestamps, so the same inputs give the same digest.

**Three identities, never conflated.** The packager computes and records all three, and each one is used only for its own purpose.

| Identity | Computed over | Same across standalone and governed? | Used for |
|---|---|---|---|
| **`toolDescriptorDigest`** (per tool) and **`descriptorSetDigest`** (the sorted set for a package) | SHA-256 of the RFC 8785 canonical bytes of the **semantic tool descriptor**: `toolId`, `mcpName`, title, description, input and output schemas, `errors[]`, effect safety, business-key settings, routes and destinations by id and template (not host values), declared globals, secrets and claims, execution settings, permissions, `descriptorGeneration`. It excludes SDK and runtime versions, artifact digests, build metadata and every deployment value. | **Required to be equal.** This is what mode equivalence (§5.8) checks. | Mode equivalence; review and security delta ("no semantic change" means equal digests); catalog entries; the permit |
| **`artifactDigest`** (per artifact) | SHA-256 of one executable artifact's exact bytes (jar, assembly, wheel, JS bundle, image) | Not required. A standalone build bundles the standalone runtime and a governed package bundles the governed runtime, so the bytes differ. | Content-addressed store, launcher verification at mount (§7.4), the permit |
| **`packageDigest`** | `SHA-256(canonical CHECKSUMS.sha256)`, the exact packaged file set (below) | Equal only if the packaged bytes are actually identical | Upload, signing, enable, rollout, pinning of invocations and tasks |

Standalone mode doesn't need a package; `toolgate dev --digests` and the standalone runtime's `server/discover` `_meta` report the `toolDescriptorDigest` values so they can be compared with a governed package.

### 7.3 Signing without a circular dependency

- **What is signed:** a DSSE envelope over an in-toto statement whose subject is the package digest above. Nothing inside `CHECKSUMS.sha256` depends on a signature.
- **Publisher signature:** optional, for example Sigstore keyless from CI. It may be placed in `signatures/publisher.dsse.json` or shipped as a detached `<file>.tgpkg.sigstore.json`.
- **Organization release and Marketplace signatures:** always detached, as `MarketplaceRelease` already is. They're stored by Control and the Marketplace, never written back into the zip.

### 7.4 Artifact form and launcher

**Base runtime images** are approved, digest-pinned and signed (§7.6). Each contains **only**:
- a language runtime (JDK, CPython, Node or .NET);
- `toolgate-launch`, a small static binary.

**They contain no SDK code.** The SDK runtime ships inside the tool's own artifact (§5.6), so SDK upgrades never change images.

**The tool artifact** (jar, assembly, wheel or JS bundle, including the SDK runtime) is mounted read-only at `/opt/tool/` from the content-addressed store after digest verification.

**The `toolgate-launch` contract:**
1. Re-verify the mounted artifact digest against the permit.
2. Expose the sandbox-local endpoints: secret-handle socket, broker address, progress channel.
3. Start the language entry point named in the manifest (for example `java -cp /opt/tool/app.jar io.ololabs.toolgate.runtime.Main`) with a cleared environment and fixed argv.
4. Pass the protocol v2 stdin/stdout frames through unchanged. The SDK runtime inside the artifact speaks the protocol.
5. Enforce frame size and deadline, and report launch failures with fixed codes.

The launcher's own version is negotiated with the Tool Host and client like the protocol range (§5.6).

`CONTAINER` packages bring their own image digest and must include a compatible `toolgate-launch` or speak protocol v2 directly. `OPENAPI` and `MCP_PROXY` run in ToolGate-owned executors and need no tool image.

### 7.5 Marketplace listing

`marketplace/listing.json` is generated from `@ToolPackage` plus build metadata. It holds display name, summary, publisher, categories, tags, license, links and icon, plus `setup`: the required globals, secrets, connections and egress hosts. It is shown before install.

### 7.6 Package and artifact verification

These checks run **at every hop**: CLI pack, Control upload, artifact mirror, Tool Host before mount, and client before mount. A failure at any hop rejects the package; nothing is repaired automatically.

**Zip rules.** Rejected if any of these is true:
- duplicate entry names;
- absolute paths, `..`, backslashes or drive letters;
- symlinks or special files;
- names that collide when case-folded or NFC-normalised;
- encrypted entries;
- entry, total or compression-ratio sizes over the ceilings (zip-bomb protection);
- data outside the central directory.

**Exact file set.** Every file except `signatures/**` must be listed in `CHECKSUMS.sha256`, and every listed file must exist. A file present but not listed rejects the package; it is never ignored.

**Canonical `CHECKSUMS.sha256`:**
- UTF-8 with LF line endings;
- one `<64 lowercase hex>  <NFC relative path>` line per file;
- sorted bytewise by path;
- no blank or comment lines.

The package digest is the SHA-256 of these exact bytes (§7.2).

**Manifest consistency:** every `artifacts[]` digest in the manifest must equal its `CHECKSUMS` entry.

**Content-addressed store.** On verification, artifacts are copied into a read-only, content-addressed store keyed by SHA-256. Sandboxes mount only from that store. This removes check-then-use races between verifying and mounting.

**Base runtime images:**
- digest-pinned and signed (for example cosign);
- the signature must come from a signer on the tenant's trusted image-signer list;
- verified when the pool or device prepares the image, not just when it is approved.

**Optional provenance.** A SLSA build provenance attestation (DSSE) from CI can be required by tenant policy. The SBOM is covered by the package digest. Upload can also run vulnerability-scan policy on it, warning or blocking.

**Signature trust.** Publisher signatures are checked against the Marketplace namespace owner's keys or Sigstore identity. Organization release signatures are checked against the tenant's release authority keys. A signature never substitutes for the digest checks above.

### 7.7 Library composition

A **tool library** is an ordinary Maven, PyPI, npm or NuGet package that carries a descriptor fragment (`toolgate-fragment.json`: library id, version, `descriptorGeneration`, namespace prefix, tools, routes, destinations, secrets, globals, claims, permissions). A tool package can include libraries; the `toolgate` packager merges the fragments with one deterministic algorithm, the same in every language.

**Default: any conflict fails packaging.** It is resolved only by an explicit namespace mapping in the build, which is recorded in the descriptor and shown at review. The packager never silently overwrites a declaration and never broadens access by merging.

| Item | Rule |
|---|---|
| Library versions | Resolved from the build's lockfile. Exactly one version per library id; two versions fail. Each library's id, version and content digest are recorded in the descriptor's `composition[]` and the SBOM. |
| Package version | Always the host project's. A library update that changes its fragment produces the normal API-compatibility and security-delta diff for the host package (§8.3). |
| `toolId` | Library tools are prefixed with the library's namespace (`<prefix>.<name>`). A duplicate `toolId` after prefixing fails. |
| `mcpName` | A duplicate fails, including a collision after client-specific shortening (§10.4). |
| Routes, destinations, secrets, globals, claims | Namespaced by library prefix by default (`acmehttp.ApiKey`), so libraries never collide by accident. **Sharing** one secret, destination or global across libraries or with the host needs an explicit mapping, allowed only when the declarations are compatible (same type and constraints; for destinations the same port, TLS settings and transport mode). Otherwise it fails. |
| Permissions | Stay per tool, never unioned at package level. A library tool can call only the routes its own fragment lists. The host may **exclude** library tools or narrow them under review, but can't widen a route pattern, add a route to a library tool, or weaken its effect class. |
| Effect safety and business keys | Taken from the library fragment. Weakening through composition fails; strengthening is allowed and recorded. |
| `descriptorGeneration` | A library fragment must use a generation the host's packager supports. It is normalised to the host's generation only through the limited rewrite set (§5.6); otherwise packaging fails. |
| Order | The merged descriptor is canonical and identical whatever order libraries are discovered in. |
| Provenance | When tenant policy requires it, library signatures (registry provenance or Sigstore) are verified at packaging and recorded in `composition[]`. |

**Mapping example** (Gradle; other build tools have the same keys):

```kotlin
toolgate {
    libraries {
        map("acme-http") {
            secret("ApiKey", to = "AcmeApiKey")          // share the host's secret
            exclude("acmehttp.debug-dump")                // don't ship this library tool
        }
    }
}
```

Review shows, for every tool, the library it came from and every mapping applied.

## 8. Upload, enable and versioning

### 8.1 Upload to a tenant (decided: one-click enable)

```text
toolgate upload pkg.tgpkg  ->  POST /api/control/v1/packages/uploads
Control:
  1. Check schema, checksums, size ceilings, secret scan and signatures.
  2. Store the sealed, immutable version.
  3. Draft for review: ControlTool records, extractor, Tool Group, a DeploymentBinding (§8.5),
     configuration form, secret/connection slots and egress requests.
Console "Review and enable":
  Shows tools, hints, a security delta (8.3), values to fill and target pools.
Admin clicks Enable:
  Org release is signed; fleet assignment and Tool Host assignment follow;
  grants go to the chosen Agent Groups; catalogs get a new revision.
```

Quickstart and dev auto-enable for the uploading admin's own agents and devices. Every call still goes through policy, ASK, permits and audit.

### 8.2 Marketplace publishing (P1/P2)

```text
toolgate publish  ->  submission  ->  scan + sandbox self-tests  ->  moderation
  ->  detached MarketplaceRelease  ->  tenant install  ->  8.1
```

### 8.3 API compatibility is separate from security review

These are two independent checks on every new version.

| Check | Looks at | Outcome |
|---|---|---|
| **API compatibility** | Input and output schemas, tool set, required inputs | Decides the *minimum semver bump* the packager allows (for example, removing a tool needs a major) |
| **Security delta** | Permissions, egress hosts or CIDRs, secrets, connections, claims, runtime tier, publisher key, base image, `kind` | **Always needs explicit admin re-approval on enable, and Marketplace re-review, whatever the version number.** A patch that adds an egress host is a compatible API change but still a security change. |
| **Security delta: service profiles** (§11.8) | Every `ServiceProfile` destination and route, compared route by route: destination host, port and TLS pins or CA; method; path template and **variable patterns**; allowed query parameters and request headers; request content types; credential binding (`httpAuth` reference and secret or connection); request and response size limits; streaming mode; timeout; redirect permission | Any **widening** is a security delta needing re-approval: a new destination or route, a broader variable pattern (for example `/orders/{id:[0-9]+}` → `/orders/{path:.*}`), new query or header names, larger limits, enabling streaming, a different credential, or removing a TLS pin. Narrowing is shown but needs no re-approval. A change the classifier can't classify counts as widening. |

The Review page shows both diffs.

### 8.4 Package version lifecycle and rollout

**States** (per package version per tenant, persisted with audit):

```text
UPLOADED ─► VERIFIED ─► IN_REVIEW ─► APPROVED ─► ROLLING_OUT ─► ACTIVE ─► DEPRECATED ─► DRAINING ─► RETIRED
                │             │                     │   ▲
                └─► REJECTED  └─► REJECTED          ▼   │ resume
                                                 PAUSED ──► ROLLED_BACK
any approved state ─► SUSPENDED (kill switch, §15) ─► (reviewed lift) previous state
```

- **VERIFIED:** passed §7.6.
- **IN_REVIEW:** security delta and values pending (§8.3).
- **APPROVED:** "Enable" clicked; org release signed.
- **ROLLING_OUT:** exposure increasing under health gates.
- **ACTIVE:** 100% of its target.
- **DEPRECATED:** still runs; new installs and new grants are discouraged.
- **DRAINING:** no new invocations; in-flight work and tasks finish.
- **RETIRED:** removed from hosts and devices; artifacts are garbage-collected after retention; audit and digests are kept.

**Rollout plan**, attached at Enable. Defaults are chosen by environment.

| | Server tools (Tool Host pools) | Client tools (fleet, Module 09) |
|---|---|---|
| Pre-stage | Prefetch the artifact into every host's content store and warm the pool; run self-tests per host. A host that fails self-tests doesn't serve the version. | Existing signed desired generation; health-gated activation per device |
| Exposure unit | **Agent cohort**: sticky hash of `(agent, package)` → %, so one agent sees one schema (§16) | Device percentage / Device Group waves (existing canary %) |
| Steps | For example 1% → 10% → 50% → 100%, each with a minimum soak time | Existing canary percentage, then waves |
| Health gates | Error rate, timeout rate, `OUTCOME_UNKNOWN` rate and p95 latency, each against the previous version's baseline over the soak window; plus self-test pass | Device activation health and self-tests (existing) plus the same invocation metrics |
| Gate failure | **Automatic pause**, then automatic rollback if configured (default on in enterprise) | Existing rollback to the previous generation |
| Manual control | Pause, resume, promote, rollback (console and API) | Same |

Quickstart and dev default to an immediate 100% with self-tests only.

**Version coexistence:**
- At most two versions per package are routable at once (current plus previous) during rollout. Policy can raise this to three.
- **Pinning:** each invocation and task is pinned to the digest chosen at admission. Tasks keep their digest to completion, even after promotion or rollback.
- **Schema changes:** each step change bumps the catalog revision for affected cohorts and emits `toolsListChanged` (§16).

**Rollback:**
- Rollback re-points exposure to the previous approved digest. For clients, that is a higher desired generation.
- Rollback never deletes the new version. It moves to `ROLLED_BACK`, and can be resumed after a fix only as a **new version**, or the same digest after review.
- In-flight invocations on the rolled-back version finish, unless the rollback is combined with a `TERMINATE` kill.

**Retirement:**
- `DRAINING` begins when a newer version is `ACTIVE` and the admin, or the auto-update policy, retires the old one.
- `RETIRED` is reached only when **no task or invocation references the digest** and the retention period has passed.
- Hosts and devices then remove the artifact. Package uninstall removes grants, bindings and catalog entries the same way.

**Configuration changes without a new version.** Changes to global values, secret bindings, egress approvals or pool targets are themselves versioned (`ToolConfigurationRevision`).
- They're audited, and roll out through the same cohort mechanism when marked "staged". They're immediate otherwise.
- A config revision is part of the permit digest, so in-flight work keeps the revision it was admitted with.

**Auto-update channels** (Marketplace or private registry), per installed package:
- `MANUAL` (default), `PATCH`, or `MINOR`.
- Auto-update still stops at `IN_REVIEW` whenever the security delta is non-empty (§8.3). Only delta-free updates proceed to rollout automatically.

**Roadmap split:**
- P0 foundation: states up to `ACTIVE`, all-at-once rollout with self-tests, and manual rollback.
- P1: cohort canaries, health gates and automatic rollback, draining and retirement automation, staged config, and auto-update channels.

### 8.5 Deployment binding and configuration precedence (independent contract)

Package content and deployment are separate. A package version says what the tool is; a **`DeploymentBinding`** says where and how one tenant runs it. It is its own versioned schema (`deployment-binding.v1.json`) with its own revisions, so changing a target pool or a setting never needs a new package.

**`DeploymentBinding` fields:**
- `bindingId`, `revision` (monotonic), `tenantId`, package id and **package digest**;
- `configRevision` (`ToolConfigurationRevision`: global values, secret and connection bindings);
- `serviceProfile` digest, including destination host settings (§11.8, §11.9);
- `target`: Tool Host pool ids (server tools) or Device Groups (client tools), with allowed runtime tiers;
- `grants` and catalog scopes (Agent Groups, agents);
- limit overrides (each at most the platform ceiling, §13.5);
- `rolloutPlan` (§8.4);
- `effectSafety`, `businessKeyNamespace` and their review approvals.

The binding's digest is part of the permit (§11.2), and in-flight work keeps the binding revision it was admitted with, as with config revisions.

**Precedence, highest first.** A higher layer always caps a lower one; it never just "wins" by being set later.

| Layer | Set by | Can |
|---|---|---|
| 1. Platform ceilings | Compiled in; operators may only lower | Cap everything |
| 2. Deployment environment (env, Helm, Kubernetes) | Operator | Bootstrap values and lower ceilings. Values marked *env-owned* (for example check-in, §17.2) can't be changed in the console. |
| 3. Tenant policy | Tenant admin, console or API | Tighten limits, restrict tiers, require ASK |
| 4. Pool or Device Group configuration | Admin | Capacity, allowed tiers, egress posture |
| 5. Deployment binding | Admin at enable | Values for this package within layers 1 to 4. Inside the binding, a pool-specific override beats a tool-group override, which beats the tenant-wide override. |
| 6. Package defaults | Author | Default values only |

**Conflict rules:**
- Two values at the **same layer and specificity** (for example two tool-group overrides for the same field) are rejected when saved, never resolved by timestamp.
- A lower layer exceeding a higher one is rejected when saved, with the limiting layer named. If a higher layer is later lowered, affected bindings show "capped by tenant policy" and the effective value changes in a new binding revision.
- **Env versus database:** env and Helm supply bootstrap defaults and ceilings. Database values are runtime settings inside those ceilings. On start-up, the database is never overwritten by env except for env-owned fields, which are always read from env and shown read-only.
- **Effective value API:** `GET /api/control/v1/deployments/{bindingId}/effective` returns every field with its value and the layer it came from. The console shows the same, so an admin can always see why a value is what it is.

Backup and restore (§18) exports bindings and tenant settings, but not env-owned values or secrets (only their references).

## 9. Identity, authentication and catalog scope

### 9.1 Three separate concepts

| Concept | Question | Decided by |
|---|---|---|
| Authentication | Which user, agent or workload is calling? | The credential only (§9.2) |
| Authorization | What may that identity do? | Control, on every request: grants, delegations, policy (§11) |
| Catalog scope | Which permitted tools should this MCP endpoint show? | The URL (§9.4). It only narrows, never widens. |

### 9.2 Credential classification

**Tenant first, from the endpoint.**
- The tenant is resolved from the request endpoint: a tenant hostname, or the configured single tenant in Quickstart.
- It is never taken from the token's `iss`. This prevents cross-tenant confusion when two tenants trust the same IdP.
- Only issuers in *that tenant's* trusted list are considered.

**Exactly one token format per issuer.** Each `TrustedIssuer` declares `tokenFormat: JWT | OPAQUE_INTROSPECT`. A combined "JWT or introspect" mode is not allowed.

The bearer value is classified in a fixed order and handed to exactly one validator. A failure is a `401`, with no fallback:

| Step | Rule | Validator |
|---|---|---|
| 1 | Value has the tenant's static prefix (`tg_`) **and** the tenant has static tokens enabled | Existing hash lookup (`auth.rs`) |
| 2 | Value is a three-segment compact JWS whose unverified `iss` equals a tenant issuer with `tokenFormat: JWT` | JWT validation (below) |
| 3 | The tenant has exactly one `OPAQUE_INTROSPECT` issuer for this endpoint's audience | RFC 7662 introspection at that issuer |
| 4 | Anything else | `401` + RFC 9728 `WWW-Authenticate` |

If a tenant configures more than one introspection issuer for the same audience, the configuration is rejected when saved. That keeps step 3 unambiguous.

**JWT validation:**
- `typ` must be `at+jwt` (RFC 9068), so ID tokens and other JWTs are refused as access tokens;
- signature from the issuer's JWKS, with an `alg` allowlist (never `none`, no HMAC for external issuers);
- `iss`, plus `aud` matching this Gateway's resource identifier (RFC 8707);
- `exp` and `nbf` with small clock skew;
- optional `jti` replay cache.

**Introspection:**
- client-authenticated (mTLS or `private_key_jwt`);
- `active`, `iss`, `aud`, `exp`, `token_type` and scopes are checked;
- positive results are cached for at most `min(exp, 60 s)`, negative results for at most 5 s;
- the cache is purged on any revocation push (§15).

**Key rotation:** JWKS is cached, with a rate-limited refetch on an unknown `kid`. Keys removed from JWKS stop validating once the cache refreshes.

`WORKLOAD` credentials (P1) are JWT issuers of type `KUBERNETES` or `SPIFFE`, with their own audience and subject rules.

### 9.3 Mapping to agent, user and delegation

Each `TrustedIssuer` has a **claim-mapping template**. ToolGate ships templates for common IdPs; custom templates are allowed and validated when saved.

| Template | Agent (client) claim | User claim | Service-token discriminator |
|---|---|---|---|
| Entra ID | `azp` (v2) / `appid` (v1) | `oid` (stable), with `tid` checked against the issuer | `idtyp == "app"`, or no `scp` and `roles` present |
| Okta | `cid` | `uid` / `sub` | `sub == cid` |
| Auth0 | `azp` | `sub` | `gty == "client-credentials"` |
| Keycloak | `azp` | `sub` | `sub` is the client's service-account user (configured) |
| Ping | `client_id` | `sub` | `sub == client_id` |
| Google | `azp` | `sub` | issuer- and audience-specific rule (configured) |
| Introspection (any) | `client_id` | `sub` / `username` | `sub == client_id`, or configured |

Each token is evaluated against its issuer's template and must yield **exactly one** profile. Zero or two matches reject the token.

| Profile | Recognised by | Agent | User |
|---|---|---|---|
| `SERVICE` | The template's service discriminator is true | Client claim → `ControlWorkloadBinding` (mode `SERVICE`) | none |
| `DELEGATED_CLIENT` | Discriminator false, no `act`. A user token issued to the agent's client, for example via auth-code flow with user consent. | Client claim → `ControlWorkloadBinding` (mode `DELEGATED`) | User claim → `ControlIdentityBinding` |
| `DELEGATED_EXCHANGE` | `act` present, and the issuer is marked `delegationTrusted` (RFC 8693) | `act.sub` → `ControlWorkloadBinding` | `sub` → `ControlIdentityBinding` |

**Delegation always needs two approvals:**
1. **The issuer** must have issued a user token to that client, or an exchange token with `act`.
2. **Control** must allow it: the agent's workload binding is mode `DELEGATED`, and its `delegatedUserId` or a delegation rule covers the user.

Claims are read only through the issuer's validated template, never from an arbitrary claim name supplied at runtime.

**Other rules** (unchanged):
- nested `act` chains map to `RequestContext.chain`, bounded by `maximumDepth`;
- IdP `groups`/`roles` never grant membership;
- epochs gate cached decisions;
- `RequestContext` carries no device.

**Conformance** (§21) includes recorded sample tokens for each shipped template. Every template is tested for exactly-one-profile behaviour.

### 9.4 Catalog scope and the URL routes

`CatalogScope` is a new type, separate from `RequestContext`:

```text
CatalogScope { kind: ALL | AGENT | AGENT_GROUP | AGENT_IN_GROUP, agentId?, agentGroupId?, toolsets?[], revision }
```

**Public routes.** These are Rahul's, kept stable; they compile to a `CatalogScope`:

| Route | Scope | Authorization requirement |
|---|---|---|
| `/mcp` | `ALL`: everything the identity is permitted | — |
| `/mcp/agent/{agent}` | `AGENT` | The authenticated agent *is* `{agent}`, or holds an issuer-approved delegation to act for it (recorded in `chain`) |
| `/mcp/agent-group/{group}` | `AGENT_GROUP`: only tools permitted *through* `{group}`, even if the caller has other groups | Caller is an enabled member of `{group}` |
| `/mcp/agent-group/{group}/{agent}` | `AGENT_IN_GROUP` | Both of the above |

**Precedence:**
- The most specific route wins.
- The URL never changes *who* the caller is, only *which permitted tools are shown and callable on this endpoint*.
- Every `tools/call` re-checks that the tool is inside the scope **and** authorized for the identity.

Other rules:
- **Mismatch:** the same `403`/JSON-RPC error and no catalog, with no fallback scope and no hint about whether the group exists.
- **Segments:** URL-safe slugs (`^[a-z0-9][a-z0-9-]{0,62}$`) on agents and groups (decided).
- **Future combinations:** added as named scopes in the `CatalogScope` model, not as new route shapes.
- **Toolsets** (`?toolset=finance`) filter **visibility only**. Authorization never reads them.
- **Decision cache:** optional, short-lived, keyed by `(identity, epochs, CatalogScope, policy revision, authorization epoch)`.

## 10. MCP transport layer (version-aware)

### 10.1 Modern core: 2026-07-28

Each property, and how the Gateway handles it:
- **Sessions:** none. Every request carries `io.modelcontextprotocol/protocolVersion` and `clientCapabilities` in `_meta`, and is independently authenticated and authorized. The Gateway already enforces this.
- **Discovery:** `server/discover` advertises supported versions, capabilities, `extensions` (including `io.modelcontextprotocol/tasks`) and server info.
- **Results:** every result carries `resultType`: `complete`, `input_required` (MRTR), or `task` (Tasks extension, only when the client declared it on that request, §12.4).
- **Subscriptions:** `subscriptions/listen`, with opt-in `toolsListChanged` and server-tagged `subscriptionId`. Progress flows on the originating request's response stream.
- **Logging:** per-request `io.modelcontextprotocol/logLevel`. The deprecated Logging feature isn't added.
- **Removed methods:** `ping` is removed from the modern path and stays available only in the legacy adapter. The Gateway currently answers it at `mcp.rs:144`.
- **Errors:** the renumbered codes (`HeaderMismatch` -32020, `MissingRequiredClientCapability` -32021, `UnsupportedProtocolVersion` -32022), returned with the supported versions.
- **Tracing:** `traceparent` / `tracestate` / `baggage` in `_meta` are propagated (§19).
- **Stream loss:** a broken stream loses the request, and clients re-issue it with a new id. Non-idempotent safety therefore comes from §12, not from transport redelivery.

### 10.2 Legacy compatibility adapter

- **Placement:** a separate module in front of the core, so the core never grows session state.
- **Supported versions (decision B, ADR 018):** `2025-11-25` and `2025-06-18`, for at least twelve months, matching the MCP deprecation window. Any other version gets `UnsupportedProtocolVersionError`.
- **What it translates:** `initialize`, `notifications/initialized`, `ping`, `Mcp-Session-Id`, the GET SSE stream and `notifications/tools/list_changed` are mapped onto the stateless core.
- **Sessions are a transport artifact only.** The adapter re-authenticates and re-authorizes every request, and **never pins identity or scope to a session**.
- **Anything the legacy version can't express** (MRTR, Tasks) degrades as described in §12.4.

### 10.3 Catalog behaviour

- `tools/list` is returned in a deterministic order, with `ttlMs` and `cacheScope: private`.
- The cache key is protocol version + negotiated extensions + `CatalogScope` + identity + **catalog revision**.
- Capabilities exposed per version: `outputSchema`, `annotations` and task support only where the negotiated version and extensions allow.

### 10.4 Tool names

| Field | Meaning | Rules |
|---|---|---|
| `toolId` | Internal, stable identifier used by policy, audit and permits | Existing `Identifier` pattern |
| `mcpName` | Name shown to MCP clients | MCP naming guidance: 1–128 characters, `A–Z a–z 0–9 _ - .`. Default `<packageAlias>.<tool>`. Unique within a catalog scope, enforced at enable time. |
| `compatibilityAlias` | Optional per-client compatibility name | Applied by a **compatibility profile**, configured on the workload binding or catalog scope. The built-in profile `llm-strict` is `^[a-zA-Z0-9_-]{1,64}$`. |

The `llm-strict` profile is a ToolGate compatibility setting, not an MCP requirement.

How an alias is derived, deterministically:
1. Replace disallowed characters with `_`.
2. If the result is longer than the limit, or collides within the scope, truncate it and append `_` plus the first 8 hex characters of `SHA-256(toolId)`.
3. If it still collides, Control rejects the enable with the conflicting tools named.

Calls accept the `mcpName` or the alias, and both resolve to the `toolId` before policy.

## 11. Authorization permits and Tool Host admission

### 11.1 Flow

```text
Agent → Gateway: classify credential → authenticate → CatalogScope
Gateway → Control: POST /access/invocations   (Control evaluates policy, ASK, budgets, business key; durable invocation)
Gateway → Control: POST /access/invocations/{id}/reserve  → permit (Control-signed, single-use nonce, aud = chosen Tool Host or device)
Gateway → Tool Host (mTLS): permit + arguments
Tool Host → Control (mTLS workload identity): permits/consume (nonce) → EXECUTING → sandbox → effects/report
Gateway, Tool Host: durable audit spool → Control / SIEM
```

This keeps today's invariant: every effect needs current Control authority, and there is no offline ALLOW. Client tools keep the existing relay path.


### 11.2 The permit and the invocation id

**`invocationId`** is created once by the Gateway for each accepted `tools/call`. It stays the same across every dispatch attempt and links the task row, the ledger claim, audit and results. A permit is per attempt; the invocation id is not.

**The permit:**
- **Issuer:** Control, through the existing `/reserve` path and its effect-signing key domain, with `kid` rotation (decision A, revised in revision 10; ADR 013). The Gateway evaluates no policy.
- **Claims:**
  - `jti`, which is per attempt;
  - `iss`, Control's effect-signing key id;
  - `aud`, the one Tool Host instance chosen for this attempt;
  - `iat`/`nbf`/`exp`, with `exp` at most the attempt's admission window, which is short, not the execution deadline;
  - identity and epochs;
  - `toolId`, `toolDescriptorDigest`, `packageDigest`, the entry `artifactDigest`, runtime and globals digests, and the **service-profile digest and revision** (§11.8);
  - canonical arguments hash, action and resources;
  - the tenant authorization epoch and the single-use nonce (the fencing token, §12.8);
  - approval proof;
  - `invocationId`, `attempt`, `effectSafety`, `businessKey?`, `businessKeyNamespace?` and `taskId?`;
  - the tool's **route set** (§5.4) and the **deployment binding digest and revision** (§8.5).

### 11.3 Admission handshake

Dispatch is a two-step exchange on the mTLS stream:

```text
Gateway → Host : DISPATCH {permit, arguments}
Host           : verify permit (signature, aud, window, jti, args hash, digests incl. service profile, policy sequence, authorization epoch, capacity)
Host → Control : permits/consume (nonce) → invocation RESERVED → EXECUTING (§12.8); required for every non-built-in tool
Host → Gateway : ADMITTED {invocationId, fencingToken}   or   REFUSED {reason}   (definitive)
Host           : start sandbox only after ADMITTED is sent
Host → Gateway : progress … RESULT {invocationId, fencingToken, outcome}
```

**Execution classification, resolved once.** At enable, Control computes each tool's **resolved effect class** from its `effectSafety` (§11.7):

```text
requiresExecutionLedger = (effectSafety == NON_IDEMPOTENT) OR (effectSafety == UNKNOWN)
```

**Where it is fixed:** the class is stored with the package version and configuration revision, and carried in the permit (`effectSafety`, `requiresExecutionLedger`).

**Who uses it:** admission, the retry rules (§11.4), the broker's no-retry and no-redirect rules (§11.8), task-store persistence (§12.1) and revocation checkpoints all read **the permit's** resolved class. None of them re-derives it.

**No bypass:**
- A missing annotation resolves to `UNKNOWN`.
- v1 packages and legacy translations resolve to `UNKNOWN`.
- `OPENAPI` and `MCP_PROXY` tools resolve to `UNKNOWN` unless a stronger value was approved at review.
- Weakening the class, for example `UNKNOWN` → `IDEMPOTENT`, is a security delta (§8.3).

**Execution ledger** (every tool with `requiresExecutionLedger`):
- the existing invocation row in Control, moved `RESERVED → EXECUTING` **once** by consuming the permit's single-use nonce;
- the row is never reclaimed, because such an invocation can't be re-dispatched after admission;
- the claim returns a **fencing token**, invalidated by cancellation or kill;
- secret redemption, broker requests and result writes present the token.

What the ledger guarantees is **at most one execution per invocation**. It doesn't make external effects exactly-once (§11.7).

Tools with `requiresExecutionLedger = false` (`READ_ONLY`, `IDEMPOTENT`) skip the ledger and keep the low-latency path.

### 11.4 Retry rules

In this table, "non-idempotent" means the permit's resolved `requiresExecutionLedger = true` (`NON_IDEMPOTENT` or `UNKNOWN`), and "idempotent" means it is false (§11.3).

| What the Gateway observed | Idempotent tool | Non-idempotent tool |
|---|---|---|
| `REFUSED` (busy, digest not present, stale sequence) | Retry on another host with a new permit | Same: refusal is definitive, nothing ran |
| Connection failed before the request was sent | Retry elsewhere | Retry elsewhere |
| Sent, but no `ADMITTED`/`REFUSED` received (timeout or reset) | Retry elsewhere | **Ambiguous: never retried elsewhere.** Gateway queries Control by `invocationId`. If claimed, it waits for or collects the outcome. If unclaimed after the admission window, the permit has expired and can't be admitted late, so it is safe to retry. |
| `ADMITTED` received, then the stream broke | Collect the result from the outbox by `invocationId` | Same; the outcome is never re-executed |
| Host crash after `ADMITTED` | Retry allowed (idempotent) | Lease expires → `OUTCOME_UNKNOWN`, surfaced to the caller, no automatic retry |

The rules that make "unclaimed and expired" safe:
- the admission window is short (seconds);
- hosts refuse permits past `exp`;
- the ledger claim must happen before `exp`.

**MCP client re-issues** after a broken stream arrive with a new JSON-RPC id. They are matched to the existing `invocationId` through the idempotency key or the dedupe window (§12.3), so they never create a second invocation for non-idempotent tools.

**Other failure cases** (same as revision 3):
- **Revocation mid-run:** the checkpoints in §15 apply.
- **Audit:** written to a fail-closed local spool.
- **Duplicate permits:** rejected by the per-host `jti` cache and the `aud` binding.

### 11.5 Sandbox isolation and warm pools

- **Key:** pools are keyed by `(tenant, package digest, runtime digest, tool, isolation tier, egress profile)`, never shared across tenants.
- **Default: single-use.** Sandboxes are pre-created warm (image pulled, process started, waiting for its first input), used once and destroyed. Pre-starting removes most of the start-up cost.
- **Opt-in reuse** applies only to tools declaring `stateless = true`. Reuse stays within the same tenant **and** the same identity class: one reuse pool per (agent, user) pair, or per agent for SERVICE-mode calls. It is capped by count and age.
  - Secrets are never cached in a reused sandbox. Handles are per invocation and expire at completion.
  - Scratch space is wiped between invocations.
- Proxy, OpenAPI and SDK executors all follow the same rules.

Direct dispatch and durable orchestration coexist. Short synchronous calls take §11's direct path. Tasks (§12) use the task store, and the same permit and admission rules apply when a worker picks them up.

### 11.6 Egress isolation

The sandbox runs untrusted code, so network policy is enforced **outside** it, in layers.

**1. Network namespace per sandbox.**
- The only interface is a veth whose routes reach **only that sandbox's own** egress listeners, attributed by ingress interface rather than by anything the sandbox sends.
- No default gateway; IPv6 follows the same path.

**2. Host firewall (nftables / Kubernetes NetworkPolicy).**
- The namespace may reach only its listeners.
- Raw DNS (53, 853), all other UDP (including QUIC) and ICMP are dropped.
- The sandbox has no resolver.

**3. Two explicit egress modes, chosen per approved destination at review:**

| | **Brokered** (default for declared HTTP APIs) | **Tunnel** |
|---|---|---|
| How the sandbox connects | Route calls over its per-sandbox broker Unix socket with an invocation-bound capability (§11.8), usually via `context.http()`. No free-form URLs. | HTTP CONNECT through the proxy listener |
| Who does TLS to the upstream | The broker, outside the sandbox, verifying the upstream certificate against the system CA store or an admin-pinned CA or SPKI | The sandbox, end to end. ToolGate can't see inside. |
| Policy granularity | Host, method, path template, header allowlist, body size | Host and port only |
| Credential injection | **Yes**: the broker adds `httpAuth` credentials and never returns them to the sandbox | **No.** If the tool needs a credential, it enters the sandbox (`CREDENTIALS_IN_SANDBOX`, a security delta). |
| Idempotency key, reconcile evidence, request log | Yes (§11.7) | Connection-level log only |
| Interception CA in the sandbox | Not needed | Not used. ToolGate never intercepts tunnels. |

**Rules for both modes:**
- **Exclusive modes.** A destination approved as brokered **can't** be reached by tunnel; CONNECT to it is refused. Brokered credentials therefore can't be bypassed by a tool that opens its own TLS connection.
- **Address checks:** the proxy or broker resolves names itself and checks **every resolved address at connect time**. It always blocks link-local, metadata, loopback and cluster CIDRs. Private ranges are allowed only through admin-approved `(host, CIDR, ports)` entries. Each new connection and each redirect hop is re-checked; the broker follows redirects only within approved destinations, and never for non-idempotent methods.
- **Tunnel checks:** the ClientHello SNI must equal the approved CONNECT host. A mismatch or an encrypted ClientHello toward a non-approved front domain is refused.

**4. Broker and proxy isolation.**
- Separate processes in their own namespace, with NetworkPolicy allowing only approved destinations.
- They can't reach the Kubernetes API, node metadata, Control, Gateway admin listeners or other tenants' brokers.
- They have no admin interface on the sandbox side.
- One broker identity per (tenant, pool). Credentials are fetched per invocation with the fencing token and held only in broker memory.

**5. ToolGate-owned executors.** OPENAPI and MCP_PROXY executors run inside the broker trust domain (no untrusted code), using brokered semantics directly.

**6. Kill and revocation.** Every new connection checks the kill-list and revocation sequence. `TERMINATE` drops open connections and invalidates the invocation's fencing token.

**7. Audit:**
- brokered: method, host, path template, status, bytes, upstream request id;
- tunnel: host, resolved IP, port, bytes.

Payloads aren't logged.

**Clients:** release 1 has no client egress. A future client egress tier must meet this same contract.

### 11.7 Effect guarantees (what ToolGate does and doesn't promise)

ToolGate controls whether and how often a tool *runs*. It can't make an external system apply an effect exactly once, and a guarantee per `invocationId` is **not** a guarantee per business operation. If a client loses a response and re-issues the request without a stable key, the Gateway can't tell an accidental retry from a deliberate second order.

#### Effect safety, declared per tool

`effectSafety = READ_ONLY | IDEMPOTENT | NON_IDEMPOTENT | UNKNOWN`. This replaces the separate `readOnly` / `idempotent` hints in the contract.

- **Who declares it:** the author, in an SDK annotation (`effect = EffectSafety.NON_IDEMPOTENT`). For OpenAPI operations and proxied MCP tools, it is set at review.
- **Default:** `UNKNOWN` when not declared. ToolGate never infers it from the HTTP method. A `GET` may have side effects, and a `POST` may be reliably idempotent.
- **Who sees it:** it is shown in review, and any change to it is a **security delta** (§8.3).
- **MCP hints are derived from it**, never the other way round: `readOnlyHint` = `READ_ONLY`; `idempotentHint` = `READ_ONLY` or `IDEMPOTENT`.

| Safety | Retry and execution | Broker and `context.http()` behaviour |
|---|---|---|
| `READ_ONLY` | At-least-once; may retry on another host | May retry per policy |
| `IDEMPOTENT` | At-least-once; may retry on another host | May retry per policy |
| `NON_IDEMPOTENT` | **At-most-once execution** per `invocationId` (ledger) | Never retries any request in the invocation, whatever the method |
| `UNKNOWN` | **Treated as `NON_IDEMPOTENT`**, and policy may also require ASK | Same as `NON_IDEMPOTENT` |

#### Business idempotency keys for high-risk tools

**Declaring it:** an admin, or the package, can mark a tool `requiresBusinessKey`. That's the default for `NON_IDEMPOTENT` + `destructive`, and for payment- or order-like tools at review.

**What the client must send:** a stable key, either in the `Idempotency-Key` header already in `gateway-v1.yaml`, or in a declared argument such as `clientReference`.
- **Scope:** set by the tool's business-key namespace (below).
- **On repeat:** the same key returns the original invocation's task or result. A different key means a new business operation.

**When the client supplies no key**, the tool's policy decides:
- `REJECT` (default for `requiresBusinessKey`): an error naming the missing key;
- `ASK`: a human confirms that this is a new operation, so an accidental re-issue becomes a visible, deliberate decision;
- `ALLOW_WITH_DEDUPE`: argument-hash dedupe within `dedupeWindow`. This is documented as heuristic, because it can't distinguish a retry from an intentional repeat.

When the upstream supports idempotency, the business key, not the `invocationId`, is what the broker forwards through the adapter contract (§12.3). The upstream then deduplicates repeats from different invocations too.

#### Business-key namespace (per tool)

Each tool that uses business keys declares **one namespace**, approved at review and carried in the permit (`businessKeyNamespace`). Changing it is a security delta (§8.3).

| Namespace | Means | Unique constraint | Allowed when | Typical use |
|---|---|---|---|---|
| `AGENT` (default) | The key identifies an operation of one agent | `(tenantId, agentId, toolId, keyHash)` | Any auth mode | An agent placing orders for its own workflow |
| `DELEGATED_USER` | The key identifies an operation of one human, through any agent acting for them | `(tenantId, userId, toolId, keyHash)` | Only DELEGATED calls with a verified `userId` (§9.3). SERVICE-mode calls are rejected with `BUSINESS_KEY_NAMESPACE_MISMATCH`. | "Submit my expense report", safe across the user's several agents |
| `TENANT` | The key is a tenant-wide business identifier, such as an external order number | `(tenantId, toolId, keyHash)` | Any auth mode, approved at review | Integrations where the upstream key is global to the tenant |

**One namespace drives everything.** For a given tool, the same namespace identity (`agentId`, `userId`, or none) is used by:
- **the unique constraint** above;
- **the fingerprint**, which deliberately *excludes* the namespace identity, since that is already in the key. It holds canonical `argumentsDigest`, package major version, resolved effect class and catalog scope kind. In `AGENT` and `TENANT` namespaces it also holds the caller's `userId` when present, so the same key from a different user is a conflict, not a replay;
- **the forwarded upstream value** (§12.3);
- **retention and tombstones**, configured per namespace (`businessKeyRetention`), with the same table and purge job.

**Reservation scope.** `reserveBusinessOperation` takes the namespace and the namespace identity resolved from the **authenticated** request (never from arguments). Its signature becomes:

```text
reserveBusinessOperation(tenantId, toolId, namespace, namespaceIdentity, businessKey, fingerprint, callerIdentity)
    -> NEW(invocationId) | EXISTING(invocationId, state) | CONFLICT | EXPIRED_KEY
```

**Cross-identity conflicts reveal nothing.** A reservation row records the caller identity that created it (agent and user).

| Situation | Response to the second caller |
|---|---|
| Same caller identity, same fingerprint | `EXISTING`: the original result or task |
| Same caller identity, different fingerprint | `IDEMPOTENCY_CONFLICT` |
| **Different caller identity** (in `TENANT`, any other agent or user; in `AGENT`, the same agent acting for a different user), any fingerprint | `IDEMPOTENCY_KEY_IN_USE`: no invocation id, task id, result, status or timestamps, and the same response and timing whether the fingerprint matches or not |
| `DELEGATED_USER`, same user, different agent, same fingerprint | `EXISTING`, but only if the second agent is also authorised for the tool now. The result is returned through the second agent's own catalog scope check. |

In `TENANT`, returning the first caller's result to a different caller requires an explicit tool setting `shareResultsWithinTenant = true`, approved at review. Without it the rule above applies. Audit always records both callers.

**Credential ownership.** The namespace and the credential the broker uses must agree, so one user's key can't drive another user's account:

| Credential source | `AGENT` | `DELEGATED_USER` | `TENANT` |
|---|---|---|---|
| Service secret or OAuth client (tenant-owned) | Yes | Yes | Yes |
| Token exchange for the calling user | Yes, DELEGATED calls only | Yes | Only with review approval |
| User-scoped secret or `CONNECTION` (§14.3) | Only DELEGATED calls, for that user | Yes. The broker looks the account up by **the permit's `userId`**, never by an argument. | **Rejected** at review unless approved as an exception |

SERVICE-mode calls can never use a user-scoped credential.

#### Atomic business-operation reservation

Lookups done before minting an `invocationId` aren't enough with several Gateway replicas. Two concurrent requests with the same key can both see "absent". Reservation is therefore one durable, atomic operation in the `TaskStore`, `reserveBusinessOperation`, with the signature given under the namespace rules above.

**Uniqueness:** a unique constraint on the namespace key (table above). The **reservation row, the invocation row and its task or ledger row are inserted in the same transaction**. The first committer wins; every other replica's insert fails on the constraint and reads the winner's row, then applies the cross-identity rules above.

**Fingerprint:** as defined under the namespace rules above.

**Outcomes:**

| Situation | Result |
|---|---|
| No row | `NEW`: the invocation is created in the same transaction |
| Row exists, same caller identity, fingerprint matches | `EXISTING`: return the same invocation's result, or its task / in-progress status (`IN_PROGRESS`, retryable, for clients without Tasks). It is never executed again. |
| Row exists, same caller identity, fingerprint differs | **`IDEMPOTENCY_CONFLICT`**. The old result is never returned for different arguments or context. |
| Row exists, different caller identity | `IDEMPOTENCY_KEY_IN_USE`, revealing nothing (above) |
| Row past retention, tombstone present | **`EXPIRED_KEY`** for `requiresBusinessKey` tools. Policy then rejects (default) or sends the call to ASK; a reused old key never silently creates a second high-risk operation. |

**Retention:**
- Retention is set per namespace. The full row (result included) is kept for `businessKeyRetention`. The default is 30 days for high-risk tools, and never shorter than the upstream's declared idempotency window.
- After that, a **tombstone** holds `(keyHash, fingerprint, outcome, timestamps)` for at least a year, or per tenant policy. Results are purged; the tombstone is not.

**Dedupe window** (`ALLOW_WITH_DEDUPE`): uses the same operation, in the tool's namespace, with a synthetic key `hash(toolId, fingerprint)` and an `expiresAt`. An insert succeeds only if no unexpired row exists, done as an atomic conditional upsert. It stays labelled heuristic.

**The task store is a hard dependency for these tools.** If it's unavailable, calls to tools that need a reservation fail with a retryable error, which is fail-closed. They never proceed unreserved.

#### Reconciliation: evidence, not a guarantee

A tool may declare `@Reconcile` (SDK) or an OpenAPI status operation (`x-toolgate-reconcile`). It receives the business key, the `invocationId` and the arguments, and answers `APPLIED`, `NOT_APPLIED` or `UNKNOWN`, each with **evidence**: the upstream record id and commit status.

- **How ToolGate uses the answer:** it updates the invocation's outcome and audit when it ends `OUTCOME_UNKNOWN`.
- **Wording:** ToolGate describes the result as **"effectively once" only when the downstream gives definitive commit evidence**. That means the upstream acknowledged the idempotency key, or the reconcile answer cites a committed upstream record. Every other case stays "at most once per invocation" with the evidence attached.
- **Re-execution after `NOT_APPLIED`:** opt-in, only under the same business key and a new `invocationId` linked in audit, and never automatic for tools without the hook.

#### Rules that keep this honest

- **No hidden retries.** For `NON_IDEMPOTENT` / `UNKNOWN` tools, `context.http()` and the broker retry nothing. Tool code is told not to retry effects. The packager warns when a known retry library is configured.
- **Broker evidence.** The broker records each brokered request's route, status and upstream request id. `OUTCOME_UNKNOWN` therefore carries evidence for operators and for reconciliation.

### 11.8 Broker request channel

The broker must not become a general-purpose HTTP proxy that a compromised tool can aim at privileged destinations. Every broker request is bound to the invocation and its approved service profile. The sandbox can only *select among routes it was granted*; it never names a destination freely.

**1. Transport.**
- A **Unix domain socket** is bind-mounted into the sandbox at `/run/toolgate/broker.sock`, one socket per sandbox and created for that invocation. The broker attributes each connection by the socket it accepted on, never by request content.
- The sandbox gets no TCP broker listener. For tunnel mode, the proxy listener stays separately attributed (§11.6).

**2. Invocation-bound capability.**
- At sandbox start, the launcher receives an opaque, unforgeable **broker capability**. It is a MAC'd token bound to `(invocationId, fencing token, sandbox id, service profile id, expiry ≤ invocation deadline)`.
- The broker accepts a request only with the capability that matches its socket, and only while the fencing token is valid (§11.3). It refuses after cancel, kill or expiry.

**3. Approved service profile, enforced on the server side.** Approved at review and pinned in the permit (digest and revision). The broker loads the profile **by the permit's digest** from the content store and refuses the request if that profile isn't present or isn't approved, so it always enforces exactly the profile approved at admission. Each `ServiceProfile` holds:
- `destinations[]`: `{id, scheme: https, host, port, tls: {caBundle | spkiPins}}`;
- `routes[]`: `{id, destinationId, method, pathTemplate (RFC 6570, typed variables with patterns), allowedQuery[], allowedHeaders[], requestContentTypes[], maxRequestBytes, maxResponseBytes, streaming: NONE | RESPONSE | BIDIRECTIONAL (default NONE), timeoutMs, auth: httpAuth ref}`.

**4. Request form.** The sandbox does not send URLs:

```text
POST /v1/call  (over the socket)
Toolgate-Capability: <capability>
{ "route": "orders.get", "pathVars": {"orderId": "123456"}, "query": {...}, "headers": {...}, "body": <bytes|json> }
```

The broker resolves the route, then builds the upstream URL from the approved destination and template.
- **Path variables:** validated against their patterns, then percent-encoded. `/`, `..` and encoded traversal are rejected.
- **Query parameters:** only allowlisted names; unknown ones are rejected.
- **Methods:** the route's method is fixed; any other is rejected.
- **Content type and size:** checked against the route.
- **Streaming:** refused unless the route allows it, with byte and time caps.

**5. Header and authorization protection.**
- Client-supplied `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `X-Forwarded-*`, and any header named in the route's auth config are **stripped and rejected**. They are never merged with injected values.
- The broker sets `Host` from the destination and injects credentials last.
- Responses are filtered before they reach the sandbox: `Set-Cookie` and auth-challenge details are removed.

**6. Limits and safety.**
- Per-invocation and per-route request rates, total egress bytes, and concurrent-connection caps.
- Upstream redirects are followed only within the same destination and an allowed route, and never for `NON_IDEMPOTENT` / `UNKNOWN` tools.
- Address checks at connect time follow §11.6.

**7. Audit.** Every broker decision (accepted or refused) is written to the invocation's audit with: `invocationId`, route id, destination id, method, status, bytes, duration, upstream request id and the refusal reason. Bodies and credentials are never logged.

**`context.http()` maps onto routes.** The SDK API is defined once, in §5.4: named routes are normative, and a URL-style call is accepted only when the packager proves it maps to exactly one declared route in the tool's route set. The permit carries that route set, and the broker refuses any other route, whatever the SDK did.

### 11.9 Transport matrix (release 1)

Release 1 supports exactly two ways for a sandboxed server tool to reach a network. Everything else is unavailable, and the packager or enable step says so rather than letting it fail at runtime.

| Transport | Status in release 1 | How the tool uses it | Policy enforced outside the sandbox | Credentials |
|---|---|---|---|---|
| **Brokered HTTP(S)** | **Supported** (default) | `context.http().route(...)` (§5.4) over the broker socket | Destination, method, path template, query, headers, sizes, streaming, TLS verification (§11.8) | Injected by the broker; never in the sandbox |
| **HTTP(S) through CONNECT tunnel** | **Supported** | The tool's own HTTP client, configured to use the proxy explicitly (`HTTPS_PROXY` set by the launcher, Java `-Dhttps.proxyHost`, .NET `HttpClient.DefaultProxy`, Node `undici` `ProxyAgent`) | Host and port, SNI equals the CONNECT host, address checks (§11.6) | `CREDENTIALS_IN_SANDBOX`, a security delta |
| **TCP through CONNECT tunnel** to an approved `(host, port)` | **Supported** | A client library that accepts a proxy or socket factory: `context.tcp().connect("orders-db")` returns a connected stream; Java `ToolGateSocketFactory` for JDBC drivers with a `socketFactory` option (for example PostgreSQL); Python and Node helpers return a socket for libraries that accept one | Host, port and address checks. **No protocol inspection**, and no SNI check unless the protocol is TLS from the first byte. | `CREDENTIALS_IN_SANDBOX`, a security delta |
| Database drivers that can't use a proxy or socket factory (unmodified JDBC/ODBC drivers without the option, Oracle OCI, native `libpq` builds) | **Unavailable** | — | The sandbox has no route; connections fail fast | — |
| Unix sockets to anything outside the sandbox (Docker socket, host services) | **Unavailable** | — | Not mounted; only the broker and secret sockets exist | — |
| UDP, QUIC/HTTP3, ICMP, multicast, raw DNS | **Unavailable** | — | Dropped by the firewall (§11.6) | — |
| Transparent interception of unmodified clients | **Unavailable** | — | ToolGate doesn't intercept traffic | — |
| Native binaries that ignore proxy settings | **Unavailable** | — | No default route | — |
| Inbound listeners | **Unavailable** | — | No inbound path | — |

**Declaring TCP.** `@TcpDestination(id = "orders-db", hostSetting = "OrdersDbHost", port = 5432)` or the equivalent. The destination becomes a tunnel-mode entry in the service profile, approved at review, and part of the security delta. Hosts are settings reviewed at enable, never globals.

**Packager checks.** The packager reports, per tool, which transports it declares. It warns when it detects a known database driver dependency without a declared TCP destination, and it fails when a tool declares a transport marked unavailable.

**Clients.** Client tools have no network in release 1 (§13.4).

**Later, not release 1 (DEFERRED, ADR 013):** brokered database adapters (the broker holds the database credential and exposes named, parameterised queries as routes), gRPC routes, and a client egress tier.

## 12. Durable tasks

### 12.1 When a task record exists

A record is created when any of these is true:
- the tool is `async`;
- the call needs ASK approval;
- the call can't finish inside its synchronous deadline;
- the target is a client tool (it is already queued today);
- the permit's resolved `requiresExecutionLedger` is true (`NON_IDEMPOTENT` or `UNKNOWN`): it always gets an execution-ledger row (§11.3);
- a business key or dedupe window applies: it always gets an atomic reservation row (§11.7).

Plain short synchronous calls create no task record: their invocation row, which every governed call has (§11.3), gets no task columns, business-key row or result row ([invocation-state-v2.md](../control-plane/invocation-state-v2.md) §1, ADR 016).

"`TaskStore`" in this plan means Control's invocation state, reached only through Control's API (§12.8): PostgreSQL in production, SQLite in Quickstart. Where §12.7 says a component writes the `TaskStore`, that component calls the Control endpoint listed in §12.8.

### 12.2 State machine (persisted before each acknowledgement)

```text
ACCEPTED ──► AWAITING_APPROVAL ──► (approved) QUEUED   (denied/expired) ► REJECTED
ACCEPTED ──► QUEUED ──► DISPATCHED(lease) ──► RUNNING ──► COMPLETED | FAILED
RUNNING  ──► INPUT_REQUIRED ──► RUNNING                 (MRTR / tasks/update)
any non-terminal ──► CANCEL_REQUESTED ──► CANCELLED | COMPLETED (race recorded)
DISPATCHED/RUNNING ──(lease expiry, effect uncertain)──► OUTCOME_UNKNOWN
QUEUED ──(pickup timeout)──► EXPIRED  or  QUEUED again (only if idempotent)
```

Each transition records the actor (Gateway, Control, Tool Host, client or admin), the time and the reason. Terminal results are retained for a configurable period. Progress keeps only the latest value, plus a bounded history.

### 12.3 Idempotency

**Inside ToolGate:**
- **Business keys** and **dedupe windows** both go through the atomic `reserveBusinessOperation` (§11.7): a unique constraint, inserted in the same transaction as the invocation.
- A matching repeat returns the existing invocation. A different fingerprint gets `IDEMPOTENCY_CONFLICT`. An expired key gets `EXPIRED_KEY`.
- There is no separate "check, then mint" step anywhere.

**Upstream, only through an adapter contract:**
- The key is forwarded only when the tool or adapter declares the upstream semantics, for example `@Idempotency(header = "Idempotency-Key", scope = PER_INVOCATION)` or `x-toolgate-idempotency`.
- **Forwarded value**, by the tool's business-key namespace (§11.7):
  - `TENANT`: the client's key, unchanged, since it is already tenant-wide;
  - `AGENT`: `a.<agentIdHash>.<key>`; `DELEGATED_USER`: `u.<userIdHash>.<key>`. The hashes are keyed per tenant, so the upstream can't link identities across tenants. A tool may declare `forwardRawKey = true` when the upstream needs the raw value and the namespace guarantees it is unique there; this is shown at review;
  - no business key: a value derived from the `invocationId`, stable across attempts of one invocation.
- Otherwise nothing is forwarded.

**Retries:** tools with `requiresExecutionLedger` are never retried automatically after admission (§11.4).

### 12.4 Mapping to the MCP Tasks extension

From the Tasks extension (`io.modelcontextprotocol/tasks`):
- **Negotiation:** the client declares the extension in each request's `_meta` client capabilities, and the server advertises it in `server/discover`.
- **Server-directed:** the server decides per request whether to return `resultType: "task"`, and **must never return a task to a client that did not declare support on that request**.
- **The task:** a `CreateTaskResult` carries `taskId`, status, `ttlMs` and `pollIntervalMs`, and must be durably created before the response.
- **Methods:** clients poll `tasks/get`, answer `inputRequests` via `tasks/update`, and cancel cooperatively via `tasks/cancel`.
- **Notifications:** optional `notifications/tasks`, through `subscriptions/listen`.

How ToolGate states (§12.2) map to MCP statuses:

| ToolGate state | MCP `status` | Notes |
|---|---|---|
| ACCEPTED, QUEUED, DISPATCHED, RUNNING | `working` | `statusMessage` gives the detail, for example "waiting for device" |
| AWAITING_APPROVAL | `input_required` | `inputRequests` holds one URL-mode elicitation to the approval page. Approval completes out of band; ToolGate satisfies the request itself and moves on. |
| INPUT_REQUIRED (tool asked) | `input_required` | The tool's own `inputRequests`, answered by `tasks/update` |
| CANCEL_REQUESTED | `working` | until terminal |
| COMPLETED | `completed` | `result` = what `tools/call` would have returned |
| FAILED, REJECTED, EXPIRED | `failed` | JSON-RPC error with ToolGate code (`APPROVAL_DENIED`, `PICKUP_TIMEOUT`, …) |
| OUTCOME_UNKNOWN | `failed` | Code `OUTCOME_UNKNOWN` with `retryable: false` and an explanation that the effect may have happened |
| CANCELLED | `cancelled` | |

### 12.5 Clients that don't negotiate Tasks

**Principle: never start work the client can't collect.**

| Tool | Client with Tasks | Client without Tasks (MRTR-capable or legacy) |
|---|---|---|
| Fits the sync deadline | Normal result (no task) | Normal result |
| `async` or may exceed the sync deadline | Task | **Not executed.** Hidden from `tools/list` for that request's capabilities; a direct call gets `MissingRequiredClientCapability` (-32021) naming the Tasks extension. The admin can opt a tool into **bounded sync mode** instead: run only if the declared max duration fits the sync deadline. |
| Needs ASK approval, client supports MRTR | Task with `input_required` | `InputRequiredResult` with URL elicitation. **Execution starts only on the client's retry after approval**, so an abandoned approval leaves no running work. `requestState` is signed, bound to identity, scope, tool and args hash, and expires with the approval. |
| Needs ASK approval, legacy client | Task | Error with approval link (today's behaviour); no execution |
| Client tool queued for an offline device | Task | If pickup can't happen within the sync deadline: `PICKUP_TIMEOUT` before anything runs |

### 12.6 Task ownership, lifetime and orphans

**Binding.** A `taskId` is an unguessable 128-bit id bound to `(tenant, authenticated identity, CatalogScope)`.
- `tasks/get`, `update` and `cancel` from any other identity or scope return not-found, never forbidden, so the call reveals nothing.
- Tokens that rotate for the same identity keep access.

**TTL:**
- `ttlMs` covers the task's whole retention. Each poll extends retention by up to `pollIntervalMs × N`, never past a hard maximum per tenant.
- After the TTL, the result is purged and the task is gone.
- Admin-visible audit keeps the outcome summary, not the payload.

**Orphans (a client stops polling):**
- Running work continues to its own deadline. Results are held until the TTL, then purged.
- **Opt-in auto-cancel:** a tool may declare `cancelIfAbandoned`, so no poll within K × `pollIntervalMs` triggers `CANCEL_REQUESTED`. This suits expensive idempotent work.
- `INPUT_REQUIRED` and `AWAITING_APPROVAL` tasks with no answer expire to `EXPIRED` at their input deadline; nothing runs further.

**Restarts and loss:**
- Gateway restarts never lose tasks; the state is in the task store.
- If the task store is unavailable, no new tasks are created and calls that need one get a retryable error. That fails closed.

### 12.7 Persistence transitions (normative)

Each row says what is durably written, when the next party is told, what happens if a step fails, and whether the tool may run. "Commit" means the transaction is durable in the `TaskStore` (PostgreSQL synchronous commit; SQLite WAL with `synchronous=FULL` in Quickstart). "Spool" means an fsync'd append to the local audit spool.

| # | Step | Durable write (one transaction unless stated) | Acknowledged to the next party | Failure and recovery | Execution may proceed? |
|---|---|---|---|---|---|
| 1 | **Request accepted** | Gateway spool: `ACCEPTED` record with `invocationId` and request digest | — | Spool append fails: request rejected with a retryable error before any state exists | No, not yet |
| 2 | **Business-key reservation + invocation creation** (when a key, dedupe, ledger or task applies, §12.1) | `TaskStore`: reservation row, invocation row, task row and ledger row (`UNCLAIMED`), together | After commit: the Gateway continues; a task client gets `CreateTaskResult` only now | Commit fails or times out: the Gateway re-reads by key (or by `invocationId`, which it generated). Found means it committed; absent means retry the transaction once, then a retryable error. A unique-constraint failure returns the winner's row (§11.7). | Only after commit |
| 3 | **ASK approval decision** | State database (§12.8), one transaction run by Control: approval row `PENDING → APPROVED/DENIED`, with a single-use `consumed` flag, plus the task transition `AWAITING_APPROVAL → QUEUED` | Gateway learns through the task store or Control stream after commit | Unknown outcome: query by approval id. Control unavailable: the task stays `AWAITING_APPROVAL` until its input deadline, then `EXPIRED` | No, until consumed |
| 4 | **Approval consumption** | State database: `taskstore.consume_approval()` swaps `consumed = false → true` tied to `invocationId` and arguments hash | Permit minting starts after the swap commits | Swap lost (already consumed by another attempt of the same invocation): reuse that attempt's state. Different invocation: refused. Unknown outcome: re-read the flag. | Only after commit |
| 5 | **Permit minted** | None in the database. Gateway spool: `PERMIT_ISSUED` (jti, aud, exp) | Sent with `DISPATCH` | Spool full: no permit (row 11) | — |
| 6 | **Host admission** | Ledger tools: ledger CAS `UNCLAIMED → CLAIMED(host, fencingToken)` in the `TaskStore`, **then** host spool `ADMITTED` | `ADMITTED` sent only after both | `TaskStore` unavailable: `REFUSED(STORE_UNAVAILABLE)`, nothing ran, retry allowed (§11.4). CAS succeeds but `ADMITTED` is lost: the Gateway queries the ledger (§11.4). Host spool fails after the CAS: the host marks the claim `ABANDONED_BEFORE_START` with the fencing token and sends `REFUSED`; if it can't, the lease expires to `OUTCOME_UNKNOWN` although nothing ran, and evidence says "not started". Non-ledger tools: host spool only. | Only after `ADMITTED` is sent |
| 7 | **Running** | Ledger: `RUNNING` with lease heartbeat (every lease/3). Secret redemption and each broker request append to the host spool and present the fencing token | — | Lease heartbeat can't commit: the host keeps running until the lease ends, then terminates the sandbox; the invocation becomes `OUTCOME_UNKNOWN` | Yes, while the fencing token is valid |
| 8 | **Result** | Host local outbox (fsync) first, then `TaskStore`: terminal state + result payload + outbox delivery row, CAS on the fencing token | Gateway gets `RESULT` after the store commit; a task client sees it on the next `tasks/get` | Store unavailable: the result waits in the host's durable outbox and is retried with backoff; the Gateway reports `working`. Fencing token invalid (killed or cancelled): the result is recorded as evidence only and the state stays `CANCELLED`. A late result after `OUTCOME_UNKNOWN` may move it to `COMPLETED` or `FAILED` with the evidence attached, never the other way. | — |
| 9 | **Terminal result retention** | Payload kept for the task TTL or `businessKeyRetention`, whichever is longer. Then one transaction replaces the payload with a tombstone (§11.7) | — | Purge job is idempotent and resumable | — |
| 10 | **Audit shipping** | Spools ship to Control / SIEM asynchronously, at least once, each record carrying a dedupe id | Spool segments are truncated only after the receiver acknowledges | Receiver down: spool grows; see row 11 | Not affected while the spool has room |

**Plain short synchronous calls** (no key, no ledger, no task, no ASK) write rows 1, 5 and 6 to the spools only, and return the result directly.

**Row 11: audit spool unavailable or full while work is running.** The spool has a hard size and a **reserved headroom** (default 10%) that only terminal, cancel, kill and recovery records may use.

| Spool state | New requests | Work already running | Results |
|---|---|---|---|
| Healthy | Accepted | Runs | Recorded |
| Above the soft limit (default 80%) | Accepted; alert raised | Runs | Recorded |
| **Normal space exhausted** (only headroom left) | **Rejected** with a retryable error (Gateway) or `REFUSED(AUDIT_UNAVAILABLE)` (Tool Host) | Continues, but each **new** secret redemption, broker request or tunnel connection needs a spool append, so it is refused. Tools see `AUDIT_UNAVAILABLE`, which the SDK surfaces as a non-retryable error for that request. | Terminal records use the headroom |
| **Headroom exhausted, or the spool disk fails** | Rejected | The host enters **`FENCED`**: it terminates every non-built-in sandbox, invalidates their fencing tokens, stops accepting work and reports unhealthy. Invocations end `OUTCOME_UNKNOWN` (ledger tools) or `FAILED` (others), resolved from the ledger and broker evidence once the spool recovers. | Recorded in the `TaskStore` when possible; otherwise in memory and replayed to the spool on recovery |

Execution never continues without the ability to audit its effects. The cost is that an audit backlog can stop tools, so spool size and the soft-limit alert are part of the deployment checks (§8.5).

**`TaskStore` unavailable, per step:** reservation is rejected (retryable) and nothing runs (row 2); ASK can't be consumed (row 4); ledger admission is refused (row 6); running ledger work ends at lease expiry (row 7); results wait in the host outbox (row 8). Plain synchronous calls to tools that need none of these continue.

### 12.8 Physical transaction model (frozen)

**Decision: Control's existing database is the only state database, and only Control writes to it.** Approvals, invocations (including task state), business-key reservations, permit nonces, budgets, reconciliations and the outbox all live in Control's PostgreSQL (`public` schema, `control_` table prefix), or in Quickstart's SQLite. Every write runs inside Control's existing per-tenant transaction with `pg_advisory_xact_lock` and the Java invariant checks (`PostgresStore.java:39-47`). Approval decisions and task transitions are therefore committed in one ordinary transaction.

**Gateway and Tool Host never get database access.** They call Control's API over HTTPS, as ADR 006 requires:

| §12.7 step | Control endpoint (existing or extended) | Transaction inside Control |
|---|---|---|
| Reservation + invocation creation (row 2) | `POST /access/invocations`, extended with business key, namespace and fingerprint (`InvocationSubmission`) | Table `control_business_keys`, primary key `(tenant, tool, namespace, namespace_identity, business_key_hash)`, inserted with the invocation (revised at D2: a tombstone outlives the invocation's result; ADR 016) |
| Approval decision and consumption (rows 3, 4) | Existing approval endpoints; `POST /access/invocations/{id}/reserve` | Approval state, invocation state and single-use nonce in one transaction |
| Admission / ledger claim (row 6) | Existing `permits/consume`, now also for Tool Host workload certificates | `RESERVED → EXECUTING` with nonce consumption: the nonce is the fencing token |
| Running (row 7) | Existing secret delivery, extended to Tool Host identity; new lease heartbeat | Checks `EXECUTING` and the nonce |
| Result (row 8) | Existing `effects/report`, plus a new result payload table for task results above the relay queue's 64 KiB cap | Terminal state + result + outbox row |

**Local files meet the database only through idempotent outboxes:**
- **Tool Host result outbox → Control** (row 8): fsync locally, then `effects/report` keyed by `(invocationId, nonce)`. Re-sending is a no-op; a stale nonce records evidence only.
- **Audit spools → Control / SIEM** (row 10): at least once, deduplicated by record id.

**Quickstart:** the same Control code path on SQLite. Each change ships two migrations (Flyway and the Quickstart runner, whose version cap is raised).

**Cost accepted:** each server-tool call makes the same 2–3 Control round trips today's calls make. Control must therefore be sized and replicated for the call rate. It is already a hard dependency for every effect.

**Rejected:** Gateway or Tool Host database roles (they would bypass Control's Java invariants and the tenant lock), and separate databases for approvals and tasks.

## 13. Execution

### 13.1 Server tools: Tool Host

`apps/tool-host` is a stateless Rust supervisor, horizontally scaled with HPA/PDB, and registered as system executor `TOOL_HOST`.
- Admins group hosts into pools (Device Groups), for example per region or per sensitivity level.
- Hosts admit as described in §11 and run executors:
  - the SDK harness runner;
  - the `OPENAPI` HTTP executor;
  - the `MCP_PROXY` executor;
  - `CONTAINER`.
- Hosts autoscale on in-flight invocations, not CPU.

### 13.2 Client tools: existing endpoint client

Unchanged trust model: signed assignment, mTLS, lease, fresh authorization, journal.

Placement is either `ANY_IN_GROUP` or `USER_DEVICE`, chosen by the execution binding. Concurrency becomes per-runtime, for example four sandboxes, capped by the tool's `maxConcurrency`. Today a client runs one job at a time.

### 13.3 Tool protocol v2

The stdin input carries:
- `protocolVersion: 2`, `invocationId`, `taskId?`, `toolId`, `entryPoint`;
- `arguments`, `globals`, `claims`;
- secret **handles**, never values;
- `deadlineUnixMs`, `idempotencyKey?`, `catalogRevision`, `traceparent`;
- `fencingToken` for non-idempotent tools, checked when secrets are redeemed and results are written.

The output carries `resultType`, `structuredContent`, `content[]`, `isError`, `error {code, retryable}`, plus progress and input-request frames for long-lived harness mode.

v1 is still accepted.

### 13.4 Runtime isolation tiers

| Tier | Runtime | Trust model |
|---|---|---|
| `OCI_SANDBOX` | OCI sandbox (today's ADR 009 boundary) | Primary isolated execution |
| `WASI` | WASI-compatible module | Restricted, capability-based execution. Only for tools that build to WASI. Language support is partial (subsets of Python, JS and .NET), not equivalent to ordinary apps. |
| `HOST_NATIVE` | Signed, approved binary with OS isolation (AppContainer, macOS sandbox profile, Linux landlock + seccomp) | OS-specific and weaker than the container tier. Allowed only under enterprise policy. |

Rules:
- Tools declare the tiers they support. The packager verifies the artifacts exist for each declared tier.
- Admins set allowed tiers per Device Group or pool. Sensitive groups default to `OCI_SANDBOX` only.
- **There is never automatic fallback to a weaker tier.** If the allowed tier isn't available on a device, the call fails with `UNSUPPORTED_TIER`.
- In release 1, client tools stay compute plus built-ins, with no network or secrets (decided).

### 13.5 Limits with hard ceilings

Three layers. Each must be at most the one below it:
1. tool request, in the manifest;
2. pool or device configuration, set by the admin;
3. **platform ceiling**, compiled-in defaults that operators can only lower.

Starting ceilings, for review: sync deadline 60 s (the effective deadline is the lower of this and the Gateway's request timeout, at most 30 s in release 1; ADR 016); task deadline 15 min; memory 4 GiB; input 1 MiB; output 4 MiB; 256 argument properties; signed descriptor 1 MiB; 64 tools per package.

Each value raises today's v1 limit (64 KiB, 32 properties, 32 KiB descriptor) under the v2 schema. Negotiation can never exceed a ceiling.

## 14. Adoption: existing MCP servers, OpenAPI, SaaS connections

### 14.1 MCP proxy (P0)

**Registration**
- **Remote:** Streamable HTTP URL, plus upstream auth (secret, OAuth client credentials, token exchange or connection).
- **Packaged:** a `.tgpkg` of `kind: MCP_PROXY` containing the server artifact or image, run on Tool Host. Being digest-pinned, it is **immutable**, so this is the preferred form for third-party servers.

**Capability snapshot and approval**
1. Discovery: `server/discover`, falling back to the legacy probe only in the proxy's client role, then `tools/list` using the upstream auth profile that real calls will use.
2. ToolGate stores a snapshot. It records server identity (`serverInfo` name and version, TLS certificate SPKI pin for remote servers) and, per tool, a **definition digest** over the name, description, input and output schemas and annotations.
3. Admin approves a subset. Tools are registered as `toolId = mcp.<server-slug>.<tool>` and governed like SDK tools.

**What the model sees.** Model clients only ever receive ToolGate's **approved snapshot** of each upstream tool: name, description, schemas and annotations. They never see the upstream's live metadata. A changed description (the classic rug pull) can't reach the model until an admin re-approves it.

**Call-time enforcement.** These checks don't depend on cache freshness:
- the Gateway validates arguments against the approved `inputSchema` before dispatch;
- the proxy executor validates the result against the approved `outputSchema` (when present), with size and type caps;
- a validation failure blocks the result, quarantines the tool and alerts.

**Freshness is defence in depth, not the guarantee:**
- **Normal mode:** the proxy keeps a per-auth-profile cache of the upstream `tools/list`, refreshed at `min(upstream ttlMs, 60 s)` and on `toolsListChanged`, and compares definition digests to the approved ones. A mismatch quarantines the tool and blocks further calls.
- **Strict mode** (per server): before each call, fetch a fresh `tools/list` for the calling auth profile and compare digests. This costs one extra round trip.

**Residual risk, documented:** MCP can't bind a `tools/call` to a definition version. Even in strict mode, an upstream can change between the check and the call, or change behaviour without changing metadata. The only complete control is a **packaged** (digest-pinned, immutable) server, which is why it's preferred for third-party servers.

**Mutable upstream defences**

| Threat | Defence |
|---|---|
| Upstream changes a tool's description or schema (rug pull) | The model only sees the approved snapshot. The freshness checks above (normal or strict mode) quarantine on digest mismatch. Input and output validation catch schema drift at call time. |
| Upstream swaps the server (DNS or host takeover) | TLS SPKI pin, or a CA plus hostname policy chosen at approval. A `serverInfo` name change quarantines the whole server; a version change triggers re-review of the snapshot. |
| Upstream renames or adds tools | Unapproved names are never exposed. Calls are made only to approved upstream names. |
| Per-user catalogs (the upstream lists different tools per credential) | Snapshots are taken per auth profile. Users of a `CONNECTION` profile only get the intersection of the approved set and their live list. |
| Same name and schema, different behaviour | Can't be detected from metadata. Mitigations: prefer packaged (immutable) servers, output validation against the approved `outputSchema`, size caps, and anomaly signals in audit. Documented as residual risk. |
| Upstream output carries prompt injection | Results are marked untrusted in `_meta` (`io.toolgate/untrusted-upstream`) and never mixed into tool descriptions. Size and type caps apply. |
| Upstream asks for input (MRTR `input_required`) | Mediated: the proxy forwards an upstream input request only if the tool's policy allows elicitation, and always under ToolGate's own `requestState`. Otherwise it fails. |
| Upstream returns its own task handle | Never exposed directly. The proxy wraps it in a ToolGate task (§12) and polls upstream on the caller's behalf, so ownership and TTL rules apply. |

**Call path:** Gateway policy → permit → proxy executor on Tool Host. Egress is limited to the upstream through §11.6, and upstream auth is injected by the proxy layer. Results are validated and returned.

Upstream resources and prompts are P1, with the same snapshot and verification.

### 14.2 OpenAPI / REST import (P0)

`toolgate import-openapi spec.yaml`, or the console upload, produces a `kind: OPENAPI` package with no code. It runs on the Tool Host HTTP executor through the §11.8 broker. Each selected operation becomes one tool **and one `ServiceProfile` route**.

**Inputs:** OpenAPI 3.1 and 3.0 natively. Swagger 2.0 is converted first, with the conversion recorded in the report.

**Parameter serialisation** (the broker serialises exactly as the operation declares):

| Location | Supported styles | Notes |
|---|---|---|
| path | `simple`, `label`, `matrix` (`explode` true/false) | Variable patterns are derived from the schema (`pattern`, `format`, `enum`); otherwise a conservative default (no `/`) |
| query | `form`, `spaceDelimited`, `pipeDelimited`, `deepObject` | `allowReserved` is honoured; `allowEmptyValue` is mapped explicitly |
| header | `simple` | Reserved and auth headers can't be parameters |
| cookie | `form` | Off by default; enabled per operation at review |

A combination the broker can't serialise faithfully makes the operation **not importable**. It is listed in the report, never approximated.

**Request bodies and content negotiation:**
- **Content type:** exactly one request content type per tool is chosen at review, from `application/json`, `application/x-www-form-urlencoded`, `multipart/form-data` and `text/plain`, or `application/octet-stream` as a `BINARY` input. It is fixed on the route.
- **Multipart:** each part's content type and encoding come from the `encoding` object. File parts become size-capped `BINARY` inputs.
- **`Accept`:** fixed to the response media type chosen at review.

**Responses and status codes:**
- The 2xx response schema becomes `outputSchema`. Multiple 2xx schemas become a `oneOf` with a status discriminator.
- **4xx** → `isError: true` with the parsed error body, not retryable, except 408 and 429, which are retryable only when `requiresExecutionLedger` is false.
- **5xx** → error, retryable under the same rule.
- **3xx** is followed only within the route rules (§11.8).
- Undeclared statuses → `UPSTREAM_ERROR` with the status code.

**Pagination:** never followed automatically. If the operation declares a pattern (`x-toolgate-pagination`: cursor, next link, offset/limit) or the admin selects one at review, the tool takes the cursor as an input and returns one page plus the next cursor.

**Authentication alternatives:**
- OpenAPI `security` is an OR of AND-sets. At review the admin picks **exactly one** alternative per operation, and it becomes the route's credential binding.
- Unsupported schemes (for example mutual TLS with client certificates the tenant has no binding for) make the operation not importable.

**Effect safety:** imported operations default to **`UNKNOWN`**, so they take the ledger path. A stronger value comes only from:
- an administrator at review; or
- an `x-toolgate-effect` declaration in a document from a **trusted source**, meaning a signed publisher package.

Even then, the value is shown in review and is a security delta.

**Provenance:**
- The package stores the **original document** (`openapi/source.*`) and its digest.
- It also stores a **transformation report** (`openapi/transform-report.json`). For each operation, the report records the generated tool and route, the parameter and serialisation mapping, the chosen content type and auth alternative, dropped or approximated features, warnings, and why any operation was not importable.
- Re-importing a new document version produces a semantic diff against the previous report. It is classified as API compatibility and security delta (§8.3).

**Other:** cURL import produces the same package shape for a single endpoint, with the same report.

### 14.3 SaaS connections: P0 contract, P1 delivery

Contract records, defined now so tools can declare them from day one:
- `ConnectionProvider`: OAuth endpoints, scopes, and a client credential reference;
- `ConnectedAccount`: tenant, `userId`, provider, granted scopes, a refresh-token secret reference, and status;
- `@Secret(scope = USER)` and `httpAuth: CONNECTION`.

How it works:
- **Consent:** when the user has no account for the provider, the call returns MRTR URL-mode elicitation to a consent page, or a task enters `INPUT_REQUIRED`.
- **Delivery:** the credential broker refreshes tokens and delivers short-lived access tokens over the same per-invocation path as secrets.

## 15. Emergency kill switch

**Scopes:** package (id or digest), publisher, tool, execution pool, runtime image digest, tenant-wide (all non-built-in tools).

**Modes:**
- `STOP_NEW`: hide from catalogs, refuse admission, let active invocations finish.
- `TERMINATE`: also cancel active invocations. The sandbox is killed, the egress proxy drops connections, and secret redemption is refused because the fencing token is invalidated.

**Inputs:**
- admin action (two-person approval when the enterprise review workflow is on);
- Marketplace advisory;
- SIEM/SOAR API;
- break-glass inside Control (§15.4).

**Reversal:** a new reviewed entry, never a deletion.

### 15.1 Kill events and merge

**Events, not a single sequence.** The kill state is a **grow-only set of immutable, signed events**. Ordering never depends on reserved numeric ranges.

```text
KillEvent     { eventId: "<issuerId>:<issuerCounter>", issuer, scope, mode: STOP_NEW|TERMINATE, reason, issuedAt, signature }
RecoveryEvent { eventId: "<issuerId>:<issuerCounter>", issuer, supersedes: [eventId...], reviewers[], issuedAt, signature }
```

- **Issuers:** `control` (normal entries) and `control-breakglass` (§15.4), each with its own monotonic counter. The `eventId` is globally unique and never reused.
- **Kill events** can be issued by Control or break-glass.
- **Recovery events** can be issued **only by Control** after two-person review. Break-glass can never issue them (§15.4).

**Merge rule** (deterministic, order-independent): a component's state is the union of every valid event it has seen from any path. The effective kills are:

```text
effective = { k ∈ KillEvents | no RecoveryEvent r in the set with k.eventId ∈ r.supersedes }
```

If several effective kills cover the same target, the strictest mode wins (`TERMINATE` over `STOP_NEW`).

**Invariant: a kill remains effective until a separately authorised recovery event supersedes it.** Events are stored in Control and merged as a union, so replaying or reordering pushes to Tool Hosts and clients can never remove a kill. Only a recovery event can.

**Push form** (to Tool Hosts and clients, for running work only):

```text
KillPush { tenant, authorizationEpoch, events[], signature }
```

A component applies events it hasn't seen and acknowledges the epoch. **Retention:** events are kept until superseded, then compacted into an audited archive.

### 15.2 Delivery to running work

Kill events are committed in Control, which bumps the tenant's authorization epoch. New work is stopped at once, because every reserve, permit consumption, secret delivery and effect report checks the epoch. Running work is reached two ways:
1. **Push:** Control → Tool Hosts over their mTLS control stream, and to clients over the socket or the next check-in, with priority.
2. **Check-in by running work:** a Tool Host or client that redeems a secret, calls the broker or reports progress is refused once the epoch moves, so running work stops at its next contact with Control.

### 15.3 Why there is no Gateway grace or freshness rule

Every new invocation needs Control: the Gateway gets the decision from `/access/invocations`, the permit from `/reserve`, and the Tool Host or device consumes the nonce at Control. While Control is down, **no new non-built-in invocation can start**, which is today's documented fail-closed behaviour. A kill recorded in Control is therefore effective for all new work as soon as it commits, and there is no Gateway-side kill list that could go stale.

What remains is **work already running** when the kill is issued, covered by §15.5.

### 15.4 Break-glass

Break-glass is an emergency path inside Control, not at the Gateway:
- a dedicated endpoint and CLI that adds `STOP_NEW` or `TERMINATE` events **without the maker/checker review** normal configuration changes need. It needs two operators (2-of-3 operator keys) and is always audited;
- it bumps the tenant's authorization epoch, so every in-flight permit, secret delivery and effect report re-checks against the kill;
- it can only add kills. Lifting one is a normal reviewed recovery event (§15.6).

If Control is fully down, nothing new runs anyway. Running work is bounded by its deadline and by `selfTerminateOnControlLoss` (§15.5).

### 15.5 What happens to work already running

On receipt of an entry, from any path, each component acts immediately:

| Component | `STOP_NEW` | `TERMINATE` |
|---|---|---|
| Gateway | Hide from catalogs; refuse new calls; cancel queued tasks not yet dispatched (→ `CANCELLED`) | Same, plus send cancel to hosts for running invocations |
| Tool Host | Refuse admission | Kill matching sandboxes now. Invalidate fencing tokens, so brokered requests and secret redemption stop. Broker and proxy drop open connections. Running invocations end `CANCELLED`, or `OUTCOME_UNKNOWN` if an effect may have been sent. |
| Endpoint client | Refuse new jobs | Kill matching sandboxes; report the outcome when connectivity returns |
| Task store (when reachable) | Matching `QUEUED` / `AWAITING_APPROVAL` / `INPUT_REQUIRED` tasks → `CANCELLED` | Same, plus running tasks → `CANCEL_REQUESTED` |

**Components that can't be reached** (partitioned host or offline client):
- They can't consume permits, redeem secrets or report effects, so they can't start new work.
- Work already running there continues until its own deadline, bounded by the platform ceiling (15 min for tasks).
- Tenants with stricter needs set `selfTerminateOnControlLoss` per pool: a Tool Host that hasn't heard from Control within the configured window terminates its running non-built-in invocations.

### 15.6 Recovery

- Lifting a kill always requires a normal reviewed Control entry.
- Audit records which path delivered each entry to each component, and when.

## 16. Catalog revisions and versions

- **Catalog revision:** each catalog scope has one, a digest over its entries, their pinned package digests, the protocol version and the extensions. It is returned in `tools/list` `_meta` and recorded in the permit.
- **Canary cohorts** are sticky per agent for server tools, so one agent sees one schema. For client tools, the execution binding only routes to devices whose installed digest matches the catalog revision the caller saw.
- **Stale calls:** if a call names a stale catalog revision and the tool's schema differs in the current revision, the Gateway rejects it with a stale-catalog error and signals `toolsListChanged`. It never executes arguments against a different schema.
- **Pinning:** each invocation is pinned to one package digest at admission.

## 17. Client check-in and job delivery (change to existing flow)

### 17.1 Five settings, not one

| Setting | Purpose |
|---|---|
| Heartbeat interval | Liveness, inventory and status reports |
| Job notification transport | How a device learns a job exists: WebSocket push (preferred) or fallback poll |
| Job pickup timeout | How long a queued job may wait for its device before it expires, or is re-queued if idempotent |
| Execution deadline | Per tool, from the manifest, capped by ceilings (§13.5) |
| Offline threshold | When a device counts as unavailable |

**A WebSocket disconnect is not device unavailability.** A dropped socket puts the device in `DEGRADED` for a grace period, with fallback polling.
- Non-idempotent leased jobs are **never** rescheduled to another device because of a socket drop. They go to `OUTCOME_UNKNOWN` at lease expiry.
- Only queued, not-yet-leased jobs may move, and only if the binding allows it.

### 17.2 Phase A: no client change (Rahul's request; built in M1)

Today one value does everything. The client obeys `nextIntervalMs` from Control (accepted range 500 ms to 1 h, `apps/endpoint-client/src/service.rs:859-865`), and the WebSocket only opens after a poll delivers a job. Control currently hard-codes 500.

Phase A makes that value configurable:

| Variable | Quickstart | Enterprise default |
|---|---|---|
| `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS` | 500 | 2000 |
| `TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT` | 0 | 20 |
| `TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS` | 120000 | max(120000, 6 × check-in) |

**Honest constraint.** In Phase A, an idle device only learns of a job at its next check-in. The check-in value is therefore the job pickup latency. Control rejects values where check-in + jitter + a 5 s execution margin exceed the 30 s sync deadline, which is release 1's effective maximum (the Gateway request timeout cap, below the 60 s platform ceiling of §13.5; ADR 016). Longer client work must use tasks (§12).

**Where it's set:** Helm `control.clientCheckIn.*` (added to `values.schema.json`). These are env-owned values (§8.5), shown read-only under Configuration > Device.

### 17.3 Phase B: needs a client release

- A persistent WebSocket becomes the steady state (`TOOLGATE_CONTROL_CLIENT_SOCKET_MODE=persistent`), with server push for jobs, permission changes and kill entries.
- Separate variables:
  - `TOOLGATE_CONTROL_CLIENT_HEARTBEAT_MS` (enterprise 30000);
  - `TOOLGATE_CONTROL_CLIENT_FALLBACK_POLL_MS` (used only while the socket is down);
  - `TOOLGATE_CONTROL_CLIENT_JOB_PICKUP_TIMEOUT_MS`;
  - `TOOLGATE_CONTROL_CLIENT_SOCKET_GRACE_MS`.
- Older clients keep Phase A behaviour.

**Idle load at 10,000 devices:** about 20,000 requests/s at 500 ms polling, 5,000 at 2 s (Phase A), and about 333/s of heartbeats at 30 s (Phase B).

### 17.4 Permission updates

Today each change sends a full `permissions.json` replacement.

Phase B adds deltas:
- Each delta is **revisioned** (base revision → target revision) and signed. It carries the digest of the resulting full document.
- The client applies a delta only if its current revision equals the base and the post-apply digest matches. Otherwise it requests a full snapshot.
- Control also sends periodic full snapshots, so recovery never depends on the delta chain.
- Server-tool permissions stay in the Gateway; devices only receive client-tool entries.

## 18. Console: Configuration menu (change to existing flow)

New sidebar group **Configuration** (`apps/admin-ui/src/App.tsx:30`). Contents move unchanged from Audit > Configuration reviews.

| Page | Route | Contents | Moved from |
|---|---|---|---|
| Server | `#config-server` | **Gateway**: Gateway name (default from `TOOLGATE_GATEWAY_NAME`) | `ServerSettings` Gateway fieldset |
| Device | `#config-device` | **Device approval**: auto-approve, duration, owner. **Check-in**: effective values, read-only (§17) | `ServerSettings` Device approval fieldset |
| Backup/Restore | `#config-backup` | **Import and export configuration**, plus the server settings file import/export | `ConfigurationTransfer`, and the settings import/export from `ServerSettings` |

Audit > Configuration reviews keeps only the review queue. Old `#configuration` links redirect.

**Saving.** Server and Device each edit part of one `ControlServerSettings` record. Saving uses **field-level PATCH**:

```text
PATCH /api/control/v1/settings
Content-Type: application/merge-patch+json
If-Match: "<revision>"
Idempotency-Key: <key>
```

- Each page sends only its own fields.
- A stale revision returns `409` with the current record, as the existing PUT does. The new fields raise `ControlServerSettings.formatVersion` to 2, and env-owned values are excluded from the startup configuration import. The UI shows "changed elsewhere, review and retry".
- The existing full-record PUT stays for import, with strict compare-and-swap on the revision.

**Later items in this menu:** trusted issuers and introspection (§9), Tool Host pools, egress approvals, kill switch (§15), allowed runtime tiers (§13.4), compatibility profiles (§10.4).

## 19. Cross-cutting

- **Observability:** W3C trace context in MCP `_meta`, propagated Gateway → Tool Host/Control → client → harness → upstream. OpenTelemetry spans have arguments and secrets redacted.
- **Rate limits:** per-agent, per-tool and per-tenant quotas in the Gateway. Per-tool `maxConcurrency` protects fragile upstreams.
- **Namespaces:** package id prefixes are bound to verified Marketplace publishers. Tenant uploads use a tenant-private prefix, so a private package can't shadow a public one.
- **Catalog size:** scoped routes, toolsets (visibility only) and, later, a tool-search meta-tool.

## 20. Contract and component changes

| Change | Where |
|---|---|
| `PackageManifest` v2, `ToolDefinition` v2, `kind`, `CatalogScope`, `InvocationPermit`, `Task` states, `KillEntry`, `ConnectionProvider` / `ConnectedAccount`, `TrustedIssuer` (JWT / introspection / workload), compatibility profiles, protocol v2, limits ceilings | `packages/contracts/schemas/v2` (MAJOR) |
| `RequestContext` without device; `slug` on agents and groups; `SystemExecutorKind.TOOL_HOST` | contracts (MAJOR) |
| Credential classification, introspection, catalog scopes and routes, permit signing for Tool Host, modern MCP completion (`resultType`, `subscriptions/listen`, Tasks extension, MRTR), legacy adapter, catalog revisions, kill-list consumer | `apps/gateway` |
| Upload/review/enable, security delta, task store, kill switch, proxy and OpenAPI registration, settings PATCH, check-in env (Phase A) | `apps/control-plane`, `packages/contracts/openapi/control-v1.yaml` |
| Configuration menu, review pages | `apps/admin-ui` |
| Tool Host, egress proxy, executors, audit spool | new `apps/tool-host` |
| Protocol v2, artifact mounting, persistent socket, deltas, concurrency, tiers | `apps/endpoint-client` (Phase B) |
| `ServiceProfile` (routes, destinations, tunnel and TCP entries), `DeploymentBinding`, business-key reservation with namespaces | `packages/contracts/schemas/v2` |
| ADRs 013 to 021 (§23) | `docs/adr` |

## 21. Conformance

The tests are grouped into **independently runnable suites**. Each suite has its own trigger and blocking scope, so a Python SDK change doesn't wait on cluster chaos testing, while security-sensitive changes still face the gates that matter to them.

| Suite | Contents | Runs when | Blocks |
|---|---|---|---|
| **Contract and descriptor** | Schema validation; cross-SDK descriptor fixtures (same tool in each SDK yields the same canonical manifest); package suite (zip-slip, duplicate and case-colliding entries, unlisted files, non-canonical checksums); the three SDK descriptor tests below; route analysis (every non-provable URL-style call fails packaging, with fixtures for concatenation, globals, ambiguous and unlisted routes, per language); error model mapping per language; digest rules (`toolDescriptorDigest` equal across modes and excluding host values and runtime versions; artifact and package digests computed over exact bytes); library composition (duplicate ids and names, conflicting declarations, mappings, order independence, no widening) | Every commit touching contracts, packager or SDK generators | That commit |
| **SDK unit** | Per-language annotations, generators, runtime, `context.*` accessors, local dev server | Every commit to that SDK | That SDK's merge |
| **Protocol compatibility** | Runtime cases (stdin v1/v2); MCP 2026-07-28 core and legacy adapter per supported version; Tasks extension (capability per request, foreign identity not-found, TTL purge, state mapping); launcher and protocol-range handshake against the oldest supported Tool Host and client | Every Gateway, Tool Host, client or SDK release, and PRs touching protocol code | The release; protocol PRs |
| **Security and authorisation** | Credential classification and IdP template tokens; delegation double-approval; permit replay and wrong audience; catalog scope; broker channel (capability, socket attribution, routes, header override, limits); egress modes and bypass attempts; service-profile delta classifier; business-key reservation (concurrency across Gateways, conflict, expired key); resolved effect class (`UNKNOWN` uses the ledger everywhere); kill merge rules (an older snapshot never removes a kill); business-key namespaces (constraint, fingerprint, forwarded value and retention agree; cross-identity conflicts reveal nothing in content or timing; USER credentials only for the permit's user); transport matrix (unavailable transports fail closed, TCP only to approved destinations) | **Every PR touching** auth, Gateway policy, broker or egress, ledger or reservation, kill switch, or packaging trust | Those PRs (always blocking) |
| **Integration and failure injection** | Duplicate-execution (dropped `ADMITTED`, Gateway or host crash, client re-issue); effect semantics and reconcile; break-glass propagation with Control down, acknowledgement and fencing; rollout gates, pause, rollback and digest pinning; delta corruption recovery; OpenAPI import fixtures with transformation reports; every §12.7 row's failure case and §12.8 hand-off (primary failover after an acknowledged commit, outbox re-apply, stale fencing token, lost commit acknowledgement, store down at each step, lost `ADMITTED`, spool full and headroom exhausted while running, host `FENCED` recovery) | CI on main, plus every release candidate | Release candidates |
| **Mode equivalence** | Every example and fixture run in standalone and governed modes with the same inputs: outputs, errors, route calls and effect behaviour under injected failures must match; no mode-only API; startup refusals (claims without verifier, client tools without device services, fixtures outside development mode) | Every SDK or runtime change | That change |
| **Official MCP SDK compatibility** | `modelcontextprotocol/conformance` plus ToolGate MRTR, Tasks and identity cases against each pinned official SDK (§5.10) | Every official SDK version change | That change |
| **Multi-host chaos and load** | Gateway and Tool Host partitions, store failover, kill propagation timing, warm-pool cross-tenant isolation under load, polling and socket scale | Nightly and release validation | Releases |
| **Full language matrix** | Every SDK × supported language versions × OS × Tool Host and client versions in the compatibility matrix (§5.6) | Each SDK release | That SDK release |

**SDK descriptor tests** (these replace the old byte-identity gate):

| Test | Requirement |
|---|---|
| **Reproducibility** | Same source, SDK and compiler version, `descriptorGeneration` and build configuration → identical canonical bytes (and package digest) |
| **Upgrade compatibility** | Within one generation, descriptors across SDK minor and patch versions are equivalent under the limited rewrite set (§5.6), unless an explicitly permitted, listed correction applies |
| **Generation migration** | Moving to a new generation produces the expected classified semantic diff (API compatibility and security delta) and triggers the matching review path |

**Ownership and speed:**
- Suites live under `tool-sdk/conformance/<suite>` and `tests/` with their own runners.
- A new governed SDK runtime in M5 must pass contract and descriptor, SDK unit and protocol compatibility for its language, plus its slice of the language matrix. It doesn't re-run platform chaos.

## 22. Roadmap

Design completion and coding are tracked separately. A **design gate** produces frozen design artifacts; a **coding milestone** implements against them and may start only when D0, D1 and D2 have passed. Java leads the platform work; the public SDK delivery is in [publishing-plan.md](publishing-plan.md).

### 22.1 Design gates (pre-code)

**Rule: no implementation work of any size starts until D0, D1 and D2 have all passed.** That includes UI and configuration changes such as the Configuration menu and the check-in variables; there are no exceptions. D3 must pass before any milestone can exit.

**What a gate produces.** Each gate's output is a set of **design artifacts**: normative files committed to the repository, reviewed and then frozen. Coding milestones implement against those artifacts and never create or redefine them. After a gate passes, a frozen artifact changes only through an ADR amendment, which re-opens that gate.

| Gate | Design artifacts it produces (all normative, all reviewed) | Location | Status |
|---|---|---|---|
| **D0: Authoring contract frozen** | Authoring contract (§5.7) per language: annotation and `ToolContext` API signatures, error model, execution semantics; runtime modes (§5.8); standalone identity (§5.9); library composition (§7.7); descriptor generation 1 rules; **official SDK compatibility record** with test results, known gaps and adapter contracts (§5.10); ADR 019 | `tool-sdk/spec/`, `docs/adr/019-*.md` | **Passed** (PR #39, merged 2026-10-10, ADR 019 accepted). |
| **D1: Contracts frozen** | **The complete v2 schema files**, written here, not in M1: `PackageManifest`, `ToolDefinition` (including `toolDescriptorDigest` rules), `CatalogScope`, `InvocationPermit`, `Task`, `KillEvent`, `ServiceProfile`, `DeploymentBinding`, business-key reservation, protocol v2 frames; valid and invalid example fixtures for each; the MCP version model; OpenAPI changes (`control-v1.yaml` settings PATCH, uploads, deployments, effective values; `gateway-v1.yaml`); check-in environment variables (§17.2); Configuration menu specification (§18); contract generator design for v2 (namespaced bindings in Rust, Java, TypeScript and PHP; stale-copy check); renumbering the duplicate ADR 012; `ROADMAP.md` rewritten to point at §22; ADRs 014, 015, 018, 020, 021 | `packages/contracts/schemas/v2/` (marked frozen), `tests/fixtures/contracts/v2/`, `packages/contracts/openapi/proposed/`, `docs/adr/` | **Passed** (PR #40, merged 2026-10-10, ADRs 014, 015, 018, 020, 021 accepted). Amended at D2 by ADR 016 (additive). |
| **D2: Trust and persistence reviewed** | Threat model for Tool Host, broker, egress and the transport matrix (§11.6 to §11.9); the physical transaction model and persistence transitions (§12.7, §12.8) as a reviewed state-machine specification; business-key namespaces (§11.7); the migrations that extend Control's invocation tables (§12.8); ADRs 013, 016, 017 | `docs/security/threat-models/`, `docs/adr/`, migration specifications for Control's Flyway and Quickstart trees (`docs/control-plane/migrations-v2/`, with `invocation-state-v2.md` and `kill-switch.md`) | In review (PR 3) |
| **D3: Conformance authored** | Fixtures and cases written (not yet passing) for every suite in §21, including the cases added in revisions 8 and 9 | `tool-sdk/conformance/`, `tests/` | Not passed |

Revision 9 completes the plan content behind every gate. Each gate passes when its artifacts are merged and its named reviewers (architecture for D0 and D1; security for D2; QA for D3) sign off.

### 22.2 Coding milestones

| Milestone | Priority | Needs | Content (implementation only, against frozen artifacts) | Exit |
|---|---|---|---|---|
| **M0: SDK standalone preview** | P0 | D0, D1, D2 | All four SDKs in standalone mode, with the protocol-layer adapters from the D0 compatibility record; starters, examples, docs (publishing plan P-1) | Under 10 minutes to a first tool in each language; standalone side of mode equivalence passes |
| **M1: Contract implementation** | P0 | D0, D1, D2 | Code that consumes the frozen v2 schemas: generated bindings (Rust, Java, TypeScript), validators, database migrations for the state database, `RequestContext` without device, slugs, the MCP version model in the Gateway; the Configuration menu and settings PATCH (§18); check-in env Phase A (§17.2) | Contract suite green against the D1 fixtures; no schema file changed |
| **M2: Foundation** | P0 | D1, D2, D3 | `toolgate` packager, governed Java runtime, lifecycle to `ACTIVE` with manual rollback (§8.4), `DeploymentBinding` (§8.5), Tool Host with egress isolation, broker and transport matrix, Control-issued Tool Host permits and admission, extensions to Control's invocation state (tasks, business-key namespaces, result payloads), persistence transitions (§12.7), audit spool with fencing, kill events and pushes, revocation checks, modern MCP completion, legacy adapter | A Java server tool and a client tool run end to end through `/mcp` and scoped routes; duplicate-execution, kill, revocation and §12.7 failure-injection cases pass |
| **M3: Adoption** | P0 | M2 | MCP proxy and OpenAPI import | An unmodified MCP server and a REST API governed through ToolGate |
| **M4: Enterprise reliability** | P1 | M2 | Full task experience, worker recovery automation, break-glass administration, connections delivery (§14.3), fleet deltas, client Phase B | Integration and chaos suites green |
| **M5: Multi-language governed** | P1 | M2, M0 | Governed runtimes for Python, TypeScript and .NET (publishing plan P-3) | Each passes protocol compatibility and its language-matrix slice |
| **M6: Distribution and extensions** | P1/P2 | M3 | Marketplace (modules 12 to 15), cohort rollouts, `CONTAINER` kind, Kubernetes workload identity, air-gapped bundles; DEFERRED items in §23 once their ADRs are accepted | Per item |

## 23. Decision register

Every decision is closed. **ACCEPTED** means it is in P0 or P1 scope as written. **REJECTED** means the alternative is not built. **DEFERRED** means it is outside release 1, with no P0 work depending on it, and comes back through its ADR. Decisions are recorded as accepted on Rahul's instruction to accommodate all review findings; each ADR still needs its reviewer's sign-off at its design gate (§22.1).

**ADRs** (new, in `docs/adr`):
- **013** Tool Host, egress, broker, secrets and transports
- **014** Package format, signing, enable trust, lifecycle and rollout
- **015** Credential classes, IdP mapping, catalog scope and URL routes
- **016** Tasks, execution ledger, effect safety, business keys and persistence
- **017** Kill switch and break-glass
- **018** MCP transport versions and legacy adapter
- **019** SDK authoring API, compatibility and `descriptorGeneration`
- **020** Deployment binding and configuration precedence
- **021** Client check-in configuration and the Configuration menu

| ID | Decision | Status | ADR | Rationale | Alternatives considered | Affected contracts |
|---|---|---|---|---|---|---|
| 1 | One-click enable, Quickstart auto-enable | ACCEPTED | 014 | Rahul's choice; review still applies through the security delta | Separate approve and deploy steps | Upload and enable API |
| 2 | Server tools on Tool Host with direct Gateway dispatch under permits | ACCEPTED | 013 | Low latency without a Control hop | Relay every call through Control | `InvocationPermit`, Tool Host protocol |
| 3 | App artifact on approved base runtime images | ACCEPTED | 014 | Small artifacts, reviewed runtimes | Arbitrary images (kept for `CONTAINER`, deferred) | `PackageManifest.runtimes` |
| 4 | Client tools have no network or secrets in release 1 | ACCEPTED | 013 | Keeps device trust unchanged | Client egress tier (DEFERRED, row X3) | Client protocol v2 |
| 5 | `title`, with `text` as an alias | ACCEPTED | 019 | MCP naming; keeps Rahul's sketch working | `text` only | `ToolDefinition` |
| 6 | Slugs in routes | ACCEPTED | 015 | Readable, stable URLs | Ids in URLs | Agent and group records |
| 7 | `RequestContext` without a device | ACCEPTED | 015 | Server-side agents have no device | Synthetic device ids | `RequestContext` (MAJOR) |
| 8 | Tool contract may change (MAJOR) | ACCEPTED | 014 | Rahul's instruction | Extend v1 compatibly | Schemas v2 |
| 9 | `toolId` / `mcpName` / `compatibilityAlias` naming | ACCEPTED | 018 | Stable ids across clients' naming limits | One name field | `ToolDefinition`, catalog |
| A | **Control issues every invocation permit**, including Tool Host permits, through the existing reserve path (revised in revision 10) | ACCEPTED | 013 | Matches the code and the no-offline-ALLOW invariant; no second policy system | Gateway signs permits from a snapshot (REJECTED, row R1) | `InvocationPermit`, Tool Host mTLS identity |
| B | Legacy MCP adapter for `2025-11-25` and `2025-06-18`, at least twelve months | ACCEPTED | 018 | Existing clients keep working; bounded cost | Modern only; unbounded support | Gateway transport |
| C | Single-use warm sandboxes; reuse opt-in for `stateless` tools | ACCEPTED | 013 | No cross-invocation leakage by default | Reuse by default | Pool configuration |
| D | Settings field-level PATCH with `If-Match`; PUT kept for import | ACCEPTED | 021 | Two pages edit one record without overwriting each other | Separate records per page | `control-v1.yaml` server settings |
| E | At-most-once execution through Control's existing `RESERVED → EXECUTING` transition with a single-use nonce as fencing token | ACCEPTED | 016 | At most one execution per invocation, reusing existing state | Separate ledger table | Invocation tables, permit, broker |
| F | Async tools hidden from clients without Tasks; bounded sync opt-in | ACCEPTED | 016 | Never start work the client can't collect | Run and drop the result | Catalog filtering |
| G | No Gateway kill list or grace: Control is on every call path, so kills apply to new work on commit (revised in revision 10) | ACCEPTED | 017 | Nothing can go stale | Gateway kill list with grace (REJECTED, row R10) | Kill events in Control |
| H | SDK runtime inside the artifact; base images hold only the launcher | ACCEPTED | 019 | SDK upgrades need no image review | Runtime in base image | Launcher protocol, manifest `sdk.*` |
| I | Brokered and tunnel egress modes, exclusive per destination, no TLS interception | ACCEPTED | 013 | Credential injection where possible, honest limits elsewhere | Interception CA; tunnel only | `ServiceProfile` |
| J | Effect semantics of §11.7, optional reconcile hook | ACCEPTED | 016 | No exactly-once overclaim | Claim exactly-once | `ToolDefinition.effectSafety`, reconcile |
| K | Break-glass inside Control: 2-of-3 operator keys, skips maker/checker, add-only (revised in revision 10) | ACCEPTED | 017 | Fast and still two-person | Gateway break-glass (REJECTED, row R10) | `KillEvent`, break-glass endpoint |
| L | Agent-cohort canaries with automatic pause and rollback | ACCEPTED (P1) | 014 | One agent sees one schema | Percentage of calls | Rollout plan in `DeploymentBinding` |
| M | `effectSafety`, `UNKNOWN` default treated as non-idempotent; business keys required for high-risk tools | ACCEPTED | 016 | Safe default without inference from HTTP methods | Infer from method | `ToolDefinition`, permit |
| N | Broker routes over a per-sandbox socket with an invocation-bound capability | ACCEPTED | 013 | The broker can't become a general proxy | URL allowlist proxy | `ServiceProfile`, broker API |
| O | Pinned `descriptorGeneration`, limited semantic equivalence | ACCEPTED | 019 | Fixes possible without silent drift | Byte identity (REJECTED, row R2) | Manifest, packager |
| P | Atomic `reserveBusinessOperation`, conflict and expired-key outcomes, retention and tombstones | ACCEPTED | 016 | Correct with several Gateway replicas | Check-then-insert | `TaskStore` schema |
| Q | Kill state as a grow-only set of issuer-scoped events; only Control recovery events lift kills | ACCEPTED | 017 | An old snapshot can never remove a kill | Last-writer-wins version number | `KillEvent`, `KillSnapshot` |
| R | OpenAPI imports default to `UNKNOWN`, keep source digest and transformation report | ACCEPTED | 014 | No unsafe assumptions from specs | Infer from method | Import records |
| S | Canonical SDK HTTP API is named routes; URL form only when statically proven (§5.4) | ACCEPTED | 019 | One contract that matches the broker | Runtime URL matching | SDK APIs, `ServiceProfile` |
| T | Release 1 transports: brokered HTTP(S), CONNECT tunnel for HTTP(S) and approved TCP (§11.9) | ACCEPTED | 013 | Clear support boundary | Transparent interception | `ServiceProfile` tunnel entries |
| U | Business-key namespaces `AGENT`, `DELEGATED_USER`, `TENANT` with credential ownership rules (§11.7) | ACCEPTED | 016 | One identity drives constraint, fingerprint, forwarding and retention | One fixed agent scope | Reservation schema, permit |
| V | Persistence transitions and audit-spool fencing (§12.7) | ACCEPTED | 016 | No execution without durable state and audit | Best-effort audit | `TaskStore`, spool format |
| W | `DeploymentBinding` contract and precedence (§8.5) | ACCEPTED | 020 | Deployment changes without new packages; explainable values | Settings scattered across records | `deployment-binding.v1.json` |
| X | Standalone SDK mode released before the governed runtime, on the complete frozen contract (publishing plan) | ACCEPTED | 019 | Community adoption doesn't wait on the platform; the architecture is still complete first | Governed only; freeze only a subset first (REJECTED, row R5) | SDK modules |
| Y | Complete authoring contract, including error model and execution semantics, frozen at D0 (§5.7) | ACCEPTED | 019 | No mode-specific meaning can creep in later | Freeze per release | All SDK APIs, `ToolDefinition.errors[]` |
| Z | Capability matrix and mode-equivalence invariant (§5.8) | ACCEPTED | 019 | Moving to ToolGate can't change permissions or effects | Best-effort parity | SDK runtimes, conformance |
| AA | Mandatory token verification in standalone production; fixtures only in loopback development mode (§5.9) | ACCEPTED | 015, 019 | Claims drive identity and execution | Optional verification (REJECTED, row R6) | Standalone runtime configuration |
| AB | Pinned official MCP SDKs with ToolGate adapters for each gap (§5.10) | ACCEPTED | 018, 019 | SDK support differs by language | Assume identical support | SDK dependencies |
| AD | Design gates produce frozen artifacts (schemas, OpenAPI, DDL, threat models, compatibility record); milestones only implement them; no work before D0 to D2 | ACCEPTED | 014, 021 | Removes circularity and exceptions | Schemas written in M1 | All |
| AE | Three identities: `toolDescriptorDigest`, `artifactDigest`, `packageDigest` (§7.2) | ACCEPTED | 014, 019 | Mode equivalence without false digest claims | One package digest for everything | `ToolDefinition`, `PackageManifest`, permit |
| AF | Control's database is the only state store and only Control writes it; Gateway and Tool Host call Control's API (§12.8, revised in revision 10) | ACCEPTED | 016 | Keeps Java invariants, tenant lock and ADR 006 | Gateway and Tool Host database roles (REJECTED, row R11) | Invocation table migrations, Control API |
| AG | D0 accepts only test results or reviewed adapters (§5.10) | ACCEPTED | 019 | Published versions prove nothing | Trust documentation | Compatibility record |
| AH | Clients keep the RS256 release JWS over `FleetPackageDocument` and digest-pinned OCI images; Control converts uploads (§2.1) | ACCEPTED | 014 | No new trust format on devices | Ship `.tgpkg` to devices (REJECTED, row R12) | Fleet contracts |
| AI | Package lifecycle builds on fleet releases, the Builder flow and maker/checker configuration changes; enable submits a configuration change, auto-approved in Quickstart (§2.1) | ACCEPTED | 014 | One governance path | Parallel lifecycle | Fleet, configuration-change APIs |
| AJ | Tier names `OCI_SANDBOX`, `WASI`, `HOST_NATIVE`; "tool protocol v2"; `@olo-labs` npm scope; `TOOLGATE_CONTROL_CLIENT_*` variables (§2.1) | ACCEPTED | 019, 021 | Avoids clashes with existing names | Keep plan names | Contracts, Helm, packages |
| AC | Library composition fails on conflict; explicit reviewed mappings; no broadening (§7.7) | ACCEPTED | 014, 019 | Deterministic and reviewable | Last-wins or union merge (REJECTED, row R7) | `toolgate-fragment.json`, `composition[]` |
| R1 | Gateway signs permits from a Control-signed snapshot | REJECTED | 013 | Needs a second policy system and contradicts the no-offline-ALLOW invariant | Row A | — |
| R2 | Byte-identical descriptors across SDK versions | REJECTED | 019 | Blocks legitimate fixes | Row O | — |
| R3 | Automatic fallback to a weaker runtime tier | REJECTED | 013 | Silent security downgrade | `UNSUPPORTED_TIER` error | — |
| R4 | TLS interception inside tunnels | REJECTED | 013 | Breaks end-to-end trust, needs a CA in the sandbox | Brokered mode | — |
| R5 | Freezing only the standalone subset before SDK 0.1 | REJECTED | 019 | Leaves governed meaning undecided | Row Y | — |
| R6 | Optional token verification in standalone mode | REJECTED | 015 | Unsafe production default | Row AA | — |
| R7 | Last-wins or union merging of library fragments | REJECTED | 014 | Silent overwrite or broadened access | Row AC | — |
| R8 | Separate databases for approvals and tasks in release 1 | REJECTED | 016 | Would need cross-store atomicity or a new outbox protocol | Row AF | — |
| R9 | Small items ("Now") built before the design gates | REJECTED | 021 | Contradicts design-first | Row AD | — |
| R10 | Gateway kill list, freshness grace and Gateway break-glass | REJECTED | 017 | Unneeded while Control is on every call path | Rows G, K | — |
| R11 | Database roles for Gateway or Tool Host | REJECTED | 016 | Bypasses Control's invariants and ADR 006 | Row AF | — |
| R12 | `.tgpkg` extraction or a new signature format on devices | REJECTED | 014 | Reverses the "no ZIP/TAR on clients" invariant | Row AH | — |
| X1 | `WASI` and `HOST_NATIVE` tiers (the tier model itself is accepted) | DEFERRED (M6) | 013 | Need per-OS certification; nothing in P0 depends on them | Ship uncertified | `RuntimeTier` values reserved |
| X2 | SaaS connection delivery (contract accepted now) | DEFERRED (M4) | 015 | Contract fixed so tools can declare it | Build in P0 | `ConnectionProvider`, `ConnectedAccount` |
| X3 | Client egress and client secrets | DEFERRED | 013 | Needs the same isolation contract on devices | Allow in release 1 | Client protocol |
| X4 | Brokered database adapters and gRPC routes | DEFERRED | 013 | TCP tunnel covers release 1 | Ship in P0 | `ServiceProfile` |
| X5 | `CONTAINER` integration kind, Kubernetes workload identity, A2A, event triggers, GraphQL | DEFERRED (M6) | 014 | Not needed for P0 adoption | — | — |

No P0 item depends on a DEFERRED row.

## 24. Risks

- Network and secrets for managed tools change documented invariants. ADR 013 needs security review first.
- Legacy MCP support costs maintenance. Bounding it to two versions and twelve months limits that.
- The Marketplace modules are unbuilt, so publishing comes after tenant upload, proxy and OpenAPI.
- Windows/macOS client isolation still depends on a container engine until the other tiers are certified. No weaker fallback is allowed.
- Audit-spool fencing (§12.7) stops tools when audit can't keep up. Spool sizing and alerts are deployment checks.
- The release 1 transport matrix excludes database drivers without proxy or socket-factory support. The packager warns early, and brokered database adapters are deferred (ADR 013).
- The kill-switch freshness rule can block tools during a Control outage. The freshness deadline needs tuning against availability targets.
