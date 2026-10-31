<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-10-31**. Branch: `feature/zero-trust-execution-plan`.
Previous containment delivery: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Three scoped deliveries are implemented:
renewable leases/durable execution recovery, qualified Python OCI containment,
and conservative explicit business-key binding. Executed validation for the current
delivery is recorded below. **0 of 7 full roadmap phases have met every exit
criterion.** Phases 0–2 have
partial delivery; phases 3–6 have not started as generalized implementations.
No percentage is assigned because the phases differ substantially in scope.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Preserved revision-10 plan, claim inventory, scoped documentation and executable OCI conformance gate with revision/source/image evidence. | Adopt the full threat matrix across security docs; execute all C1–C8 conformance cases and publish a complete release scorecard. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; SQLite/PostgreSQL migrations and tests; conservative business-key operation binding. | Generalized requiresBusinessKey/namespace/dedupe-window policy, retention maintenance, safe unconsumed rebinding, separate attempt records and operation-level recovery/finalization; complete admission/rebinding and crash conformance. |
| 2 — Containment | Partial; Python worker slice delivered | Rootless Podman/crun workers; permit-bound image; isolated namespaces and UID ranges; read-only files; enforced resource limits; stdio broker; teardown/restart recovery; no native fallback; service unit and CI gate. | Separate the trusted broker's OS identity/network namespace as specified by the plan; broader deployment/interface conformance and deployment SLO evidence. Other languages and VM tiers require separate qualification. |
| 3 — Effect authorization and recovery | Not started | Existing OLO service permits remain a narrower baseline, not the generalized phase-3 implementation. | Per-effect ledger/generations/dispatch ownership, complete request binding, consent slots, target fencing SDK/shared claims, deduplication guarantees, reconciliation and operation finalization. |
| 4 — Evidence presentation and tamper evidence | Not started | Raw executor evidence is retained by phase 1. | Outcome grades in presentation/exports, tenant audit chains, signed external anchors and offline verification. |
| 5 — Identity hardening | Not started | Existing device certificates remain the baseline. | Sender-constrained agent-run credentials, narrowing delegation and continuously verified executor posture. |
| 6 — Policy differentiation | Not started | Existing approvals remain the baseline. | Broker OAuth/token exchange, trusted provenance, exact effect previews and stronger step-up approvals. |

### First vertical-slice acceptance

The complete first milestone in issue #230 is **not yet accepted**.

| Acceptance item | Status | Evidence / outstanding work |
|---|---|---|
| Exact consent rejects recipient changes and additional effects | Not implemented | Requires phase-3 request binding and consent slots. |
| Revocation refuses subsequent sensitive effects and stops healthy workers | Partial | Lease revocation tears down a real OCI SDK worker; generalized sensitive-effect authorization and measured deployment bounds remain outstanding. |
| Crashes around dispatch/target commit preserve evidence and prevent unsafe retries | Partial | Admission/result recovery and unknown outcomes are tested; target-commit/effect-ledger crash scenarios remain outstanding. |
| Partition, duplicates and concurrent target replicas exercise fencing/recovery | Partial | Host Control-partition and OCI recovery tests pass; cooperating-target fencing and replica races remain outstanding. |
| Operation finalization includes all attempts/effects and late evidence | Partial | Late executor evidence and explicit-key operations are retained across new request IDs; terminal key outcomes commit with invocation state. Operation-wide attempt/effect aggregation is not implemented. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | OCI report for `0813b71b` records six executed tests, source digest, image and architecture. Target guarantees and the complete release scorecard remain outstanding. |

**Next implementation target:** explicit operation/attempt records, conservative
rebinding and operation-level finalization required by the per-effect ledger; then
phase-3 authorization, consent and target fencing. The delivered key binding is
single-attempt and never silently rebinds a consumed or expired operation.

### Progress maintenance

For every future issue-230 implementation change, update these tables and the
claim inventory **in the same commit as the code**, then push them together to
the feature branch. Record delivered scope, executed validation and remaining
work. A scoped delivery or passing subset must not mark an entire phase complete.
Use a known tested revision or source digest; never invent the SHA of a commit
that has not yet been created. Repository instructions in [AGENTS.md](../../AGENTS.md)
carry this requirement into future work.

## Security claim inventory

| Claim | Implementation status | Evidence / remaining work |
|---|---|---|
| C1, no ambient target authority | Available for qualified Python OCI pools | Workers have no network socket authority, route, resolver, host mounts or ambient supervisor environment. Native pools do not establish this claim. |
| C2, verified sender-constrained run identity | Partial | Device certificate admission exists; sender-constrained agent runs remain planned. |
| C3a, current admission authorization | Implemented existing boundary | Signed-permit admission and current Control checks; Tool Host `bad_permits_are_refused_before_anything_runs`. |
| C3b, current per-effect authorization | Planned | Existing OLO service permits are narrower; the generalized effect ledger and target SDK remain required. |
| C3c, bounded unmediated execution | Partial | Native direct-worker supervision and OCI teardown on cancellation/deadline are tested. Enforced service cgroups contain worker processes; measured deployment revocation SLOs remain required. |
| C4, bound admission intent | Implemented existing boundary | Tool Host admission binds signature, audience, arguments and content digests; dispatch integration tests. |
| C5, contained execution | Available for qualified Python OCI pools | Executed rootless namespace, UID, filesystem, socket, syscall, resource, flooding, cancellation and recovery tests. Requires the documented delegated service; other runtime tiers do not inherit the claim. |
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence and host result retry; standalone SDK SQLite restart/late-completion recovery uses immediate writer transactions. Hash chains and external anchors remain required. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required. |
| C8, operation/effect integrity | Partial | Single-use admission and durable explicit operation keys exist; keyed unknown outcomes cannot reopen via legacy resend. Separate attempts, generalized key policy, effect slots, generations, target deduplication and operation finalization remain required. |

This is an implementation inventory, not a passing per-release C1–C8 scorecard.
No full-plan acceptance is claimed by this increment. A release scorecard must
retain executed evidence tied to that release's source revision and deployment.

## Lease and recovery verification

- `tools/security/sandbox.py`: mandatory real-rootless containment gate, with
  executed test names, image and source digest under `build/security/sandbox/`.
  See the [pool guide](../operations/rootless-tool-host.md).

- `cargo test -p olo-toolgate-tool-host --locked`: real-host dispatch,
  pre-start refusal, live-worker revocation and Control partition, durable result
  reopen/retry and admission-spool recovery.
- `./gradlew :control-plane:test --tests io.ololabs.toolgate.control.ExecutionLeaseTest`:
  certificate-derived device/fence binding, irreversible expiry, cancellation,
  durable watchdog settlement and late evidence. The full Java gate additionally
  requires the repository's isolated PostgreSQL fixture.
- `python -m unittest discover -s tests/contracts -p test_execution_lease_storage.py -v`:
  shipped SQLite constraints; set `TOOLGATE_D2_POSTGRES=host:port` to execute the
  same PostgreSQL constraint checks against a disposable server.

The [operator guide](../operations/execution-leases.md) states persistence,
rollout, healthy-host limitations and the remaining roadmap work.

## Explicit business-key delivery

[ADR 034](../adr/034-durable-business-operation-binding.md) and the
[caller/operator guide](../operations/business-operation-keys.md) define this
single-attempt slice. Native Gateway headers and modern MCP call metadata resolve
to a stable operation despite new request IDs or Gateway restarts. Control derives
the owner/namespace, binds exact inputs, reserves atomically with invocation/audit,
and refuses owner changes, mismatched inputs, expired retention and ambiguous
resend. The standalone Java SDK task store also reserves SQLite writer authority
before reading transitions, preventing a late old-runtime completion/poll race
from failing the restarted runtime with SQLITE_BUSY. Terminal key outcomes commit with invocation state on SQLite/PostgreSQL.

No separate-attempt or effect-level guarantee is claimed. Automatic rebind,
mandatory-key tool policy, argument keys, TENANT sharing, dedupe windows and
retention/tombstone jobs remain outstanding. Historical native transport keys are
not backfilled: the operator guide requires a coordinated cutover and settlement
of legacy workflows on their original invocation IDs. Existing unkeyed calls retain their
legacy behavior. This delivery does not satisfy the complete first milestone.

Executed validation for the final business-key source:

- `EnterpriseOperationTest`: **43 passed, no skips**, including a concurrent
  reservation/replay/unknown-outcome case through a disposable real PostgreSQL
  runtime-role fixture; the other cases use SQLite. Includes concurrent reservation, new request IDs/reopen,
  mismatched input/owner, retention expiry, current-policy refusal, ambiguous
  downstream retry, expired unconsumed binding, denied-admission rollback and
  canonical-operation cancellation ownership.
- Java SDK runtime: **100 passed**, including restarted-runtime recovery with a
  late old-runtime completion. The initial SQLite lock failure was reproduced
  and fixed with immediate writer acquisition; no debug logging was retained.
- `python tools/check.py --ci-common --ci-part rust`: **passed** workspace format,
  tests and Clippy. Gateway includes native-key stability and modern MCP metadata
  replay/malformed-input tests.
- Contract suite: **132 passed, 2 PostgreSQL-only skips (134 total)**; generated bindings
  verified with `python tools/contracts/generate.py --check`. The separate real
  PostgreSQL Java business-key check above executed without skips.

- Final `./gradlew --no-parallel --max-workers=2 javaCheck`: **passed** with a
  fresh isolated PostgreSQL fixture after the SQLite fix and corrected expiry
  fixture. Control's full enterprise suite: **162 passed, no skips**. Control's
  main suite: **259 passed, 5 configured skips (264 total)**. The required Java
  module checks completed successfully.

Source SHA-256: `8502890893a9f40c43417b0b8349fb78cdd820151988579a45b84d9f34282948` (344 files). Digest input is the sorted POSIX
relative path, one NUL byte, then file bytes for `.java`, `.rs`, `.json`, `.sql`
and `.py` files recursively under `apps/control-plane/src`, `apps/gateway/src`,
`apps/gateway/tests`, `packages/contracts/schemas`, `tools/contracts` and
`tool-sdk/java-sdk/runtime-mcp/src/main/java`. Documentation is excluded so this
record can be committed with the tested code without a self-referential hash.
