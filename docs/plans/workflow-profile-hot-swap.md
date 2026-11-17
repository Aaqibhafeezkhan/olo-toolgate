<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow profile hot swap — implementation plan

Status: accepted; implementation started locally. No hot-swap acceptance is
claimed. This plan follows the current #295 application and reuses #274 triggers
and #230 execution controls. Their baselines and exit criteria are unchanged.
Revised 2026-11-17 to require one persistent device, dynamic per-trigger heartbeat
reports, an explicit graph rebuild API, external LLM services and stateless core
containers. These are planned changes, not implemented capabilities.

## 1. Outcome and scope

One persistent `debug/profiled` stack runs Control, Gateway, Tool Host, a device,
LangGraph runtime and the configured supporting services. Model hosting is
**outside the core stack** and supplied by the user/cloud operations. An administrator can
import a workflow `.olo`, edit its JSON, prepare dependencies and activate a new
revision from the running console. Existing containers and identities remain in
place. New runs use the activated revision; existing runs retain their admitted
revision and finish against it.

**First startup is always blank:** create only a password-protected administrator
and the installation's infrastructure/trust metadata. Do not discover, select or
seed a workflow from repository configuration folders, and do not preinstall a
scenario's packages. An administrator imports a `.olo` after sign-in. Restarting
an initialized installation preserves its data; blank startup is not a reset.

The Admin UI and authenticated operator scripts use the same backend API
operations for bundle upload, preparation, review, activation and device
management. Scripts may explicitly enable a named enrolled device, approve its
verified key and assign group membership under the administrator's current
authority. These remain separate, revision-guarded actions; importing a profile
does not perform them implicitly. Honor pending configuration review, refusals,
idempotency and concurrency checks rather than bypassing them through Docker,
database edits or a local privileged issuer. Enrollment itself still happens on
the device. Scripts cannot create host permissions, mounts or ports remotely.

The unit of import/export and replacement remains **one workflow** with its
referenced data and exact `.olotool` packages. Other workflows can coexist.
Replacing one workflow must preserve shared references. A future whole-installation
profile switch is not silently introduced as another package format.

No deployment environment or monitoring configuration belongs in a workflow
profile. Monitoring is edited manually in installation-owned files. No changes
are to be committed or pushed without a new user instruction.

## 2. Configuration ownership

| Owner | Configuration | How it changes |
|---|---|---|
| Workflow `.olo` / console JSON | Graph selection/configuration, agents, prompts, model-provider references, tool dependencies, trigger configuration/connections and workflow-owned delivery settings | Stage, validate, prepare, activate while containers run |
| User/cloud-operations installation files | Core images, ports, mounts, container resources, external persistence and supported runtime capabilities | User/cloud operations supplies infrastructure; changing tools/triggers does not recreate core containers |
| Manual monitoring files | Collector pipelines/exporters, dashboard provisioning and monitoring deployment settings | Manual edit using each component's actual reload mechanism; independent of workflow activation |
| Ignored private credentials folder | Admin/user credentials, workload credentials, TLS material and provider/exporter secrets | Existing login, vault and credential lifecycle; excluded from workflow bundles |
| Device policy and approval | Permitted file roots/extensions, package execution capability, approved devices and trust | Existing device/Control management; a profile cannot expand these implicitly |

Move existing workflow `deployment.platform`, runtime image, local model-engine
launch and monitoring fields out of canonical workflow profiles and export data.
Keep only workflow behavior and references there. Define the installation file
format explicitly and update current schemas, fixtures, builders and examples.
Do not provide compatibility readers, conversion code or aliases for old formats.
Preserve unsupported existing stores/files and report the mismatch.

Environment variables, if retained for launching infrastructure, remain outside
the profile. They are not a mechanism for changing the active workflow. Connection
types, public provider endpoints, model IDs, generation parameters and routing
order remain workflow configuration; credentials are secret references. Model
weights, provider containers, server binaries and provider networking are entirely
the user's/cloud operations' responsibility. Remove model-hosting services and
model startup/custody generation from the core launcher.

### Fresh provisioning only — no backward compatibility

Implement a **new provisioning flow** for this architecture, from profile import
through device placement, package/configuration reconciliation, graph preparation
and activation. Do not adapt the old scenario-specific bootstrap or restart-based
deployment flow into a compatibility layer. Remove obsolete provisioning code,
configuration fields and callers when their replacements are delivered.

- Use current canonical package/API contracts and fresh database schemas only.
  No legacy aliases, compatibility constructors/readers, dual-format handling,
  runtime fallback to old provisioning, database migrations, upgrades, backfills
  or automatic conversion of old configuration/state.
- Reject unsupported old formats/stores with a clear error before changing them.
  Do not silently adopt, convert, delete or overwrite an existing installation.
  Fresh initialization uses new/current-schema storage. Destructive cleanup or
  factory reset is an explicitly selected administrative operation.
- Reuse current generic verification, authorization, Gateway/device transport and
  #274 reconciliation interfaces where suitable; reusing those interfaces is not
  retaining an obsolete provisioning contract. Revise affected current contracts
  and consumers together rather than supporting old and new wire formats.
- The new flow must support idempotent retry, crash recovery, device reconnect
  and interrupted deployment recovery **within its current schema**. Removing
  backward compatibility does not remove these operational requirements.
- Document fresh-install requirements and test explicit rejection of obsolete
  inputs. No migration instructions or old-flow escape hatch are part of the demo.

## 3. Current blockers in this repository

| Component | Current behavior | Required change |
|---|---|---|
| Deployment launcher | WorkflowStack deploy uses force recreation and device restart | One stable debug project; initial provisioning separate from workflow activation |
| Trigger listener | Reads one subscription from `listener.json` at process startup | Reconcile the authorized active subscription set in the same running service |
| Folder adapter/device file access | Reads one configuration/delivery binding at startup; device execution policy also has startup settings | Package-defined folder defaults, admin-editable paths, live propagation on save, device permission validation and applied-generation acknowledgement |
| Import/activation | Staging, package import, references, trigger restoration and publication are separate operations | A visible readiness/activation flow coordinating existing operations |
| Graph creation | Current native factories are installed in the runtime; there is no dedicated rebuild API | Compile the configured graph dynamically through an authenticated runtime API; validate installed node/adapter capabilities |
| LLM integration | The local deployment is coupled to a bundled model service | Remove model hosting from core; configure external connections and explicit per-agent routing/sequence |
| Provider network policy | Local deployment injects allowed model origins as startup environment | Separate user-managed network trust from workflow connections; allow authorized live updates through Control without treating a profile URL as network permission |
| Heartbeat | Existing #274 check-in has trigger snapshots/checkpoints/events and acknowledgements; full desired status behavior needs an audit | Extend current contracts as needed for dynamic instance status lists and bounded identified event batches on every check-in |
| Cleanup/persistence | Current launcher uses local custody/results/journals and process caches | Externalize durable state, delete eligible database resources through administration, invalidate all runtime caches |

The worker already resolves run configuration through Control's admitted execution
data. Reuse and verify that path; do not create a second configuration source in
the listener, device or a Docker operator. There is no hot-swap implementation yet.

## 4. Activation protocol

### Profile-contained package deployment and device placement

A workflow `.olo` contains **all required tool and trigger `.olotool` archives**,
their exact versions/digests, tool/trigger configuration, requested bindings and
authorization configuration, subscription/dispatch declarations and device-group
placement requirements. A list of package names without the actual required
archives is insufficient. External credentials, trust and infrastructure remain
installation-owned as defined above.

The profile specifies target device-group references/definitions and a placement
policy: deploy this workflow's required device-local tools and triggers to **one
eligible member** of the configured group. Device IDs are resolved at deployment,
not hardcoded into a portable export. Define required capabilities, platforms,
trigger modes, file-access requirements and any colocation constraints. A file
watch and the tools reading its local files must resolve to the same selected
device unless an explicit supported remote-access arrangement is configured.

For a **new device**, the user/administrator must explicitly enable it and assign
its device-group membership through device management, completing any required
approval. Enrollment or profile import must not enable a device, add it to a
group or approve it automatically. The profile declares where it may deploy;
device eligibility is independently administered. Once the configured group has
an enabled, approved, connected and capable member, pending deployment reconciles
automatically. Later disablement, approval revocation or group removal must stop
new work under existing lease/revocation rules and update readiness/status.

On import, Control performs the existing verification/review steps, stores the
embedded packages, resolves authorized group references and requested bindings,
selects an enabled/approved/connected/capable device, and provisions the required
package releases/deployments through Gateway. It then sends the configured tool
and trigger settings, obtains applied-generation/package readiness, creates or
reconciles subscriptions and workflow mappings, builds the graph and activates
only when the complete dependency set is ready. These are coordinated steps of
one visible import/deployment operation, not repeated manual package uploads.
Profile content requests configuration; it cannot grant itself authority.

If no suitable device exists or is connected, keep the candidate in a visible
pending-device/readiness state. When a device connects and joins the authorized
group, reconcile automatically without another import or container restart.
Group membership alone is insufficient when approval, capabilities, permissions,
credentials or package execution requirements are missing; show the exact blocker.
Do not replace a currently working revision with a partially deployed candidate.

Persist the selected placement and its generation so repeated check-ins/import
retries reuse the same deployment. Group membership must not broadcast the same
watch to every member when the policy is one device. Reassignment/failover must
fence the old instance/lease and define event/delivery handover; an offline device
must not return and run a second active copy. Shared packages/configurations are
reused only when their identity/settings match and retained while referenced.

This removes the need for users to separately upload each package, choose a
physical device ID or hand-wire subscriptions after every profile change. Host
permissions/mounts/ports, installed runtime adapters, external model services and
secret/trust provisioning remain explicit destination prerequisites.

### Candidate preparation and publication

Use the existing profile staging/review/publication model, extending it only
where necessary after inspecting its transaction and readiness contracts.

1. **Stage:** validate `.olo` structure and hashes, workflow JSON and dependency
   closure. Show the proposed changes and retain the active revision.
2. **Prepare:** store/verify embedded tool packages; resolve existing identities
   and secrets; prepare referenced data, package releases/deployments, trigger
   mappings, call the graph rebuild API and check external model-provider readiness. Existing review
   and independent approval requirements remain mandatory.
3. **Check readiness:** show each dependency as ready, pending or refused with a
   specific reason. Include the selected device's supported trigger mode,
   capability, package version, current approval and applied configuration where
   applicable. Structural validation alone cannot mark a workflow runnable.
4. **Activate:** compare-and-set against the reviewed candidate digest and current
   publication generation. Commit the authoritative workflow/binding revision at
   the Control admission boundary. Concurrent or stale activation is refused.
5. **Reconcile:** listener, worker and delivery adapters observe the new published
   generation. Expose desired/applied generation and reconciliation failures.
   Do not restart/recreate a container to apply profile data.
6. **Drain:** already admitted runs and staged input deliveries retain their old
   immutable configuration and journals. Stop new admission to the replaced
   workflow revision; finish or explicitly cancel old work under existing rules.

Preparing distributed device watchers is not a globally atomic transaction.
Specify the handover boundary and event treatment explicitly: Control admission
is authoritative, a watcher acknowledgement is readiness evidence, and a device
must never dispatch directly to an event-selected workflow. Test overlapping old
and new watcher generations, delayed events and acknowledgements. Do not promise
lossless or duplicate-free handover until those cases execute successfully.

An error before activation leaves the old revision active. A previous revision
can be activated again only after current dependency and authorization checks.
Rollback does not erase external effects, replay an unknown effect or restore a
revoked credential.

## 5. Component work

### A. Stable local stack and image reuse

- Finish consolidation to `debug/profiled`; remove specialized hotfolder and
  standard launchers and update callers, tests, Docker COPY paths and docs.
- Keep the Compose project, network, volumes, device identity and credential
  paths stable across workflow changes.
- Keep **one device runtime running** for the debug installation. New tool and
  trigger packages are uploaded/reviewed/deployed from the administrator console
  through Control and Gateway to that device using existing package mechanisms.
  Package/configuration updates must not restart the device service, Gateway,
  Tool Host or LangGraph container. A versioned package worker may be started or
  retired as part of package lifecycle; do not confuse that with recreating the
  persistent device/core containers.
- Inspect every needed local image. Reuse suitable existing images; build missing
  repository images in dependency order and pull missing third-party images.
  Provide an explicit rebuild option. Do not silently use an image missing a
  required runtime capability just because its tag exists.
- Include all modules needed by the deployed services. Gateway/Tool Host binaries
  embedded in Quickstart are accounted for by that image build, rather than
  pretending they are separate deployed containers.
- Build/reuse only the needed **core** images. Do not launch/build a Llama,
  OpenRouter gateway, LLM router or other model-serving container. Those services
  are supplied and operated independently by the user/cloud operations.
- Seed a fresh installation once from a selected configuration. If none is
  selected/configured, start with an admin login and no active workflow. Starting
  an existing stack must preserve subsequent UI changes.
- Do not couple ordinary startup to the full repository test suite. Keep
  validation/build and explicit destructive reset operations separate.
- Keep generated credentials private and ignored; print their locations, never
  their values. Preserve existing credentials on start and workflow switches.

### B. Dynamic trigger listener

- Use the existing authenticated Device -> Gateway -> Control check-in and
  response path. No second public callback/socket or trigger-specific transport
  is needed for management synchronization. Here "ping/pong" means that
  application check-in, not raw WebSocket protocol control frames.
- Registered assignments are desired configuration. A registered trigger may be
  disabled, pending a package/lease, active, draining or failed; registration
  alone must not start an unapproved watcher. Each check-in carries a **list** of
  the device's trigger-instance reports, including active status even when no
  event has occurred. No device is restricted to one active trigger.
- Identify each report by package-qualified `triggerId`, immutable assignment
  UID, instance/device identity and desired/applied configuration generation.
  Include package identity/digest, lifecycle state, health/error code, last
  observation time and backlog count. Multiple instances of one trigger ID must
  remain distinguishable. Schema field names are finalized against #274 before
  implementation; these conceptual states do not silently replace its enums.
- Every check-in includes pending event payloads as a bounded list, with trigger
  identity, event ID, lease epoch, sequence, configuration generation and
  observation time. With no events, send an empty event list and current status.
  Repeat only unacknowledged events; preserve durable outbox/acknowledgement,
  deduplication, backpressure and retry behavior. Do not resend an entire log or
  invent a trigger event merely to satisfy a heartbeat.
- Include status-list generation, paging/completeness metadata and total count
  when the configured set cannot fit the existing check-in byte bound. The UI
  must distinguish a partial page from a removed trigger. If exact fresh status
  for every active instance cannot fit one exchange, reject an excessive active
  set or page explicitly; never silently omit instances or send unbounded data.
- Control's response contains dynamic desired configuration/package changes,
  leases, revocations and event/status acknowledgements as required. Device
  reconciliation updates only affected instances; unchanged instances continue.
- A new folder configuration creates a new applied generation. A package must
  implement live reconfiguration or a controlled watcher/worker handover inside
  the same device container, reporting success/failure. A package that cannot
  reconfigure must fail readiness explicitly; do not restart the device as a
  fallback. Revalidate access/permissions before switching folder watches.
- Each file-access tool/trigger package declares its default folder names and
  configuration schema. Defaults initialize unset configuration only; they do
  not overwrite an administrator's saved paths. Avoid a single core-hardcoded
  `hotfolder` root shared by every package/use case.
- Show the defaults and current input/output/processed folder settings in the
  administrator UI, scoped to the package assignment/device placement. An
  authorized save persists a new desired configuration generation and propagates
  it through Control/Gateway to the device independently of graph/container
  restart. Show desired versus applied generation and pending/applied/refused
  status. If a device is offline, keep the saved update pending and reconcile
  it when the device reconnects.
- The user is responsible for creating/providing folders, correct service-account
  permissions and any container mounts. Save does not run chmod/chown/ACL changes,
  mount a host path or broaden OS access. The device validates the configured
  path against independently administered policy and actual service-account
  access, then applies the update or reports a bounded, actionable error.
- A newly selected accessible path is not rejected merely because it differs
  from a startup folder default. Replace that startup assumption with the live
  reviewed access configuration; keep intentional device-policy restrictions
  authoritative. Reject traversal/symlink escapes and cross-tenant access.
- Failed reconfiguration reports that the desired revision was not applied and
  preserves the previous valid watcher configuration unless it was explicitly
  disabled/revoked. Do not silently fall back to the package default or report
  success while still watching the old path. Existing deliveries retain their
  original folder settings while draining.
- Discover authorized active subscriptions using current Control interfaces.
  Reconcile additions, removals and immutable UID replacements with bounded
  concurrency and bounded shutdown/reconnect intervals.
- Close removed listener sessions without acknowledging unadmitted events.
  Durable server cursors and admission receipts remain authoritative.
- Reuse #274 assignment/check-in/package interfaces, updating shared contracts
  and all consumers together where required. No trigger-name switch or special
  hotfolder protocol. These are fresh contracts; no legacy compatibility path.
- Surface unsupported PUSH/POLL modes or unavailable executors in readiness;
  the launcher cannot make an unfinished trigger implementation available.

### C. Dynamic graph construction and runtime API

- Verify that each claim freezes the profile, workflow version, graph/config,
  model and tool references for that run, including after a publication change.
- Build LangGraph topology at runtime from versioned workflow JSON: node types,
  agent references, model policies, tool calls, edges, routing conditions,
  entry/exit points, branches, loops, parallelism, joins and subgraph references.
  Include supported interrupt/resume, retries, timeouts and execution budgets in
  the schema/capability matrix. Reject unsupported constructs before activation;
  do not call a partial JSON compiler "full LangGraph support".
- Keep a generic, versioned node/adapter registry rather than one hardcoded
  graph per use case. JSON refers to supported node types and installed trusted
  factories by digest. Arbitrary Python is not silently accepted as inert JSON.
  New executable extensions still require an operator-installed trusted package
  or image; ordinary graph topology/configuration changes do not.
- Add a dedicated internal runtime operation, proposed as
  `POST /internal/runtime/v1/graphs:rebuild`, plus operation/readiness status.
  Define its canonical OpenAPI/JSON contracts before implementation. The
  authorized request names tenant, profile, workflow, candidate digest and
  expected generation; the runtime fetches the reviewed immutable definition
  from Control. Do not accept arbitrary code, filesystem paths or caller-chosen
  credentials in a build request.
- Control invokes this endpoint under authenticated service authority after
  candidate validation/review. The browser calls Control, not an unauthenticated
  container port. Rebuild is idempotent by candidate digest/build specification;
  an asynchronous response identifies the operation and exact compiled digest.
  A successful build does not itself publish the profile or grant execution.
- Validate/compile/warm a candidate beside the active graph, then report its
  capability and dependency results. Activation uses the existing generation
  check. An unsuccessful build preserves the active graph. Compilation must not
  call models/tools or perform workflow side effects.
- Keep clients, secrets and per-run identity out of reusable topology caches.
  Cache keys include graph/config/compiler/node-registry versions and enforce
  tenant isolation unless a proven pure artifact can safely be shared.
- All runtime replicas reconcile the published digest. A new replica reconstructs
  its graph from authoritative state without depending on another replica's
  memory; eviction or process loss triggers lazy rebuilding, not lost definition.
- Model connections and agent/provider sequence are resolved from the admitted
  run revision, including during a profile switch.

### D. File delivery and resource ownership

- Derive delivery behavior from the published workflow and its admitted result,
  not from model-provided paths or a startup-only profile file.
- Retain a journal and immutable delivery configuration for each staged input.
  Old deliveries drain with old folder/projection settings while new inputs use
  the new revision. Avoid double-watching the same path during handover.
- Preserve bounded file access, digest checks, no-overwrite projection and
  conservative handling of changed inputs/unknown outcomes.
- Changing input/processed/output folders or tool settings is a configuration
  update saved in the administrator UI and sent through Control/Gateway, not a
  container mount edit. Packages supply defaults; administrators can override
  them. It takes effect dynamically within authorized, operator-accessible paths. Missing
  mounts, OS permissions or unopened ingress ports are explicit readiness
  failures with operator instructions, never silent permission expansion.
- Delete only exclusively workflow-owned definitions/resources after checking
  references and in-flight use. Human accounts, credentials, installed trust,
  audit history and shared package assets are not disposable workflow data.

### E. Console lifecycle

- Offer import/replace, JSON editing, dependency preparation, readiness, review
  and activation from the existing workflow screens.
- Show active and staged digests, generation, preparation results and drain
  status. Refresh the UI after successful operations.
- Reuse current configuration review, package and trigger administration APIs.
  Do not add a browser-to-Docker switching endpoint or bypass approvals.
- Keep `.olo` export portable with referenced data and required `.olotool`
  archives. Exclude monitoring, deployment environment, credentials and README.

### F. Manual monitoring

- Remove monitoring settings from workflow contracts, source configurations and
  exported workflow bundles.
- Provide installation-owned example files for collector/exporter configuration
  and private credentials, including the currently supported Langfuse/LangSmith
  integrations and local observability services.
- Document which components support reload and which require an explicit service
  operation. Profile switching must never rewrite monitoring settings.

### G. External LLM connection types and execution sequence

- Model services are not part of the core stack. Support configurable connection
  protocols through adapters: initially OpenAI-compatible endpoints (including
  user-hosted Llama servers, OpenRouter and compatible LLM routers), then any
  explicitly implemented provider-native adapters. Provider branding and wire
  protocol are separate fields; a URL alone must not imply API compatibility.
- Each connection declares a stable connection ID, adapter/connection type,
  endpoint, model ID, authentication mode plus secret reference, allowed network
  scope, TLS requirements, timeouts, concurrency and supported capabilities.
  Exact enums match actual adapters, not a promise that every provider works.
- Each agent declares the ordered connection/model choices it can use and the
  strategy: one selected connection, ordered fallback under explicit failure
  rules, or a defined multi-model sequence whose outputs feed later steps.
  Specify which failures permit fallback, per-attempt/overall budgets and output
  mapping. Never silently change provider after a refused authorization or
  replay a side-effecting tool call as an LLM retry.
- The graph separately defines **agent execution order**, routing and parallel
  branches. Distinguish an agent's provider fallback list from sequential agent
  execution. Show both in JSON and the UI, with no environment-only provider
  choice or embedded default model name.
- Export non-secret connection configuration and references with the workflow.
  Destination users supply credentials, external services and network access;
  readiness checks verify them before activation. Profiles do not deploy those
  services or alter their container settings.

Conceptual example (final field names require canonical schema work):

```json
{
  "connections": [
    {"id": "local", "connectionType": "openai-compatible", "provider": "llama.cpp", "endpoint": "https://llm.internal/v1", "modelId": "user-selected-model", "secretRef": "local-llm-key"},
    {"id": "premium", "connectionType": "openai-compatible", "provider": "openrouter", "endpoint": "https://openrouter.ai/api/v1", "modelId": "user-selected-premium-model", "secretRef": "router-key"}
  ],
  "agents": [
    {"id": "summarizer", "modelPolicy": {"strategy": "ordered-fallback", "connections": ["local", "premium"], "fallbackOn": ["timeout", "rate-limit"]}},
    {"id": "reviewer", "modelPolicy": {"strategy": "single", "connections": ["premium"]}}
  ],
  "graph": {"entry": "summarizer", "edges": [{"from": "summarizer", "to": "reviewer"}], "exit": "reviewer"}
}
```

The example contains no working credentials or deployment configuration. Adapter
capabilities, approvals and network policy still determine actual availability.

### H. Database cleanup, cache invalidation and stateless containers

- Persist workflow definitions, activation generations, run snapshots,
  checkpoints, results, admission receipts and required delivery journals in
  external durable stores. Store immutable package bytes in an external artifact
  store or database-backed package service. Credentials remain in external
  custody/vault. No required durable state belongs in a container writable layer.
- Treat runtime graph caches as disposable. A cold/replaced runtime must rebuild
  from Control and external stores and resume eligible work through existing
  lease/recovery rules. No mounted startup JSON or local result directory is the
  source of truth for an active workflow.
- A device still needs a durable trigger outbox/checkpoint while disconnected.
  Use a user-managed external durable store/volume for that custody; deleting the
  container must not delete it. Calling the container stateless does not justify
  dropping unacknowledged events. In-memory buffers alone are insufficient.
- Provide administrative delete/replace/reset operations that **remove eligible
  workflow-owned rows directly from the database and invalidate caches** rather
  than relying on container restart or retaining a hidden local copy. Enforce
  tenant scope and shared/in-flight dependency checks in the transaction.
- Coordinate cleanup through Control's administration boundary, with an explicit
  cancellation/drain policy. Do not advise ad hoc SQL while workers still hold
  valid leases. Retain required audit/effect-ledger/idempotency evidence under
  existing retention policy; workflow cleanup cannot erase unknown effects or
  human credentials. Full destructive installation wipe is a separate operation.
- After database commit, publish a generation/invalidation event and track cache
  acknowledgements across runtime replicas/listeners/devices as applicable.
  Rejoining replicas reconcile before admitting work. Cached artifacts never
  establish authority after the workflow was deleted. Report pending invalidation
  honestly instead of claiming a distributed purge completed early.
- Keep shared tool packages, connections and directories until no other workflow,
  active lease/run, pending event or retained delivery requires them. Physical
  artifact garbage collection runs only after those checks.

### I. Operator README and ready-to-run prerequisites

- Every supplied configuration/use-case README explains its watched inputs,
  referenced tools, resulting outputs and required external LLM connections.
- Document all extra host/device work: folder creation, ownership/ACLs, allowed
  file extensions, mounts, DNS/TLS trust, provider reachability and ingress ports.
  Keep these requirements outside workflow profile deployment settings.
- Supply commands ready to run for the selected platform, resolved paths,
  service account, source CIDR and actual configured port. Include PowerShell
  and Linux variants where supported. The UI/launcher can render or export an
  operator prerequisite sheet using the selected configuration; unresolved
  placeholder-only commands do not count as a finished example README.
- Explain each command's scope and provide a verification command: directory ACL
  inspection, connection check or firewall rule inspection. If a firewall rule
  is needed, provide a narrowly scoped creation command and its removal command.
  Local loopback-only operation must not instruct users to open inbound ports.
- Users/cloud operations execute host, firewall and container changes manually.
  Profile import/rebuild must never run those commands automatically. If a new
  mount/port is needed, report it and wait for the operator to prepare it; no
  automatic core container recreation is an acceptable substitute.

## 6. Delivery sequence

| Step | Deliverable | Exit evidence |
|---|---|---|
| 1 | Agree scope/ownership and audit existing publication/run snapshot semantics | This reviewed plan and mapped gaps; no runtime completion credit |
| 2 | Stable core launcher/device, external persistence/custody and manual installation/monitoring files; remove bundled model service | Fresh/blank startup, image reuse/missing-image builds, no core model container, preserved identity |
| 3 | Dynamic device tool/trigger configuration, check-in status/event lists and subscription reconciliation | Multiple active triggers, add/update/remove and folder A-to-B handover without core/device restarts |
| 4 | Dynamic graph rebuild API and external LLM connection/routing schemas | Rebuild and readiness checks; different agent/provider sequences run using configured mock providers |
| 5 | UI activation, run isolation, delivery draining and database/cache cleanup | In-flight A completes on A; new work uses B; shared/in-use resources retained; all runtime replicas reconcile invalidations |
| 6 | Stateless recovery, integrated acceptance and operator documentation | Cold-container recovery and all scenarios below executed; source-bound evidence recorded in #295 status |

Implementation checkpoint (component evidence, no phase acceptance): step 1 is
accepted. Step 2 remains pending. Step 3 has runtime subscription reconciliation;
device placement and applied package/trigger readiness are now implemented at
the component boundary, with persisted one-device selection and group-fenced
targets; automatic deployment reconciliation and activation handover remain
pending. Step 4 has JSON and
installed native-factory rebuilding, admin preparation, durable receipts and
compiler pinning and expiring compatible-worker presence for publication/new
admission. External LLM routing and per-replica topology readiness remain pending.
Step 5 has admitted-run A/B configuration isolation; coordinated device handover,
invalidation and cleanup remain pending. Step 6 has component/image tests, with
full-stack recovery and the live demo still pending. The authoritative executed
checks and limitations remain in
[workflow-v1-status.md](../architecture/workflow-v1-status.md).

The user accepted these solutions and authorized implementation. Work proceeds
in focused changes. The user subsequently authorized committing, pushing and
opening a draft PR for the initial implementation slice. Delivery must update the live
#295 status; changes affecting #230/#274 retain their component verification
and status obligations. No new roadmap weight is invented by this plan.

## 7. Required acceptance

1. Start a dedicated fresh stack with cached local images; prove no unnecessary
   rebuild. Remove one required test image and prove only its missing dependency
   chain builds. Preserve unrelated stacks and images.
2. Start blank, sign in as admin, import workflow A, prepare/review/activate it,
   trigger a real file event, call the configured agent/tools and verify output.
3. Import workflow B with a different trigger/configuration and output behavior
   through the console. Activate B and execute it without container recreation
   or process restart caused by the profile change.
4. Capture container IDs, start times, restart counts, workload process identity
   and device/admin identity before/after. Verify their stability and verify that
   credential contents remain unchanged without logging those contents.
5. Keep an A run and file delivery in flight across activation of B. Verify A's
   immutable configuration/output destination and B's new configuration separately.
6. Refuse a missing tool package, uninstalled graph, unavailable model, unsupported
   trigger mode, unapproved device, missing secret and stale activation generation;
   the previous active workflow must remain usable.
7. Exercise disconnect/reconnect, subscription removal/replacement, late events,
   duplicate admission and service recovery without synthetic acknowledgements
   or duplicate authorized external effects.
8. Keep a second workflow sharing an agent/model/tool reference; replacing or
   deleting the first must retain shared and in-flight dependencies.
9. Verify profile export/import in a second prepared installation without
   transferring human credentials or installation/monitoring settings. Report
   any missing destination prerequisites rather than claiming identical readiness.
10. Verify monitoring configuration is untouched across A/B swaps and that an
    installation restart preserves the UI-published profile instead of reseeding.
11. Use one device with at least two active trigger instances. Every check-in
    reports their identified statuses, including idle healthy periods. Produce
    events on both; verify identified payloads, acknowledgements, retries and
    deduplicated admission, plus paging/maximum-size behavior.
12. Upload/deploy a new `.olotool` through the admin console and Gateway, then
    update a folder watch/tool configuration while the device stays running.
    Verify old-folder handover, new-folder events, applied-generation feedback,
    permission refusal and stable core/device container and process identities.
13. Rebuild a graph through the dedicated authenticated API. Change branches,
    agent sequence and per-agent external LLM connection sequence; verify the
    resulting behavior, failed-build preservation, stale-generation refusal and
    unauthorized/cross-tenant rejection. No model container is deployed by core.
14. Delete a workflow through administration, prove eligible database rows and
    all relevant caches are cleared, and prove stale replicas cannot run it.
    Retain shared dependencies and required ledger/audit evidence. Repeat with
    an in-flight run under the documented drain/cancel policy.
15. Replace a runtime container with a clean instance and empty local cache.
    Recover current graph configuration, eligible checkpoints/results and pending
    delivery state from external stores. Separately verify device outbox survival
    across process/container replacement and a temporary offline interval.
16. Run README prerequisite commands on the supported test platform in a
    disposable environment. Verify actual folder permissions and narrowly scoped
    network rules; prove no profile operation creates them behind the user's back.
17. Import a self-contained profile into a prepared installation without its
    tool/trigger packages already installed. Verify one operation uploads/verifies
    the embedded packages, selects one eligible device from the configured group,
    installs/configures both tools and triggers, wires subscriptions/dispatch and
    executes the workflow. Verify the local file tools and watcher are colocated.
    Repeat with no eligible device connected: remain pending, connect/approve/join
    a suitable device, then complete automatically without re-import or core/device
    restart. Verify wrong-group/ineligible devices are refused, retries do not
    duplicate deployments and a second group member does not create another watch.
18. Enroll a new device and prove profile import does not enable it, approve it
    or assign group membership. Enable and configure membership manually; prove
    pending deployment then reconciles. Test disablement, group removal and
    approval revocation against new dispatch and existing trigger leases.
19. Verify package folder defaults appear in the UI. Save overrides for a running
    tool/trigger and prove propagation, applied-generation acknowledgement and
    events/output at the new paths without restarting the device/core services.
    Retry while offline and reconcile on reconnect. Refuse inaccessible or
    policy-disallowed folders with an actionable status, preserve the previous
    valid configuration, and prove no permission or mount changes occur implicitly.
20. Start with fresh current-schema storage and exercise the new provisioning
    flow end to end. Submit obsolete profile/package/state formats and verify
    clear rejection without conversion, deletion or fallback to old provisioning.
    Independently prove retry/reconnect/crash recovery of current-format pending
    deployments; these cases must not require re-import or device recreation.

Use mock external providers in package/pipeline tests as previously requested,
plus real local Control/device/runtime integration for admission and execution.
Run relevant SQLite/PostgreSQL, tenant-negative, generated-contract, UI and
component gates. Record actual results and limitations, not inferred completion.

## 8. Working-tree checkpoint

Implementation was paused at the user's request on 2026-11-17. Local work already
started moving `debug/standard` utilities to `debug/profiled`, removing the old
hotfolder launcher and updating references. Those edits are incomplete,
uncommitted and unpushed. They do not deliver this plan. Review the diff before
resuming; no new stack or hot-swap demonstration has been run for this work.

## 9. What remains constant

The target contains no hardcoded workflow ID, trigger/package name, number of
active triggers, watched folder, output folder, agent order, model/provider choice,
model host or monitoring exporter. These are dynamic configuration or user-owned
infrastructure. The current code still has the blockers in section 3; this is a
target architecture, not a statement that they have already been removed.

| Remaining fixed boundary | Why it remains | How it evolves |
|---|---|---|
| LangGraph execution engine and supported JSON graph schema | The compiler needs defined semantics | Versioned runtime/schema release; topology changes remain dynamic |
| Installed node, tool runtime and LLM protocol adapter implementations | Configuration cannot invent an unimplemented protocol or safely supply arbitrary executable code | Trusted extension/package installation where supported, otherwise an operator runtime upgrade |
| Package extensions and manifest/API versions (`.olo`, `.olotool`) | Consistent validation and interoperability | Explicit current-contract release; no legacy aliases |
| Device, assignment, instance and tenant identities | Required to correlate configuration, status and event admission | Instances/configuration generations change; stable device identity is preserved |
| Authentication, authorization, tenant fencing and effect/lease rules | A hot swap cannot create authority or bypass isolation | Existing reviewed security/policy administration, never model/profile-selected credentials |
| Payload, concurrency, retry and resource ceilings | Dynamic lists/graphs still need bounded execution and backpressure | Installation policy may tune supported limits; workflow settings cannot exceed them |
| Container networking, host mounts/ACLs and external persistence availability | Containers cannot create missing host/cloud infrastructure from a workflow | User/cloud-operations changes; README supplies the exact prerequisites |
| Durable outbox, run snapshot and effect evidence requirements | Stateless core containers must not lose events, admitted work or unknown effects | External storage/retention policy; not an in-memory-only implementation |

Changing already supported graph JSON, tool/trigger packages, folder settings
within approved roots, agent/provider routing and workflow connections must not
need a core/device restart. A new host mount, network opening, executable adapter
or runtime binary is infrastructure/extension work and is reported explicitly,
not hidden inside a supposed profile hot swap.

### Demo blocker conclusion

At planning review, no additional use-case-specific constant is identified beyond
the explicit replacement work in section 3. The fresh provisioning flow must not
carry forward those scenario-specific constants. This is a design assessment,
not an executed end-to-end result; implementation is now in progress.

Remaining prerequisites are an enabled/approved device with manually assigned
group membership, appropriate host access/networking, available external model
connections/secrets/storage, and installed support for the graph nodes, trigger
modes and connection adapters used by the demo. Readiness must check these before
activation. They are explicit capability/authority/infrastructure requirements,
not hidden hardcoded workflow choices or reasons to restart on a profile change.

## 10. Proposed decisions for the seven open design points

These decisions make the design concrete for review. Implementation is still
in progress; this section alone establishes neither implemented coverage nor
successful execution. The user accepted these decisions before implementation.
They supersede any earlier suggestion that duplicate suppression must prevent
all producer-side redelivery.

### 1. Trigger handover: at-least-once delivery, consumer-owned event handling

- The producer retains stable event identity and a durable outbox, delivering
  at least once until acknowledged. Retries and watcher handover must preserve
  the logical event identity; package contracts define source identity and event
  identity, rather than guessing identity from an arbitrary payload hash.
- **The consumer owns duplicate/in-progress handling.** For workflow consumers,
  reuse/extend Control's durable admission/run records with a unique key scoped
  to tenant, logical consumer, logical source and event ID. A replacement profile
  revision must not change that key for a retried event. Different intentional
  consumers may independently handle the same event.
- Atomically reserve the event before starting work. A repeated event in
  accepted/processing state returns the existing admission/run; a completed
  event returns its recorded disposition. It never starts another run for that
  consumer. An in-memory busy flag is insufficient across replicas/restarts.
- A crashed consumer resumes/reconciles the same durable run under the current
  lease/fencing rules. Lease expiry alone must not create a second logical run
  or retry an unknown external effect. Non-workflow consumers own equivalent
  durable handling in their own implementation; document the consumer contract.
- A transport acknowledgement means the event was durably accepted or terminally
  rejected, not that business processing completed. Pending admission remains
  in the producer's outbox until it receives that acknowledgement.
- Use generation-based watcher handover with draining of already captured
  events. Duplicates are allowed and handled by consumers. Preserve checkpoints
  where the package declares them compatible; a folder/source change declares
  its initial-scan policy explicitly (existing inputs versus new observations).
  Do not silently claim lossless discovery for an unsupported source transition.

### 2. Package reconfiguration: prepare, apply and report generation

- Define a versioned SDK lifecycle contract for configurable tools/triggers:
  `validate_config`, `prepare_config`, `apply_config` and `stop/drain`, with
  configuration schema/defaults and explicit live-reconfiguration capability.
  Names describe the proposed contract, not existing methods already implemented.
- An admin save creates desired generation N. The device validates schema,
  policy, mounts and actual permissions, prepares N while the old valid instance
  runs, then atomically switches its local active configuration. It reports
  desired/prepared/applied generation and a bounded error if application fails.
- Unchanged instances continue running. A package that needs a fresh worker can
  start/drain that package worker inside the same persistent device deployment;
  it cannot demand a device/core restart. If neither live apply nor a supported
  worker handover is possible, refuse readiness before activation.
- Keep event/journal records associated with the configuration that created
  them. Reapplying the same generation/digest is idempotent. A failed candidate
  leaves the previous valid configuration active and is clearly shown in the UI.
- New folders/configuration do not imply new OS permission. Users prepare access
  using the documented commands; the device applies configuration only after
  that access is valid. No silent chmod, mount or privilege change is allowed.

### 3. Graph coverage: versioned JSON compiler and explicit node registry

- Implement a generic JSON-to-LangGraph compiler instead of per-use-case graph
  builders. The first supported contract covers JSON state/reducers, agent/model/
  tool/transform nodes, conditional edges, bounded loops, static and dynamic
  fan-out, joins, referenced subgraphs, interrupts/resume, node retry/timeout
  policy and run budgets. Include asynchronous execution and an observable run
  event stream in the runtime contract.
- Conditions and transforms use a defined typed expression/operation registry;
  never Python `eval`, import paths or executable text from the profile.
  Validate state, references, reducers, joins and loop/resource bounds before
  reporting a candidate compiled.
- Every node, expression, reducer, adapter and graph feature has a schema/version
  and executable acceptance case. Publish this capability matrix from the runtime
  API. A profile requiring an unsupported feature fails with its exact location.
- New graph topology using installed capabilities rebuilds dynamically. New
  executable node implementations require a trusted installed extension/runtime
  release. Arbitrary LangGraph Python SDK code cannot honestly be promised as
  universally expressible in JSON; an installed native factory remains an
  explicit extension path, not an implicit fallback for rejected JSON.
- The protected rebuild API compiles an immutable candidate and records its
  specification/compiler/registry digest and result. Keep compiled Python graph
  objects in memory only; persist validated definitions/build metadata, not
  pickled graph objects. A fresh worker compiles from those definitions.

### 4. Distributed readiness: durable deployment operation and activation barrier

- Persist one idempotent provisioning operation per reviewed candidate digest,
  with visible dependency steps for package verification/release, eligible device
  placement, prepared configuration, bindings/subscriptions, graph compilation,
  provider/secret access and required storage. Recover this operation after a
  Control restart instead of repeating side effects blindly.
- Each acknowledgement names the exact candidate/package/configuration digest,
  generation and instance. Old or expired readiness reports cannot satisfy a
  new candidate. New candidate watchers may prepare without being authorized
  to admit events into the new workflow before activation.
- Activation requires all mandatory dependencies and the installation's required
  number of compatible ready runtime workers (default one). Non-ready workers
  are ineligible to claim that graph version until they compile/reconcile it.
  Final dependency/authorization checks and expected-generation comparison occur
  at the authoritative Control publication transaction.
- After publication, devices/listeners receive the activation generation through
  existing synchronization and complete handover. This is an observable
  distributed convergence, not a fictional instantaneous global switch. Report
  reconciling/draining status until the required components acknowledge it.
- If preparation fails, keep the old profile active. If a dependency disappears
  after activation, block affected new admissions and expose degraded readiness;
  do not silently publish another profile or duplicate an in-progress event.

### 5. Persistence: Control PostgreSQL plus an external device outbox volume

- Use operator-provided PostgreSQL as the shared durable store for the demo's
  multi-replica/stateless Control/runtime path. Control owns workflow definitions,
  provisioning/admission records, run snapshots, leases, JSON checkpoints,
  results and delivery journals. Runtime persistence adapters use lease-checked,
  tenant-scoped Control storage operations; graph authors receive no database
  credentials or raw saver namespace control.
- Keep package archives in the existing Control package-content store, backed
  by that external database for the initial implementation. Do not introduce a
  mandatory object-store service merely for this demo. A later artifact backend
  can use the same content-addressed package contract.
- Keep the device's offline outbox/checkpoints in a fresh-schema SQLite store on
  a user-provided durable host directory/volume, outside the container writable
  layer. This is the deliberate offline custody exception to purely remote state;
  it survives container replacement and does not require a connection to Control
  to retain an observed event.
- Runtime graph caches are memory-only and disposable. Local result files and
  folder-adapter SQLite journals cease to be core authority; their required
  data moves behind the Control persistence adapter.
- SQLite remains useful for existing component/unit tests and a documented
  single-instance development mode; it does not qualify the planned multi-replica
  recovery demonstration. No database migration or legacy store adoption is added.

### 6. Cache invalidation: transactional database outbox and revision checks

- Commit authoritative changes and an invalidation record in the same Control
  database transaction. Runtime/listener instances consume or poll the durable
  invalidation stream, acknowledge their observed generation and evict affected
  unused cache entries. Redis is not a required new dependency.
- Identify cached artifacts by immutable definition/compiler/registry digest;
  mutable profile pointers additionally carry their current publication generation.
  A cache hit is never permission to admit or execute deleted/revoked work.
- Require current Control admission/lease authorization for new work. A replica
  reconnecting after missed invalidations reconciles its generation before it
  claims work. It cannot execute a stale profile merely because it retained a
  compiled graph in memory.
- In-flight runs pin their original graph until drained, or follow explicit
  cancellation/revocation. Do not destroy an in-use graph object and leave a
  running node halfway through a mutation. Purge eligible database rows/artifacts
  after reference, run and retention checks; expose pending cleanup/acknowledgements.

### 7. LLM adapters: one initial protocol, three explicit routing strategies

- Ship one fully qualified initial protocol adapter: **OpenAI-compatible chat
  completion**, for user-provided compatible Llama endpoints, OpenRouter and
  compatible LLM routers/premium routes. Validate the selected endpoint's actual
  supported features. Do not advertise direct native-provider protocols until
  their adapters have been implemented and tested.
- Keep connection type, provider label, endpoint, model ID, authentication/secret
  reference, capability declarations and timeouts in the canonical connection
  configuration. Keep network trust/policy independently administered and live
  configurable through Control. No startup-only provider URL/model selection.
- Support `single`, `ordered-fallback` and `sequential` policies explicitly.
  Fallback is ordered and permitted only by configured transient error classes;
  authorization/policy failures never trigger a provider switch. Sequential
  policy defines how each prior output is mapped into the next request. The
  graph separately defines agent execution order and parallel branches.
- Enforce per-attempt and total budgets, cancellation and capability checks.
  Retrying/falling back an inference request must not rerun a tool effect;
  workflow tool execution remains under its existing ledger/authorization path.
- Readiness validates connection configuration, reachability/TLS, secret access
  and available model capabilities; actual inference is proved by acceptance
  calls against mock providers and the separately supplied demo provider.

### Verification that closes these decisions

Add acceptance cases for concurrent duplicate events across consumer replicas,
same-run crash recovery, config prepare/apply failure and offline replay, every
declared JSON graph feature, stale readiness acknowledgements, database/runtime
restart recovery, delayed invalidation and rejoining replicas, and all three LLM
routing strategies with denied and transient failures. Assert the same event does
not start a second logical run for the same consumer. Retain the existing no-core/
device-restart, tenant-negative and effect-ledger checks.
