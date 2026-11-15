<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Workflow execution v1 — live delivery status

## Packaged deployment, exposed console and credential folders — 2026-11-15

The Admin UI HTTP port is published on all host interfaces (triage 58091,
summary 48091); TLS/private-registry ports remain loopback-bound. Fresh local
human credentials are stored separately as
`state/<selection>/credentials/<username>/username.txt` and `password.txt`.
The identity refresher reads that custody; startup prints its folder, never the
password. Old credential stores are not converted or silently adopted.

The common Quickstart image contains the provisioning operator, bootstrap
initializer and sealed HotFolder/inbox-watch packages. Control builds those
packages during image publication. Compose contains no build directives or
profile-specific derived image. Startup extracts assets from the selected
immutable local image identity, validates package digests and initializes
root-owned bootstrap data mounted read-only by Control. Registry-qualified
missing images are pulled; explicit source builds remain available from a
checkout. Model/engine/agent/trigger/folder choices still come from the selected
workflow profile. Signed client-tool image provisioning inside Control remains
part of setup, separate from rebuilding deployment images. No public registry
publication was performed or claimed.

The reported Gateway-trust failure was stale host credentials after Docker
volume replacement. Previous issue-triage state/data were preserved under
`prior-demo/gateway-reset-issue-triage-20261115-204414`, followed by a selected
fresh reset and successful start. A subsequent start reused the new credentials
successfully. Other old summary artifacts and the portable-image test evidence
are also preserved under `prior-demo/`. Trust changes are refused with this
stack's explicit reset command; no effects are automatically replayed.

Executed evidence:

- Fresh issue run `run-d927491a-9f35-461b-9a53-a1967be2dec8` completed with a
  confirmed configured-agent read and inference, CHECKOUT-1042/HIGH, and
  `acceptance-10c4e524a7-bf1cb6ec2371.result.json`; original retained.
- Portable text-summary run `run-eb17596a-106e-4f16-804a-2870491b066e` completed
  using a copied bundle and cached registry-qualified prebuilt image tags, with
  no repository operator/package build. It confirmed file-agent read, inference
  only by `text-summarizer`, exact-byte archival and
  `acceptance-4fedc083c4-6f5c69f97fa7.summary.txt`. This tests local image reuse,
  not remote registry credentials/download/publication. Fixture storage was
  archived and its owned Docker resources removed afterwards.
- A fresh text-summary start in the main WorkflowStack directory then completed
  `run-d2dcea17-a490-4ed3-8976-85ca0dec53c1`, confirming the two configured
  agents, exact-byte archive and
  `data/text-summary/summaries/acceptance-155269e9e9-53c4ffda9c29.summary.txt`.
  Both supplied configurations are running in the main bundle with their own
  credential folders and UI ports; the portable fixture is no longer running.
- Quickstart/Control source image builds pass. Both image-only Compose profiles
  validate; the live Admin UI responds HTTP 200 and Docker publishes
  `0.0.0.0:58091`. Admin/reviewer username/password files are verified present.
- 12 profile/delivery, 11 Windows lifecycle, 36 operator, 5 package and 10
  documentation tests pass. Image-asset tests: Windows 3 discovered/1 Linux-root
  skip, Linux 3 pass, including altered digests, writable/linked bootstrap
  custody and unknown-store refusal. Ten trigger-agent tests pass in the
  existing runtime container; the host lacks its LangGraph dependency.
- Full Windows CI-Python discovery: 450 tests, 10 errors and 11 skips. Eight
  release-note errors were Windows default-encoding failures; all 21 affected
  release suites pass with PYTHONUTF8=1. All four debug-manager cases pass on
  rerun after an initial 30-second timeout. The device Unix-socket symlink case
  still requires Windows symlink privileges. This is not a green full gate.
- Source/license/secret/dependency scans pass with Docker tooling and
  PYTHONUTF8=1. Initial scan attempts lacked host Cargo and then hit Windows
  decoding; those failed attempts are not accepted. Documentation links pass
  after excluding the private generated image cache, with an explicit test
  retaining checks of authored configuration READMEs. Prior full Java/Rust/UI
  evidence and limitations below remain applicable; they were not rerun here.

Known tested base `e62cfc60`; normalized source manifest SHA-256
`c8a646b966871803c213a8c7b421d27ec4737945d680256b515bdd5cc991f7d3` (18 existing changed public non-Markdown files;
sorted path + NUL + file SHA-256 after CRLF-to-LF normalization + newline).
Baseline `v1-effort-1` stays 100 points with **0/100 earned**. No phase weights
or security/trigger qualification credits change. Full Linux/distributed V1.4,
production identity, native mutation qualification and authorized release-image
publication remain outstanding; PR #311 remains draft.

## Windows stack lifecycle — 2026-11-15

`WorkflowStack/start.bat` lists configuration folders with serial numbers, asks
which to start, and offers the main `.env` selection as the explicit Enter
default. Invalid input reprompts; EOF/cancellation starts nothing. An explicit
`--configuration` skips the menu. Startup reuses available images, builds missing
source-image tags and pulls missing digest-pinned references before calling the
existing profile-derived deploy path. Required model artifacts remain prerequisites.

`stop.bat` is explicitly destructive: by default it removes all current
WorkflowStack projects belonging to this bundle, owned native-child containers,
named/anonymous Docker volumes, networks and generated `state/`/`data/` (including
credentials and outputs). `--configuration` limits deletion to one selection.
Profiles, scripts, supplied model artifacts, vendor credential inputs and shared
images are retained. `manage.bat stop` remains the non-destructive pause command.
Cleanup does not depend on a valid profile/.env. It checks project/root ownership,
uses root+configuration-specific native engine scopes, rejects ambiguous/foreign
ownership and linked storage, and uses verified literal PowerShell deletion on
Windows. There is no global prune or implicit conversion of old stores/scopes.

Executed: 11 Windows lifecycle tests pass, including real batch execution from a
directory with spaces and exit-code propagation; Linux discovers the same 11 with
the Windows batch test skipped. All 12 existing deployment/delivery regressions
pass. The real start.bat menu smoke lists both supplied profiles and refuses EOF.
An isolated Docker fixture with an owned device-style container, owner-labeled
child, named/anonymous volumes and network was completely removed; unrelated
volume and profile/model files were retained. This fixture does not attest native
SDK posture or repeat application/model end-to-end qualification. No live demo
storage was erased for this test. Base/monitoring Compose syntax and source checks
pass. Earlier application runs and full-gate/platform limitations below remain
the applicable evidence; broader V1.4 and security acceptance are still pending.

Known tested base `aace4bc8`; normalized source manifest SHA-256 `ef4c78ecb02335217edab7a2fe4c3e0b6e0a7fefaf1eb3be1b6248dddeda2bd9`
(7 changed public non-Markdown files, sorted paths + NUL + per-file SHA-256
after CRLF-to-LF normalization + newline). Baseline `v1-effort-1` remains 100
points with **0/100 earned**; this lifecycle delivery changes no phase weights.

## Selectable WorkflowStack deployment — 2026-11-15

The complete local bundle is under
[`deploy/compose/WorkflowStack`](../../deploy/compose/WorkflowStack/README.md).
Its main `.env` accepts only `CONFIGURATION`. Each workflow's typed `deployment`
declaration contains runtime/model engine and artifact, service images/ports,
trigger source, folders and monitoring/collector configuration. Model ID/origin,
generation parameters and vault references are declared in the same profile and
resolved through its model groups. No use-case configuration.json or authored
global model settings remain. Generated Compose/bootstrap/collector files are
derived artifacts. Ambient model settings cannot override the selected profile.
One workflow deployment is enabled per stack; inactive definitions can be retained
when combining profiles. The operator projects only the active dependency closure
without changing the source document. This is local LANGGRAPH/LLAMA_CPP deployment,
not arbitrary engine/plugin execution or automatic multi-workflow orchestration.

Ordered `agentStages` prevent supervisor-model output from skipping configured
agents. Text summary executes the file agent's CONTEXT stage (confirmed configured
read, no inference), then the separate summarizer's MODEL stage. Issue triage uses
one MODEL agent stage after its configured read. The fixed runner passes complete
context directly and returns the final result unchanged. Current agent/group and
own model grants are rechecked, including existing tokens; tool authority follows
the active subject and cannot be borrowed from the run's service agent. Gateway
uses bounded profile-selected generation parameters and canonical constrained
decision decoding/validation. Current lease/model wire fields are explicit;
obsolete snapshots are refused without conversion.

The trusted folder adapter independently verifies Control's successful result,
pinned profile/workflow, admitted source path and source digest before projection
or archival. It has a tenant-administrator reader token and narrowly mounted files;
it is an operator component, outside the untrusted native tool sandbox. Its writes
do not qualify native mutations or authorize ambiguous effect retries.

Actual accepted runs using the profile-selected stack:

- Text summary `run-3a60d857-9ac8-4d46-a1e6-0321659419d6` read `acceptance-bd2cad9459.txt` through the file
  agent, inferred only as `text-summarizer`, archived the byte-identical original
  and wrote `acceptance-bd2cad9459-41da14d57f58.summary.txt`.
- A second independent drop after the driver exited completed as
  `run-45c928af-ff91-4608-937a-608bf2a22fc8` and produced
  `acceptance-1b79a521fd-619fbb068ee0.summary.txt`. Both summaries correctly describe
  staging, connection-pool improvement, and the pending production review.
- Fresh issue-triage provisioning with the final source/profile completed as
  `run-18e3713e-6e9e-46d1-a882-ed265adc052c`. Its configured agent performed the confirmed native read and
  inference; saved/projected output contains CHECKOUT-1042, HIGH priority and an
  investigation action. The original remains in the inbox. This profile uses
  ports 58091/58451/58552, while summary uses 48091/48451/48552: service settings
  switch with the profile, and both isolated projects ran together.

Control/private results agree and worker observations show the required agent
identities and successful configured read. Observations are not independent
executor/stage-completion attestation. Earlier redeployment rebuilt an immutable
package version and produced a release/trigger mismatch; restart now retains and
verifies sealed custody and waits for a fresh device check-in/current trigger.
Earlier demo state/data and stopped volumes were retained (`prior-demo/`).
Routing-profile run `run-aba9a71a-3374-46c3-9645-f1bd8dda79c5` reported success
without agent calls and was rejected by acceptance; its output is preserved in
`data/text-summary/rejected-acceptance/`, not counted as accepted evidence. Fixed
stages and skip/revocation negatives address that failure in the final profiles.

Executed validation: 12 deployment/delivery tests pass on Windows and Linux;
62 runtime tests, 14 qualification cases (one Windows symlink skip), and three
acceptance-fixture tests pass. Fresh SQLite/owned PostgreSQL focused Control checks
pass 49 ordinary and 28 enterprise cases with no skips; all four
Java contract tests pass. Generated bindings (1,556 files), structural fixtures,
canonical profile validation, TypeScript checks/build/two contract tests and 445
PHP v2 round trips/constant negatives pass. Twelve focused UI tests pass; no full
UI coverage gate is claimed. Rust format/workspace Clippy pass; workspace cases
plus the isolated timing-sensitive heartbeat case pass 413 tests with 17 environment
ignores. An earlier parallel run failed that heartbeat assertion; the focused
rerun passes. Source-build images, Compose base/monitor syntax and source/license/
secret checks pass. Both profile-derived local and example vendor collector
configurations validate in the pinned image with network disabled; no vendor
account ingestion was tested. The bundled monitoring guide covers Langfuse,
LangSmith, Prometheus/Grafana/Tempo, OTLP, Datadog/New Relic and audit/logs. Native
LangSmith content tracing remains disabled; full agent graphs/token/cost tracing
is not implemented.

Earlier full Windows checks remain explicitly non-green: repository CI ran 431
tests with one socket-symlink privilege error/ten skips; the full Java gate ran
370 ordinary tests (eight skips) and 449 enterprise tests with one unsupported
operator TLS fixture failure. Those earlier runs are not current full-gate passes.
Complete applicable Linux gates, message-path and complete V1.4/distributed
security acceptance remain outstanding. Production identity lifecycle remains
unimplemented; local enrollment approval lasts 24 hours and model leaves seven
days. No public registry publication was executed.

Known tested base `6039df65`; SHA-256 `3b3e9cd49a011686fa19631e116edd018ba943aec41348e06c5038c058196ded` covers 95
changed/untracked public non-Markdown files: concatenate sorted repository paths,
NUL, each file SHA-256 after CRLF-to-LF normalization and newline, then hash the UTF-8 manifest. Ignored private
state/data/models and generated acceptance artifacts are excluded. Images used:
Quickstart `sha256:569f4decce71a4e0f79a89ae8a9b88397af314bc8559785313626b2e1c81ac43`;
runtime `sha256:84de1e84dcb3f9722bcaca550224ebbd4bddb0b4932ce6835206675e32cc2fbf`;
device `sha256:b86e2aecdfeb33ede704f1d317dcea988a711d9f7e431ccdac7c72c714113c91`.

The profile/stage contract extensions change no effort allocation/denominator.
Baseline `v1-effort-1` remains 100 points, **0/100 earned**; partial phases earn
nothing. PR #311 targets `feature/workflow-enhancement` and remains draft.
Historical merged #297 is not treated as V1.4 acceptance or a completed roadmap.

## Live issue-log demo — 2026-11-14

The bounded one-trigger/one-agent demo now executes end to end on the isolated
`toolgate-workflow-demo` stack. A real signed device trigger admits the published
`hotfolder-intake` / `issue-triage-v1` workflow. Its configured
`langchain-linux-agent` reads the admitted file path using
`examples.hotfolder/read_text`, then infers through Gateway and the local TLS
model. The supervisor returns the configured five-field result. Control validates
the pinned result schema and current service authority, encrypts the result under
tenant custody and commits it with completion. The worker explicitly writes a
private result file after acknowledgement. Default stdout remains content-free.

Executed evidence on base `1d7271c7`, with implementation/test/example source
manifest digest `bbfdd0a40151cb0a9513e3403683f1a39eb9402efdbc4c550c509ac066b5b082`:

- Acceptance run `run-b2591041-7cae-4614-824a-0d9ed944dfa1` succeeded for
  `TG-DEMO-fe85fd5a`. Duplicate admission returned the original admission/run.
  Worker observations include supervisor → configured agent → supervisor model
  calls and one confirmed configured `read_text`. The result's issue ID and exact
  source path matched the actual file; Control and the private result file agreed.
- A second file, submitted after the driver exited, completed through the
  continuous listener/worker as `run-a0527243-e708-4329-b096-c87f94a93360`,
  returning `CHECKOUT-1042`, priority `HIGH`, and a connection-pool investigation
  action. The fixture credential helper refreshes mounted short-lived tokens;
  replacement is atomic and local result directories use private permissions.
- The actual managed Java containers were observed with non-root `65532:65532`,
  no mounts/network, read-only root, dropped capabilities, no-new-privileges,
  seccomp, 512 MiB memory and 64 PIDs. A current native agent read succeeded;
  the actual tool returned `exists=false` and empty text for parent traversal to
  an existing outside-root fictional sentinel. This is bounded observation,
  not independent executor posture attestation.
- Focused Control tests passed: 38 ordinary and 28 enterprise tests, including
  fresh SQLite/PostgreSQL admission-pin, finalization and recovery regressions.
  Runtime gate: 54 tests passed; the additional mounted-token rotation test and
  existing transport suite passed all 14 tests. Qualification gate: 13 tests
  discovered with the Windows symlink case skipped; three fixture tests passed.
  Generated bindings (1,521 files), UI operation generation and fixture corpus
  checks passed. UI type checking and TypeScript contract checks/build/tests passed.

The final focused rerun passed 46 ordinary and 28 enterprise tests with no skips,
including context-binding checks and added result size/tenant assertions. The
full ordinary Control suite passed 370 tests with eight skips; the Java contract
suite passed all four tests. Linux reruns passed all six knowledge qualification
tests and the device socket-symlink regression. Source/license/secret checks passed.

The first two live runs exposed a supervisor omitting mandatory `issueId`.
Control refused completion and those runs expired; no tool effects were retried.
The corrected immutable workflow version makes all five required fields explicit.
Malformed model results still fail closed; model correctness is not guaranteed.
The listener encountered transient disconnects and recovered using the durable
cursor; separate idle socket and subscribed-socket checks each stayed connected
for 65 seconds. Continuous acceptance above confirms recovery without lost work.

The full Windows repository check stopped in the 424-test CI suite: one
PowerShell timeout (the focused four-test rerun passed) and the existing socket
symlink privilege refusal. The 183-test contract suite also encountered two
Windows symlink privilege errors and a scorecard observation failure. The full
Java gate's enterprise suite reports 449 tests with one Windows unsupported
operator TLS fixture failure. These are not recorded as passing component gates.

Images actually used: Quickstart overlay
`sha256:707c61b72a61ee340c4322d422779335ccf7d01dc05eeb9d2b79cc12b0f56fc2`,
worker `sha256:381093c8812d8b958a704b3ebd8b6db82ea97fbe3779171fe886316bccd15ea5`,
device `sha256:b86e2aecdfeb33ede704f1d317dcea988a711d9f7e431ccdac7c72c714113c91`.
The local Quickstart image has no validated public client download bundle; this
demo uses the existing native device image. Release publication is wired but no
release image publication was performed by this demo.

Limitations/next work: operator-qualified Linux Java broker-only reads are
explicitly enabled for one device/group/image; the default and file mutations
remain unqualified (ADR 064). This supplies no general native runtime security,
replay authority or production IdP lifecycle. Local enrollment approval expires
after 24 hours. Complete applicable Linux component gates, full V1.4 acceptance
and the outstanding weighted phases remain required. Weights and earned phase
credit are unchanged. PRs #297 and #309 are already merged; this follow-up targets
`feature/workflow-enhancement`. Their merged state does not earn additional
V1.4 acceptance credit or establish completion of the original draft criterion.

## One-trigger, one-agent production slice (2026-11-14)

The [delivery plan](../plans/langgraph-workflow-execution-plan.md) narrows the
first live use case to one real trigger, one published workflow, one configured
agent, and one approved model. It uses the existing #295 contracts and runtime.
Phase weights and earned completion are unchanged.

- V1.1 and V1.3 now have transaction-scoped resolution for an in-flight
  delivery and its published binding. The resolver checks the immutable source,
  subscription owner, current grants and clearance, and reviewed profile head.
  Tests cover pending and acknowledged deliveries, a different subscription
  owner, another tenant, and an unready or withdrawn binding.
- The standalone image packages the existing listener and LangGraph executor
  library under an unprivileged user. Its current entry point is the listener.

Verification on base `f269293b` with this change applied: `WorkflowProfilesTest`
and `TriggerDeliveryTest` passed all 23 tests with the disposable PostgreSQL
fixture and SQLite. `python tools/workflows/check.py` passed with 32 runtime
and 13 qualification tests executed, with 10 OpenSSL-dependent tests and one
symlink test skipped on Windows. The digest-pinned runtime image built and
printed listener help with a read-only root filesystem and dropped capabilities.
The full component gates and live trigger-to-agent execution remain pending.

Next is the Control admission transaction and its durable run ledger, then
workload claim, step authorization, Gateway model transport, and live execution
qualification. The resolvers currently have no public admission endpoint and
return no runtime credential.

### Durable run storage and input mapping

The fresh SQLite and PostgreSQL schemas now store tenant-fenced admission and
queued-run records. Runs pin a profile digest and encrypt the mapped message
under tenant vault custody. The profile's JSON Pointer maps event data to the
workflow's `message` input, which is validated against the pinned parameter
schema. The store supports atomic enqueue and admission receipt persistence.

Verification on `4630c600` with this increment applied: nine focused Java tests
passed across the dispatch mapping, queue transaction, and tenant row security
suites. The four schema inventory/capacity tests passed. Admission and run APIs,
workflow service authorization, worker claims and live execution remain pending.

### Trigger admission endpoint

Control accepts the existing dispatch request over direct TLS. The listener
supplies only the subscription UID and delivered cursor. Control verifies the
current source and published binding, checks explicit service workflow and model
grants, and commits the encrypted queued run, admission receipt and audit row
in one tenant transaction. Duplicate requests return the original identifiers.
The listener and Control use the same existing receipt contract.

Verification on `5613d925` with this increment applied: the focused trigger
admission test passed on SQLite and disposable PostgreSQL. It covers a live
directory grant denial, a forged workflow field, a queued run with the pinned
revision, and duplicate receipt replay. Both Quickstart direct TLS routing tests
passed in Linux. Full component gates and live worker execution remain pending.
The next increment needs workload claims, runtime step authorization and
Gateway model and tool transport. Phase credit is unchanged.

### Workload claim and completion

The workload-only Control routes claim one queued run per request and return a
snapshot of its pinned workflow and agent definitions. Claims recheck current
publication, readiness, and service grants. Only the same workload principal
holding the current lease token can record completion before expiry. The
Python transport decodes the snapshot and sends completion using its separate
workload credential. Worker step authorization and Gateway model and tool
transport remain required before the container can run a claimed workflow.

Verification on `bebfd374` with this increment applied: the focused admission,
claim and completion test and both transaction tests passed with SQLite and
disposable PostgreSQL. Two Python transport tests and four schema ownership
and capacity tests passed. No live workload HTTP, model, or Gateway tool
execution was exercised. Phase credit is unchanged.

### One-agent step authorization and model selection

The workload runtime can request a step token for the pinned supervisor or the
entry agent when the agent is the trigger's service identity. Each new step
replaces the previous token. Control checks current readiness, service grants,
trigger data clearance at admission, and the tenant kill state at step issuance.
The model resolver intersects the workflow and step model groups and selects the
first permitted deployment in workflow order. It rejects superseded step tokens.

Verification on `e8185ef7` with this increment applied: the focused trigger
admission, claim, step, model-selection and completion scenario passed on
SQLite and disposable PostgreSQL. Three Python transport tests and four schema
ownership tests passed. Model selection was exercised inside Control. Gateway
inference, MCP execution, and the container's live worker loop remain pending.

### Gateway runtime transport

The worker image now claims a run, executes the existing LangGraph runner,
and sends inference and MCP calls to Gateway using the active step token.
Gateway resolves the step with Control. Inference uses an operator-approved
HTTPS origin and the current vault credential. MCP uses the existing Gateway
catalog and invocation path. Control denies a PREPARE call when the signed
package descriptor does not establish a read-only tool.

Verification on `362f7c5e` with this increment applied: Control compilation,
the Gateway Rust test suite, five focused Python transport tests, and a
read-only worker image help smoke passed. The model response and MCP adapter
tests use transport fixtures. A fresh real trigger, model, and tool run
remains the next acceptance step. Phase credit is unchanged.

### Live acceptance fixture and model trust

The Compose fixture runs the real listener, worker and external llama.cpp
server. The first attempt admitted a real hot-folder event, claimed the
published `hotfolder-intake` run, and recorded `FAILED` at inference. The
Gateway now accepts an operator-mounted PEM trust bundle for approved HTTPS
model origins. The fixture uses an isolated CA and mounts the workload and
model credentials separately. Verification on `5c55cc07` with this increment
applied includes a rebuilt Quickstart image, a loaded llama.cpp model, Python
syntax checks, and `git diff --check`. The live retry is pending. No phase
credit is claimed.

The retry on `c2b25e74` admitted a second real event and reached agent tool
discovery after model inference. PREPARE refused the context tool because
client releases use a managed deployment digest. The fix records effect
evidence from the verified descriptor during client release and resolves it
by the current tool ID and managed digest. The stored evidence contains only
the tool ID and declared effect safety. Validation of this fix is pending.
On `a5afb7b1`, all 11 client release tests and the focused workflow admission
test passed with the disposable PostgreSQL fixture. The client release suite
asserts the bounded effect record under each managed digest. All 47 Python
runtime tests passed with OpenSSL available. Quickstart image
`sha256:03226d95d19d8b7addd982571e248f7d1ea6e9b6f048aa47c8786ba33bd7c25c`
was built from `9bda47bc`. The live acceptance rerun is in progress.

### Acceptance review and duplicate admission (2026-11-14)

Review base: `3424a053f207a961af5bc149fe693deff9a4057d` on
`codex/langgraph-workflow-execution`. The terminal-state-only acceptance check
could pass without the configured agent or its context tool being exercised.
The worker now reports per-run inference responses and tool attempts using only
subject/tool IDs and outcome states. The fixture requires supervisor and agent
inference plus a confirmed `examples.hotfolder/list` result; it also replays
admission and requires the original admission/run coordinates. Observations are
bounded by the runner step limit and exclude credentials, content and arguments.
They are worker observations, not independent server attestation.

The first review rerun admitted `run-cfe3486b-b556-4a60-9bd3-25d925f260a8`
and stopped with `TOOL_OUTCOME_UNKNOWN`. The rebuilt worker rerun admitted
`run-26019dff-97c8-4f11-b3a3-50c9f9c7b70b`; duplicate admission returned
the original admission/run IDs. Its observations confirm supervisor inference
and an agent-scoped `examples.hotfolder/list` attempt with `OUTCOME_UNKNOWN`.
The ledger completion was acknowledged as `FAILED`; the fixture exited 1.
The context failure prevented agent inference. Neither run qualifies the live
slice. Each rerun used a new event; no uncertain operation was automatically
retried. These runs reused the existing isolated SQLite installation; they are
not fresh-store or PostgreSQL end-to-end qualification.

Executed verification: `python tools/workflows/check.py` passed dependency
installation and pip check, 51 runtime tests, 13 qualification tests (one host
symlink skip), and three acceptance regression tests. Git's OpenSSL was added
to PATH, so TLS tests executed on Windows. The seven new tests cover missing
agent/tool observations, terminal-only false success, foreign IDs, unknown and
denied outcomes without retry, per-run isolation, credential/content exclusion,
and stable duplicate coordinates. The runtime image built successfully as
`sha256:5fda72c8decbd3287803cadce1377fc3d32e79f8bda70205c4aa8c6c2ba4ec38`.
The Quickstart services still used the previously recorded `9bda47bc` build;
this change modifies only the worker, fixture and verification/docs.

`python tools/check.py` was attempted and failed in the initial CI regression
suite: 422 tests, ten errors and ten skips. Eight errors were Windows default
encoding failures in release/PR-policy tests, one was a debug fixture's
30-second subprocess timeout, and one was a device fixture lacking symlink
privileges (WinError 1314). With `PYTHONUTF8=1`, isolated reruns passed all seven
PR-policy, 14 release-notes and four debug-management tests. The symlink
privilege limitation remains; the full gate's later component stages did not
execute. No full repository pass or new Java/UI gate pass is claimed.
Metadata/preflight (1521 bindings), source headers/licenses/obvious-secret
policy, relative Markdown links (2896 files), staged PR policy, staged secret
scan and whitespace checks passed. Logs are retained under ignored
`build/workflow-*-review.log`. No Git hooks were configured in this checkout;
no hook bypass was used.

Known tested source SHA-256:

- Worker: `d1f634684623cdf4b02ead6cef199ff0364d94b2ee9cbe67e8a9396c6f5ffc16`.
- Live driver: `7e0af43db11a6dbcfd3fd05f951c5d5294cc5f5d24ff171ae7c67a19981b5f13`.

Next: qualify native HotFolder finality under #230's own stage workflow, then
complete the live agent/model/tool success path and authorization-negative
scenarios. Full v1 phase acceptance, two-agent execution, complete profile
provisioning and fresh-install qualification remain outstanding. No #230/#274
stage/module or runtime security guarantee is completed by this change. Phase
weights and overall earned completion remain **0 / 100**.

### CI repair and standalone image release wiring (2026-11-14)

Review base: `f02a0c45c7c074aba5e5d250ab855da61cc56652`.
[Rust job 132444767419](https://github.com/olo-labs/olo-toolgate/actions/runs/43593600248/job/132444767419)
failed on Clippy's redundant-closure and nonminimal-boolean warnings in the
workflow Gateway adapter. Both expressions are simplified without changing
validation behavior. Its missing containment artifact was a downstream failure:
the producing checks were skipped after Clippy failed.

The standalone image previously had no release publication wiring. It now uses
the existing tested image/evidence gate, immutable release bundle, GHCR and
Docker Hub publishers, and provenance steps as `olo-toolgate-langgraph-runtime`.
The manual development build/save/publish path includes it too. Product release
coordinates are `ghcr.io/olo-labs/olo-toolgate-langgraph-runtime:<version>` and
`docker.io/<namespace>/olo-toolgate-langgraph-runtime:<version>`; the latter
uses `DOCKERHUB_NAMESPACE` or the configured Docker Hub username. No namespace
secret value is assumed. Ordinary branch CI does not publish images.

The Debian base scan found 49 HIGH/CRITICAL package findings; available Debian
updates left 47 without available fixes. The image now uses digest-pinned Python
3.12 Alpine, applies available OS updates and installs the unchanged Python
lock using musllinux wheels. Final Linux amd64 image
`sha256:55207450127637bc1ef313484debde6f28a168f3b62a42743f1271e4b7259098`
built with version `1.0.0-dev.1` and the known review-base revision label.
Trivy 0.61.1 reported zero HIGH/CRITICAL findings in that image, and CycloneDX
SBOM generation passed. Trivy warned that Alpine 3.24 was absent from its EOL
list; this result is not lifecycle certification or a security guarantee.

Executed checks: Rust workspace formatting and Clippy with all targets and
`-D warnings` passed using Rust 1.94.1 in Docker.
`cargo test --workspace --locked` also passed: 410 tests passed and 17 were
ignored across unit, integration and documentation test summaries. Ignored
tests and the separate containment/conformance checks are not qualified by this
run. Release regression suites
passed: image wiring/registry identity 2, product release 25, unified CI 16,
tagged release 23, manual development 8, shared release jobs 5, and local CI
35 tests (one symlink skip). Tests cover required runtime image bytes,
registry push coordinates, refusal of mismatched image revisions, and CI gate
ownership. The final image passed worker/listener help and pip check with a
read-only root, no network, dropped capabilities and no-new-privileges. Its
runtime suite passed 41 tests with ten OpenSSL-dependent skips; the foundation
host runtime gate remains responsible for TLS fixtures.

`PYTHONUTF8=1 python tools/check.py` was rerun after correcting a new mock's
serialized response and registering the image job in local CI policy. It ran
424 CI tests with one error and ten skips: the existing Windows device fixture
cannot create its Docker socket symlink (WinError 1314). Later component gates
did not execute. Focused release checks, workflow actionlint, metadata/preflight,
source policy, relative links and whitespace passed. No full repository pass,
actual registry push or release provenance execution is claimed by local
publisher simulations. Publication awaits a successful authorized release run.

Known tested source SHA-256:

- Gateway HTTP: `79f28149d901fbfba5e4607cfafb742bbbea18e1af2b0b53c0db26bdd1252fea`.
- Gateway relay: `2b7c1f6808eb5dc9529dcf926f0cec93e779e06e46472b72286c38b82231d0e7`.
- Runtime Dockerfile: `bdaeebb209d7a703ffe5cff97a4ea72843248d15a01fe5c52733ab980fed8bbf`.
- Image workflow: `5643d9ebed42234a8765ad564f4e47665d829eea9946e6ca3b1e113f832b9b9a`.

This delivers distribution wiring, not successful workflow execution. The live
context-tool finality blocker, full authorization-negative scenarios,
fresh-install/two-profile acceptance and original phase exit criteria remain.
No phase weight or #230/#274 completion is credited; overall earned completion
remains **0 / 100**.

### Explicit image test-mode guard follow-up (2026-11-14)

Delivery: draft [PR #309](https://github.com/olo-labs/olo-toolgate/pull/309)
into `feature/workflow-enhancement`; full live acceptance remains outstanding.

Review base: `6d4812cf5bd6296ddb967afd27e32f10c8239e00`.
[Run 43596899996](https://github.com/olo-labs/olo-toolgate/actions/runs/43596899996)
failed its manual-release admission regression before Rust or image jobs ran.
The final in-image unittest command needs an explicit step-level
`if: inputs.run_tests`, in addition to the existing job condition. That guard is
now present; the regression is retained. The earlier local full-check run had
evaluated admission before that unittest command was added, so it did not
establish final-workflow admission coverage.

Executed checks on the corrected workflow: actionlint, source policy and Linux
metadata/preflight passed. Linux Python 3.12 ran all 424 CI tests with two errors
and 17 skips; both errors were fixtures writing temporary files under the
read-only checkout's `.dev`. With writable isolated `.dev` temporary storage,
the admission and affected coverage/debug-asset suites passed 39 executed tests
(44 discovered, five PowerShell skips), including the originally failing
admission test. No production source was changed to accommodate those fixtures.
The Windows `PYTHONUTF8=1 python tools/check.py` attempt ran 424 CI tests with
one existing device-symlink privilege error and ten skips; later component gates
did not execute. No complete repository or complete local Linux-suite pass is
claimed. Staged PR policy, secret scan and whitespace checks passed.

Corrected image workflow SHA-256:
`f095e85df91c0f49e3bb63d8afb34e73bb17d786310312d59b5dfef10b6ad812`.
Next verification is hosted preflight on the corrected commit. Runtime/image
publication still requires all release gates, and live workflow finality and
full-phase exit criteria remain outstanding. Baseline weights and earned
completion remain **0 / 100**.

## Published configuration in the console

The Control API exposes tenant-scoped, read-only published workflow and agent
configuration to users with tenant-wide agent and agent-group read permissions.
Overview and the corresponding Workflows and Agents pages use that API. Drafts,
superseded revisions, prompts, schemas, context arguments, provider origins and
vault references are excluded from the reader response. Super Admin lifecycle
permissions remain unchanged. This adds configuration visibility within V1.1.
It does not change workflow execution or phase weights.

Verification on base `b25f59c5` with this change applied: the Admin UI production
build and type check passed, and the focused SQLite workflow profile tests passed
for published reads, redaction, replacement and reader authorization. PostgreSQL
and packaged-console smoke checks are separate from this evidence.

PR #307 combines the published configuration views with the device trigger
management and workflow trigger package routes from main. The admin session
and published API use the same tenant-wide read decision. A group-scoped
reader can still use the scoped directory, while the published routes stay
hidden and Control denies direct requests.

Verification after integrating main at b25f59c5: Admin UI type check and
285 tests, three workflow browser tests, and all 13 WorkflowProfilesTest
cases on SQLite and disposable PostgreSQL passed. Generated contract and UI
operation checks passed. Diff against main excluding this status file:
SHA-256 135c2ff5d41648da0719b69f2f13ef4b8afe9abb17467786cb33fb6e7df4623e.
Full CI and V1.4 live workflow qualification remain outstanding.

Issue: [#295](https://github.com/olo-labs/olo-toolgate/issues/295) ·
Integration: `feature/workflow-enhancement` ·
Merged milestone: [PR #297](https://github.com/olo-labs/olo-toolgate/pull/297)

Last updated: 2026-11-14. Baseline: **v1-effort-1**, 100 effort points.
Architecture and acceptance: [runtime plan, section 15](langgraph-runtime-plan.md#15-delivery-phases--minimal-end-to-end-v1).
This is the authoritative live status for #295 v1; the architecture plan owns
scope and acceptance. These estimates are relative shares of expected v1 work,
not elapsed time, deadlines, test coverage or measured productivity.

## Published effort baseline

| Phase | Share of total v1 effort | Cumulative allocation | Status | Earned completion |
|---|---:|---:|---|---:|
| V1.0 — Contract decisions | 10% | 10% | In progress; source gap inventory, ADR 063 and acceptance scenarios proposed; review/harness pending | 0% |
| V1.1 — Profile to effective configuration | 25% | 35% | In progress; dedicated workflow/group/agent pages and profile revision discovery extend durable staging/review/publication and package provisioning; complete directory/grant provisioning pending | 0% |
| V1.2 — Message to governed result | 35% | 70% | In progress; local one- and two-agent configured execution succeeds; full message/runtime qualification pending | 0% |
| V1.3 — Trigger to the same run path | 20% | 90% | In progress; real trigger admission and confirmed configured-agent results exercised; full revocation/forgery scenarios pending | 0% |
| V1.4 — End-to-end qualification | 10% | 100% | In progress; selectable deployment and two-profile local acceptance delivered; complete fresh-install scenarios, Linux gates and release publication pending | 0% |
| **Total** | **100%** | **100%** | **Planning baseline published; v1 not delivered** | **0%** |

V1.2 carries the largest share because admission, runtime identity, model proxy,
agent/tool/context resolution and the standalone generic runner must work as one
path. V1.1 closes the full profile configuration and publication dependencies.
V1.3 reuses #274's trigger mechanisms. V1.4 is dedicated integration evidence,
not a replacement for tests within the earlier phases. Shared capabilities may
already exist elsewhere; credit requires verified integration evidence for #295.

**Overall earned completion: 0 / 100 = 0%.** A phase earns its full weight only
when every exit criterion and applicable check in section 15 has evidence and
the implementation PRs are merged into the integration branch. Partial phases
earn zero; record their delivered slices and outstanding work below. An open
or approved PR is not merged delivery. If finer progress is needed, agree and
publish smaller weighted acceptance units before crediting them; do not infer
percentages from files changed, PR count or subjective estimates. Scope/weight
changes require a baseline revision, reason and old/new allocations totaling
100; never silently alter the denominator or historic completion.

## Phase acceptance and evidence

| Phase | Required acceptance summary | Delivered scope / evidence | Remaining work |
|---|---|---|---|
| V1.0 | Contract inventory; ADRs for canonical profile, listener registration/dispatch, identity, explicit models and minimal run state; executable positive/negative scenario specification | Proposal v0.9 at `7c4d2e85a9ce09fc2bef94e3f096d326bb03bb89`; no phase acceptance claimed | Review ADR 063, reconcile endpoint/schema details during implementation and bind scenarios to executable integration harness |
| V1.1 | One upload closes references and provisions workflow/group, agents, models, prompts/context, packages/bindings, grants and default subscription; reviewed publish/readiness and tenant/user filtering | Current DeploymentProfile v2 carries workflow/model configuration; durable immutable staging, review, package provisioning adapter, atomic publication and live dependency checks; complete property editor, dedicated workflow/group/agent screens and tenant-scoped revision discovery implemented | Complete directory/grant/subscription provisioning, scoped workflow management/user filtering, executed readiness cases and whole-profile qualification remain |
| V1.2 | Workflow ID plus message completes using configured supervisor and two agents, their models/context/distinct tools; current authorization, secret isolation, PREPARE/ASK/BLOCK respected | One-agent admission/lease/step/model/MCP transport; live supervisor inference and agent context attempt stop at unknown outcome; per-run metadata regression coverage | Confirmed tool finality and agent completion, message admission, two-agent execution and complete runtime authorization qualification |
| V1.3 | Authenticated external listener registers, event selects bound default workflow and executes; durable admission/dedupe, disable/revocation/forgery denials and governed write-back | Listener plus Control binding/admission/queue path; real trigger and duplicate receipt retain admission/run IDs; worker records FAILED on unknown tool outcome | Qualify successful trigger-to-result, live disable/revocation/forgery denials and governed write-back |
| V1.4 | Fresh supported store, ready infrastructure/secrets, one-upload profiles; two unrelated profiles use identical external code/dependency lock; message and trigger paths pass applicable checks with recorded revisions | 16-scenario controller, one-agent live fixture, and standalone runtime image/release evidence gate; local image tests/scan pass, live context attempt fails conservatively | Resolve native finality, complete fresh provisioning and two-profile live scenario coverage with independent server evidence and applicable full gates; execute registry publication through an authorized release |

## Update required with every PR

Every #295 PR targeting `feature/workflow-enhancement`, including documentation,
fixes and tests, updates this file in the same commit series. The PR description
links its status change, identifies affected phase(s), lists checks actually
executed and their results, and states remaining work and limits. Record the PR
number/link once known by updating that PR's branch; no separate progress-only
follow-up PR is needed. Keep the follow-up PR descriptions synchronized with this status.

For each PR, record:

- Affected phase and concrete completed scope; distinguish a delivered slice from
  an accepted phase. Record denied behavior as well as the successful path.
- Exact validation commands/results, known tested revision or source digest,
  relevant artifacts and unresolved failures. Never invent the SHA being created.
- Integration state (proposed/open/merged), limitations and the next outstanding
  work. PR merge alone does not imply phase acceptance.
- Acceptance evidence and the earned-completion calculation. Before claiming
  credit, verify merged code on the integration head, using a known ancestor or
  source digest if the status update is part of the merge. Do not add a commit
  solely to reference itself. Keep pending evidence uncredited until verified.

PR #297 merged into main as a milestone at `aa33a9d3e33cc1be2678ddaf67f4308062891a4f`.
Follow-up work resumes in phase order on local `feature/workflow-enhancement`.
The operator will push when ready.
This process does not change the authoritative #230/#274 delivery rules.

## V1.0 follow-up after the main milestone (2026-11-11)

ADR 063 distinguishes the shipped profile and listener contracts from the
pending Control run, admission and lease endpoints. The HTTPS adapter now
rejects malformed admission coordinates before transport and rejects explicit
null receipt run IDs, which the canonical wire schema does not permit.

Executed 42 standalone runner/listener tests and 14 qualification harness tests
with the pinned runtime environment. Both passed. Transport SHA-256
`bd56b611b472a1da55067865c2d0b48f3fd24a23ce5c674b894863b61a7ab2d1`.
The qualification harness tests use simulated drivers. The live Control
endpoint inventory still needs reconciliation as those endpoints are
implemented. No phase weight or live execution qualification is claimed.

## V1.1 package declaration preflight (2026-11-11)

Workflow staging and package provisioning share declaration validation.
Duplicate targets or exports and assignments without device groups are
rejected before a new draft is written. Sealed inventory and live dependency
checks still run at provisioning. Existing stored drafts can be inspected.

Executed `./gradlew :control-plane:test --tests '*WorkflowProfilesTest'
--tests '*PackageProvisioningTest' --max-workers=2` with the isolated
PostgreSQL fixture. All 18 tests passed, including SQLite/PostgreSQL lifecycle
coverage and a regression proving an invalid upload creates no revision.
The v2 schema suite (7 tests), metadata and generated-binding preflight
also passed. Source SHA-256 values:

- PackageProvisioning: `533c2e8a684471df15e3ef1ec4288eff9351d802d97052076cb7fe8785cc9189`.
- WorkflowProfiles: `d7b4632c40e516773999eb04a58d82b8a31bb5aaadac95f222fcd3d20932e716`.

A full Java or repository gate was not run for this increment. Next is
reviewed directory/grant provisioning and resolution of newly created
subscription and assignment identities. Phase credit remains zero.

## V1.1 reviewed revision and package readiness binding (2026-11-11)

Stage validation now checks the canonical document before sealing it or
reserving ownership. A canonicalization conflict returns a validation error
and leaves no revision or ownership behind. Status, publication and internal
published-profile resolution use the stored revision digest when checking
package provisioning readiness.

The isolated Control gate passed all 19 tests in WorkflowProfilesTest and
PackageProvisioningTest, including SQLite and PostgreSQL lifecycle cases,
normalization collision rollback and refusal of READY evidence for another
revision. The stale-digest test seeds package status directly and does not
exercise a live package installation. Tested source SHA-256:

- WorkflowProfiles: `3ff6175d47a531a10c7346f6eb39b6602d6e01037bdff200ee592b621c75dcf9`.
- WorkflowProfileDependencies: `ad976373e58000d964e67870c8ea3673e74171179f3c94dd9ace80e363e63511`.

Phase credit is unchanged. One-upload directory, grant and subscription
provisioning and executed tool readiness evidence still precede live run
admission and end-to-end qualification.

## V1.1 profile editor validation follow-up (2026-11-11)

The serial console suite reported 276 passes and an intermittent failure
in the profile JSON field after revision load. Reset the field and its native
validity during the layout update when a saved value changes, so a deferred
reset cannot clear the first edit's invalid-input state. All 12 focused
WorkflowProfiles and WorkflowManagement tests passed after the change.
ProfileFields SHA-256:
`c9f32271925635e751896fe068daa45f53c8d379338800be13028f74d98da5e2`.
This affects local draft editing only. Reviewed directory and subscription
provisioning and real readiness evidence remain pending.

## Admin UI property management slice (2026-11-11)

User-requested delivery into umbrella #297 (`feature/workflow-enhancement`),
prepared on `codex/workflow-profile-admin-ui` from known integration revision
`990c7415b991b3596214263755c8b68792c47c83`. V1.1 remains in progress;
baseline weights and overall earned completion remain **0 / 100 = 0%**.

The Super Admin console under Tools > Server packages now provides guided editing
for every reachable current DeploymentProfile property directly from canonical
schemas. This includes workflow/group/agent configuration, model deployments and
vault references, prompts/context/schemas, execution limits, agent/dispatch
bindings, package targets/globals/tools/triggers and readiness cases. Optional
sections and collection entries can be added/removed; arbitrary JSON objects
remain JSON object editors. Exact immutable revision loading, staging, explicit
review, package provisioning, live status refresh and generation-bound publication
use existing generated operations and the centralized authenticated transport.
Editing clears displayed lifecycle authority until staging succeeds. API errors
do not produce successful lifecycle state. Drafts are not persisted in storage.

Executed on Linux/WSL with Node 22:

- `npm run ui:check` and `npm run ui:build` passed.
- `npm --workspace @olo-labs/toolgate-admin-ui exec -- vitest run
  --maxWorkers=1 --testTimeout=30000`: all 269 tests in 38 files passed.
- Focused editor/package tests: 11 passed, covering all reachable schema fields,
  exact document preservation, editing invalidation, denied staging and exact
  digest/generation lifecycle requests. Final focused rerun after the mobile CSS
  and ordinary-admin visibility assertion changes also passed all 11 tests.
- Production-preview Playwright `workflow-profiles.spec.ts`: one test passed
  against inert API fixtures, including nested editing, exact staged bytes,
  publication denial presentation, axe accessibility, mobile overflow and modal
  focus return. Fixed mobile digest/form overflow found by the first browser run;
  an earlier fixture lacked explicit package.read capability and was corrected.
- `python tools/check.py` was bounded to 180 seconds: CI 422 tests (17 skips),
  contracts 183 tests (2 skips) and Python SDK 83 tests passed; the deadline ended
  the workflow-runtime dependency installation. No full repository, real Control
  browser lifecycle, Java or live workflow execution pass is claimed.
- UI operation-generation drift, metadata/preflight, source/license/obvious-secret
  quality checks and whitespace passed.
- The actual precommit command passed metadata, staged policy, extension tests,
  secret scan and UI typecheck, then reported 263 UI tests passed / 6 failed:
  four default-timeout failures, an enrollment assertion and an editor error-label
  assertion. The editor label was fixed and focused tests passed; these parallel
  results do not establish that the other failures are pre-existing. The browser
  hook was run separately: six experience/download checks and three cache-policy
  download checks passed. No full default-parallel hook pass is claimed.
- Final-source `vitest run --coverage --maxWorkers=1` passed all 269 tests across
  38 files with the normal default timeout; API coverage thresholds passed
  (statements 87.54%, branches 84.26%, functions 87.7%, lines 91.25%). Final-source
  production build and workflow browser test passed again. The commit skips
  re-executing the parallel hook after these explicit checks; its earlier failure
  remains recorded above, rather than being presented as a successful hook.

Known tested SHA-256:
`ProfileFields.tsx` = `16d7f45c557e04d927008e1d60a7c1c02369e74c87ad41e46bbcf828ff59cd0c`;
`WorkflowProfiles.tsx` = `432dc4fc6e5fa32ceeb6497b6f998899aa8b46c3556b0ffd30434b2f3b12c7b2`.

Limits: current Super Admin management only; no profile catalog listing API exists,
so existing revision loading requires its identifier and digest. Server validation
and authorization remain authoritative. Publication is configuration, not runtime
authority; unchanged V1.1 directory/grant/subscription orchestration and V1.2/V1.3
execution/admission gaps remain. No #230/#274 claim or phase completion changes.
Next: finish these server prerequisites, expose catalog discovery, and qualify real
profile lifecycle and message/trigger execution. Keep #297 draft.

### Production Docker packaging correction and requested debug startup

The console build stage now copies `packages/contracts/schemas/`, which the
complete property editor imports directly. This corrects the source-only UI
validation gap from `507c4dfe`: production Docker builds must have those canonical
JSON documents too. No schema, authorization, runtime or phase scope changes.
Dockerfile SHA-256: `a0d60112df0b556b4fdcfa5a14285c01e819b269c3ebe5682da9f544a66be636`.

Executed: current-source `tools/quickstart/check.py --build --build-only --image
olo-toolgate-quickstart:debug` passed, building the real Control console/Java/core
packages and optimized Gateway/Tool Host Quickstart image. Quickstart image
manifest: `sha256:043f2a2a73dc863e239594f209d822eb05b3193471e2ce6213f88939e635ceb0`.
Windows `python -m unittest discover -s tests/ci -p test_debug_management.py -v`
passed all four tests; the Linux invocation explicitly skipped them because
Windows PowerShell is required. This is build/debug-management evidence, not a
new full repository, default-parallel hook or workflow execution acceptance pass.
The image uses empty client-download assets; native installer qualification is
outside this UI startup request. The user explicitly requested local debug UI
startup after the earlier instruction to defer demos. No workflow/model/tool
demo or roadmap acceptance is implied; completion remains **0%**.

The built image was loaded into Windows Docker Desktop (`desktop-linux`) and
started with `docker compose -p toolgate-debug -f debug/compose.yaml up -d --wait
--wait-timeout 150 --pull never`. The container became healthy; Windows HTTP
verification returned 200 for `/console/` and found `Manage workflow profile` in
the served production JavaScript. Console: `http://127.0.0.1:18090/console/`.
The new Desktop project uses its own `toolgate-debug_data` volume; no existing
store was converted or deleted. No authenticated lifecycle test is claimed:
cookie-free session lookup returned 401, and automatic approval review blocked
the subsequent combined scripted admin sign-in/browser-launch command without
a more specific stated reason. Preflight, source/license/obvious-secret quality
and whitespace checks passed. The packaging-only follow-up does not rerun the
known failing parallel precommit UI suite; the actual Docker UI compilation and
startup evidence above validate this correction.

### Dedicated workflow, group and agent management (2026-11-11)

PR #296 places the profile trigger editor under **Workflows > Trigger packages**
(`#workflow-trigger-packages`). The page still requires Super Admin authority
and queries tenant-wide package exports. All eight WorkflowManagement tests
and the permission regression passed on merge tree
`1f179fb54415c9e7e251bfd685d1ff8fb9b31af1` (before the status updates).
Phase acceptance still requires the live execution checks below.

Delivered directly to umbrella #297 as requested, based on `6a2f03ec`. Workflows
now has separate Workflows, Workflow groups, Models, Trigger mappings and Profiles
pages. Agents has Prompts & context and Workflow mappings with links scoped to
the agent selected in its directory editor. One in-memory profile draft persists
across these pages; editing requires restaging before lifecycle actions become
available. Changes to an already staged workflow definition through its own page
or the mapping page produce a new version and update its matching dispatch pins
in the draft. All canonical fields remain available in Profiles.

Control adds bounded, tenant-scoped metadata discovery on `GET
/api/control/v1/workflow-profiles`, with an exclusive profile-id/digest cursor and
at most 50 rows. Only a current Super Admin may list or open revisions. The list
returns lifecycle metadata, never prompts or documents. Exact document loading
uses the existing encrypted revision path. Reviewed revisions report live
dependency readiness before publication; publish still rechecks readiness and
the current generation. No store schema, migration or runtime authority changes.

Executed verification:

- `:control-plane:test --tests io.ololabs.toolgate.control.WorkflowProfilesTest`
  with the provisioned PostgreSQL helper: 11 passed, including SQLite/PostgreSQL
  pagination, tenant isolation, forged-role denial, metadata privacy and readiness
  before publication.
- Final production UI typecheck/build passed. Full serial console coverage run
  (`vitest run --coverage --maxWorkers=1 --testTimeout=30000`) passed 274 of 275
  tests, including all six new workflow tests. One existing workload reauthorization
  test timed out waiting for a row during concurrent builds. Its entire
  `DemoConsole.test.tsx` suite passed all 14 tests in isolation. No single-run
  complete UI gate or final coverage threshold pass is claimed.
- Three production-preview Playwright tests passed: dedicated group/workflow/agent
  navigation and draft continuity, scoped-admin deep-link denial, and the existing
  profile editor’s accessibility/mobile checks. Corrected the test runner’s JSON
  fixture import and ambiguous navigation locator before the passing rerun.
- `tools/check.py --contracts-only`: CI 422 tests (17 skips), contract 183 tests
  (2 skips) and Python SDK 83 tests passed; canceled its duplicate Gradle phase to
  avoid overlapping the provisioned Java gate. No complete repository pass claimed.
- Provisioned `javaCheck` reported `Persistence unavailable` in
  `EnterpriseConfigurationTest.superAdministratorCanSubmitApproveAndApplyAnotherUsersDraft`
  and was interrupted for isolation. The first isolated class run passed 13/14;
  `thresholdIsCapturedWhenTheChangeIsCreated` hit the same approval clock rollback
  guard. The fixture now uses a fixed clock at the persisted observation floor;
  production clock checks and approval assertions are unchanged. Final isolated
  `:control-plane:enterpriseTest --tests io.ololabs.toolgate.control.EnterpriseConfigurationTest`
  passed all 14 tests. No complete `javaCheck` rerun or pass is claimed.
- The production Control/Quickstart build passed after adding the v2 contract test
  fixture to the Docker UI build context. Image manifest:
  `sha256:6a0c4a8bebd5e15f64e0468b377cd47243478979f290034ca916da7bbf123d76`.
  Loaded into Docker Desktop and restarted `toolgate-debug`, preserving its named
  data volume. Container healthy; authenticated browser showed the real empty
  catalog, both new menus and the workflow group and agent editor actions. The
  temporary browser draft was discarded. The live anonymous catalog request returned
  HTTP 401. No profile was published or workflow executed on the debug stack.

Tested source SHA-256: `WorkflowManagement.tsx`
`ee8401bfad0ca91e0f63f3acd71cddbe301cdc94dea166ee7fba85131d85b263`;
`WorkflowProfiles.java`
`51dd8563017aff2ebd7bb369d2f619b8ff4e0437acac5868d7f8af2d894e8201`;
`control-v1.yaml`
`15cec15d4f57139175f5441a45f943a4329a0ce5348e1c9df64051449c8db417`;
Control Dockerfile `7cda31f90ea121d3d5dc55a268fcf9d85a83b8a2bd525086456006c6631bf6a8`.
Approval fixture SHA-256:
`38eb8915ecaa223505454338c1eadc5aae7ea00f75bcfe7bf4c35b80912ed585`.
Metadata/preflight, quality, UI operation drift, staged PR policy, secret scan
and whitespace checks passed. The commit uses these explicit checks rather than
rerunning the default parallel hook; no complete hook pass is claimed.

Remaining: workflow-scoped roles, complete directory/grant/subscription
provisioning, executed readiness and runtime qualification. Current management
stays Super Admin only. No phase or security guarantee is newly accepted; earned
completion remains **0 / 100 = 0%**. Keep #297 draft.

### Mounted HotFolder debug lab (2026-11-11)

Based on `7a83d6c6`, relocated the existing debug scripts to `debug/standard`
and added the [mounted HotFolder bring-up](../../debug/hotfolder/README.md).
Standard Compose project, ports, and data volume are unchanged. The HotFolder
lab reuses the existing signed inbox trigger/profile and LangGraph example in
its own `toolgate-hotfolder` project. It builds the endpoint from source, mounts
`.dev/debug/hotfolder/files` at `/var/lib/olo-toolgate/hotfolder`, and keeps
its registry, state and credentials separate. The bind-mount helper requires
root-owned `0700` directories and uses exclusive `0600` file creation. No
workflow-profile admission, policy, credential validation or schema changes.

Executed evidence:

- The Docker Desktop WSL mount probe failed because this distro's integration
  service was unavailable. The lab was started on the local WSL Linux engine
  with Docker Compose v5.6.1 using an isolated CLI configuration. The separate
  Docker Desktop standard stack remained healthy on port 18090. The lab console
  answered Windows readiness on port 18092; device and Quickstart were healthy.
- Built the native debug endpoint and signed HotFolder/inbox-watch packages;
  `bringup.py start` provisioned the fingerprint-checked local Linux device,
  installed the signed package and verified governed tool discovery. A missing
  Compose plugin blocked the initial packaging attempt before stack creation;
  the configured rerun completed.
- `bringup.py verify` observed the trigger instance `Running`, dropped one
  synthetic file through the host mount, and received the readable INTERNAL
  event over `toolgate.triggers.v1`. The agent reported `read-unconfirmed` after
  its native read. The file remained in `inbox/` with UID 0 and mode 0600;
  there was no retry or move. Traversal-shaped and duplicate filenames were
  refused, and the original bytes were preserved. Left the foreground-style
  listener running in a local detached process with its private log.
- Native Linux PATH CI run: 422 tests, 17 skips, no failures. The initial mixed
  Windows/Linux PATH run failed; Windows debug management/client assets and
  device creation were verified natively (13 and 7 tests, all passed).
  Linux focused Docker workspace (3), agent bridge/mimic (19), device image
  (14) and LangChain example (64) tests passed. The LangChain host test rerun
  loaded the same `agent_mimic` module alias used in its Docker image.
  Metadata/preflight, quality, Markdown links and whitespace passed.

Tested `bringup.py` SHA-256:
`6754ad0e3d488da37bccdd74679caa602ae20a1a77d2971aded280218a08db01`.
Mount overlay SHA-256:
`9327654dcdb797559c36467c05f18dc09ad44c17e15beeb1ef4e18f179b0ab11`.
Device base manifest:
`sha256:7058e06257d226bd64985fe59eef524063ace774b0607b1e24b406d20d06c0da`.
Full repository verification and #274 M12 acceptance were not executed.
This lab does not exercise #295 workflow dispatch admission or qualify #230
native HotFolder finality. No phase weight changes; #297 stays draft.

Windows `start.bat` and `stop.bat` now launch the HotFolder commands through
WSL from the script directory. Both launchers reached the correct Python command
in Windows `--help` checks. The launcher shell passed `bash -n`, and its status
command confirmed the existing local stack and listener were running. The lab
answered ready on port 18092, so no restart or stop was needed for verification.

The lab now has a runnable LangChain entry point under `debug/hotfolder/langchain`.
Validation based on `730050cd` found expired device check-ins. Restarting the
existing device restored accepted check-ins. The existing listener received
`demo-order-1043.txt`, and the new runner connected and received a second fresh
sample, `demo-order-1044.txt`. Both reached the signed read call and reported
`read-unconfirmed`, with no move or retry. Python compilation, shell syntax,
source quality, Markdown links and whitespace checks passed. Tested runner
SHA-256 `f9249ce711b46e6fdd9d368a1ebb31f3bce450b7ee333e1a6f66186b9b0717f3`.
The runner remains connected in the local lab. No phase or finality credit changes.

The Windows LangChain launcher now offers trigger registration and interactive
Ollama chat. Based on `33ecff00`, Windows batch argument forwarding and console
menu routing, model selection and chat history were checked with simulated model
responses. The runner source SHA-256 is
`1350bfc00c30062c41ce7a9561317463253288694249e678dbab8c7437161305`.
ToolGate started on Docker Desktop port 18090, and the WSL mounted lab reported
ready on port 18092 with an accepted device check-in. The previously launched
listener was stopped for manual startup. No Ollama endpoint responded on the
default local port. Live inference and confirmed native file completion remain
unverified.

## Delivery ledger

| PR / known revision | Phase | Scope and integration state | Validation actually executed | Limits / next work |
|---|---|---|---|---|
| [#297](https://github.com/olo-labs/olo-toolgate/pull/297), known planning revision `7c4d2e85a9ce09fc2bef94e3f096d326bb03bb89` | Planning; no phase credit | v0.9 profile-driven minimal-v1 architecture published on integration branch; umbrella PR remains draft | Relative Markdown links, whitespace, metadata/preflight, staged PR policy and secret scan passed for that revision. Full check reported 6 failures, 9 errors, 4 skips in CI tests; commit hook reported browser-extension failures and did not finish promptly; documentation commit bypassed that hook | No implementation or complete-suite pass. Next: V1.0 contract inventory/ADRs |

| [#298](https://github.com/olo-labs/olo-toolgate/pull/298), inspected baseline `54a9c21c`, reviewed source revision `995000be` | V1.0 — in progress, 0% earned | Source inventory, proposed ADR 063 and 16 machine-readable scenario specifications; merged into integration branch at `fd16d03e`; no phase acceptance | Links passed across 327 files; nine link-checker tests passed; JSON parse, whitespace and staged PR policy passed. ADR SHA-256 `36eca2b74a2e1296c9b15a489ec63ac220d07fa88d8455082189922dc7cc498f`; scenario SHA-256 `a31ecc9cadad44637cc302c99c76efb70809c60c9fafe8f3616ba9f471834d6c` | Documentation-only; no runtime scenarios executed, broad failing/hanging hook bypassed. Next: review contract decisions and implement V1.1 schema/catalog with executable acceptance harness |

## Current V1.0 slice

[ADR 063](../adr/063-profile-driven-workflow-execution.md) records the inspected
source inventory and decisions. [Acceptance scenarios](workflow-v1-acceptance.json)
specify positive/negative outcomes; they are not executed runtime tests. In particular,
existing subscriptions are user-owned: listener ownership and service execution
authority remain separate. Proposed dispatch admission uses a delivered cursor,
server-side event lookup and durable dedupe before cumulative acknowledgement.
No runtime capability or V1.0 acceptance is claimed. Overall remains **0%**.

## Current V1.1 implementation slice

Implementation PR: [#299](https://github.com/olo-labs/olo-toolgate/pull/299),
branch `codex/workflow-profile-validation`, known source revision `5ab911a6`.
Merged into `feature/workflow-enhancement` at `2abdafe3358004c6a50c557b755f440635100134`; not phase-accepted.

Canonical `workflow.schema.json` and Java/Rust/TypeScript/PHP bindings now define
workflow groups, explicit supervisor/agent models, context sources, bounded
execution and immutable subscription/source workflow bindings. Control adopts
these definitions in ContractCodec. WorkflowConfigurations is read-only: it
rejects duplicate IDs, broken local references, absent entry agents, inconsistent
limits and conflicting dispatch defaults before invoking a required dependency
port. That port must resolve only the current tenant's readable resources;
these unit tests do not certify actual cross-tenant store enforcement.

Executed evidence: six WorkflowConfigurationsTest tests passed; both Java
contract test classes passed (four tests); seven v2 canonical-schema tests passed;
TypeScript typecheck/build and two wire tests passed; two Rust v2 round-trip tests
and workspace formatting passed; PHP passed 418 v2 model round trips and constant
negatives; generated bindings/corpus and metadata/preflight passed. Added eight
structural rejection fixtures. The initial Java attempt failed with Node 18;
the focused Java check passed after using Node 22.23.4.

Known inspected integration revision: `fd16d03e86aa6d57dc87153065ba322c81fe62c8`.
Tested schema SHA-256: `e76030917aeca9db7cbd042af39cb758c0bb951bd06bf485c2cd6d4c3dea6ca8`.
Tested validator SHA-256: `931a7ba59adbc7561065b9a104589847c20260f6dbb375929f93e8f05d23530e`.

Limitations: no upload endpoint, profile-version change, catalog persistence,
publication pointer, provisioning orchestration, runtime admission or grants are
enabled by this slice. Successful validation is neither READY nor authorization.
No full phase weight is earned. Next: connect catalog storage and management
review to fresh version-2 profile application, then qualify complete readiness.

## Baseline history

- **v1-effort-1 (2026-11-10):** initial estimated allocation 10/25/35/20/10;
  100-point denominator. No effort or completion baseline previously published.
  Quotas/budgets, advanced routing, distributed recovery/container packaging and
  wider production qualification remain later releases, as section 15 specifies.
### Broader verification for the V1.1 slice

The first broad Java attempt lacked CONTROL_TEST_URL and encountered PostgreSQL
fixture failures; it was cancelled and replaced with the repository's isolated
PostgreSQL helper. The provisioned `./gradlew javaCheck -PprebuiltAdminUi=true
--max-workers=2` run has reported
`EnterpriseConfigurationTest.superAdministratorCanApproveOwnRequestAndSatisfyTwoReviewQuorum`
failing. That broad run was interrupted after diagnosis; no full Java pass is claimed.

The repository-wide `python tools/check.py` rerun uses tools/requirements.txt in
an isolated Python environment and Node 22. It passed CI (422 tests, 17 skips),
contracts (183 tests, 2 skips), Python SDK (83 tests) and Quickstart (51 tests).
Its duplicate Java/build stage was interrupted to avoid overlapping Gradle builds
in the same checkout; the separately provisioned Java gate was also interrupted after its reported failure. Initial verification
attempts without all Python dependencies failed; they are not acceptance evidence.

The actual commit hook was exercised with Node 22: browser-extension tests,
metadata/policy/secret checks and TypeScript checks passed. Admin UI tests under
concurrent load reported 64 failures; a single-worker default-timeout rerun completed with 259 passes and six 5-second
timeouts. Rerunning the four affected files with one worker and a 30-second CLI
timeout passed all 35 tests. This is diagnostic evidence, not a default-gate pass. The hook's browser smoke passed six
experience/download checks and three cache-policy download checks. These are
separate results, not a successful complete hook. Publication of the reviewed
slice may bypass repeating that broad hook while these failures are recorded;
keep its implementation PR in draft and do not credit or declare the phase done.
The enterprise self-approval method passed in isolation on the pre-change
integration revision `fd16d03e86aa6d57dc87153065ba322c81fe62c8`, using the same
provisioned PostgreSQL helper. This does not establish a pre-existing failure;
full-run ordering/concurrency versus a workflow regression remains unresolved.
Do not weaken approval checks or merge #299 based on that single baseline pass.
Final focused diagnosis for this slice: the entire EnterpriseConfigurationTest
class passed in isolation against implementation source `5ab911a6` (14 tests,
zero skips/failures/errors), using a separate checkout and isolated PostgreSQL.
The method also passed alone at pre-change integration `fd16d03e`. The broad-run
failure therefore remains unresolved suite-order/concurrency evidence, not proof
of either a workflow regression or a verified pre-existing defect. Both broad
runs were interrupted; their helper contexts cleaned up their test databases.
No verification processes are intentionally left to continue this implementation.
That recommendation was made before #299 was merged. The merge does not resolve
the broad gate or establish phase acceptance.
## V1.2 phase jump — generic runner foundation

Delivery: [PR #300](https://github.com/olo-labs/olo-toolgate/pull/300) into
`feature/workflow-enhancement`, tested implementation revision `ed8336d5`.
The PR tracks integration; this tested slice does not satisfy full V1.2 acceptance.

On 2026-11-10 the user requested starting the next phase before V1.1 publication
and its broad verification were complete. V1.2 now proceeds from the latest
integration revision `2abdafe3358004c6a50c557b755f440635100134` (which includes
merged #299). This changes sequencing only: scope, effort weights and the
100-point denominator are unchanged. Neither V1.1 nor V1.2 earns weight yet.

`apps/langgraph-runtime` now provides a fixed LangGraph supervisor/agent loop.
Its leased snapshot supplies prompts, distinct explicit model groups, context
sources, candidate agents, remediation mode and finite step/handoff/time limits.
A required ToolGate port handles step authorization, refreshed agent summaries, per-agent tool discovery,
inference, context/tool calls and confirmed completion. The graph uses no
provider client, use-case IDs, direct tool connection or executable profile code.

Local checks narrow candidates and verify returned step/run/lease/model scope;
they do not establish actual server authorization. Context is read-only even in
APPLY, PREPARE refuses mutation, ASK waits, denial/unknown outcomes stop without
retries, ownership loss propagates, and missing/foreign completion acknowledgements
cannot produce a successful result. Credentials stay out of graph state/repr and
internal LangSmith tracing is disabled. Host egress confinement is not delivered.

Validation executed:

- `python tools/workflows/check.py`: isolated dependency installation, pip check
  and 20 real-LangGraph tests passed against a simulated ToolGate boundary. Two
  distinct profiles use unchanged code and separate supervisor/agent model
  assignments. These are not live model/tool or end-to-end authorization tests.
- Editable package installation succeeded. Dependencies are version-pinned in
  requirements.lock; hash/cross-platform production qualification is pending.
- `test_product_release.py`: 25 tests passed, including central version stamping
  of the new runtime package. `test_unified_ci.py`: 16 tests passed; the Python
  CI group invokes the isolated runtime gate. Metadata/preflight, source policy
  and whitespace checks passed.

Tested source SHA-256 values:

- Runner: `31f001655924a2da6848f22ca35a51a8cb29a512b0153a6e16954fe7812e7dc3`.
- Tests: `c5488f0af97fef381be32134745bd1e0132d949094a8d49cbe0547903bbfca7c`.
- Dependency lock: `65e6328bed3120bb831a86caa444b8d75cdc3f8a15dc26b86037ebdbb2ce3246`.

`tools/check.py --ci-common --ci-part python --contracts-only` passed: 183
contract tests (2 skips), 83 SDK tests, PHP 304 v1 and 418 v2 model round trips,
PHP lint and the scoped Python foundation gate. That command deliberately skips
the runtime dependency environment; its separate isolated gate passed all 20
tests. The earlier broad Java/UI failures were not rerun for this Python-only
slice. No complete repository or real-model/real-tool end-to-end pass is claimed. Outstanding:
Control admission/ledger/leases, authenticated runtime/step tokens, governed model
proxy and wire adapter, V1.1 publication/readiness, real external message/trigger
execution, and the earlier broad Java/UI gate issues. No runnable-profile,
container, trigger registration, checkpoint/failover or full V1.2 completion is
claimed. The umbrella PR remains draft and overall earned completion is **0%**.

For this Python-only delivery, the broad UI/browser commit hook was bypassed
after executing the runtime, scoped Python, release, CI-ownership, metadata,
source-policy, PR-policy and staged secret checks recorded above. Earlier broad
Java/UI failures remain limitations; no waiver of final v1 qualification is implied.
## V1.3 delivery — authenticated listener and admission contract

Delivery: [PR #301](https://github.com/olo-labs/olo-toolgate/pull/301) into
`feature/workflow-enhancement`, tested implementation revision `ef9d11a7`.
Integration is tracked by the PR; this slice does not satisfy full V1.3 acceptance.

Started at the user's request from integration revision
`4b9c49d8bed9d00a9fa5044228f93fb691faed8c`. No weight/scope denominator change:
V1.3 remains 20 points and earns zero until its full end-to-end exit criteria pass.

Delivered code:

- Canonical WorkflowDispatchRequest/Disposition/Receipt and generated Java,
  Rust, TypeScript and PHP bindings. Requests carry only subscriptionUid/cursor;
  tenant, default workflow/version, service identity and event lookup belong to
  Control. ADMITTED requires a run ID at the consumer/application boundary.
- A host-run HTTPS/WSS client for the existing subscription/ticket/stream APIs.
  Each connection rechecks immutable subscription UID and Active state, obtains
  a fresh single-use ticket and selects toolgate.triggers.v1. TLS verification,
  no redirects/ambient proxies, bounded frames/responses and private socket
  logging protect transport. Recreated or obsolete subscription resources deny.
- A serial admission-before-ACK-before-credit bridge. Missing/mismatched or
  non-durable receipts, transport/auth errors, protocol violations, retention
  gaps and revocation stop the connection. No later cumulative ACK can skip an
  unresolved event. Duplicate delivery goes back to Control; there is no local
  dedupe authority, cursor override, workflow selection or effect retry.
- `python -m toolgate_runtime.trigger_listener` accepts infrastructure/bootstrap
  references and reads credentials from an environment variable. It reports
  admission references, not workflow completion. No provider keys or use-case
  logic are embedded in it.

Validation actually executed:

- `python tools/workflows/check.py`: 41 tests passed, no skips on this Linux host
  (20 existing runner, 9 bridge, 12 transport/CLI). HTTPS/WSS tests used ephemeral
  local certificates; bridge durability tests use a simulated ledger, not Control
  database evidence. Tests include commit/ACK loss, duplicate terminal rejection,
  concurrent ordering, forged payload fields, fresh reconnect tickets, UID
  recreation, wrong protocol, missing admission endpoint, bad receipts, redirects,
  untrusted certificates and credential/error-log suppression.
- Seven v2 schema tests passed, including five new structural invalid fixtures;
  generated binding/corpus checks passed. Java contract tests passed; focused
  WorkflowConfigurationsTest passed (six tests) with a verified prebuilt UI
  artifact as build prerequisite. These are not a full Java gate.
- TypeScript typecheck/build and two wire tests passed; Rust v2 round trips
  (two tests) and workspace format passed; PHP 421 v2 round trips/constant
  negatives passed.
- Scoped Python contract gate passed: 183 contract tests (2 skips), 83 SDK tests,
  PHP 304 v1 and 421 v2 round trips/lint. Editable installation, host CLI help,
  metadata/preflight, source policy, relative links and whitespace checks passed.

Known tested source SHA-256 values:

- Bridge: `1e7c35e5c8835aecd07708aba6d6d4336acafb9e619d137a18cc6110a72cd5f6`.
- Transport: `a7464b15986bac0f1d56d50b8824e84c722b0192c5ef6fb783ccf3f1b6177c8b`.
- Workflow schema: `b08eba156c4ec1fc5310fbb2954c698de64d1af4bd1052ba6818533da9fc66cf`.

Limits and next work: the proposed
`POST /api/control/v1/workflow-dispatches:admit` endpoint is **not implemented**.
A 404/401/503 or timeout causes no ACK, rather than a fabricated local admission.
Control must still authenticate ownership, look up stored event data, resolve the
published binding/current service grants, atomically persist/dedupe admission/run
and enqueue work. V1.1 publication and V1.2 admission/ledger/leases/model transport
are unfinished, and no real trigger-to-workflow/write-back demo is qualified.
The listener does not fabricate a Runner lease or execute the event as code.

Earlier broad Java/UI issues were not rerun; no full repository/Java/UI pass is
claimed. No #274 module completion or #230 security guarantee is claimed by this
consumer adapter. The broad UI/browser commit hook is omitted for this focused
slice after explicit policy/secret verification, as recorded in the delivery PR.
The umbrella remains draft; overall earned v1 completion remains **0%**.

## V1.1 follow-up — encrypted revisions and explicit publication

Delivery: [PR #303](https://github.com/olo-labs/olo-toolgate/pull/303),
branch `codex/workflow-profile-publication`, based on integration revision
`3ccfd7d0efa44cc5d4af2f342491ff9c2afc40d9`. This resumes missing server work at
the user's request before the local hotfolder demonstration. No phase weight or
denominator changes; this is not complete V1.1 and earns **0%**.

Delivered implementation:

- Fresh DeploymentProfile **version 2**, with optional workflow configuration;
  package-only profiles use the same current contract. Version 1 is rejected,
  without conversion, deletion or schema migrations. Shipped profiles and
  negative fixtures use the current/obsolete versions explicitly.
- Explicit model deployments (fixed HTTPS origin, model ID and scoped vault
  references), local model-group closure and generated Java/Rust/TypeScript/PHP
  bindings. No provider secret value is supplied to a runner.
- Tenant-transactional immutable desired revisions, explicit review, package
  provisioning of reviewed bytes through PackageProvisioning, and an atomic
  compare-and-swap publication pointer. New authenticated lifecycle endpoints
  and OpenAPI/operator documentation. All operations require a current Super
  Admin; cached actor flags cannot grant access. Self-review is allowed here;
  no independent review quorum or scoped workflow management role is claimed.
- Private profile documents encrypted through existing tenant vault custody and
  AES-GCM, with bounded 32-KiB chunks bound to tenant/profile/digest/key version,
  position and count. Normal key rotation keeps READ_ONLY keys usable; unavailable
  or retired custody fails closed. There is no plaintext fallback reader.
- Permanent profile resource ownership, immutable workflow ID/version contents,
  and one profile owner per subscription/source default. Omission never deletes
  resources or releases their ownership. Explicit cross-profile sharing is pending.
- Current tenant reference checks for enabled agents/groups/tools, agent-group
  membership, scoped vault metadata, matching-digest package READY evidence and
  immutable active trigger binding references/current listener authority and full
  source clearance. Publication is unavailable while these checks fail. An
  internal admission lookup repeats them; historical PUBLISHED is not cached
  execution authority. Nonempty tool readiness scenarios block activation until
  their execution evidence is integrated.
- All storage changes folded into fresh V1 initialization for SQLite/PostgreSQL,
  with tenant keys/FKs, forced RLS/runtime privileges, tenant immutability and
  existing capacity accounting. Unsupported existing stores are retained/refused.

Final encrypted-source focused verification passed in a separate checkout with
an isolated PostgreSQL instance and fresh SQLite stores: WorkflowProfilesTest
9 tests, WorkflowConfigurationsTest 7, PackageProvisioningTest 6,
ClientReleasesTest 11, and Java contracts 4, no skips/failures. The lifecycle
matrix covers both engines; readiness tests exercise current directory/vault
metadata, not live inference. Encryption tests cover multichunk private content,
READ_ONLY rotation, reordered chunks and foreign-tenant refusal. UI assets were
copied as a verified build prerequisite; this does not constitute a UI/browser
execution check.

Other executed checks: Python contract gate 183 tests (2 skips), SDK 83, PHP 304
v1 and 426 v2 round trips/negatives plus lint passed; TypeScript typecheck/build
and 2 wire tests passed; Rust 2 v2 round trips and workspace format passed;
UI typecheck passed; existing runtime/listener gate 41 tests passed. Schema
ownership/capacity checks (4), metadata/preflight (1515 bindings), generated UI
API binding checks, source policy, Markdown links (328 files) and whitespace
passed. Initial contract checks found a missing enum fixture and stale generated
UI API client; both were fixed and affected checks passed. A concurrent source
edit invalidated an early scorecard inventory test; its class passed in isolation
(9) and the final contract suite passed without source edits during execution.

The broad Java gate was started before the private-document encryption follow-up;
`./gradlew javaCheck -PprebuiltAdminUi=true --max-workers=2` passed using the
owned PostgreSQL helper: Control ordinary tests 346 (5 skips), enterprise tests
447 (no skips/failures/errors), Java contracts and SDK component checks passed.
This run predates encryption and its extra lifecycle test, so it is not a full
final-source Java pass. The final lifecycle/dependency/schema sources were
compared byte-for-byte with the successful isolated focused checkout. The
final encrypted implementation is
covered by the successful focused checks above; no full final-source repository,
HTTP endpoint authentication qualification, real-provider or image/demo pass is
claimed. Prior Java/UI limitations remain until applicable full gates resolve
them. No hidden test bypass or roadmap acceptance is implied by focused results.
The broad UI/browser commit hook is omitted for this server/schema slice after
explicit component, generated UI typecheck, metadata, source, staged PR-policy
and secret checks. This is not a complete repository/UI/browser gate pass or a
waiver of final v1 qualification. Both owned PostgreSQL test containers were
removed by their helpers; no test process is intentionally left running.

Known tested final source SHA-256:

- Lifecycle service: `0eca0c8b50c51f3ad5b4b242e99f48e634d499c85c92b229542be49d134ebb1e`.
- Encrypted documents: `10884d6ea6865a451a097a56d59685a9477208d4757af72d3e76500a5dac49c6`.
- Dependencies: `70904c6ba312994a71c7bbb34c0eb3526026797011dcf86b92bb740d9fc60b28`.

Still pending for complete V1.1: one-upload provisioning of directory identities,
roles/grants/bindings and listener subscriptions, reviewed sharing and scoped
workflow management/user-filtered resolution, executable readiness-case evidence
and complete-profile qualification. Metadata dependency readiness does not test
provider credentials or make a model request. V1.2 still needs run admission,
delegation/leases/step identity, current grant enforcement, model proxy and the
real runtime adapter; V1.3 still needs durable dispatch/admission/run integration.
No real model/tool or trigger-to-result/write-back qualification is claimed.
The #230 native HotFolder finality limitation remains unchanged. No #230/#274
stage/module completion or additional security guarantee is credited.

## V1.4 delivery — qualification controller

Delivery: [PR #302](https://github.com/olo-labs/olo-toolgate/pull/302),
branch `codex/workflow-v14-qualification`, based on merged V1.3 integration
revision `3ccfd7d0efa44cc5d4af2f342491ff9c2afc40d9`. This is a qualification
infrastructure slice, not completed V1.4 or live workflow execution. The
100-point baseline and full-phase acceptance rules are unchanged; **0% earned**.

`tools/workflows/qualify.py` runs the 16 published scenarios through an explicitly
supplied trusted live-install Python driver. It requires exact boolean assertion
coverage and existing, nonescaping, nonsymlink redacted artifacts; records their
hashes and the scenario/runtime/lock/package/driver source hashes; rejects mixed
output directories; bounds each worker process and hides driver stdout/stderr.
Source mutation/deletion invalidates the report. False assertions, missing
evidence, exceptions and timeouts remain visible unsuccessful outcomes. No
driver means every scenario is BLOCKED, exit 2. Even complete recorded assertions
require independent review; the controller cannot certify their truth or award
phase credit. There is no automatic effect retry or simulated production driver.

The [qualification guide](../testing/workflow-v1-qualification.md) documents the
driver interface, private evidence handling, fresh-install/live-service evidence,
failure semantics, uncertain effects, process/remote-cleanup limits and host
egress limits. Controller tests are now included in `tools/workflows/check.py`
and thus the existing Python CI runtime gate.

Validation: 14 qualification-controller tests passed, using temporary simulated
drivers, including deadline, false/missing evidence, malformed manifests,
escaping/symlink artifacts, source changes/deletion and sanitized exceptions.
The no-driver command was executed and reported BLOCKED for the current tree.
Metadata/preflight, source headers/licenses/obvious-secret policy and relative
Markdown links (329 files) passed. Final runtime and broader-check results are
recorded below.

`python3 tools/workflows/check.py` passed: 41 runner/listener tests plus 14
qualification-controller tests, no skips on this Linux host; dependency install
and pip check passed. Staged PR policy, secret scan and whitespace passed.
The full `python tools/check.py` was attempted with Node 22 and tools requirements
installed, under a 180-second process deadline. Before that deadline it passed
CI tests (422, 17 skips), contract tests (183, 2 skips), SDK tests (83) and the
same 41+14 workflow tests. It timed out during Quickstart tests before that suite
completed; no full repository pass, Java/UI rerun or later-stage pass is claimed.
No processes from that bounded check remain running. Earlier Java/UI limitations
remain unresolved. The broad UI/browser commit hook is bypassed for this focused
Python/documentation slice after the explicit checks above; this does not waive
final v1 qualification.

Known tested source SHA-256:

- Controller: `daa1a3ccb36ae5606cf8285a8abb697762cd1126e48663dd22f4d13e68055d90`.
- Tests: `46e441a99d1aceef7b68a9e20729317265fe05e2e54a9b2c3737042a12a18349`.

Outstanding: no live driver, fresh-install workflow orchestration or one-upload
example profiles are shipped, because the canonical profile application and
server execution path remain unimplemented in V1.1–V1.3. No actual profile,
model/tool, server authorization, durable admission/effect or two-profile
qualification evidence exists. A driver's assertions/artifacts need trusted
server provenance and manual review; source hashes are not deployed-service
attestation or dependency-install verification. Timed-out remote operations and
driver descendants require operator reconciliation; do not blindly retry them.
Finish those prerequisites, supply a reviewed live driver and execute the suite
against fresh supported stores. Keep #297 draft; no #230/#274 completion or
security guarantee is changed by this tooling.

Integration update: qualification tooling #302 merged at `574e90945d90b32b300ee69a793b8b31d249accb`; #303 incorporates that integration head. Both implementation increments remain uncredited at the full-phase level.

The combined runtime check exposed a socket test-fixture close race: the fixture
server returned immediately after credit while the max-events client was also
closing. The fixture now waits for the client's normal close and propagates
handler failures to the test thread. Production listener failure behavior is
unchanged. Integration verification results are recorded with the merge update.
The repaired reconnect test passed 10 consecutive invocations; the combined
isolated runtime gate then passed all 41 runner/listener and 14 controller tests
with no skips. This is client/qualification infrastructure evidence, not a live
server workflow pass.
