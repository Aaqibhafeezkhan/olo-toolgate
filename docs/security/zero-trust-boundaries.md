<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution boundaries and threats

This is the adopted security model for issue #230. The
[revision-10 proposal](../plans/zero-trust-tool-execution.md) remains design history.
The [live claim inventory](zero-trust-execution-status.md#security-claim-inventory)
is authoritative for implementation and executed evidence. This model defines
what must be protected; it does not certify that every mitigation is delivered.

## Trusted computing base

Control's application, database and signing custody, the identity provider and
workload CA, and the execution host kernel/runtime are trusted computing base.
A compromised Control can issue arbitrary authority. A compromised kernel/runtime
invalidates isolation guarantees for its host.

The Tool Host supervisor, broker and enrolled endpoint enforce Control decisions;
they cannot create authority. Native workers do not gain containment merely from
using the broker. Qualified Python OCI workers have the documented
[rootless boundary](../operations/rootless-tool-host.md); separate broker OS/network
identity remains outstanding. Gateway routes requests and has no permit-signing
authority. Agents, MCP clients, worker code and upstream responses are untrusted.
Tenant reviewers have only their documented management authority; a Super Admin's
audited self-review exception does not supply runtime grants.

## Attacker matrix

A1–A9 retain the proposal's identifiers. E1–E7 refer to the existing
[Tool Host model](threat-models/tool-host.md). Every row states both the intended
boundary and the qualification or residual risk; no row implies whole-plan acceptance.

| Attacker | Capabilities | Boundary / available scope | Residual risk and outstanding controls |
|---|---|---|---|
| A1 — malicious or prompt-injected agent (E2) | Chooses tools and arguments; holds its own credential. | Current grants, independent scoped reviews and signed argument binding constrain admission. Caller identity labels cannot widen authority. | Misuse within granted scope and social engineering remain possible. Exact per-effect consent and sender-constrained run credentials are pending; stolen bearer credentials are not made harmless by admission binding. |
| A2 — malicious worker/package (E1) | Arbitrary code in its execution process; may request broker effects. | Qualified Python OCI workers lack raw network, host mounts and ambient broker credentials; signed profiles and OLO bound-write checks constrain supported broker routes. | Native pools are uncontained. Separate broker OS/network identity, all-route authorization and the full interface matrix remain outstanding. Approved routes/secrets can be misused; kernel exploits and covert channels remain residual risks. |
| A3 — compromised Gateway replica (E5) | Gateway workload certificate and Control token; can drop, delay or alter forwarded requests. | Cannot mint Control signatures; executor digest/audience checks and online nonce consumption reject modified or replayed permits. | Denial of service remains. The current OLO integration documents a HUMAN-context trust residual at Gateway; lack of a signing key alone does not prove authenticated end-user identity against a malicious Gateway. See the OLO model's section 5. |
| A4 — compromised supervisor/node (E6) | Root on one host, live credentials, watchdog and running workers. | Cannot mint permits or consume another host's permits. Control refuses new authority after disable/revocation under its current checks. Qualified fenced OLO targets independently consume at Control. | Root can disable supervision: lease/kill time bounds do not constrain it. Previously delivered credentials remain usable at unfenced targets until target expiry/rotation; Control revocation or host certificate expiry does not revoke those credentials. Host reports can be fabricated or omitted. Generalized fencing and independently target-enforced credentials remain pending. |
| A5 — compromised tenant administrator (E7) | Own-tenant management scope; may change profiles, grants or reviews. | Tenant isolation, runtime grants and scoped independent review remain required. Super Admin self-review is the explicit audited ADR 022 exception. | Misconfiguration and authorized misuse remain possible. An own-tenant Super Admin is not prevented from self-approving; external tamper-evident anchoring is planned, not a delivered detection guarantee. |
| A6 — compromised Control operator/database owner | Control host/database/signing access. | Outside the authorization guarantee's trusted boundary. No runtime authorization guarantee survives arbitrary Control compromise. | Can issue authority or rewrite unanchored records. Hash chains with independently retained signed anchors are planned; current durable evidence is not proof against the database owner. |
| A7 — compromised kernel/runtime/hypervisor | Full privileged control of the execution environment. | No isolation guarantee for invocations on that host. | Patching and separately qualified VM-backed tiers can reduce exposure; attestation is planned and cannot repair a compromised kernel. Other hosts are not thereby granted authority. |
| A8 — network attacker (E4) | Reaches service IPs; drops, delays, reorders or replays traffic; cannot break TLS. | Verified TLS/mTLS at supported boundaries, signed permits, single-use consumption and conservative ambiguous-send handling constrain replay and forgery. | Denial of service and partitions remain. Development loopback fixtures do not establish production network isolation or connectivity-class revocation SLOs. |
| A9 — malicious upstream (E3) | Controls its responses, redirects, DNS and valid certificates for its own names. | Approved route resolution, address/TLS checks, redirect refusal and broker header handling constrain the supported HTTP path. Request fingerprints bind the prepared request. | Upstream can lie about an effect or echo secrets in bodies. Header filtering cannot remove every transformed secret. Approved-destination data exfiltration and upstream-side authorization bugs remain risks; a successful response alone is not independently confirmed business outcome evidence. |

## Adopted claims and falsification criteria

C1–C8 are security requirements with the following scope. Availability and gaps
remain in the live inventory; these rows must not be read as all passing today.

| Claim | Required boundary | Example that falsifies the claim |
|---|---|---|
| C1 — no ambient target authority | Agent/worker cannot directly reach a brokered target with ambient credentials. Applies only to the qualified containment/profile scope. | Worker bypasses broker and reaches a brokered target. |
| C2 — verified sender-constrained run identity | Verified agent run, human if applicable and executor; proof of possession binds the run credential. | Exfiltrated credential works from another key holder. Current bearer identity does not establish this stronger claim. |
| C3a — current admission authorization | Control rechecks current grants, sessions, device/workload state and kills at admission consumption. | Revocation commits first but a new admission still succeeds. |
| C3b — current per-effect authorization | Fenced target consumption at Control is the ordering point. A revocation committed before consumption refuses the effect. For unfenced routes the proposal requires short-lived broker-enforced authority; generalized enforcement is pending. | Fenced target acts after refused consumption, or broker sends after its effect authorization expires. Admission alone does not establish per-effect authorization. |
| C3c — bounded unmediated execution | Healthy supervisor stops unmediated work on cancellation/deadline/lease loss within qualified bounds. | Healthy enforcement continues after its documented bound. This requirement does not constrain root controlling the supervisor. |
| C4 — bound admission intent | Exact arguments, package, executor and attempt are signed and checked once. | Swapped arguments/package/host still execute under the original permit. |
| C5 — contained execution | Worker can do only what its qualified profile permits, assuming intact kernel/runtime. | Worker reaches host files, another invocation or raw network beyond the profile. Native execution does not establish C5. |
| C6 — durable, honest and tamper-evident evidence | Durable pre-effect admission/attempt/effect intent; graded observations and business outcomes; detectable rewriting. | Missing pre-effect intent or undetectable record change. Current durability alone does not establish hash chains/external anchors. |
| C7 — revocation within declared limits | Bounds depend on healthy-host connectivity class, authorization ordering and target fencing. | Work continues past a measured class-specific bound. A development freshness test is not a production SLO. |
| C8 — operation/effect integrity | No concurrent valid generations; no redispatch or operation re-execution while an earlier effect is ambiguous, except an explicitly approved target-deduplicated retry protocol. | Concurrent valid authorizations, or retry of ambiguous work without the declared target guarantee. This is not an exactly-once promise for external systems. |

## Ordering, recovery and evidence rules

Admission authorizes launch, not every later sensitive effect. The qualified OLO
write path independently binds the exact HTTP request and rechecks authority at
the owner-bound SENDING gate and target claim consumption. Revocation after
successful consumption does not undo an already authorized effect; deadlines
still constrain the original authorization. Other route classes do not inherit
that guarantee automatically.

Logical operation identity, permit-attempt identity, stable effect identity,
authorization generation, dispatch owner and target claim ownership are separate.
Consumption is pending work, not proof of success. Target claim replay returns
status without permission to act again. An APPLYING claim cannot be recovered as
not-started. An absent spool entry, lost response or expired lease is not proof
that an external effect did not happen. Unknown outcomes stay fenced; automatic
resend requires an approved target deduplication guarantee and protocol.

Executor reports are reported observations; prepared/sent HTTP facts are broker
observations. Authenticated target outcomes provide target evidence for their
bound effect, not automatic operation-wide success. Full success requires every
attempt/effect to be resolved against a sealed manifest; generalized finalization
and user-facing evidence grades remain outstanding. Late evidence cannot restore
authority or erase contradictory history.

## Qualification and release use

Use the live claim inventory and source-bound reports, not a design-table case
name, to determine available guarantees. The first vertical-slice acceptance and
all seven full roadmap phases remain subject to their outstanding criteria.
The weighted delivery percentage is a planning metric, not a passing C1–C8 score.

Rootless OCI, actual target component crash/replica tests and the real
Control/Gateway development fixture establish different scopes. Complete C1–C8
release scorecards, full Control/Gateway partition/crash conformance, broker OS
separation and production connectivity-class SLOs are still required. See
[verification](../testing/README.md), [leases](../operations/execution-leases.md)
and [claim status](zero-trust-execution-status.md).
