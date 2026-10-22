<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# MCP proxy: govern existing MCP servers through ToolGate

Plan for [olo-labs/olo-toolgate#129](https://github.com/olo-labs/olo-toolgate/issues/129). Status: proposed, not
implemented. Revision 5 (22 October 2026):

- **Revision 2** adopted the multitenancy model merged in #176 ([ADR 029](../../adr/029-tenant-ownership-and-trust-boundaries.md)).
- **Revision 3** resolves the first review: admission for native execution and scans, quarantine binding, pool
  targeting, streaming transport, result provenance, approval versioning, and discovery limits.
- **Revision 4** resolves the second review: scoped secret authority for scans, the approved TLS pin on refreshes,
  retiring removed tools, Control runtime compatibility, slug and package-id alignment, and a maximum check age for
  calls (§13).
- **Revision 5** resolves the third review: pools are administered Device Groups, never host-claimed; permits carry
  an explicit verification status; scans have a signed execution deadline (§14).

## 1. Goal

An administrator of a tenant adds an existing MCP server by URL. ToolGate scans it, the administrator approves the tools
they want, and that tenant's agents reach those tools only through ToolGate. The tools are governed by the same Tool
Groups, grants, policies (Allow/Ask/Block), permits and audit as every other tool. Models only ever see ToolGate's
approved snapshot of each tool, never the upstream's live metadata. Nothing about one tenant's MCP servers is visible
to, or reachable from, another tenant.

The product is unreleased: one current contract, no backward compatibility, no fallbacks.

## 2. Design in one paragraph

**An approved MCP server becomes a package of its tenant.** ToolGate tools come only from verified packages, so
approving a scanned server does not create tools directly. Control generates a sealed package of kind `MCP_PROXY` from
the approved snapshot, in that tenant. It holds no code. Its one artifact is a proxy document (`mcp-proxy.json`) with
the upstream route, server identity and a definition digest per tool, and its runtime is the Tool Host's native
`mcp-proxy` runtime. Calls are therefore admitted by the **ordinary** permit path, which already binds tool, runtime
and artifact digests. **All upstream contact, both scanning and calls, runs in the tenant's dedicated Tool Host pool,
never in shared Control.** A scan happens before any package exists, so it gets its own scan permit, a sibling of the
invocation permit with the same issuance, fencing and secret-custody rules. The proxy executor talks to the upstream
through a new **streaming** egress client: it shares the broker's address policy, pinned TLS and secret injection, and
reads Streamable HTTP responses and server-sent events with bounds and cancellation. Results carry a typed
**provenance** marker that survives the result pipeline and replay. A quarantine is accepted only for the targets of
the authenticated invocation or scan that observed the problem, and only for the package version that is current.

## 3. Existing building blocks and their gaps

| Need | Already in place | Gap this plan closes |
|---|---|---|
| Package kind | `PackageKind.MCP_PROXY` (v2 package schema) | Kind rules, verifier support, native runtime and artifact (T1, T4, T5) |
| Runtime compatibility | `PackageCompatibility.RUNTIME_LANGUAGES` is `{java, python}`, checked by `ServerPackages.upload` | Accept `mcp-proxy` for `MCP_PROXY` packages only (T4) |
| Redeploy | `ServerPackages.deploy` writes the new manifest's tools and leaves omitted ones untouched | Retire tools a new version no longer contains, for every package (T4) |
| Upstream host, port, TLS pins, path, auth | `DestinationDeclaration`, `RouteDeclaration`, `HttpAuthDeclaration`, `SecretDeclaration` | — |
| Call admission | `ServerPackages.permitIn` (needs deployment, descriptor, runtime, artifact); Tool Host `Store::prepare` (needs runtime, artifact digests) | The native runtime and the proxy document satisfy both unchanged (T1, T5) |
| Pre-approval admission | — | Scan permit and scan job (T1, T3, T5) |
| Secrets | Tenant vault; `secrets/deliver` against a consumed invocation permit, and the secret's Tool Group and Device Group must equal the invocation's execution binding | Delivery against a consumed scan job with the same scoped group check (T3) |
| Egress | `ROUTE_CALL` buffers the response to EOF or its cap, then returns `bodyBase64` | Streaming egress client with bounded SSE parsing and cancellation (T5s) |
| Placement | Deployments target `hostIds`; empty means every Tool Host of the tenant. Tool Hosts are enrolled devices that administrators place in Device Groups (ADR 013 calls these groups pools); the `poolId` a host sends at registration is self-reported and unused | Deployments, assignments, placement and permits targeted by administered Device Group membership (TP) |
| Result shape | `ResultFrame` is closed; the Gateway's `success()` replaces `_meta` | Typed result provenance through Control, replay and the Gateway (TR) |
| Package limits | 64 tools per manifest and deployment | Approval capped at 64; snapshots may show more (§5) |
| Tenant ownership | ADR 029: tenant UUID, forced RLS, `schema-ownership.json`, routed and JWT-bound context, dedicated pools, capacity accounting, recovery | Applied to new tables and flows (§4) |

## 4. Multitenancy rules for this feature (ADR 029)

1. **Tenant context.** Every MCP server API is a tenant API. The tenant comes from the routed host authority, and the
   verified JWT's tenant must agree with it before any lookup. No request body, header or document carries a tenant id
   that Control trusts. Unknown and suspended tenants get the same anonymous response.
2. **Tenant-owned data.** New tables (`control_mcp_servers`, `control_mcp_snapshots`, `control_mcp_approvals`,
   `control_mcp_scans`) key every row by the tenant UUID. They have forced row-level security with a restrictive tenant
   fence, a foreign key to `control_tenants`, and minimal runtime privileges. They go into the single first-release
   schemas (`db/migration/V1__initial_schema.sql`, `db/quickstart/V1.sql`), `db/schema-ownership.json`, the isolation
   tests, and the capacity tracking (`tools/tenancy/capacity_schema.py`).
3. **No customer-directed work in shared Control.** Shared Control never connects to an upstream MCP server, and never
   decrypts an upstream secret for itself. Scans, refreshes and calls run on a Tool Host of the registration's pool in
   the same tenant.
4. **Per-tenant identity.** Slugs, package ids (`mcp.<slug>`) and tool ids (`mcp.<slug>/<name>`) are unique within
   their tenant. A registration can name only its own tenant's secrets and pools.
5. **Executors and caches stay inside one tenant.** Sessions and `tools/list` caches are per tenant, server and auth
   profile. Quarantine and scan reports are accepted only from the host that holds the reported invocation's or scan's
   lease (§6). Pool membership is administered Device Group membership (§6), never a host's own claim.
6. **Admission and accounting.** Per-tenant caps: registrations 32, stored snapshots per server 16 (oldest
   unreferenced pruned), concurrent scans 2, scan jobs queued per server 1. Background refresh scheduling is paginated
   per tenant through the platform path, and its work runs in tenant transactions.
7. **SQLite and Quickstart.** SQLite supports the one default tenant. Shared hosting requires PostgreSQL.
8. **Recovery.** Tenant recovery restores registrations, snapshots, approvals and generated packages exactly. It
   expires open scan jobs and dispatches, and keeps `QUARANTINED` states. The tenant comes back `SUSPENDED` until
   resumed. Nothing re-scans or re-approves automatically.

## 5. Names, versions and limits

- **Slug:** `^[a-z][a-z0-9-]{1,30}$` (2–31 characters, starting with a letter), unique per tenant. It is exactly one
  `PackageId` component, so `mcp.<slug>` always satisfies `^[a-z][a-z0-9-]{1,30}(\.[a-z][a-z0-9-]{1,30}){1,3}$`.
  Registration rejects any other slug. Boundary tests: `a`, `1server`, `-server`, a 31- and a 32-character slug,
  and uppercase.
- **Package:** id `mcp.<slug>`, version `1.0.<approvalSequence>`. `approvalSequence` is a per-registration counter in
  `control_mcp_servers`, incremented by every accepted approval. Each approval row records its source snapshot
  revision, the approved tool set with names and digests, the generated package digest and the request digest.
  - **Idempotency:** a retry with the same `Idempotency-Key` and request digest returns the original approval. An
    approval identical in effect to the current one (same snapshot revision, tool set and mappings) returns the current
    approval without a new sequence. Anything else takes the next sequence, so different subsets of one snapshot never
    share a version.
- **Tool id:** `mcp.<slug>/<name>`. The upstream name is normalised to ToolGate's tool-name rules. Collisions and
  unrepresentable names are refused at approval, never silently renamed. The proxy document maps every tool id to its
  exact upstream name.
- **Definition digest** (one spec, Java and Rust): `sha256` over the canonical JSON of `{name, title?, description?,
  inputSchema, outputSchema?, annotations?}` as returned. Shared vectors in `tool-sdk/conformance/mcp-proxy/`.
- **Limits.** The host enforces them **before** aggregating anything (it stops reading and reports
  `SCAN_LIMIT_EXCEEDED`), and Control enforces them again on receipt.

| Limit | Value |
|---|---|
| `tools/list` pages per scan | 16 |
| Bytes per page, after decoding | 1 MiB |
| Total bytes per scan | 4 MiB |
| Tools per snapshot | 256 |
| Name / title | 128 characters |
| Description | 8 KiB |
| One tool's `inputSchema` + `outputSchema` + `annotations` | 64 KiB |
| Schema nesting depth | 32 |
| Properties per object | 256 |
| `enum` values | 256 |
| Tools per approval (the existing manifest and deployment cap) | 64 |
| Scan execution deadline (signed in the scan permit) | 30 s |

  A server with more approvable tools than 64 needs more than one registration, each with its own slug. Phase 1 does
  not change the 64 cap.

## 6. Admission, secrets and reports

**Calls (ordinary path, unchanged code).**
- An `MCP_PROXY` package declares:
  - runtime `{runtimeId: "mcp-proxy", language: "mcp-proxy", version: <tool protocol>}`;
  - one artifact `mcp-proxy.json` (media type `application/vnd.ololabs.toolgate.mcp-proxy+json`);
  - every tool's `runtimeId` and `artifactIds: ["mcp-proxy"]`.
- `permitIn` therefore finds deployment, descriptor, runtime and artifact as for any package. The permit binds their
  digests.
- The Tool Host's `Store::prepare` verifies the same digests. For runtime `mcp-proxy` it parses the proxy document
  instead of starting a process.
- Secret delivery is the existing consumed-permit path.

**Registration authority (scoped like a tool call).** A registration names an **execution binding** of its tenant
(`bindingId`: a Tool Group and a Device Group). Registering, scanning and approving require `mcp_server.register`
**and** the same scoped management authority over that binding's Tool Group and Device Group that deploying a tool
there requires. The secret must belong to exactly that Tool Group and Device Group, which is the condition
`VaultService.deliver` already enforces for tool calls. The registration's pool is that Device Group (below). So
someone allowed to register servers cannot direct a secret scoped to other groups to an upstream of their choosing.

**Pools are Device Groups.** A pool is the binding's Device Group itself: the set of enrolled Tool Host devices that
administrators placed there through reviewed directory changes. A host never chooses its pool. The `poolId` in a
Tool Host's registration is not trusted for anything: Control derives membership from the directory at each decision
(assignment, placement, scan targeting, permit issuance) and refuses a registration whose claimed `poolId` disagrees
with the host's administered Device Group membership. A registration therefore names no separate pool; its pool is its
binding's Device Group.

**Scans (new, sibling of the invocation permit).**
1. A scan request creates a `control_mcp_scans` row (tenant-owned) with its id, registration, binding, purpose
   (`DISCOVER` | `REFRESH`), target host and fencing token. For a refresh it also records the approval sequence it
   checks and that approval's **SPKI pins**. The row moves `QUEUED` → `CLAIMED` → `ADMITTED` → `FINISHED` | `FAILED` |
   `EXPIRED`.
2. Control issues a **scan permit**, a compact JWS in the same effect-signing key domain:
   - protected header `typ: toolgate-mcp-scan-permit+jws`;
   - claims: tenant, scan id, registration slug and revision, binding id, purpose, URL, auth scheme and header, secret
     name, `spkiPins`, `aud` (the host), fencing token, and a signed execution **`deadlineUnixMs`** (issuance + 30 s);
   - for a `REFRESH`, `spkiPins` is the current approval's pinned set and is **mandatory**. The host verifies TLS
     against it **before** sending any credential, and a mismatch fails the scan as `PIN_MISMATCH`, which quarantines
     the server. Only a `DISCOVER` scan, started explicitly by an administrator, connects without a pin, records the
     observed pin, and can propose a replacement, which takes effect only through a new approval;
   - `exp - iat ≤ 10 s`, for admission only.
3. The target host is chosen among live hosts of the registration's pool. The host fetches the scan as it fetches
   pull-mode dispatches, verifies and consumes the permit, then redeems the secret. `secrets/deliver` gains a scan
   branch that requires all of:
   - a consumed, current scan;
   - the caller is its lease holder;
   - the secret name equals the registration's;
   - the secret's Tool Group and Device Group equal the registration's execution binding (the existing tool-call
     rule);
   - the registration's binding and pool are still authorized and current, under live tenant authority.
4. The result (`McpScanResult`) is accepted only from the lease holder with the fencing token, once, and only
   before the deadline.
5. **Deadline.** The scan job's lease expires at the signed `deadlineUnixMs`. The host enforces it as one wall-clock
   budget across connect, TLS, `initialize`, every `tools/list` page and every byte read, cancelling the connection
   when it is reached, independent of byte limits and idle timeouts (a server that trickles data slowly is cut at the
   deadline). Control refuses secret redemption and results after the deadline. A watchdog (paginated per tenant)
   marks abandoned jobs `EXPIRED` and releases their concurrency slot; an expired refresh counts as a failed check.

**Quarantine reports are bound to their evidence.**
- A report names exactly one `invocationId` (a call) or `scanId` (a refresh) with its fencing token. It does not
  carry free tool lists.
- Control accepts it only from that invocation's or scan's lease holder, and derives the allowed targets itself:
  - for a call: the invocation's tool id, and the package digest its permit bound;
  - for a refresh: the registration and the approval sequence the scan recorded. Only a refresh can quarantine the
    whole server.
- Control applies the report only when that package digest or approval sequence is still the current deployment of
  `mcp.<slug>`. Otherwise it records the report as **stale** (audit only) and changes nothing.
- Tests:
  - an unrelated tool of the same tenant;
  - another registration;
  - a report arriving after re-approval;
  - a report from a different host;
  - a replayed report.

## 7. Flows

**Register.** A tenant admin sends `{slug, url, auth: {secretName, scheme: BEARER|HEADER, header?}, strict, bindingId}`.
Control checks the URL form (HTTPS), the scoped authority and secret groups (§6), and that the binding's Device Group
has at least one enrolled Tool Host, then stores `REGISTERED`.

**Scan.** As in §6. The host:
1. resolves and checks the address;
2. verifies TLS and records the leaf SPKI pin;
3. runs `initialize` and `notifications/initialized`;
4. pages through `tools/list` within the §5 limits;
5. reports raw definitions.

Control recomputes digests, enforces the limits again, and stores an immutable snapshot (`SCANNED`).

**Approve.** The admin picks at most 64 tools from one snapshot. Control:
1. validates the schemas;
2. assigns the next approval sequence (§5);
3. generates the package (§6);
4. verifies and imports it with `ServerPackages.upload`;
5. deploys it to the registration's pool (TP). In the same directory change, every `mcp.<slug>/…` tool of the
   previous version that the new approval omits is **retired**: disabled, removed from the deployment's `toolIds`,
   its extractor disabled, and the directory revision and authorization epoch advanced. Agents' catalogs stop listing
   it at once, and new permits for it are refused.

Tools new to this version start disabled. Tools kept from the previous version keep their state. Enabling, Tool
Groups and grants are the ordinary reviewed changes.

**Call.** Agent → the tenant's Gateway → Control authorizes and mints the permit for a host of the deployment's pool →
that host admits it → proxy executor:
1. validates arguments against the approved `inputSchema`;
2. runs an inline `tools/list` check, against the approved pins and digests within the call's permit, when strict mode
   is on **or** the permit's `mcpVerification.status` is not `CURRENT`. If that inline check fails or can't complete,
   the call is refused (`UPSTREAM_UNVERIFIED`) and nothing is sent to the upstream tool. Control computes the status
   at permit issuance: `CURRENT` only when the **most recent** check for the deployed approval sequence succeeded and
   is at most 120 s old; any later failed or pending-and-overdue refresh, a missing check, an older check or another
   approval sequence makes it `UNVERIFIED`. The host checks `checkedAtUnixMs` again against its own clock and treats a
   `CURRENT` status older than 120 s as `UNVERIFIED`;
3. sends `tools/call` through the streaming egress client (T5s);
4. reads the JSON-RPC response with the matching id, handling notifications within bounds, then cancels the stream;
5. validates the result (`outputSchema`, content types, 4 MiB);
6. returns it with provenance `UNTRUSTED_UPSTREAM` (TR).

**Refresh (normal mode).** Control schedules a `REFRESH` scan per approved registration at most every 60 s, paginated
per tenant. Each refresh outcome replaces the registration's verification record: success sets `CURRENT` with its
time and approval sequence; a failure sets `UNVERIFIED` with the failure, discarding the earlier success, so it can't
be used as freshness evidence. Scheduler backlog or upstream outages therefore
make calls fall back to the inline check, or be refused, rather than run on stale evidence. A `notifications/tools/list_changed` seen during any call or scan schedules an immediate refresh. Hosts do
not keep long-lived listeners in phase 1. Comparing against the approval:
- a changed digest or missing tool quarantines that tool;
- a changed `serverInfo.name` quarantines the server;
- a changed `serverInfo.version` requests re-review;
- a changed TLS key (`PIN_MISMATCH`) quarantines the server before any credential is sent.

**Quarantine.** Control disables the affected tools in one audited directory change, marks the registration
`QUARANTINED` (with the reason and evidence id), and alerts that tenant's administrators. Recovery is a re-scan and
re-approval. Nothing auto-recovers.

## 8. Independent tasks

Each task is one PR with its own tests and states its dependencies. **T1 and T2 come first.** After them, these run in
parallel: **TP** (pool targeting), **TR** (result provenance), **T5s** (streaming egress), **T3** (registration and
scan in Control), **T7** (console) and **T8a** (fixture). Then T4 (after T3 and TP), T5 (after T5s and TR), T6 (after
T4 and T5), and finally T8b.

```text
T1 contracts ─┬─> TP pool targeting ─────────────────────────┐
T2 digest ────┼─> T3 Control register/scan ─> T4 approve/package/quarantine ─┐
              ├─> TR result provenance ────┐                                  ├─> T6 refresh/strict ─> T8b e2e (two tenants) + docs
              ├─> T5s streaming egress ────┴─> T5 Tool Host executor + scan ──┘
              ├─> T7 console ─────────────────────────────────────────────────────────────────────────┘
              └─> T8a fixture MCP server ────────────────────────────────────────────────────────────┘
```

### T1. Contracts (foundation)
- `packages/contracts/schemas/v2/mcp-proxy.schema.json`:
  - registration request and record (`slug` with the §5 pattern, `url`, `auth`, `bindingId`, `strict`; the pool is the binding's Device Group), the verification record (`status`, `checkedAtUnixMs`, `approvalSequence`, `failure?`), and `McpServerState` (`REGISTERED`, `SCANNING`, `SCANNED`, `APPROVED`, `QUARANTINED`); no tenant field;
  - `McpSnapshot` and `McpToolDefinition` (with `definitionDigest`);
  - `McpScanResult` (raw definitions, `serverInfo`, `protocolVersion`, `spkiPin`, `truncated: false` only);
  - `McpApprovalRequest` (`snapshotRevision`, tools ≤ 64, name mappings) and `McpApproval` (`sequence`, `snapshotRevision`, tools, `packageDigest`);
  - `McpProxyDocument` (`server`, tools map, `strict`);
  - `McpScanClaims` (permit claims, including `bindingId` and `spkiPins`, mandatory for `REFRESH`) and `McpScanJob`;
  - invocation permit claims for `MCP_PROXY` tools: `mcpVerification {status: CURRENT | UNVERIFIED, approvalSequence, checkedAtUnixMs?}`;
  - `McpQuarantineReport` (`invocationId` | `scanId`, `fencingToken`, `reason`, `observedDigest?`, `observedServerInfo?`).
- `package.schema.json`: rules for `MCP_PROXY` (runtime `mcp-proxy`, exactly one artifact of the proxy media type, every tool references it).
- `protocol.schema.json`: `ResultFrame.provenance?` (`TOOL` | `UNTRUSTED_UPSTREAM`) for TR.
- Deployment schema: `deviceGroupIds` for Tool Host targeting (TP).
- OpenAPI:
  - tenant routes: `/api/control/v1/mcp-servers` (POST, GET), `/{slug}` (GET, DELETE), `/{slug}/scans` (POST), `/{slug}/snapshots/{revision}` (GET), `/{slug}/approvals` (POST, GET);
  - Tool Host routes: `/tool-hosts/mcp-scans/next`, `/tool-hosts/mcp-scans/{id}/consume`, `/tool-hosts/mcp-scans/{id}/result`, `/tool-hosts/mcp-quarantines`.
- Permissions `mcp_server.read` and `mcp_server.register`.
- Regenerate the bindings and fixtures.
- **Done when:** the contract checks and the Python contract tests pass.

### T2. Definition digest specification
- Vectors in `tool-sdk/conformance/mcp-proxy/definition-digest.json`; Java (`McpDefinitions.digest`) and Rust
  (`mcp::definition_digest`) implementations, each tested against them.
- **Depends on:** nothing.

### TP. Device-Group-targeted Tool Host deployments (pools)
- A pool is an administered Device Group of enrolled Tool Host devices (ADR 013). Membership changes only through
  reviewed directory changes; Control reads it live at every decision.
- Tool Host registration: the host's self-reported `poolId` is never trusted. Control refuses a registration (first or
  later) whose `poolId` is not one of the Device Groups the host is an administered member of. No assignment, scan or
  permit is ever derived from the claimed value.
- `PackageDeploymentRequest` gains `deviceGroupIds` for Tool Host targets, mutually exclusive with `hostIds`;
  `MCP_PROXY` deployments must use exactly the registration's binding Device Group. Resolution is live:
  - `assignments` lists a deployment for every registered host that is currently a member of a target group, so a
    replacement host gets it once an administrator adds it to the group;
  - invocation placement and scan targeting choose only among live member hosts;
  - `permitIn` and scan permit issuance re-check membership of the audience host at issuance.
- Existing `hostIds` semantics are unchanged.
- **Depends on:** T1. **Tests:**
  - a host's **first** registration claiming a Device Group it is not an administered member of is refused, and it
    receives no assignment, scan or permit;
  - a replacement host added to the group receives assignments; one removed from it stops receiving them and permits;
  - a host of another group of the same tenant gets neither assignment nor permit; a forged placement is refused at
    issuance.

### TR. Result provenance through the pipeline
- `ResultFrame.provenance` (T1) is stored with run events and durable results (the FINISHED dispatch result that replays return).
- The Gateway's `success()` merges instead of replacing: it keeps its own `serverInfo` and adds `"io.toolgate/untrusted-upstream": true` when the provenance is `UNTRUSTED_UPSTREAM`. SDK tools default to `TOOL`, with no marker.
- **Depends on:** T1. **Tests:**
  - Gateway unit tests for both values;
  - Control stores the provenance;
  - an end-to-end assertion that the marker reaches the MCP client on the first call **and** on a replay served from Control's durable result.

### T5s. Streaming egress client (Tool Host)
- A new egress API next to `ROUTE_CALL` for the host's own native executors. It is not a broker operation offered to package code.
- It uses the same destination, address policy, connect-time checks, pinned TLS and secret injection, with the secret redeemed under the current invocation's or scan's consumed permit.
- It returns a bounded response reader:
  - `application/json` bodies within a cap;
  - `text/event-stream` parsed incrementally with limits on event bytes, events per response and total bytes;
  - an idle timeout;
  - cancellation when the caller stops, the permit or deadline ends, or a limit is reached.
- It never follows redirects.
- **Depends on:** T1. **Tests:** a fixture endpoint that keeps the stream open after emitting the result or a notification (the reader returns as soon as it has what it needs and closes); oversize and malformed events; a never-ending stream cut at the deadline; cancellation releases the connection.

### T3. Control: register and scan
- Tables `control_mcp_servers`, `control_mcp_snapshots` and `control_mcp_scans` per §4.2.
- Registration API in the routed tenant context.
- Scan orchestration:
  - scan jobs and permits (§6), with live target host selection in the pool;
  - Tool Host scan routes;
  - the `secrets/deliver` scan branch with the scoped Tool Group and Device Group check (§6);
  - result acceptance with digest recomputation and §5 limits;
  - snapshot storage and audit (`MCP_SERVER_REGISTERED`, `MCP_SCAN_*`).
- Scan deadline per §6: a signed `deadlineUnixMs` in the scan permit, lease expiry, refusal of late secret redemption
  and results, and the per-tenant watchdog that expires abandoned scans and frees their concurrency slots.
- **Depends on:** T1, T2 (TP only for Device Group membership lookups, which can stub). **Tests:**
  - late secret redemption and late results are refused; an abandoned scan expires, frees its slot, and an expired
    refresh makes verification `UNVERIFIED`;
  - unit: register; scan claim, consume and result from the lease holder only; a result accepted once; limits; a foreign secret or pool refused;
  - slug boundaries (§5) and a registered slug always yielding a valid `PackageId`;
  - scoped authority: in one tenant, a registrar without authority over a secret's groups cannot register, scan or receive it; a secret of other groups is refused at registration and at delivery;
  - secret delivery refused for an unconsumed, other-host or other-secret scan;
  - PostgreSQL RLS isolation: cross-tenant reads and writes refused, and the same slug in two tenants.

### T4. Control: approve, package and quarantine
- Approval per §5 (sequence, idempotency, 64-tool cap) and package generation per §6, with `ServerPackages.upload` and deployment to the registration's pool (TP).
- `PackageCompatibility`: accept runtime language `mcp-proxy` only for packages of kind `MCP_PROXY`, and refuse it for any other kind. Refuse `MCP_PROXY` packages with any other runtime.
- `ServerPackages.deploy`, for every package kind: in the same directory change, retire tools of the previous deployment that the new manifest omits (disable the tool and its extractor, drop it from `toolIds`, advance the revision and epoch) (§7).
- Maintain the verification record from refresh outcomes (a failure replaces an earlier success) and compute `mcpVerification` into `MCP_PROXY` invocation permits at issuance (§7).
- Quarantine endpoint per §6: evidence-bound and current-version-only, stale reports audited.
- Unregister.
- Tenant recovery: extend `tools/enterprise/restore.py` and the tenant recovery tooling to expire open scans and keep registrations, snapshots, approvals and states.
- **Depends on:** T1, T2, T3, TP. **Tests:**
  - the generated package goes through the **real pipeline**: `PackageArchive.validate`, `ServerPackages.upload` (runtime compatibility), `deploy`, then `permitIn`; an `mcp-proxy` runtime in a non-`MCP_PROXY` package is refused;
  - subset shrink: approve `{A, B}`, enable both, then approve `{A}`, and B is disabled, absent from catalogs and refused at permit issuance, while A keeps its state; the same retirement for an ordinary SDK package redeployed without a tool;
  - approval idempotency and distinct versions for different subsets;
  - the §6 quarantine cases (unrelated tool, other registration, after re-approval, other host, replay);
  - restore.

### T5. Tool Host: proxy executor and scan worker
- Runtime `mcp-proxy` in `store::RUNTIMES`. `Store::prepare` verifies and parses the proxy document as the artifact.
- The executor:
  - validates arguments (`jsonschema`);
  - keeps an upstream session per tenant and server;
  - calls `tools/call` through T5s and reads the matching response;
  - validates results and caps;
  - sets provenance (TR);
  - maps errors;
  - reports quarantines with evidence (§6).
- The scan worker: fetch, consume, verify the permit's `spkiPins` for `REFRESH` before sending any credential, redeem, then initialize and paginate `tools/list` through T5s, enforcing §5 limits as it reads, and report the result.
- Calls run the inline check when the permit's `mcpVerification.status` is `UNVERIFIED`, when a `CURRENT` status is older than 120 s by the host's clock, or in strict mode. A failed or incomplete check refuses the call with `UPSTREAM_UNVERIFIED` before any upstream `tools/call`.
- Phase 1 refuses upstream input requests and upstream tasks with a clear error.
- **Depends on:** T1, T2, T5s, TR (tests build `MCP_PROXY` packages by hand, independent of T4). **Tests:** against T8a, covering:
  - a good call; bad arguments; a bad or oversize result; a wrong pin; an upstream error; the deadline;
  - a stream left open after the result; notifications during a call;
  - a scan with pagination; each scan limit;
  - a certificate-key change with unchanged metadata: a refresh fails `PIN_MISMATCH` without sending the secret;
  - a trickling scan (bytes arrive slowly but never stop) terminates at the signed deadline, and the job's concurrency
    slot is released;
  - stale, missing or failed check evidence makes the call check inline; a failing inline check refuses it;
  - success → failed refresh → call within 120 s: the permit says `UNVERIFIED` and the call checks inline;
  - sessions not shared across two tenants' configurations.

### T6. Refresh and strict mode
- Control: schedule `REFRESH` scans per approved registration (≤ 60 s, paginated per tenant), and an immediate refresh when a host reports a seen `list_changed`. Compare and quarantine per §7.
- Tool Host: strict mode runs the inline `tools/list` check before each call, within that call's permit.
- **Depends on:** T4, T5. **Tests:**
  - the fixture changes a description, schema or name and the expected quarantine follows within one refresh;
  - strict mode catches it on the next call; `serverInfo` changes;
  - a certificate-key change quarantines the server before any credential is sent;
  - scheduler backlog (refreshes paused past 120 s) makes calls check inline; an upstream discovery failure refuses calls with `UPSTREAM_UNVERIFIED`, and recovery resumes them;
  - refresh work for one tenant never touches another's registrations.

### T7. Console: MCP servers page
- The tenant console's Administration page: add by URL (secret, scheme, pool, strict), scan, review definitions with a diff against the approved one, approve up to 64, history with approval sequence and source snapshot, state and quarantine evidence, re-scan, unregister.
- **Depends on:** T1 (mocked against the OpenAPI). **Tests:** vitest and axe per state, permission gating, the diff, the 64 cap.

### T8a. Fixture MCP server
- Python standard library, Streamable HTTP, under `tests/fixtures/mcp-server/`. Scriptable tools; changes to description, schema, name and `serverInfo`; bad output; errors; delays; `list_changed`; JSON or SSE replies; streams left open; paginated and oversize `tools/list`.
- Runs in-process for unit tests and in an existing Python image for composed tests.
- **Depends on:** nothing.

### T8b. End to end (two tenants), docs and example
- **Composed check** (default tenant): register, scan, approve two tools, deploy to a pool, enable, grant, call through the Gateway. Then the scenarios below, plus a subset shrink that retires a tool and a certificate-key change that quarantines:
  - the untrusted marker on the result and on a replay;
  - a denied call without a grant;
  - a quarantine after a description change;
  - a stale report after re-approval;
  - re-approval.
- **Two-tenant check** (`tools/tenancy/check.py`): the same slug and URL in both tenants; each sees only its own servers, snapshots, approvals, packages and tools; A's admin and A's hosts cannot read, approve, scan, call or quarantine anything of B's.
- **Docs:** `docs/gateway/mcp-proxy.md`; an ADR (next free number); `docs/control-plane/tenant-isolation.md` (inventory); the glossary; the planning backlog.
- **Example:** `examples/mcp-proxy/README.md`.
- **Depends on:** all of the above.

### T9. Phase 2 (follow-up issues)
- OAuth client credentials, token exchange and per-user connections (per-auth-profile snapshots and intersections).
- Packaged servers: digest-pinned server images run in the tenant's pool. This is the only complete defence against same-metadata behaviour changes.
- Mediated upstream input requests and upstream tasks.
- Resources and prompts.
- More than 64 tools per approval (requires raising package and deployment limits).
- Long-lived `list_changed` listeners.

## 9. Security requirements

- Models see only approved snapshots; unapproved upstream tools are never listed or callable.
- Tenant isolation per §4.
- Shared Control never contacts an upstream or holds a decrypted upstream secret. Secrets are delivered only to the
  lease holder of a consumed call or scan permit.
- Every outbound connection (scan, refresh, call) passes the egress address policy and the pinned TLS check, follows no
  redirect, and is bounded in bytes, events and time.
- Results are untrusted (provenance, end to end), size-capped and type-checked. A validation failure blocks the result
  and quarantines.
- Quarantine is evidence-bound and version-bound (§6).
- Secrets reach a scan only under the same Tool Group and Device Group scoping as a tool call, and only after the
  approved TLS pin matched (refresh) or under an explicit administrator discovery.
- No call runs on stale verification: missing, expired (over 120 s) or failed check evidence forces an inline check or
  refusal.
- Approval shrinks retire tools atomically; no tool outlives its approval in catalogs or permits.
- Pool membership is administered Device Group membership, never a host's claim.
- Every scan is bounded by a signed execution deadline; late redemptions and results are refused.
- Residual risk (documented): behaviour changes without metadata changes, or between the check and the call. Packaged
  servers (T9) are the full control.

## 10. How I will finish it

1. T1 and T2 first: small PRs; review the contract shapes with you before behaviour.
2. In parallel, each in its own session and worktree with your OK: TP, TR, T5s, T3, T7, T8a. Their areas are disjoint
   (Control placement, the Gateway result path, Tool Host egress, Control MCP tables and APIs, the console, a test
   fixture).
3. Then T4 and T5, then T6, then T8b.
4. Every PR:
   - unit and integration tests and contract checks;
   - clippy for Linux and Windows on Rust changes;
   - PostgreSQL tests, including the RLS isolation suite, on Control changes;
   - console tests and typecheck;
   - no local image builds.
5. Close #129 when T8b's composed and two-tenant checks pass in CI. File T9 as follow-up issues.

## 11. Review resolution (revision 3)

| Finding | Resolution |
|---|---|
| P1: permits for native calls and pre-approval scans | §6: native runtime and proxy-document artifact satisfy `permitIn` and `Store::prepare` unchanged; scan permit, scan job and the `secrets/deliver` scan branch (T1, T3, T5) |
| P1: quarantine bound to deployment and target | §6: one invocation or scan as evidence, targets derived by Control, applied only to the current package digest or approval sequence, stale reports audited; tests listed (T4) |
| P1: pool-specific deployment is new work | TP (refined in revision 5 to administered Device Groups): targeted deployments, live assignments for replacement hosts, placement and permit-time checks |
| P1: streaming transport | T5s: streaming egress client with bounded SSE parsing, cancellation and permit-bound secrets; fixture keeps streams open (T8a) |
| P2: untrusted marker lost | TR: typed `ResultFrame.provenance`, stored and replayed, Gateway merges `_meta`; end-to-end and replay assertions |
| P2: snapshot revision is not a version | §5: per-registration approval sequence, source snapshot kept separately, idempotency rules |
| P2: limits and discovery bounds | §5: approval ≤ 64 (existing cap); host-enforced numeric limits before aggregation, re-checked by Control |

## 12. Mapping to the issue's checklist

| #129 checklist item | Task |
|---|---|
| Register a remote server by URL with upstream auth | T1, T3 (secret auth); T9 (OAuth, token exchange, connections) |
| Register a packaged server | T9 |
| Discovery with the call-time auth profile | T3, T5 (scan in the tenant's pool), T5s |
| Snapshot: identity, SPKI pin, digest per tool | T2, T3 |
| Console: add, review, approve a subset as `mcp.<slug>…` | T4, T7 |
| Governed like SDK tools | T4, TP |
| Models only see the approved snapshot | T4, T5 |
| Call-time validation; failure blocks, quarantines, alerts | T5, T4, TR |
| Freshness ≤ 60 s, `toolsListChanged`, strict mode | T6 |
| Docs and an example | T8b |
| (multitenancy) tenant-owned data, pools and checks | §4, TP, T3, T4, T8b |

## 13. Review resolution (revision 4)

| Finding | Resolution |
|---|---|
| P1: scan secret delivery bypasses group authorization | §6: registrations name an execution binding; registering, scanning and approving require scoped authority over its Tool Group and Device Group; scan secret delivery enforces the existing secret-to-binding group equality; same-tenant unauthorized-group tests (T3) |
| P1: refresh scans lack an approved TLS pin | §6: `REFRESH` permits carry the approval's mandatory `spkiPins`, verified before any credential; `PIN_MISMATCH` quarantines; only an explicit `DISCOVER` can propose a new pin, effective on re-approval; key-change test with unchanged metadata (T5, T6) |
| P1: re-approval leaves removed tools enabled | §7 and T4: `deploy` retires omitted tools atomically for every package (disabled, dropped from `toolIds`, extractor disabled, revision and epoch advanced); subset-shrink test, also for SDK packages |
| P2: Control rejects the `mcp-proxy` runtime | T4: `PackageCompatibility` accepts `mcp-proxy` only for `MCP_PROXY`; verified through upload, deploy, permit and execution |
| P2: slugs can produce invalid package ids | §5: slug `^[a-z][a-z0-9-]{1,30}$`, one `PackageId` component; boundary tests (T3) |
| P2: refresh scheduling doesn't bound freshness at execution | §7: 120 s maximum check age carried in the permit; missing, expired or failed evidence forces an inline check or refusal `UPSTREAM_UNVERIFIED`; backlog and failure tests (T5, T6) |

## 14. Review resolution (revision 5)

| Finding | Resolution |
|---|---|
| P1: pool membership trusts the host's first registration | TP and §6: a pool is an administered Device Group of enrolled Tool Hosts (ADR 013); the self-reported `poolId` is never trusted and a registration claiming a group the host isn't an administered member of is refused, including the first; first-registration test |
| P2: failed freshness checks not in the permit | §7: permits carry `mcpVerification.status`, `CURRENT` only when the latest check for the deployed approval succeeded within 120 s; a failure replaces earlier success; success → failed refresh → call test |
| P2: no scan execution deadline | §6: signed `deadlineUnixMs` (30 s) enforced as one wall-clock budget across the whole scan; lease expiry; late secret redemption and results refused; watchdog expires abandoned scans and frees slots; trickling-server test |
