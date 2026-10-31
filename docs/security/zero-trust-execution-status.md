<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution implementation status

The [revision-10 plan](../plans/zero-trust-tool-execution.md) is the preserved
proposal linked by issue #230. A merged design or an authored conformance case
does not establish a passing security claim.

## Overall task progress

Updated: **2026-10-31**. Branch: `feature/zero-trust-execution-plan`.
Latest validated implementation: [`0813b71b`](https://github.com/olo-labs/olo-toolgate/commit/0813b71bbe2a0f9276a1b2e2260b25454a31d3d4).

**Overall: partially implemented.** Two scoped deliveries are implemented and
tested: renewable leases/durable execution recovery, and qualified Python OCI
containment. **0 of 7 full roadmap phases have met every exit criterion.** Phases 0–2 have
partial delivery; phases 3–6 have not started as generalized implementations.
No percentage is assigned because the phases differ substantially in scope.

| Plan phase | Status | Delivered | Remaining before full phase completion |
|---|---|---|---|
| 0 — Boundary and harness | Partial | Preserved revision-10 plan, claim inventory, scoped documentation and executable OCI conformance gate with revision/source/image evidence. | Adopt the full threat matrix across security docs; execute all C1–C8 conformance cases and publish a complete release scorecard. |
| 1 — Operations, leases and evidence | Partial | Durable device/fence-bound leases; heartbeat/watchdog; admission spool; result outbox; immutable late executor evidence; SQLite/PostgreSQL migrations and tests. | Business-key operation binding, explicit attempt records and operation-level recovery/finalization; complete admission/rebinding and crash conformance. |
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
| Operation finalization includes all attempts/effects and late evidence | Partial | Late executor evidence is retained; operation-wide attempt/effect aggregation is not implemented. |
| Revision-bound evidence distinguishes fenced/unfenced guarantees | Partial | OCI report for `0813b71b` records six executed tests, source digest, image and architecture. Target guarantees and the complete release scorecard remain outstanding. |

**Next implementation target:** business-key operation/attempt binding required by
the per-effect ledger, followed by phase-3 authorization, consent and target fencing.

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
| C6, honest tamper-evident record | Partial | Durable admission, immutable executor evidence and host result retry; hash chains and external anchors remain required. |
| C7, revocation | Partial | Renewable leases and current authority checks; full target and compromised-host distinctions remain required. |
| C8, operation/effect integrity | Partial | Single-use admission exists; operation keys, effect slots, generations, target deduplication and operation finalization remain required. |

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
