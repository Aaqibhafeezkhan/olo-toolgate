# Plan: Zero Trust Tool Execution (flagship differentiator)

> **Promise.** An AI agent can request a privileged action, but it cannot perform
> that action unless ToolGate independently authorizes it at execution time.
>
> Every agent action is **identity-verified, policy-authorized, isolated, auditable
> and revocable**, even at the execution boundary, within the trust boundary and
> limits stated in §1 and §2.

Status: proposal, revision 10, 2026-10-28. This document analyses gaps and plans
work; it changes no code. Findings come from reading the repository on branch
`codex/complete-coverage-baseline` at `5411656c`.

Revision 2 addresses review findings F1–F6: an explicit trust boundary (§1),
authorization per effect rather than per launch (§4.2), effect idempotency kept
separate from permit single use (§4.3), a concrete sandbox runtime design (§4.4),
a partition model for revocation (§4.5) and graded audit evidence (§4.6).

Revision 3 addresses the second review:
- **The ordering boundary for per-effect authorization.** Fenced targets order at the target's consumption; unfenced targets order at Control's decision, with the window stated (§4.2).
- **The compromised-host guarantee now matches §4.5** (§1.2, §1.3).
- **Per-effect identifiers** (`effectId`) and preserving `operationId` across retries (§4.3).
- **G16 narrowed** to build on the existing device-evidence model.
- **G11 now credits pull-mode dispatch,** and the revocation model accounts for it (§4.5).
- **Intent records moved** into the phases that consume them (§5).

Revision 4 addresses the third review:
- **G5 narrowed.** It now credits the existing invocation, replay and
  reconciliation machinery and names only the missing business-key integration
  and per-effect ledger (§3).
- **Safe downstream retry kept as an explicit decision,** with constraints and
  migration work (§4.3.6).
- **Re-authorization and rebinding need a positive durable record at Control;**
  a missing spool entry is no longer accepted as proof (§4.3.3, §4.3.5).
- **Execution authority separated from evidence submission,** so late reports
  can reconcile without restoring authority (§4.6.3, G18).
- **Provenance trusted only from ToolGate's own observations or an
  authenticated integration;** agent labels can only lower trust (§4.7).
- **C8 wording:** no concurrent valid authorization, and no redispatch while an
  earlier dispatch is ambiguous.

Revision 5 addresses the fourth review:
- **Authorization generations:** `effectId` stays stable, and each
  authorization gets its own generation and nonce, which fenced targets must
  validate (§4.3.4).
- **Single send:** a durable dispatch owner and a single-send `SENDING`
  transition; no automatic HTTP retries; uncertain sends are fenced; concurrency
  tests (§4.3.4).
- **Complete request binding:** canonical destination, path, query, content
  type, body bytes and profile revision, with an exact exclusion list.
- **Provenance is not consent:** high-risk effects need a consent binding to
  the complete action; when a value matches both sources, the more restrictive
  wins (§4.7).
- **Brokered-only rebinding disabled** until containment and the effect ledger
  exist (§4.3.3).

Revision 6 addresses the fifth review:
- **No assumed deduplication window.** Automatic resend needs an approved,
  documented target guarantee; existing packages without one fall back to
  status lookup or manual reconciliation (§4.3.6).
- **Consent reaches the outbound request,** through trusted broker field
  mappings or request-level consent from a plan phase (§4.7).
- **`CONSUMED` is pending, not an outcome.** Target outcome reports, an outcome
  deadline, idempotent consumption and crash tests (§4.3.4).

Revision 7 addresses the sixth review:
- **Effect slots** limit how many effects a consent permits; reservation is
  atomic (§4.7).
- **Fenced deduplication** needs a shared target-side claim store; a
  consumption replay returns status and never authorizes (§4.3.4).
- **Attempt outcomes are stored separately,** and operation status is derived
  from attempts and effects (§4.6.4).

Revision 8 addresses the seventh review:
- **Target claim takeover:** recovery uses an ownership generation; the owner
  must enter `APPLYING` before acting, and recovery reports "not applied" only
  after permanently fencing the owner (§4.3.4).
- **Conflicts judged by effect identity and target receipt,** so legitimate
  deduplicated retries merge.
- **`SUCCEEDED` needs a sealed effect manifest** (fixed plan, or a completed
  attempt), and `RUNNING` takes precedence (§4.6.4).

Revision 9 addresses the eighth review:
- **Success is judged over every effect from every attempt;** an unresolved
  older effect blocks it (§4.6.4).
- **Provisional and final statuses separated.** Final statuses are derived only
  once everything is resolved, so late evidence can only confirm or contradict
  them.

Revision 10 addresses the ninth review:
- **Settling silent attempts:** an `OUTCOME_UNKNOWN` attempt stops blocking
  once its ledger is closed and resolved and it can't hide effects outside the
  ledger (§4.6.4).
- **Caller status maps one to one;** running work is never shown as unknown.
- **Observations graded separately from the business outcome;** consumption
  and dispatch are never outcome evidence (§4.6.5).

---

## 1. Trust boundary and threat model (F1)

ToolGate can only guarantee what holds while its trusted computing base is intact.
This section says which components are trusted, what each attacker can do, what
ToolGate still enforces against them, and what is accepted as residual risk. It
extends `docs/security/threat-model.md` and `docs/security/threat-models/tool-host.md`
rather than replacing them; IDs E1–E7 match the Tool Host model.

### 1.1 Components and their trust level

| Component | Trust | Why |
|---|---|---|
| Control (application, signing keys, database) | **Trusted** (TCB) | Sole authority for grants, permits, kills, audit. Compromise is out of scope except where §1.2 limits it. |
| Identity provider, workload CA | **Trusted** (TCB) | Roots of human and workload identity. |
| Host kernel, container runtime, hypervisor | **Trusted** (TCB) | Isolation is built on them. ToolGate cannot defend against them. |
| Tool Host supervisor and broker | **Trusted to enforce, not to authorize** | Enforces Control's decisions; holds no authority of its own. Compromise is bounded (row E6). |
| Endpoint client and its OS | **Trusted to enforce** on an enrolled, approved device | Same bound as the supervisor. |
| Gateway | **Untrusted for authority** | Routes calls; holds no signing key; cannot authorize. |
| Agent / MCP client | **Untrusted** | May be prompt-injected or malicious. |
| Tool package code (worker) | **Untrusted** | May be malicious or compromised. |
| Upstream target systems | **Untrusted** | Outside ToolGate; may lie or misbehave. |
| Tenant administrators, reviewers | **Partly trusted** | Bounded by independent review and runtime grant rules. |

### 1.2 Attacker matrix

| Attacker | Capabilities assumed | ToolGate still guarantees | Accepted residual risk |
|---|---|---|---|
| **A1 Malicious or prompt-injected agent** (E2) | Picks any tool and arguments in its catalog; holds its own credential | No call outside its grants; ASK goes to an independent human; arguments bound to the permit; with sender-constrained credentials (§4.1), a stolen token is useless elsewhere | Misuse *within* granted scope; social engineering of approvers |
| **A2 Malicious tool package / worker** (E1) | Arbitrary code inside one sandbox for one invocation | Reaches only approved routes through the broker; no raw network, host files, other invocations or secrets it was not granted; each sensitive brokered effect re-authorized (§4.2); killed on revocation | Misuse of approved routes and of `CREDENTIALS_IN_SANDBOX` secrets; kernel or runtime zero-days; covert channels within a reuse pool |
| **A3 Compromised Gateway replica** (E5) | Gateway workload certificate and Control token | Cannot mint, alter or replay permits; cannot swap arguments or packages | Denial of service; dropping or delaying calls |
| **A4 Compromised Tool Host supervisor or node** (E6) | Root on one Tool Host: its workload certificate, live sandboxes, the watchdog, and every credential delivered to it | Cannot mint permits, consume permits addressed to another host, or obtain *new* Control-issued authority (permits, effect authorizations, secret deliveries, lease renewals) after the host is disabled, its certificate expires or the invocations are revoked. **Fenced targets** (§4.2) refuse its effects once Control revokes them, because the target consumes at Control. | Lease bounds do **not** apply: root can disable the watchdog and keep running. Credentials already delivered (brokered or in-sandbox) stay usable against **unfenced** targets until they expire or are rotated *at the target*; certificate expiry and Control revocation do not revoke them. It can fabricate or omit reports; its receipts are only *reported* evidence (§4.6). Mitigation: per-invocation, short-lived, target-enforced credentials (token exchange, downscoped tokens) and fenced targets for sensitive effects. |
| **A5 Compromised tenant administrator** (E7) | Admin rights in their own tenant | Cannot reach other tenants or the platform; scoped changes need independent review; Super Admin changes are audited | A Super Admin can self-approve within the tenant (ADR 022); audit and external anchoring (§4.6) make it detectable, not preventable |
| **A6 Compromised Control operator / database owner** | Direct database or host access to Control | Hash-chained audit with an external anchor detects rewriting after the anchor point (§4.6) | Can issue arbitrary permits while in control; ToolGate's authorization guarantees do not hold |
| **A7 Compromised kernel, container runtime or hypervisor** | Full control of the execution host | **None** for invocations on that host | Out of scope. Mitigation is deployment: VM-backed runtime class for sensitive pools, patched hosts, attestation as a policy input (§4.1) |
| **A8 Network attacker** (E4) | Can reach pod IPs, drop and replay packets; cannot break TLS | Permits, receipts and kill events are signed; replay is refused; ambiguous dispatch never re-executes a ledger tool | Denial of service; partitions handled by §4.5 |
| **A9 Malicious upstream** (E3) | Controls its responses, redirects, DNS and certificates for its own names | Redirects and DNS rebinding cannot escape approved routes; broker strips credentials from responses | Lying about whether an effect happened; echoing credentials in bodies |

### 1.3 Statement the product makes

> ToolGate guarantees that no governed effect is authorized without Control's
> current decision, **provided Control, the identity roots and the execution host
> kernel and runtime are intact**.
>
> - **Healthy enforcement host:** lease bounds hold. Revoked work stops within the limits of §4.5.
> - **Compromised enforcement host:** it gets no new authority from Control.
>   - **Fenced targets** refuse its effects once Control revokes them.
>   - **Unfenced targets** stay exposed to the credentials it already holds, until those credentials expire or are rotated at the target.
>
> Stronger bounds than leases require independently enforced target credentials
> or target-side fencing. ToolGate does not guarantee isolation from an attacker
> who controls the host kernel or a privileged runtime.

---

## 2. Testable claims (revised)

| # | Claim | Holds against | Falsified by |
|---|---|---|---|
| C1 | **No ambient authority.** The agent and the tool worker hold no credential that reaches a brokered target directly. | A1, A2 | The agent or worker reaches the target without passing the broker. |
| C2 | **Verified identity.** Every call names a verified agent run, the human it acts for (if any) and the executor; a stolen agent credential cannot be used from another key holder. | A1, A8 | A replayed or exfiltrated token works from another process or host. |
| C3a | **Authorization at admission.** Policy is evaluated against current grants, sessions, devices and kills when the permit is consumed. | A1, A3 | A revoked grant still admits a new invocation. |
| C3b | **Authorization at each mediated effect, with a defined ordering point.** For **fenced** targets, the target's consumption of a single-use effect permit at Control is the ordering point: a revocation that commits first makes consumption fail, so the target never applies the effect. For **unfenced** targets, Control's authorization decision is the ordering point: once a revocation commits, no new effect authorization is issued, and an authorization issued before it expires within its short lifetime (≤ 2 s) and is refused by the broker after that. | A1, A2 (fenced targets also A4) | A fenced target applies an effect whose consumption followed the revocation commit; Control issues an effect authorization after the revocation commit; the broker dispatches with an expired authorization. |
| C3c | **Bounded window for unmediated effects.** Effects the broker cannot see (local computation, `CREDENTIALS_IN_SANDBOX`, tunnels) continue after revocation for at most the lease period of §4.5. | A2 | An unmediated effect continues past the documented bound on a connected host. |
| C4 | **Bound intent.** The permit authorizes these exact arguments, package, host and attempt, once. | A1, A3 | Arguments, package or host change after authorization and the call still runs. |
| C5 | **Contained execution.** Worker code can do only what its approved profile allows. | A2 (not A7) | Worker code opens a raw socket, reads host files or another invocation's data. |
| C6 | **Honest, tamper-evident record.** Every admitted invocation has a durable intent record before any effect, and ends with graded observations and a graded business outcome (target-confirmed, broker-observed, human-reconciled, executor-reported or unknown; §4.6.5); changes to the record are detectable. | A1–A5, partly A6 | An admitted invocation with no intent record, or a record changed without detection. |
| C7 | **Revocable within stated limits.** Revocation meets the SLOs of §4.5 for the host's connectivity class. | A1, A2, A3 | Work continues past the SLO for its class. |
| C8 | **No duplicate effect from ToolGate retries.** A logical operation is never re-executed by ToolGate while its previous attempt's outcome is ambiguous, except under the declared, target-deduplicated retry rule of §4.3.6. An individual effect (`effectId`) never has two valid authorizations at once, and is never redispatched while an earlier dispatch of it is ambiguous (same exception). ToolGate does **not** claim exactly-once effects on external systems. | A8, crashes | Two valid authorizations exist for one `effectId`; ToolGate redispatches an effect or re-executes an operation whose earlier dispatch is ambiguous outside §4.3.6. |

---

## 3. Where ToolGate stands today

### Strong already

- **Control-signed, single-use permits** with `aud` = host, short window, `jti`
  cache and online nonce consumption (`apps/tool-host/src/permit.rs`, ADR 013).
- **Argument and digest binding** recomputed at the host (T-AD-3).
- **Re-checks at consumption** of grants, session epoch, device and workload.
- **Target-side fencing for OLO services:** the broker obtains a 15-second,
  purpose-bound, single-use service permit (`control.rs` `service_permit`,
  `broker.rs:444`). The *target service* consumes it at Control.
  `ServicePermits.consume` (`ServicePermits.java:59`) re-checks the invocation is
  `EXECUTING`, the execution nonce, the request fingerprint, the executor key and
  the authorization epoch (`requireFresh`), all in the transaction that consumes
  the nonce. This is the fenced pattern C3b relies on. Its write nonce is
  derived from (invocation, audience), so today one invocation gets one write per
  audience. §4.3 generalizes that to `effectId`.
- **Device evidence and posture conditions:** the evaluator picks the newest
  unexpired `ControlDeviceEvidence` for the device (`EnterpriseEvaluator.java:87`),
  caps the decision's validity at the evidence expiry, and requires evidence for
  posture, region and network-CIDR conditions (`EnterpriseEvaluator.java:187`).
- **Authenticated remote pull dispatch:** Tool Hosts that the Gateway cannot reach
  poll Control with their certificate for queued signed dispatches, then post
  admission and results (`apps/tool-host/README.md`, "Local or remote: pull
  mode").
- **Invocation state machine, replay and reconciliation (implemented)** in
  `EnterpriseOperations.java`:
  - **States:** governed invocations move through `PENDING_APPROVAL` →
    `QUEUED` → `RESERVED` → `EXECUTING` → `SUCCEEDED` / `FAILED` / `PARTIAL` /
    `OUTCOME_UNKNOWN`.
  - **Replay:** a resubmission with the same `requestId` returns the stored
    invocation. A different request digest is a conflict. Before answering, the
    invocation is renewed under the caller's *current* session and credential
    epochs and the decision is re-evaluated (`submitOnce`, `renewed`).
  - **Effect watchdog:** an `EXECUTING` invocation past its expiry becomes
    `OUTCOME_UNKNOWN`. Cancelling an executing call does the same, never
    `CANCELLED`.
  - **Declared reconciliation method per tool:**
    - `DOWNSTREAM_IDEMPOTENCY`: a replay of an `OUTCOME_UNKNOWN` call is requeued
      under the same invocation, arguments and `downstreamIdempotencyKey`, after
      re-checking the approval and epoch (`reconciled`, line 79).
    - `STATUS_LOOKUP` and `MANUAL`: stay unknown until reconciled.
  - **Human reconciliation:** propose and decide, with an evidence digest and
    independent review (`reconcile`, line 373).
  - **Reports:** idempotent per revision and digest; results kept so a repeated
    call returns them instead of re-running.
- **Effect-safety design beyond that** (leases, business keys, task states) is
  fully specified (ADR 016, `invocation-state-v2.md`), and the business-key table
  exists in the schema.
- **Default deny**, exact-digest ASK approval, route-based broker with SSRF and
  address checks, append-only audit by grant (`SELECT, INSERT` only), endpoint OCI
  sandbox (ADR 009).

### Gaps

Severity: **Critical** = the promise is false today for a realistic attack;
**High** = holds only with caveats a buyer will find; **Medium** = needed to win.

| Id | Gap | Claim | Severity | Evidence |
|---|---|---|---|---|
| G1 | **Tool Host has no sandbox.** The worker runs as the supervisor's user with a cleared environment and its own directory only. | C5, C1 | Critical | `runner.rs` (`env_clear`, `current_dir`); `sandbox.json` all `authored` |
| G2 | **Broker is bypassable.** Egress rules apply to the broker's own client only; workers can open sockets directly. | C1, C3b | Critical | `egress.rs`; no netns or firewall; `egress.json` `authored` |
| G3 | **Kill switch is specified, not built.** No `KillEvent`, break-glass or push code in `apps/`. | C7 | Critical | ADR 017, `kill-switch.md`; `kill-merge.json` `authored` |
| G4 | **No leases in code.** Leases, heartbeats (T6) and lease expiry (T11) are specified, but the Tool Host never renews a lease and Control has no `lease_expires_at` handling. The effect watchdog acts only at the invocation's overall expiry, so a running worker is bounded only by that. | C3c, C7 | Critical | No heartbeat or lease in `apps/tool-host/src`; watchdog uses `expiresAtUnixMs` (`EnterpriseOperations.java:60`) |
| G5 | **Two missing pieces on top of working invocation machinery.** Request-level replay, `OUTCOME_UNKNOWN` and reconciliation exist (§3). Missing: **(a) business-key integration.** Replay is keyed only by `requestId`, so a retry that arrives with a new `requestId` (new agent step, framework restart) is a new invocation. `control_business_keys` (namespaces, `IDEMPOTENCY_KEY_IN_USE`, rebinding rules, tombstones) exists in the schema but no code uses it. **(b) A per-effect ledger.** There is one `downstreamIdempotencyKey` per invocation and no effect-level record, so a tool with several writes cannot be deduplicated or reconciled per write. | C8 | High | `submitOnce` (`EnterpriseOperations.java:142`); `V1__initial_schema.sql`; no `business_key` in Control or Gateway code |
| G6 | **Broker re-authorizes only OLO service writes.** Third-party route calls are checked against the admission-time profile, not against current authority per effect. OLO writes allow one per audience per invocation. | C3b, C8 | High | `broker.rs` service-permit path only for OLO audiences; `ServicePermits.java` nonce keyed by (invocation, audience) |
| G7 | **Agent credentials are static bearer tokens**, not sender-constrained, run-scoped or attested. | C2 | High | `apps/gateway/src/auth.rs` |
| G8 | **Delegation chain is not first class**, and README and enterprise doc disagree on its status. | C2 | High | `enterprise-access-control.md` status note; `delegation.json` `authored` |
| G9 | **Audit is append-only, not tamper-evident**, and no intent record precedes dispatch. | C6 | High | `control_audit` schema |
| G10 | **No Tool Host spool or result outbox.** A report Control misses is logged, not retried. | C6 | High | Tool Host README "Not yet"; no spool in `apps/tool-host/src` |
| G11 | **No inbound mTLS dispatch listener and no kill push.** Remote execution already works through certificate-authenticated pull mode via Control. What's missing is the direct inbound listener for remote Gateways and a push channel for kills. Pull-mode hosts learn of kills only when they next contact Control. | C7 | Medium | Tool Host README "Not yet" and "Local or remote: pull mode" |
| G12 | **Broker lacks OAuth, token exchange, tunnels, streaming**, so many tools need credentials in the sandbox. | C1 | High | Tool Host README "Not yet" |
| G13 | **Security conformance authored, not executed.** | All | High | `tool-sdk/conformance/security/*.json` |
| G14 | **No provenance in policy.** Arguments derived from untrusted content are treated like user input. | C3a | Medium (differentiator) | No provenance concept |
| G15 | **Approver strength and context**: no step-up per approval, no effect preview. | C3a | Medium | `approvals.md` |
| G16 | **No runtime attestation, and posture is only checked at admission.** Device evidence (posture, region, verified network address, expiry) already exists and policy conditions use it. Missing: (a) evidence from runtime attestation (TPM, cloud instance identity, TEE quotes) and measured runtime facts (runtime digest, sandbox tier) for Tool Hosts; (b) re-evaluation while work runs, so evidence that expires or is withdrawn ends an active lease. | C2 | Medium | `EnterpriseEvaluator.java:87,187`; no attestation verifier; evidence not re-checked after consumption |
| G17 | **Messaging ahead of reality**: "Zero-trust execution" marked Available. | Credibility | Medium | `README.md` |
| G18 | **Late evidence is refused.** An executor report is accepted only while the invocation is `EXECUTING` and the fencing token is the current nonce. After `OUTCOME_UNKNOWN` (or cancellation), a genuine late report gets a 409, so automatic reconciliation from the executor's own evidence is impossible; only human reconciliation remains. | C6, C8 | High | `reportInSession` (`EnterpriseOperations.java:293`); `reportInvocation` nonce check (line 236) |

---

## 4. Design to close the gaps

### 4.1 Identity (G7, G8, G16) → C2

1. **Sender-constrained agent credentials:** mTLS client certificates (SPIFFE IDs
   for agent workloads) or DPoP. Short lifetime (minutes), issued by Control.
2. **Agent run identity:** a per-run identity derived from the agent identity and
   carried in the permit; revoking a run leaves the agent intact.
3. **Delegation chain in the permit:** the full actor chain (human → agent →
   sub-agent) in an `act`-style claim; each hop can only narrow scope; policy can
   match on any hop and on depth. Reconcile README and enterprise docs.
4. **Extend the existing device-evidence model; don't add a parallel one.**
   - **Attestation as an evidence source:** add attestation verifiers (TPM quote,
     cloud instance identity document, TEE quote) that write `ControlDeviceEvidence`
     records with a `source` and measured facts. Tool Hosts already enroll as
     devices, so the same record serves them.
   - **New posture values:** runtime facts become posture values, for example
     `runtime-digest:<sha256>`, `sandbox-tier:OCI_SANDBOX`, `kernel:<version>`.
     Existing `devicePosture` conditions can then require them unchanged.
   - **Continuous refresh:** the executor resubmits evidence on a schedule. Lease
     renewal (§4.2) re-runs the evidence check, and when the evidence has expired
     or no longer matches, the renewal returns `cancelRequested`. The evaluator
     already caps validity at the evidence expiry; the lease takes the same cap.

   Attestation is the only practical lever against A7.

### 4.2 Authorization at the effect boundary (F2; G4, G6) → C3a, C3b, C3c

A permit authorizes **admission**. It does not authorize everything the worker does
for the rest of its life. Authority after admission is held as a lease and checked
again at each effect ToolGate can mediate.

1. **Authorization lease.** On consumption (T5) Control grants a lease (default
   30 s, pool range 10–120 s). The supervisor renews every lease/3 (T6). Every
   renewal re-evaluates current grants, sessions, device and kill set against the
   tenant **authorization epoch**. If authority is gone, the renewal returns
   `cancelRequested` and the fencing token is invalidated.
2. **Fencing token on every execution path.** Secret delivery, broker calls,
   effect authorizations, service permits and heartbeats all present the fencing
   token. An invalidated token stops all of them at Control. **Evidence
   submission is not an execution path.** Reports still name the attempt by its
   fencing token, but they are accepted after invalidation without restoring any
   authority (§4.6.3).
3. **Broker-side effect authorization.** Each route gets an effect class
   (`READ`, `WRITE`, `DESTRUCTIVE`, `FINANCIAL`, …) at review. For any route above
   `READ`, the broker obtains a single-use **effect authorization** from Control
   before dispatching. It binds the invocation, fencing token, route id,
   `effectId` (§4.3), the digest of the exact outbound request, and the current
   epoch. Reads may use a cached epoch check no older than a configured bound
   (for example 2 s) to limit latency.
4. **Ordering point: who decides whether an effect is "before" or "after" a
   revocation.** There is always a gap between authorizing a request and the
   target applying it. A revocation can commit inside that gap. The plan names
   the ordering point for each kind of target and claims nothing stronger.

   | Target kind | Ordering point | Guarantee | Window after revocation commits |
   |---|---|---|---|
   | **Fenced** (cooperating target, like OLO services today) | The target's **consumption** of the effect permit at Control, in one transaction with the epoch, invocation-state and nonce checks (`ServicePermits.consume` pattern) | A revocation that commits before consumption makes consumption fail, so the target never applies the effect. One that commits after consumption orders after the effect; that effect is recorded as authorized. Holds against A4, because the target, not the host, asks Control. | None for effects that consume after the commit |
   | **Unfenced** (third-party API that cannot call Control) | Control's **authorization decision** | No effect authorization is issued after the revocation commits. One issued before it can still be dispatched until it expires. The broker refuses any authorization past its `exp` and checks it right before writing to the socket. | ≤ effect-authorization lifetime (target 2 s), plus network time to the target. Not enforced against A4. |
   | **Unmediated** (not brokered) | Lease renewal | See step 6 | ≤ one lease period, healthy host only |

   **How to make more targets fenced:**
   - **Fencing SDK for the target side:** a small library that verifies an effect
     permit and consumes it at Control, for services the customer owns
     (internal APIs, databases behind a small adapter).
   - **Short-lived, effect-scoped credentials:** where the target supports them
     (OAuth token exchange, downscoped tokens, presigned requests), mint them per
     effect so the credential itself expires within the window.
   - **Review flags every unfenced `DESTRUCTIVE` or `FINANCIAL` route** as a
     security delta.
5. **Authorization and deduplication in one call.** The effect authorization
   reserves the `effectId` (§4.3), so one Control transaction both authorizes the
   effect and stops it being authorized twice.
6. **Unmediated effects have a documented bound.** Pure computation, local file
   writes inside the sandbox, `CREDENTIALS_IN_SANDBOX` use and approved tunnels are
   invisible to the broker. For them the guarantee is: on a **healthy** enforcement
   host they stop within **one lease period plus kill time** after revocation
   (§4.5). Tools that need a stronger guarantee must use brokered routes, ideally
   to fenced targets; review shows the choice as a security delta.
7. **Long-running tools** may additionally declare checkpoints (for example
   "before commit") where the SDK calls the broker for an explicit authorization
   even without an outbound request.

The example in F2 (wait 20 s, then destroy) plays out in three ways:
- **Brokered, fenced target:** the target's consumption fails once the revocation has committed.
- **Brokered, unfenced target:** Control issues no authorization after the commit. One issued at most 2 s earlier may still go through.
- **Unmediated:** step 1 stops the worker at the next renewal, at most one lease period after revocation, on a healthy host.

The last two cases are stated as limitations.

### 4.3 Effect integrity, separate from permit single use (F3; G5) → C8

A single-use permit stops **that permit** being consumed twice. It does not stop
the workflow from asking again after a crash. Effect integrity needs its own
identifiers and states.

Three identifiers, at three levels:

| Id | Scope | Stable across | Purpose |
|---|---|---|---|
| `operationId` | One logical request by the caller (for example "pay invoice 42") | Agent retries, Gateway restarts, new attempts | Deduplicate the request |
| `attemptId` | One execution attempt of an operation, with its own permit and fencing token | Nothing; new per attempt | Single-use permit and lease |
| `effectId` | One individual external effect inside an operation (for example "charge card", "send receipt email") | Attempts of the same operation | Deduplicate each effect, including at the target |

1. **Where `operationId` comes from, and how it survives restarts and retries.**
   Gateway is stateless, so it cannot be what remembers an operation.
   - **Caller-supplied key (preferred):** the business key, an
     `Idempotency-Key` header, or an MCP request-level idempotency field. SDKs and
     agent-framework adapters derive it deterministically from (agent run id, step
     id, tool), so a retried step produces the same key.
   - **Durable binding at Control:** at admission (T1), Control derives
     `operationId` from the hash of (tenant, namespace identity, tool, key) and
     stores it in `control_business_keys` in the same transaction. A retry through
     any Gateway replica, after any restart, resolves to the same row.
   - **Returned to the caller:** Gateway returns `operationId` in the admission
     response and MCP result metadata, so a caller that lost its own key can
     resume with it (`tasks/get`).
   - **No key supplied:** Gateway generates an `operationId` for this request
     only, and a retry without a key is a **new operation**. That is acceptable
     for idempotent tools only. Tools whose effect safety is `NON_IDEMPOTENT` or
     `UNKNOWN` set `requiresBusinessKey`, and Gateway refuses calls to them without
     a key, so a duplicate can't come in silently.
2. **Business-key reservation** in `control_business_keys`, as already specified
   (ADR 016 §5): `NEW`, `EXISTING`, `IDEMPOTENCY_CONFLICT`,
   `IDEMPOTENCY_KEY_IN_USE`, `EXPIRED_KEY`. **Implement it** in front of the
   existing `requestId` replay rather than replacing it: the business key resolves
   to the operation's current invocation, and the existing replay, renewal and
   reconciliation logic then runs on that invocation unchanged.
3. **Rebinding rule: positive durable evidence only.** A new attempt for a ledger
   operation (`NON_IDEMPOTENT` or `UNKNOWN`) is allowed only when a durable record
   at Control shows the previous attempt could not have had an effect. Missing or
   unavailable evidence, such as a host spool with no dispatch entry, a spool that
   was lost, or a silent host, never counts. Accepted evidence:
   - **Never consumed:** Control's own record shows the permit was never consumed
     (expired in `RESERVED` or `DISPATCHED`). Consumption is the durable "may have
     started" transition.
   - **Consumed, but no effect path was ever opened.** *Disabled until Phase 2
     (containment) and Phase 3 (effect ledger) are both live on the host's pool;
     until then a consumed attempt always stays fenced.* Once enabled:
     - the tool is **brokered-only** (no `CREDENTIALS_IN_SANDBOX`, no tunnels, no
       other unmediated effect capability, recorded in the permit);
     - the permit records that it ran in the `OCI_SANDBOX` tier, so the sandbox
       really had no other way out;
     - Control's per-effect ledger shows that no authorization generation above
       `READ` for that attempt ever reached `SENDING` or `CONSUMED`, and every
       generation it issued is `RELEASED` (step 4).

     Then Control's own records prove that no mediated write left.
   - **Anything else stays fenced** in `OUTCOME_UNKNOWN` until reconciled. That
     includes a `notStarted` report for a tool with unmediated effect paths: it is
     *reported* evidence, recorded, but not enough to rebind.
4. **`effectId` for each individual effect.** One operation may legitimately do
   several writes, so a key derived from `operationId` alone would merge distinct
   effects into one.
   - **Definition:** `effectId = H("toolgate-effect-v1", tenantId, operationId,
     routeId, effectKey)`.
   - **Where `effectKey` comes from:**
     - The tool supplies it through the SDK for each logical write, for example
       `"charge"` or `"receipt:customer-7"`. It is **required** for routes with an
       effect class above `READ` in ledger tools.
     - For idempotent tools that supply none, the broker uses the ordinal of this
       write among this operation's writes to that route. That is safe only
       because the request-digest check below catches any reordering.
   - **Stable across attempts:** a new attempt of the same operation yields the
     same `effectId` for the same logical write.
   - **Bound request: what the digest covers.** The first authorization for an
     `effectId` records `requestDigest`, the SHA-256 of a canonical encoding of
     the request **as the broker will send it**:

     | Included | Canonical form |
     |---|---|
     | Service profile id and **profile revision digest**, route id | As loaded by the permit's digest |
     | Destination | Scheme, lowercased host, port, from the approved profile (never from the tool) |
     | Method | Uppercase |
     | Path | After variable substitution and percent-encoding, exactly as sent |
     | Query | Every parameter, sorted by name with duplicate names kept in sent order, names and values percent-encoded exactly as sent |
     | Content type | Normalized media type and parameters (`charset`, `boundary`) |
     | Body | Digest of the exact body bytes sent, after serialization and any compression |
     | Other headers | Every header the tool influenced (the route's allowlisted headers), name lowercased, value as sent |

     **Excluded, exhaustively:**
     - credential material the broker injects from the route's auth declaration:
       the `Authorization` and `Proxy-Authorization` values; header, query or
       cookie values the declaration names as credentials. Their **names and the
       auth declaration id** are still included, so swapping an unauthenticated
       call for an authenticated one changes the digest;
     - the downstream idempotency key, which is derived from `effectId` and
       therefore already bound;
     - transport headers fixed by the broker or computed from included fields:
       `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, broker
       `User-Agent`, `traceparent`/`tracestate`.

     Anything not on the exclusion list is included. A route that needs a
     varying non-credential field the tool doesn't control (for example a
     request timestamp the target requires) must declare it in the profile, and
     review shows it as a security delta.

   - **Authorization generations: `effectId` is stable, authorizations are
     not.** Each authorization of an `effectId` gets:
     - a `generation`, monotonic per `effectId`, starting at 1;
     - a unique random `authNonce`.

     The pair `(effectId, generation)` is what moves through the states below.
     Fenced targets consume `authNonce`, and consumption checks that it belongs
     to the **current** generation, in state `AUTHORIZED` or `SENDING`, owned by
     the presenting host. A released, expired or superseded generation can never
     be consumed again, even after the same effect is reopened under a new
     generation.

   - **Effect states at Control (per generation).**

     | State | Entered by | Meaning |
     |---|---|---|
     | `AUTHORIZED` | Effect-authorization transaction; records the **dispatch owner** (host id, broker instance id, request handler id) | Reserved for that owner; nothing sent yet |
     | `RELEASED` | The owner calls Control **before** the socket write (validation failed, tool cancelled, deadline) | Durably not sent; the generation is void forever |
     | `SENDING` | The owner calls Control immediately before the socket write: a compare-and-set from `AUTHORIZED` on (generation, owner). **Only one `SENDING` per generation.** | Single-send point for unfenced targets. Release is no longer possible; only an outcome or `AMBIGUOUS` can follow. |
     | `CONSUMED` | Fenced target consumes `authNonce` at Control | Single-send point for fenced targets (no `SENDING` call needed). **Not an outcome:** the target accepted the authorization but may still be processing, or may crash before or after applying the effect. Outcome **pending**. |
     | `COMPLETED` / `FAILED` | Outcome evidence (§4.6; for `CONSUMED`, see "Completing a consumed effect" below) | Outcome known |
     | `AMBIGUOUS` | `SENDING` or `CONSUMED` with no outcome by its outcome deadline, lease loss or `OUTCOME_UNKNOWN`; an uncertain send (below); an `AUTHORIZED` generation that expired without release or send | May have happened; fenced |

   - **Completing a consumed effect.** For fenced targets, consumption and the
     outcome are separate steps.
     - **Target outcome report (target-confirmed).** The target-side fencing SDK posts
       `complete(authNonce, outcome, resultDigest)` to Control, signed with the
       target service's own identity, after it commits or rejects the effect.
       This moves `CONSUMED → COMPLETED | FAILED` with a **target-confirmed** outcome,
       because the target, not the executor, says so. Like the executor evidence
       channel (§4.6.3), it is idempotent and accepted late.
     - **Broker-observed response (reported).** The broker reports the response
       it saw. That is recorded, but it does not settle a consumed effect unless
       it agrees with the target's report or a reconciler lookup.
     - **Outcome deadline.** Each fenced route declares one, for example 60 s.
       A `CONSUMED` generation with no target report by then becomes
       `AMBIGUOUS`, and the reconciler queries the target by its downstream key.
     - **Who may execute: the target's local atomic claim, not Control's
       reply.** Several replicas of a target usually share one service
       identity, so Control cannot tell them apart. The right to apply an effect
       therefore comes from a **durable, shared, target-side deduplication
       store** that every replica uses:
       1. **Claim.** A replica receiving a fenced request first claims
          `effectId` in that store atomically (unique insert or compare-and-set).
          The claim records:
          - a random `claimToken`;
          - an **ownership generation** `g`, starting at 1;
          - the authorization generation and the request digest;
          - state `CLAIMED`.

          A replica that loses the claim applies nothing. It returns the current
          status (in progress, or the recorded outcome).
       2. **Consume.** Only the claim owner calls Control to consume `authNonce`,
          presenting its `claimToken`.
       3. **Enter `APPLYING` before acting.** The owner must succeed at a
          compare-and-set `CLAIMED(g, claimToken) → APPLYING(g, claimToken)`
          **before** it takes any action with an effect. If the compare-and-set
          fails, because the claim was taken over (step 5), the owner aborts and
          applies nothing. Where the target supports it, the compare-and-set,
          the effect and the move to `DONE` share one transaction. Otherwise
          `APPLYING` is committed first and `DONE` afterwards.
       4. **Finish.** `APPLYING → DONE | FAILED`, then the owner reports the
          outcome (`complete(...)`).
       5. **Takeover (recovery).** A recovering replica may act only by a
          compare-and-set on the current ownership generation:
          - **From `CLAIMED(g)`:** move to `ABANDONED(g + 1)`. That permanently
            prevents the original owner's step 3, because its compare-and-set on
            `CLAIMED(g)` can never succeed again, even if it was only paused and
            later resumes. Only **after this compare-and-set commits** may
            recovery report "not applied" (`FAILED`, not started).
          - **From `APPLYING(g)`:** recovery cannot prove the effect didn't
            happen; the owner may be mid-action. It checks the target's own
            state for the effect. If that is conclusive, it records
            `DONE`/`FAILED` with the evidence. Otherwise it reports `AMBIGUOUS`.
            It never reports "not applied" from `APPLYING`.
          - **Takeover timing** (for example, a claim older than the outcome
            deadline) affects only liveness. Safety comes from the
            compare-and-set, not from timing.
     - **Consumption replay returns status; it never authorizes.** A repeat
       consumption with the same identity, `authNonce`, fingerprint **and**
       `claimToken` returns `CONSUMED` (the status the owner needs after a lost
       response). A repeat with a different `claimToken` returns
       `CONSUMED_BY_OTHER_CLAIM`. Neither reply is a permission to execute; that
       comes only from owning the local claim. Today `ServicePermits.consume`
       denies any repeat (`consumedAt != null`). Changing it to this
       status-returning replay is required.
     - **Target crash after consumption.** On restart, any replica reads the
       claim:
       - **`DONE` / `FAILED`:** report the recorded outcome.
       - **`APPLYING`:** follow the takeover rule from `APPLYING`: check the
         target's state, or report `AMBIGUOUS`.
       - **`CLAIMED`:** take over to `ABANDONED(g + 1)` first. Only once that
         commits, report "not applied".
     - **Guarantee levels for fenced targets.**
       - **Fenced (authorization):** the target consumes at Control, which gives
         the ordering point of §4.2 and resists A4.
       - **Fenced with deduplication:** additionally uses the shared claim store
         above, verified by conformance. Only this level carries the
         duplicate-prevention guarantee.

       The fencing SDK provides the store adapters (SQL databases first). A
       target without one is fenced for authorization only, and the route shows
       that.
     - **Tests** (`duplicate-execution.json`, OLO service suites, fencing-SDK
       conformance):
       - **concurrent identical requests to two target replicas sharing one
         service identity:** exactly one applies;
       - **paused owner:** the owner claims, then pauses. Recovery takes over
         to `ABANDONED` and reports not applied. The owner resumes, its
         `APPLYING` compare-and-set fails, and nothing is applied;
       - recovery racing an owner that is entering `APPLYING`: exactly one of
         takeover or `APPLYING` wins;
       - recovery finding `APPLYING`: never reports "not applied" without
         conclusive target state;
       - target crash right after claim, after consumption, mid-apply and after
         apply;
       - lost consumption response followed by the owner's repeat;
       - a repeat by a non-owner replica (`CONSUMED_BY_OTHER_CLAIM`, nothing
         applied);
       - a repeat by a different identity or fingerprint (denied);
       - a target outcome report arriving after the outcome deadline;
       - a broker report that conflicts with the target report.

     **Latency option:** for unfenced routes, a combined "authorize and send"
     call may go straight from nothing to `SENDING` in one transaction. It gives
     up `RELEASED` for that effect, so any later failure means `AMBIGUOUS`.

   - **Single send at the broker:**
     - **One owner.** Only the recorded dispatch owner may move a generation to
       `SENDING` or `RELEASED`. A second broker handler, another host, or a
       replay after a lost response gets `EFFECT_IN_FLIGHT`, not the
       authorization.
     - **No automatic retries.** Write routes use an HTTP client configuration
       with retries off, no redirect following, and no replay of a request on a
       new connection: pooled-connection retry, HTTP/2 `GOAWAY` and 0-RTT/early
       data are all disabled for non-`READ` routes. Each `SENDING` produces at
       most one request on the wire.
     - **Uncertain send fences.** A timeout, reset or TLS error after
       `SENDING`, meaning any failure that can't prove the request bytes never
       left, makes the broker report `AMBIGUOUS`. Recovery then follows the
       conflict table: fenced, reconciler, or the §4.3.6 retry rule.
     - **Owner loss.** If the owner dies, its generation stays where it was.
       `AUTHORIZED` expires to `AMBIGUOUS`, not back to a state that can be sent.
       A fresh generation is issued only through the rules below.

   - **Retry and conflict behaviour** (decided in the effect-authorization
     transaction):

     | Latest generation state | Same request digest | Different request digest |
     |---|---|---|
     | None | Issue generation 1 (`NEW`) | — |
     | `AUTHORIZED`, valid, **same owner** | Return that same generation (lost-response replay) | `EFFECT_CONFLICT`, audited, invocation fails |
     | `AUTHORIZED` or `SENDING`, **different owner** | `EFFECT_IN_FLIGHT`; no authorization | `EFFECT_CONFLICT`, audited |
     | `SENDING`, same owner | `EFFECT_IN_FLIGHT`; the owner must report an outcome | `EFFECT_CONFLICT`, audited |
     | `RELEASED` | Issue the next generation with a new `authNonce` | `EFFECT_CONFLICT` |
     | `CONSUMED` | `EFFECT_PENDING`: no new authorization and no outcome. The broker tells the tool the effect was accepted and its outcome is pending; it settles through the target report, the outcome deadline or the reconciler | `EFFECT_CONFLICT`, audited |
     | `COMPLETED` / `FAILED` | `EFFECT_EXISTING`: no new authorization; return the recorded outcome to the tool through the broker | `EFFECT_CONFLICT`, audited |
     | `AMBIGUOUS` | `EFFECT_FENCED`: to reconciliation. **Only exception:** the §4.3.6 rule, which issues the next generation | `EFFECT_CONFLICT`, audited |

   - **Downstream key:** for routes that accept an idempotency key (payment APIs
     and similar), the broker forwards a keyed hash of `effectId`, the same for
     every generation (per `platform-requirements.md` §12.3). A duplicate
     dispatch across generations is therefore deduplicated at the target.
   - **Fenced targets:** the consumed nonce is the generation's `authNonce`,
     checked against `(effectId, generation)`. This generalizes today's
     (invocation, audience) nonce in `ServicePermits`, which allows only one
     write per audience per invocation.
   - **Conformance cases to add** (`duplicate-execution.json`,
     `broker.json`):
     - simultaneous duplicate broker requests for one `effectId`: two handlers
       on one host, two hosts, and a replay racing the original;
     - lost authorization response, then a replay;
     - owner crash in `AUTHORIZED` and in `SENDING`;
     - connection reset mid-body;
     - an attempted pooled-connection retry and an HTTP/2 `GOAWAY` during a
       write;
     - consuming a released or superseded generation's nonce;
     - query-only and content-type-only request changes producing
       `EFFECT_CONFLICT`.
5. **Explicit reconciliation states.** These use the existing states and add the
   lease:
   - **Into unknown:** `EXECUTING → OUTCOME_UNKNOWN` on lease loss (in addition
     to today's expiry watchdog).
   - **Out of unknown:** `OUTCOME_UNKNOWN → SUCCEEDED | FAILED | PARTIAL` only
     with evidence: a late executor report through the evidence channel
     (§4.6.3), a reconciler query of the target, or the existing human
     reconciliation.
   - **No automatic re-execution,** except under step 6.
6. **Decision: keep safe downstream retry, as a narrow declared exception.**
   Today `DOWNSTREAM_IDEMPOTENCY` tools are requeued from `OUTCOME_UNKNOWN` on
   replay (`EnterpriseOperations.java:79`). That is sound **when the target
   reliably deduplicates by the forwarded key**, and removing it would break
   working tools and push operators into manual reconciliation. So it stays, but
   only under all of these conditions:
   - **Opt-in per tool:** the tool declares `DOWNSTREAM_IDEMPOTENCY`, as today.
   - **Per effect:** every write route in the tool forwards the downstream key,
     derived from `effectId` (not one key per invocation).
   - **A reviewed target guarantee, never an assumed window.** Each such route
     carries a `targetDedupeGuarantee` in its service profile, approved through
     independent configuration review as a security delta. It records:
     - the target's **documented** key-retention period, with a reference to the
       target's own documentation or contract;
     - the deduplication scope (per account, per API key, global);
     - what the target does with a repeated key and a different body (reject,
       or replay the first result);
     - a review expiry, so the guarantee is re-checked when the target changes.

     ToolGate never supplies a default. A route without an approved guarantee
     is **not** eligible for automatic resend.
   - **Inside the guaranteed window:** the resend happens within the effective
     window: the documented retention minus a safety margin (for example 10 %,
     at least 1 h), measured conservatively from the first authorization of each
     affected `effectId`. After that the effect stays `AMBIGUOUS` and falls back
     to `STATUS_LOOKUP` or `MANUAL`.
   - **Same content:** same arguments digest, same `effectId`s, same request
     digests.
   - **Current authority:** decision, approval and epoch re-checked, as the
     code already does.
   - **New attempt:** the resend runs under a new `attemptId` and permit for the
     same operation. The old attempt's late evidence still lands on the old
     attempt, and the operation's status is derived from all of them
     (§4.6.4).
   - **Audited** as `RECONCILIATION_RESEND`, as today. The evidence grade of the
     final outcome records that it relied on target deduplication.

   Everything else in C8 holds without exception.

   **Compatibility and migration:**
   - **Contracts:** add `targetDedupeGuarantee` per route to the service
     profile, and reference it from the `DOWNSTREAM_IDEMPOTENCY` declaration in
     `descriptor.schema.json` v2. Additive and optional, with fixtures and
     conformance cases (`duplicate-execution.json`).
   - **Existing packages without an approved guarantee: a deliberate behaviour
     change.** Automatic resend is **disabled** for them. Their `OUTCOME_UNKNOWN`
     calls behave like `STATUS_LOOKUP`, if the package declares a lookup tool or
     route, or otherwise `MANUAL`, until an operator gets a guarantee approved
     through review. That restores resend without repackaging.
     - The release notes call this out as a safety-motivated change.
     - Operator Insights lists every affected tool before and after the upgrade.
     - An upgrade preflight reports the affected tools, so nobody is surprised.
   - **Existing single `downstreamIdempotencyKey`:** treat it as the
     `effectKey` `"default"`. Tools that make more than one write under that one
     key must either adopt per-effect keys or be downgraded to `STATUS_LOOKUP`.
     The packager detects multiple write routes and flags them.
   - **Package reconciliation:** `PackageReconciliation.java`'s
     `DOWNSTREAM_IDEMPOTENCY` checks gain the window and per-route validation.
   - **Docs:** ADR 016 and `invocation-state-v2.md` get an amendment recording
     this decision.
7. **Reconciler adapters.** Per service profile, an optional read-only route that
   looks up an effect by its downstream idempotency key (the `effectId` hash).
   Control runs it for `OUTCOME_UNKNOWN` operations and `AMBIGUOUS` effects and
   records the result as witnessed evidence (§4.6). This extends today's
   `STATUS_LOOKUP` method from per-tool to per-effect.
8. **Wording.** Product and docs say "at most one execution per ledger operation
   by ToolGate, except declared target-deduplicated retries". They never say
   "exactly once".

F3's example: the payment succeeds, the executor crashes before reporting.
1. The lease expires and the operation moves to `OUTCOME_UNKNOWN`.
2. The workflow retries through a restarted Gateway with the same business key. It resolves to the same `operationId` and gets `EXISTING` with outcome unknown, not a new permit.
3. Had a non-ledger tool retried instead, its "charge" write would find its `effectId` `AMBIGUOUS` and get `EFFECT_FENCED`.
4. If the payment route has an approved `targetDedupeGuarantee` and the retry is within its effective window, the retry may resend under the same `effectId`, and the provider deduplicates it.
5. Otherwise the reconciler queries the payment provider by the forwarded `effectId` key and moves the operation to `SUCCEEDED` with witnessed evidence.

### 4.4 Sandbox runtime design (F4; G1, G2) → C5, C1

**Process and privilege layout (Linux, one Tool Host node):**

| Process | Runs as | Holds | Never holds |
|---|---|---|---|
| Supervisor | Dedicated unprivileged service user. Only the capabilities needed to create user namespaces; or delegates creation to a rootless OCI runtime (crun/runc rootless, or gVisor/Kata runtime class) | Workload certificate, Control client, permit verifier, spool | Tenant secrets beyond invocations in flight; container-runtime admin sockets |
| Broker (per invocation) | Separate unprivileged user, own network namespace with egress allowed only to approved destinations | That invocation's profile, fencing token, brokered credentials (memory only) | Any other invocation's state; Control signing material |
| Worker (sandbox) | Unique subordinate UID/GID per invocation, mapped to an unprivileged range on the host; root inside the namespace maps to nobody outside | Its input, its scratch directory, secrets declared `CREDENTIALS_IN_SANDBOX` | Docker/containerd/Podman sockets, host credentials, cloud metadata, Kubernetes service-account tokens, the supervisor's certificate, other sandboxes' files |

**Sandbox lifecycle:**

1. **Create** after permit verification and consumption: fresh user, mount, PID,
   IPC, UTS and network namespaces; cgroup v2 leaf with memory, CPU, PID and I/O
   limits; seccomp default-deny profile (ADR 009); `no_new_privs`; all capabilities
   dropped.
2. **Mounts:** read-only root from the verified runtime image (digest from the
   permit); read-only artifact; one `tmpfs` scratch directory with size cap;
   `/proc` restricted (`hidepid`), no `/sys` writes, no device nodes except
   `/dev/null`, `/dev/zero`, `/dev/urandom`. No host bind mounts. No sockets
   mounted in, including the broker: the broker channel is the worker's own stdio.
3. **Network:** the worker namespace has loopback only. No route, no resolver.
   All egress goes through the stdio broker protocol (`ROUTE_CALL`). Approved
   tunnels, when added, use a per-invocation proxy reachable through a dedicated
   veth to the broker namespace only.
4. **Broker authentication:** the broker trusts only the process whose stdio it
   owns (ADR 027). There is no network API, token or capability a worker could
   steal or forge to address another invocation's broker. Requests are schema
   checked, size capped and bound to the invocation's fencing token server-side.
5. **Teardown:** on result, deadline, lease loss or kill: SIGKILL the cgroup
   (`cgroup.kill`), destroy namespaces, unmount and wipe scratch, drop the broker
   and its in-memory credentials, record teardown in the spool.
6. **Emergency termination:** kill or lease loss triggers step 5 regardless of
   worker cooperation. If the supervisor cannot reach Control and
   `selfTerminateOnControlLoss` is set, the watchdog of §4.5 triggers step 5.
7. **No fallback:** if the configured tier cannot be created, the host refuses the
   pool at registration and every permit for it. The tier used is recorded in the
   receipt.

**Attacks on the supervisor ↔ sandbox interface** (added to `sandbox.json` and
`protocol/` conformance):

| Attack | Defence |
|---|---|
| Oversized, malformed or deeply nested frames | Strict schema, byte and count caps, unknown fields refused; fuzzing in CI |
| Frame flooding to exhaust supervisor memory or the spool | Per-invocation byte budget; backpressure on stdout; budget exhaustion ends the invocation |
| Forged `RESULT` before a brokered write completes | Supervisor ignores result frames while a broker call is outstanding; the outcome records the in-flight effect |
| `SECRET_REDEEM` for undeclared secrets | Handle checked against the invocation's declared secrets and at Control |
| Escaping via `/proc/<pid>` of the supervisor | Separate PID namespace; `hidepid`; different UID |
| Holding file descriptors across teardown | Close-on-exec everywhere; cgroup kill; namespace destruction |
| Timing or resource side channels within a reuse pool | Single-use sandboxes by default; reuse only for `stateless` tools within one tenant and identity class |

Windows and macOS Tool Hosts cannot provide this tier. They are refused for pools
that require `OCI_SANDBOX`, or must use a VM-backed runtime.

### 4.5 Revocation and the partition model (F5; G3, G4, G11) → C7

**Mechanisms:** the kill switch as specified in ADR 017 (insert-only signed
events, epoch bump, apply to stored work, break-glass 2-of-3), plus authorization
leases (§4.2), push to connected executors, pull on every heartbeat, and a local
watchdog.

**Pull-mode hosts** (no inbound route, already supported) need no push channel
for correctness:
- **Queued work:** a kill cancels queued dispatches at Control. A host that
  polls afterwards gets nothing, or a dispatch whose permit consumption fails.
- **Running work:** the host learns of revocation on its next heartbeat. That
  heartbeat goes to Control over the same certificate-authenticated outbound
  connection it already uses for polling and results.
- **Faster kills:** heartbeat responses carry the kill set, and an optional
  long-poll on that connection can deliver kills within a second without an
  inbound listener.

Pull-mode hosts are therefore in the **Degraded** class below unless they hold
that long-poll open.

**Connectivity classes and SLOs** (targets to be measured, not promises yet):

| Class | Condition | New work | Running mediated effects (brokered writes) | Running unmediated work |
|---|---|---|---|---|
| **Connected** | Supervisor healthy and reachable; push channel or pull long-poll up | Refused at Control within 1 s of commit | Fenced: refused at consumption once committed. Unfenced: no new authorization after commit; one already issued valid ≤ 2 s | Terminated within push latency + teardown, target 5 s |
| **Degraded** | Push down or pull-mode host without long-poll; heartbeats work | Refused within 1 s; queued pull dispatches cancelled at Control | Fenced: immediate. Unfenced: no new authorization, ≤ 2 s for one already issued | Terminated at next heartbeat: ≤ lease/3 + teardown |
| **Partitioned** | Supervisor cannot reach Control | No new work can start (permit consumption is online) | Refused: effect authorization and secret delivery need Control | Runs until the lease ends (≤ lease), then the watchdog kills it; with `selfTerminateOnControlLoss`, after a shorter grace period |
| **Supervisor dead** | Crashed, not malicious | Refused at Control | Refused at Control | Sandboxes must live in the supervisor's unit cgroup so they die with it (deployment requirement); lease expiry moves the operation to `OUTCOME_UNKNOWN` |
| **Host compromised** (A4) | Root attacker | Refused at Control | Fenced: refused at Control. Unfenced: **no bound from ToolGate.** Credentials already delivered work until they expire or are rotated at the target | **No bound**; the watchdog can be disabled. Lease expiry only moves the operation to `OUTCOME_UNKNOWN`. Residual risk per §1.2 |

**Rules:**

1. **Maximum offline execution duration on a healthy host** = the lease (default
   30 s, max 120 s). No configuration allows execution beyond lease expiry
   without a successful renewal. This replaces "runs to deadline". It is enforced
   by the supervisor, so it binds only while the supervisor is intact.
2. **Local watchdog** in the supervisor, independent of the invocation task: on
   lease expiry without renewal, kill the cgroup and record it in the spool.
3. **Sensitive pools** set `selfTerminateOnControlLoss` and a short lease (10 s).
4. **Killing a process does not undo effects.** A completed database update or
   payment stays done. Revocation stops *further* effects; the outcome record
   (§4.6) and reconciliation (§4.3) handle what already happened.
5. Measure all SLOs in `conformance/chaos-load` (partition, push loss, replayed
   and reordered kill events, supervisor crash).

### 4.6 Audit with graded evidence (F6; G9, G10) → C6

Signed receipts prove who signed what. They do not prove that the claimed effect
happened. The record therefore separates intent, evidence and outcome.

1. **Durable execution intent before dispatch.** Control writes an intent record
   in the same transaction as permit consumption (T5): operation and attempt ids,
   actor chain, policy decision and version, approvals, argument and package
   digests, sandbox tier, lease. For brokered effects, the effect authorization of
   §4.2 writes a per-effect intent (route, request digest) before the broker
   dispatches. **No effect can be authorized without an intent record.**
2. **Host spool before acting.** The supervisor appends admission, sandbox start,
   each broker dispatch and teardown to a durable fail-closed spool (T-AD-6)
   before acting, and replays it to Control through a **result outbox** until
   acknowledged. A full spool fences the host.
3. **Outcome record through a separate evidence channel (G18).** Permission to
   execute and permission to submit evidence are different things.
   - **Execution authority** (valid fencing token, current epoch, live lease) is
     needed for heartbeats, secrets, effect authorizations and service permits.
     Revocation removes it.
   - **Evidence submission** needs only:
     - the executor's authenticated workload identity, matching the
       `lease_holder` recorded for that attempt;
     - the attempt's fencing token, used as an **identifier**, not as authority;
     - a report signed with the workload key.

     It is accepted in any state, including `CANCEL_REQUESTED`, `CANCELLED`,
     `OUTCOME_UNKNOWN`, after a kill and after the token was invalidated, within
     a retention window (for example 7 days).
   - **What a late report may do:** settle **its own attempt**
     (`OUTCOME_UNKNOWN` → terminal, once) and its own effect generations
     (`AMBIGUOUS` → `COMPLETED`/`FAILED`). It never sets the operation's status
     directly. That status is derived (step 4).
   - **What it never does:** extend a lease, reissue secrets, issue effect
     authorizations, undo a cancellation or kill, or overwrite a terminal state.
     A report that conflicts with a recorded outcome is stored and flagged, and
     the state does not change.
   - **Disabled or compromised hosts:** a report from a host disabled for
     suspected compromise is recorded as **reported** evidence and flagged. It
     cannot settle an outcome alone; a witnessed source or human reconciliation
     must confirm it.
   - **Retired hosts:** an operator can upload the host's signed spool export
     through the existing reconciliation flow.

   This replaces today's "`EXECUTING` only" rule in `reportInSession`. The
   current-state path stays as it is.
4. **Operation status is derived from attempts and effects, never overwritten
   by one report.** A late report from an old attempt can arrive while a newer
   attempt (§4.3.6) is running or has already succeeded.
   - **Stored independently:**
     - each attempt's own state and evidence (`attemptId`, fencing token, lease
       holder, outcome, grade);
     - each effect generation's state (§4.3.4).

     Today the resend requeues the **same** invocation row
     (`EnterpriseOperations.java:79`) and overwrites its state. That has to
     change: attempts get their own records under the operation. The migration
     maps existing invocation rows to single-attempt operations.
   - **Effect manifest.** Each attempt has a manifest: the set of `effectId`s
     it was authorized for. It becomes **sealed** in one of two ways:
     - **Fixed plan:** the manifest is the consent's slots from a plan phase
       (§4.7), known before execution and sealed from the start.
     - **Dynamic:** for every other tool, the manifest grows as the worker
       requests effects. It is sealed by the attempt's terminal report. After
       that, Control refuses further effect authorizations for the attempt
       (`EFFECT_MANIFEST_SEALED`). A read-only tool seals an empty manifest.

     An unsealed dynamic manifest never supports `SUCCEEDED`, because more
     effects may still come.
   - **When two reports actually conflict.** The test is the effect's identity
     and the target's receipt, not "two attempts said success".
     - **Not a conflict:** under the §4.3.6 retry, two attempts can rightly both
       report success for **one** target effect. Same `effectId`, and either
       the same target receipt (the payment id, or the record the target
       returned for the downstream key) or no receipt plus an approved
       `targetDedupeGuarantee`. These merge into one `COMPLETED` effect. The
       grade records "deduplicated by target", and when there's no receipt,
       "deduplication assumed".
     - **Conflict:**
       - one `effectId` with **different** target receipts, meaning two real
         effects;
       - a reconciler lookup that finds more than one target record for one
         downstream key;
       - one effect generation reported `COMPLETED` and `FAILED` by two
         witnessed sources;
       - an attempt reporting success for an `effectId` outside its manifest or
         its consent slots.
   - **The operation-wide effect set.** Success is judged over `U`, the union of
     every `effectId` authorized for the operation, across **all** attempts and
     generations, not over one attempt's manifest. Each effect in `U` is
     either:
     - **Resolved:**
       - `COMPLETED`;
       - `FAILED` with conclusive evidence it wasn't applied (including the
         takeover rule of §4.3.4);
       - every generation `RELEASED`;
       - `EQUIVALENT_TO(e')`, where reconciliation (a reconciler lookup or the
         existing human reconciliation) shows it is the same target effect as a
         `COMPLETED` effect `e'`, for example the same target receipt.
     - **Unresolved:** `AUTHORIZED` (still valid), `SENDING`, `CONSUMED` or
       `AMBIGUOUS`. An authorization that expired without a send is `AMBIGUOUS`
       (§4.3.4), so it is unresolved too.
   - **Provisional and final statuses.**
     - **Provisional:** `RUNNING`, `OUTCOME_UNKNOWN`, `RECONCILIATION_REQUIRED`.
       They can change as evidence arrives.
     - **Final:** `SUCCEEDED`, `PARTIAL`, `FAILED`. They can be derived **only
       when** every attempt is terminal and every effect in `U` is resolved, so
       no authorization is outstanding, no send is uncertain and no target
       outcome is pending.

     Because a final status needs everything resolved first, later evidence can
     only confirm it or contradict it. It can never legitimately turn `FAILED`
     into `PARTIAL`. A contradiction is a conflict (rule 1).
   - **Derivation rules, applied in order** whenever an attempt or effect record
     changes:

     | # | Condition | Operation status |
     |---|---|---|
     | 1 | A conflict as defined above, **or** a `COMPLETED` effect in `U` outside the manifest that rule 4 relies on and not resolved as `EQUIVALENT_TO` an effect in it (an extra real effect) | `RECONCILIATION_REQUIRED` (provisional): no further resend; alert as a suspected duplicate |
     | 2 | Any attempt is non-terminal (`RESERVED` … `EXECUTING`) | `RUNNING` (provisional) |
     | 3 | Any effect in `U` is unresolved, or any attempt is `OUTCOME_UNKNOWN` **and not settled** (below) | `OUTCOME_UNKNOWN` (provisional), even if a newer attempt succeeded |
     | 4 | Everything resolved, and either: (a) a **fixed-plan** manifest whose every slot is `COMPLETED`; or (b) an attempt that **ended `SUCCEEDED`** (the worker returned a success result) whose **sealed** manifest is entirely `COMPLETED`, including an empty manifest for a read-only tool. Every other effect in `U` is `FAILED`, `RELEASED` or `EQUIVALENT_TO` one of those | `SUCCEEDED` (final) |
     | 5 | Everything resolved, at least one effect `COMPLETED`, rule 4 not met | `PARTIAL` (final) |
     | 6 | Everything resolved, no effect `COMPLETED` | `FAILED` (final) |

     **Settling an attempt that never reports again.** An attempt stuck in
     `OUTCOME_UNKNOWN` blocks the operation only because of what it **might
     still hide**, not because of its historical state. It becomes `SETTLED`,
     a recorded attempt-level transition that stays as evidence, when both of
     these hold:
     - **(i) Its ledger is closed and resolved.** Its fencing token is
       invalidated and its lease has ended, so it can obtain no new authority.
       Control seals its manifest, refusing any further effect authorization for
       it. Every effect in that manifest is resolved.
     - **(ii) It cannot have had effects outside the ledger.** One of:
       - **automatically,** if it ran brokered-only in the `OCI_SANDBOX` tier
         (the same conditions as §4.3.3: no `CREDENTIALS_IN_SANDBOX`, no
         tunnels, recorded in its permit), because then every effect it could
         have had is in `U`;
       - **through the existing human reconciliation flow,** for tools with
         unmediated effect paths: an independent reviewer attests, with an
         evidence digest, that the attempt had no further effects.

     Late reports from a settled attempt are still accepted as evidence. If they
     contradict what settled it, the result is rule 1. Settlement never turns
     the attempt into `SUCCEEDED` or `FAILED`; it only stops the attempt from
     blocking.

     **Why this order:**
     - **Rule 2 before success:** a dynamic tool whose first writes have
       completed is still `RUNNING`.
     - **Rule 3 before success:** an unresolved effect from an older attempt
       blocks success even when a retry finished cleanly. If attempt 1 sent
       effect A and a nondeterministic retry produced and completed effect B, the
       operation stays `OUTCOME_UNKNOWN` until A is resolved:
       - **A not applied:** `SUCCEEDED`;
       - **A equivalent to B:** `SUCCEEDED`;
       - **A also applied:** rule 1, an extra effect.
     - **Rule 4(a):** a fixed-plan tool can succeed even if its worker crashed
       after its last write, because the sealed plan said there was nothing
       more.

   - **Consequences:**
     - An old attempt's late `FAILED` is recorded on that attempt. If its
       effects are resolved, it changes nothing. If they aren't, the operation
       was already held at rule 3.
     - A late `SUCCEEDED` from an old attempt for the same `effectId`s with
       matching receipts merges. One with different receipts trips rule 1.
     - **A worker that fails after dispatching a write whose outcome is
       unknown** ends `OUTCOME_UNKNOWN`, not `FAILED`. When target evidence later
       resolves the write, it becomes `PARTIAL` (write completed) or `FAILED`
       (write not applied). That is a provisional status becoming final, not a
       final status changing.
     - A worker that fails after its first **completed** write, with nothing
       else outstanding, ends `PARTIAL`.
     - **Caller view:** business-key `outcome` and the caller-visible task state
       follow the derived status, one to one:

       | Derived status | Caller sees | Business-key `outcome` |
       |---|---|---|
       | `RUNNING` | `working` (MCP task) / running, as today | none yet |
       | `OUTCOME_UNKNOWN` | `OUTCOME_UNKNOWN`, `retryable: false`, with the count of unresolved effects and unsettled attempts | `OUTCOME_UNKNOWN` |
       | `RECONCILIATION_REQUIRED` | `RECONCILIATION_REQUIRED`, `retryable: false` | `OUTCOME_UNKNOWN` |
       | `SUCCEEDED` / `PARTIAL` / `FAILED` | As named | `COMPLETED` / `PARTIAL` / `FAILED` |

       `OUTCOME_UNKNOWN` is reserved for uncertain execution. Ordinary running
       work is never reported as unknown.
     - **ADR 016's one-way rule** (`OUTCOME_UNKNOWN` → terminal once) applies to
       final statuses. ADR 016 gets an amendment adding `PARTIAL` to the
       business-key outcomes and naming `RECONCILIATION_REQUIRED` as the only
       exit from a final status.
   - **Tests** (`transitions.json`, `duplicate-execution.json`):
     - old attempt reports `FAILED` late while the new attempt is running, and
       again after it succeeded;
     - old attempt reports `SUCCEEDED` late after the new attempt succeeded:
       matching receipts merge; different receipts → `RECONCILIATION_REQUIRED`;
     - a §4.3.6 retry where both attempts succeed for one target-deduplicated
       effect → `SUCCEEDED`, not reconciliation;
     - **read-only tool:** empty sealed manifest plus a successful attempt →
       `SUCCEEDED`; a crashed attempt → `FAILED`;
     - **dynamic multi-write tool:** two writes `COMPLETED` while the attempt
       runs → `RUNNING`. The attempt succeeds → `SUCCEEDED`. A write requested
       after the seal → `EFFECT_MANIFEST_SEALED`;
     - **worker fails after its first completed write** → `PARTIAL`;
     - fixed-plan tool, all slots `COMPLETED`, worker crashes before reporting
       → `SUCCEEDED`;
     - **retry with a changed effect set:** attempt 1 leaves effect A
       `AMBIGUOUS`, and attempt 2 produces and completes effect B →
       `OUTCOME_UNKNOWN`. Then:
       - A reconciled as not applied → `SUCCEEDED`;
       - A shown `EQUIVALENT_TO` B → `SUCCEEDED`;
       - A shown applied separately → `RECONCILIATION_REQUIRED`;
     - **late completion after worker failure:** the worker fails with a write
       in `SENDING` → `OUTCOME_UNKNOWN`. A target report of `COMPLETED` →
       `PARTIAL`; of not applied → `FAILED`;
     - **late completion after authorization expiry:** `AUTHORIZED` expires
       without a recorded send → `AMBIGUOUS`, then `OUTCOME_UNKNOWN`. The
       reconciler finds the write applied → `PARTIAL`; not applied → `FAILED`;
     - a final status contradicted by later witnessed evidence →
       `RECONCILIATION_REQUIRED`, never a silent move between final statuses;
     - **attempt 1 never reports again:**
       - its lease ends and it goes `OUTCOME_UNKNOWN`;
       - its effect A is reconciled as not applied;
       - being brokered-only in `OCI_SANDBOX`, it settles automatically;
       - attempt 2 succeeds → `SUCCEEDED`.

       Variant: the same tool with an unmediated effect path stays
       `OUTCOME_UNKNOWN` until a reviewer settles attempt 1, then →
       `SUCCEEDED`;
     - a settled attempt sends a late report that contradicts its settlement →
       `RECONCILIATION_REQUIRED`;
     - the caller sees `working` while an attempt runs, and `OUTCOME_UNKNOWN`
       only once execution is uncertain;
     - effect evidence that conflicts across a target report and a broker report;
     - every derivation row.
5. **Evidence: observations are graded separately from the business
   outcome.** Seeing that something happened on the wire is not the same as
   knowing the effect completed. Each effect therefore carries two things.
   - **Observations.** Every recorded fact, with its source:

     | Observation | What it establishes | Source and trust |
     |---|---|---|
     | Authorization issued, `SENDING` recorded | ToolGate permitted it; the send may have happened | Control (authoritative for its own records) |
     | Dispatch observed | Request bytes left the broker | Broker; trusted only while the host is intact (A4) |
     | Response observed | The target answered with this status and body | Broker; same trust |
     | Authorization consumed | The target **accepted the permit**, not that it applied the effect | Control record of the target's consumption |
     | Executor report | The executor's claim of the outcome | Supervisor or endpoint, signed with its workload key |
     | Target outcome report | The target committed or rejected the effect | Target, signed with its own identity (§4.3.4) |
     | Reconciler lookup | The target has (or lacks) a record for the downstream key | Control-run read of the target |
     | Signed upstream receipt | The target vouches for the outcome | Target's signature |
     | Human reconciliation | A reviewer's decision with an evidence digest | Independent reviewer |

   - **Business outcome and its grade.** `COMPLETED`, `FAILED` or unresolved.
     It is derived only from **outcome-bearing** observations, and graded by
     the strongest source behind it:

     | Outcome grade | Established by |
     |---|---|
     | **Target-confirmed** | Target outcome report, reconciler lookup, or signed upstream receipt |
     | **Broker-observed** | A response on a route whose review declares its synchronous response authoritative (for example, the API commits before it answers 2xx). Depends on an intact broker. |
     | **Human-reconciled** | Human reconciliation |
     | **Executor-reported** | Executor report alone |
     | **Unknown** | Nothing outcome-bearing: the outcome is unresolved |

     Authorization consumption and dispatch observations are **never** outcome
     evidence on their own. A `CONSUMED` effect stays unresolved until an
     outcome-bearing observation arrives (§4.3.4). A broker-observed response on
     a route not declared authoritative is recorded as an observation only.

   Consoles, exports and analytics show both the observations and the outcome
   grade. Unresolved outcomes stay fenced until reconciled (§4.3). Where earlier
   sections say "witnessed", they mean **target-confirmed**, plus
   **broker-observed** only on authoritative-response routes.
6. **Tamper evidence.** Each audit row stores the digest of the previous row per
   tenant; Control signs the chain head periodically and anchors it externally
   (customer bucket with object lock, or a transparency log). `toolgate audit
   verify` checks chain, signatures and anchors offline. This detects rewriting by
   A5 and by A6 after the last anchor; it cannot stop A6 from writing false
   records while in control.
7. **Wording.** The product says "every authorized invocation has a durable
   intent record and a graded outcome". It never says "every effect is
   verifiably recorded".

F6's example: a malicious executor (A4) performs a side effect and crashes. The
intent record exists from T5. No outcome arrives, the lease expires, the operation
becomes `OUTCOME_UNKNOWN` (graded Unknown) and is fenced. If the effect went
through a brokered route, the per-effect intent shows exactly which request was
authorized, and the reconciler can query the target.

### 4.7 Policy differentiators (G14, G15)

1. **Provenance-aware policy, trusting only sources an agent cannot forge.** An
   untrusted agent can label injected content as user input, so the agent's own
   labels are never a source of trust.
   - **Positive trust only.** An argument counts as "user-originated" only when a
     trusted source vouches for it. Everything else, including every
     agent-supplied label, is **untrusted** by default. Agent labels may *lower*
     trust (for example the agent marks something as web content) but never raise
     it.
   - **Trusted source 1: ToolGate's own observations.** Gateway and Control
     already see every governed tool result returned to an agent run. Control
     keeps fingerprints of result content per run, marked with the source tool's
     trust level, for example "external web content". An argument whose value, or
     a substantial substring of it, matches untrusted result content is tainted.
     The agent did not supply this lineage, so it cannot fake it.
   - **Trusted source 2: an authenticated integration that maintains the
     lineage.** The chat front-end or agent runtime is registered as a workload
     with its own identity. It signs digests of the user's actual inputs for each
     turn and binds them to the run identity (§4.1) with an expiry. An argument
     that matches a signed user input **from the same run, still valid** is
     user-originated. Unsigned claims of user input are ignored, and so are
     signed inputs from other runs.
   - **Precedence when a value matches both sources: the more restrictive
     wins.** A value that matches signed user input *and* content from an
     untrusted tool result is treated as **tainted**. The attacker may have
     planted it in the tool output precisely so it would look like something the
     user said. The effect preview shows both matches, so a reviewer can decide.
   - **In the permit and policy:** the permit binds a provenance summary per
     argument: `USER_SIGNED`, `TAINTED(tool)`, or `UNKNOWN`. Conditions then act
     on it, for example "ASK when any argument to a `WRITE` route is not
     `USER_SIGNED`" or "BLOCK sending content tainted by `web.fetch` to an
     external destination".
   - **Known limit:** paraphrased or transformed content evades taint matching.
     That is why the safe policies are written as "require user-signed", not
     "block tainted": a paraphrase is still not user-signed. Taint matching makes
     the explanation better, but the positive-trust rule is what enforces.
   - **Provenance is not consent.** That a value came from the user does not
     mean the user authorized *this* action with it. An agent can reuse a
     recipient or amount the user gave earlier for a different action. So
     provenance only ever *lowers* friction for low-risk effects or *raises*
     it. It never authorizes a high-risk effect alone.
   - **Consent binding for high-risk effects.** Effect classes `DESTRUCTIVE`
     and `FINANCIAL`, and any route that policy marks high-risk, need an
     explicit consent record. It is either an ASK approval, as today, or a
     signed **user intent** from the authenticated front-end. Either way it
     binds the complete action:
     - tool, action and effect class;
     - the digest of the **entire** argument set, not individual values;
     - resources, `operationId` and run identity;
     - a validity window;
     - **explicit effect slots** that limit how many effects it permits (below).
   - **Consent must reach the actual effect, not just the tool's inputs.** The
     worker is untrusted (A2). It can receive the approved arguments ("pay $50 to
     Alice") and still build a different request on an allowed route, for
     example with Bob's account in the body. Binding the argument set alone does
     not stop that. So every high-risk route must use one of these two
     mechanisms, and effect authorization enforces it:
     - **(a) Trusted broker mappings (preferred).** At review, the route declares
       its **sensitive outbound fields**: body JSON pointers, path variables,
       query parameters and headers such as recipient account, amount, currency
       and target resource. For each one, a fixed mapping from consented
       arguments: a copy, or a declared format template. The **broker** fills
       those fields from the consented argument values. The worker cannot set
       them, and a request that tries to is refused. The request digest then
       contains exactly the mapped values.
     - **(b) Request-level consent.** For tools whose sensitive fields can't be
       mapped declaratively, the tool runs a **plan phase** first. The worker
       computes the outbound effect requests without sending them, and the
       broker returns their canonical digests and sensitive fields. The approval
       or user intent then binds those exact `requestDigest`s, and the reviewer
       sees the actual recipient and amount in the effect preview. At execution,
       effect authorization accepts only a request whose digest matches a free
       consent slot (below). A tool that computes a different request, because it is
       nondeterministic or malicious, fails.

     A high-risk route offering neither is refused at review, or approved with an
     explicit "argument-level consent only" delta. ToolGate then makes no
     payment-style guarantee for it.
   - **Effect slots: consent limits how many times, not just what.** A
     malicious worker could submit the same approved payment several times
     under different `effectKey`s. Each would get its own `effectId` and
     downstream key, so neither request matching nor target deduplication would
     stop it. Consuming consent once per attempt doesn't help either, because
     all of those effects happen inside one attempt. So consent lists its
     permitted effects as **slots**:

     `slot = (consentId, slotIndex, operationId, routeId, effectKey → effectId, requestDigest or mapped sensitive-field values, permittedCount)`

     - **(b) plan-phase consent:** one slot per planned request, with the
       `effectKey`, `effectId` and `requestDigest` the plan produced, and
       `permittedCount = 1` unless the reviewer explicitly approves more.
     - **(a) mapped consent:** the consent states the slots per route, for
       example "payment route: 1 effect, `effectKey` `charge`, amount and
       recipient from the arguments". There is no open-ended "any number of
       requests with these values".
     - **Atomic reservation.** The effect-authorization transaction binds the
       request to a free slot that matches its route, `effectId` and digest or
       mapped values, and increments the slot's use count. Refusals, all
       audited:
       - no matching slot: `EFFECT_CONSENT_MISMATCH`;
       - an `effectId` that isn't the slot's: `EFFECT_CONSENT_MISMATCH`;
       - slot exhausted: `EFFECT_CONSENT_EXHAUSTED`.

       Slot reservation and `effectId` reservation commit together.
     - **Retries reuse the slot.** A new generation, or a new attempt of the
       same operation under §4.3.6, for the **same** `effectId` stays in the
       slot it already holds and doesn't count again. A different `effectId`
       needs a different slot.
     - **Consent is used up when** every slot is exhausted or the validity
       window ends. It no longer ends "per attempt".
   - **Enforcement point.** For a consented effect, the effect-authorization
     transaction checks the request against (a) or (b) and reserves its slot.
     For **fenced** targets, the effect permit carries the consented
     sensitive-field values and the slot id, and the target-side fencing SDK
     checks the request it received against them. Its claim store (§4.3.4) also
     refuses a second `effectId` for a slot that already has one. That holds
     even against a compromised Tool Host (A4). For unfenced targets, the check
     depends on an intact broker, as in §1.2.
   - **Tests:**
     - **two identical payment requests with different `effectKey`s under one
       single-slot consent:** the second gets `EFFECT_CONSENT_EXHAUSTED`;
     - the same across two attempts;
     - a mapped route asked for a second effect;
     - a retry of the same `effectId` after `RELEASED` and after `AMBIGUOUS`
       under §4.3.6: it stays in the same slot and is not counted again;
     - a slot bound to a fenced target, presented with a different `effectId`.

     So a consent record for "send $50 to Alice" cannot authorize sending to
     Bob, sending to Alice's other account, or a second payment. Provenance is
     shown alongside it in the effect preview as context, not as the decision.
2. **Effect preview** for approvers (target, recipients, amount, diff, provenance).
3. **Step-up approval** (WebAuthn per approval) for high-risk effect classes, with
   the assertion in the intent record.
4. **Blast-radius view** per agent from the evaluator's witnesses.

### 4.8 Credibility (G13, G17)

1. Turn every security conformance case into an executed CI gate; list known
   failures openly.
2. Publish a per-release **Zero Trust Execution scorecard**: claims C1–C8, the
   attacker classes each holds against, tests, pass/fail, tested revision.
3. Align README and docs on one truth table (Available / Partial / Planned).
4. External red-team exercise against §1 once Phases 1–3 land.

---

## 5. Phased roadmap

| Phase | Goal | Gaps | Exit criteria |
|---|---|---|---|
| **0. Boundary and harness** (≈2 wks) | Make the claim precise and measurable | G13, G17; §1 | Threat matrix reviewed and merged into security docs; claims C1–C8 adopted; security conformance runnable in CI (failures expected); docs aligned |
| **1. Leases, operations, admission records and evidence channel** (≈6–8 wks) | Stop running to deadline; no duplicate operations from ToolGate; crash-recoverable admission; late evidence accepted | G4, G5a, G10, G18, part of G9 | Heartbeat/lease/watchdog live alongside the existing expiry watchdog. Business keys in front of `requestId` replay; `requiresBusinessKey` refusal. Rebinding only on Control's own "never consumed" record; the brokered-only path stays disabled. **Durable admission and attempt intent records** written in the T5 transaction. Host spool and result outbox. Separate evidence channel for late reports. `duplicate-execution`, `audit-spool`, `transitions` pass |
| **2. Containment** (≈6–8 wks) | Worker cannot escape its profile | G1, G2 | §4.4 tier on Linux Tool Hosts; no fallback; `sandbox`, `egress`, interface-attack cases pass |
| **3. Effect-boundary authorization, effect ids and revocation** (≈6–8 wks) | Per-effect authorization with a defined ordering point; revocation meets §4.5 SLOs | G3, G5b, G6, G11, part of G9 | Effect authorization for non-read routes with **per-effect intent records**, the per-generation effect state machine (`AUTHORIZED`/`RELEASED`/`SENDING`/`CONSUMED`/`AMBIGUOUS`) with a dispatch owner and unique `authNonce`, the full canonical request digest, the conflict table and a broker HTTP client with no retries for writes. Brokered-only rebinding enabled only on pools where Phase 2 containment is also live. `DOWNSTREAM_IDEMPOTENCY` migrated to per-effect keys, with resend only for routes that have an approved `targetDedupeGuarantee` (§4.3.6). `CONSUMED` treated as pending; target outcome reports; status-only consumption replay keyed by `claimToken`; fencing SDK with a shared claim store ("fenced with deduplication" level). Per-attempt records and derived operation status (§4.6.4). Consent effect slots (§4.7), wherever consent-bound routes ship. Fenced-target consumption generalized from `ServicePermits`, plus a target-side fencing SDK. Downstream `effectId` keys; reconciler adapters. Kill switch with push and pull-mode long-poll; inbound mTLS dispatch. SLOs measured per connectivity class; `kill-*` pass |
| **4. Evidence presentation and tamper evidence** (≈3–4 wks) | Honest record anyone can verify | rest of G9 | Evidence grades shown in console, exports and analytics; per-tenant hash chain; signed and externally anchored chain heads; `toolgate audit verify` offline |
| **5. Identity hardening** (≈6 wks) | Stolen agent credential useless; posture stays live | G7, G8, G16 | Sender-constrained short-lived creds, run identity, delegation chain. Attestation verifiers that feed `ControlDeviceEvidence`, and evidence re-checked at lease renewal. `identity`, `delegation` pass |
| **6. Differentiation** (≈6–8 wks) | Lead the market | G12, G14, G15 | Broker OAuth/token exchange; provenance policy; effect preview; step-up approvals |

Phase 1 comes before containment. Leases, operations and admission records are
the foundation C3c, C7 and C8 rest on, and their design is already reviewed. Each
phase builds the records it needs:
- **Phase 1:** admission and attempt intent records.
- **Phase 3:** per-effect intent records.
- **Phase 4:** only presentation, anchoring and offline verification.

Phases 2 and 3 can run in parallel with separate owners.

---

## 6. Flagship demo

1. A prompt-injected agent tries to email the customer list outside and delete a
   production record. **Provenance policy** sends both to ASK; the reviewer sees
   the effect preview and denies.
2. The stolen agent credential is replayed from another machine → **refused**.
3. A malicious tool opens a raw socket to the database and reads instance
   metadata → **blocked** by the sandbox network namespace.
4. A long-running tool waits, then issues a destructive brokered call to a fenced
   target after its grant is revoked → the target's consumption is **refused**
   at Control.
5. The admin kills the package; a running unmediated call is terminated within the
   connected SLO. The Tool Host is then partitioned; its remaining work dies at
   lease expiry.
6. The executor is crashed right after a payment route call → operation goes
   `OUTCOME_UNKNOWN`, the workflow retry gets `EXISTING`, the reconciler confirms
   the payment as **witnessed**. `toolgate audit verify` validates the chain and
   detects a deliberately edited row.

---

## 7. Risks and open questions

- **Latency.** Per-effect authorization adds one or two Control round trips
  (authorize, then `SENDING`) to every non-read brokered call to an unfenced
  target. The combined "authorize and send" call reduces that to one, at the
  cost of the release path. Set a p99 budget and measure it; consider regional
  Control replicas for reads of the epoch only (never for authorization).
- **Control availability becomes effect availability.** By design, no Control
  means no new effects. Publish this as a property and size Control HA for it.
- **Consent-to-effect binding needs per-route work.** Every high-risk route
  needs sensitive-field mappings or a plan phase. Some tools will ship with
  "argument-level consent only" until they are adapted, and the product must
  show that per route.
- **Linux-only isolation.** Decide whether non-Linux Tool Hosts are unsupported for
  sensitive pools or use a VM tier.
- **Provenance needs an authenticated front-end integration** for user-signed
  inputs. Without one, every argument is untrusted, and "require user-signed"
  policies degrade to ASK. That is safe, but it causes approval fatigue.
- **`DOWNSTREAM_IDEMPOTENCY` relies on the target.** A target that deduplicates
  less reliably than reviewed turns the §4.3.6 exception into a duplicate. Review
  must check the target's documented dedupe window, and the evidence grade
  records the reliance.
- **Reconciler coverage.** Witnessed evidence needs a lookup route per target;
  many APIs lack one. Unknown outcomes then need operator attestation.
- **Key custody** for receipts and chain anchors joins `key-management.md` and
  the recovery model.
- **Fenced-target adoption.** The strongest guarantees (C3b against A4, zero
  window) need targets that consume at Control. Third-party SaaS will mostly stay
  unfenced; the product must show per route which kind it is.
- **`effectKey` discipline.** Tools that compute keys from nondeterministic data
  break deduplication. The SDK should provide helpers and lint for it, and
  conformance should include a nondeterministic-tool case.
- **Confirm** the delegation status with its owners.