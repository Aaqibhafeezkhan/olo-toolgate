# Threat model: Tool Host, broker, egress and secrets

## Purpose

This is the reviewed threat model behind design gate D2. It covers the server-side execution path of plan.md §11 and §13: the Tool Host supervisor, its sandboxes, the request broker, the egress proxy, the release-1 transport matrix, the Tool Host's workload identity, and secret delivery to server tools.

It extends the platform [threat model](../threat-model.md) and does not repeat it. Client tools keep their existing model in [endpoint enforcement](../endpoint-enforcement.md). Decisions made here are recorded in ADR 013. Every threat below has a conformance case to be authored at gate D3, named in its last column.

## 1. System and trust boundaries

```text
             (B1) mTLS, Gateway workload cert            (B3) mTLS, Tool Host workload cert
  Gateway ─────────────────────────────────► Tool Host ─────────────────────────────────► Control
                DISPATCH {permit, args}      supervisor     permits/consume, heartbeat,        (state DB,
                                                │           secrets/deliver, effects/report      signing keys)
                                                │ (B2) launcher: namespaces, seccomp, cgroups
                                                ▼
                                   ┌──────── sandbox (untrusted tool code) ────────┐
                                   │ /run/toolgate/broker.sock   /run/toolgate/secret.sock │
                                   └──────┬───────────────────────────┬─────────────┘
                                          │ (B4) per-sandbox UDS       │ (B5) per-sandbox UDS
                                          ▼                            ▼
                                   broker / egress proxy  ◄── credentials (memory only)
                                   (own netns, NetworkPolicy)
                                          │ (B6) TLS to approved destinations only
                                          ▼
                                   upstream APIs, approved TCP destinations
```

| Boundary | Between | What crosses it | Who is trusted on each side |
|---|---|---|---|
| B1 | Gateway → Tool Host | Permit, canonical arguments, progress, results | Both are ToolGate components. Neither trusts the other's authority: the Tool Host trusts only Control's signature on the permit. |
| B2 | Supervisor → sandbox | stdin `ToolInput` v2, frames on stdout | The sandbox runs **untrusted** code: the published package, and anything it was tricked into running. |
| B3 | Tool Host → Control | Nonce consumption, lease, secrets, results | Control is the authority. The Tool Host is trusted to enforce what Control decides, nothing more. |
| B4 | Sandbox → broker | Route-selected HTTP requests with a capability | The broker trusts the socket it accepted on, never request content. |
| B5 | Sandbox → secret socket | Handle redemption, only for `CREDENTIALS_IN_SANDBOX` tools | Same as B4 |
| B6 | Broker or proxy → upstream | TLS to an approved destination | The upstream is outside ToolGate. It may be malicious or compromised. |

## 2. Assets

- **A1 Tenant credentials:** upstream API keys, OAuth tokens, database passwords (`control_secrets`, broker memory).
- **A2 Execution authority:** Control-signed permits, single-use nonces, fencing tokens, broker capabilities.
- **A3 Tenant data in flight:** arguments, results, upstream responses.
- **A4 Effect integrity:** at most one execution per ledger invocation, and honest outcomes (`OUTCOME_UNKNOWN` instead of a guess).
- **A5 Isolation:** one tenant's or invocation's code, data and credentials are never reachable from another's.
- **A6 Audit completeness:** no effect without a durable record (plan.md §12.7 row 11).
- **A7 Platform reachability:** Kubernetes API, node metadata, Control and Gateway admin listeners, other tenants' brokers.

## 3. Adversaries

| Id | Adversary | Capability assumed |
|---|---|---|
| E1 | Malicious or compromised tool package | Arbitrary code inside one sandbox, for one invocation or a reuse pool |
| E2 | Prompt-injected agent | Chooses tools and arguments within its catalog scope |
| E3 | Malicious upstream service | Controls responses, redirects, DNS for its own names, TLS certificates for its own names |
| E4 | Network attacker inside the cluster | Can reach pod IPs. Can't break TLS. |
| E5 | Compromised Gateway replica | Holds the Gateway workload certificate and the Gateway's Control token. **No policy or signing key.** |
| E6 | Compromised Tool Host node | Root on one host: its workload certificate, the sandboxes on it, the credentials it holds in memory at that time |
| E7 | Malicious tenant administrator | Configures their own tenant's pools, profiles and secrets; tries to reach other tenants or the platform |

## 4. Threats and mitigations

Each row gives the mitigation as designed (with the plan section), what remains, and the D3 conformance case that will prove it.

### 4.1 Admission and execution authority

| Id | Threat (adversary) | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-AD-1 | Forged or altered permit (E5, E4) | Control signs every permit (RS256, `typ: toolgate-invocation-permit+jws`, pinned `kid` set; ADR 013). The Tool Host verifies the signature, `aud` = its own host id, the window, and every digest against what it will execute (plan.md §11.3). | Theft of Control's effect key, which is out of scope here and covered in [key management](../key-management.md) | `admission/forged-permit`, `admission/wrong-aud` |
| T-AD-2 | Replay of a valid permit on the same or another host (E5) | Per-host `jti` cache; `aud` binding; Control consumes the single-use nonce in `RESERVED → EXECUTING` ([invocation-state-v2.md](../../control-plane/invocation-state-v2.md) T5). The `exp` window is seconds long. | None for ledger tools. A non-ledger tool may run twice across a host crash, by design (plan.md §11.4). | `admission/replay-same-host`, `admission/replay-other-host` |
| T-AD-3 | Gateway swaps the arguments or the package after the permit (E5) | The permit binds the canonical arguments digest, `toolDescriptorDigest`, package, artifact, runtime, globals, service profile and deployment binding digests. The host recomputes the arguments digest and refuses a mismatch. | None | `admission/args-swap`, `admission/digest-swap` |
| T-AD-4 | Double execution of a non-idempotent tool after an ambiguous dispatch (E4 drops packets) | Ledger CAS before `ADMITTED`. The Gateway never retries elsewhere after a send without an answer, and asks Control instead (plan.md §11.4). | External effect duplication by the upstream itself. That is out of ToolGate's control and stated in plan.md §11.7. | `ledger/ambiguous-dispatch`, `ledger/crash-after-admitted` |
| T-AD-5 | Work continues after cancel or kill (E1 ignores signals) | The fencing token is invalidated at Control. Secret delivery, the broker and the lease refuse it. The supervisor kills the sandbox's cgroup, and the broker and proxy drop its connections ([kill-switch.md](../../control-plane/kill-switch.md) §5). | A partitioned host runs to the deadline, unless `selfTerminateOnControlLoss` is set | `kill/terminate-running`, `kill/partitioned-host` |
| T-AD-6 | Tool runs without audit (E6 fills the disk; E1 floods logs) | Fail-closed spool with reserved headroom, then the `FENCED` state (plan.md §12.7 row 11). Tool log frames count against the invocation's byte cap. | Effects already sent before `FENCED` are reported `OUTCOME_UNKNOWN` | `audit/spool-full`, `audit/fenced` |

### 4.2 Sandbox and supervisor (B2)

| Id | Threat | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-SB-1 | Sandbox escape to the node (E1) | `OCI_SANDBOX` tier: the ADR 009 boundary (user namespace, no capabilities, seccomp default-deny, read-only root, no host mounts other than the two sockets), plus cgroup limits from §13.5. There is never a fallback to a weaker tier (plan.md §13.4). | Kernel zero-days. Operators may choose a VM-backed runtime class for sensitive pools. | `sandbox/escape-probes` (the existing ADR 009 suite run on the Tool Host) |
| T-SB-2 | Cross-invocation leakage through a reused sandbox (E1) | Single-use by default. Reuse only for `stateless` tools, within one tenant and identity class, capped by count and age. Secrets are never cached, and scratch space is wiped (plan.md §11.5). | Side channels within a reuse pool of the same identity, which is accepted | `sandbox/reuse-isolation` |
| T-SB-3 | Cross-tenant pool sharing (E7) | Pools are keyed by tenant and never shared. The supervisor refuses a permit whose tenant isn't the pool's. | None | `sandbox/cross-tenant-permit` |
| T-SB-4 | Malformed or oversized frames crash or confuse the supervisor (E1) | Strict frame schema (`protocol.schema.json`), byte and count caps, and unknown fields rejected. The supervisor is Rust and parses before acting. | Supervisor parser bugs, covered by fuzzing in M2 | `protocol/frame-fuzz`, `protocol/oversize` |
| T-SB-5 | Resource exhaustion of the host (E1) | cgroup memory, CPU, PID and disk quotas per sandbox; the platform ceilings; autoscaling on in-flight work | Noisy-neighbour latency within a pool | `limits/exhaustion` |

### 4.3 Broker request channel (B4)

| Id | Threat | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-BR-1 | Using the broker as an open proxy to internal targets, SSRF (E1, E2) | The sandbox selects a **route id**. It never sends a URL. The destination comes from the approved service profile, loaded by the permit's digest (plan.md §11.8). | None at the broker. A mis-approved route is a review failure. | `broker/url-not-accepted`, `broker/unknown-route` |
| T-BR-2 | Path, query or header injection to reach other upstream resources (E1, E2) | Typed path variables checked against patterns, then percent-encoded. `/`, `..` and encoded traversal are refused. Query names and headers are allowlisted, and the method is fixed per route. | Upstream-side authorization bugs | `broker/path-traversal`, `broker/header-smuggle` |
| T-BR-3 | Credential theft through the broker (E1) | The broker injects credentials last and strips client `Authorization`, `Cookie`, `Proxy-Authorization`, `Host` and `X-Forwarded-*`. It filters `Set-Cookie` and auth challenges from responses, and never returns credentials. | An upstream that echoes the credential in a response body. Review checks routes against known echo endpoints. | `broker/credential-echo-filter`, `broker/auth-header-refused` |
| T-BR-4 | Another sandbox's socket or capability is used (E1, E6) | One socket per sandbox, attribution by accepting socket, and a MAC'd capability bound to the invocation, fencing token, sandbox id, profile id and expiry | E6 holds all of a host's live capabilities until they expire | `broker/foreign-capability` |
| T-BR-5 | Redirect or DNS rebinding to a blocked address (E3) | Every resolved address is checked at connect time; link-local, metadata, loopback and cluster CIDRs are always blocked. Redirects are followed only within approved routes, and never for ledger tools (plan.md §11.6). | None | `egress/rebinding`, `egress/redirect-out` |
| T-BR-6 | Upstream TLS interception (E4) | The broker verifies the upstream certificate against the system store or an admin-pinned CA or SPKI | A compromised public CA, which pinning mitigates | `egress/bad-cert` |

### 4.4 Egress proxy and the transport matrix (B6)

| Id | Threat | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-EG-1 | Direct network access bypassing the proxy (E1) | A namespace per sandbox whose only routes lead to its own listeners, no default gateway, no resolver. Host firewall drops raw DNS, UDP, QUIC and ICMP (plan.md §11.6). | None | `egress/no-route`, `egress/udp-dropped` |
| T-EG-2 | Tunnel to a brokered destination to skip credential and route policy (E1) | Modes are exclusive per destination: CONNECT to a brokered destination is refused | None | `egress/tunnel-to-brokered` |
| T-EG-3 | Domain fronting or SNI mismatch through the tunnel (E1) | The ClientHello SNI must equal the approved CONNECT host. Encrypted ClientHello toward a non-approved front domain is refused. | Data exfiltration to an approved destination, which is inherent to granting that destination | `egress/sni-mismatch` |
| T-EG-4 | TCP tunnel abused for another protocol (E1) | Approved `(host, port)` only, with address checks. No protocol inspection, stated in plan.md §11.9. | Anything the approved host accepts on that port. The destination is shown in review as a security delta. | `egress/tcp-only-approved` |
| T-EG-5 | Unsupported transports silently fail open (E1) | The packager fails on a declared unavailable transport, and the runtime has no route for one (plan.md §11.9) | None | `transport/unavailable-fails-fast` |
| T-EG-6 | Broker or proxy reaches the platform (E1 after a broker bug; E7) | Separate processes and network namespace. NetworkPolicy allows approved destinations only, never the Kubernetes API, metadata, Control, Gateway admin or other tenants' brokers. One broker identity per (tenant, pool). | A broker compromise exposes that pool's in-memory credentials | `egress/broker-isolation` |

### 4.5 Tool Host workload identity (B1, B3)

**Design (ADR 013):**
- **Certificate:** each Tool Host has an X.509 workload certificate from the operator's workload CA (for example cert-manager or SPIFFE), lifetime at most 24 hours, with URI SAN `spiffe://<trust-domain>/toolgate/tool-host/<tenantId>/<poolId>/<hostId>`. The Gateway's certificate uses `…/toolgate/gateway/<id>`.
- **Pinning:** Control pins the workload CA bundle and trust domain in configuration, separately from the device enrollment CA. A device certificate can never act as a Tool Host, and a Tool Host certificate can never act as a device.
- **Registration:** `POST tool-hosts/registrations` binds `hostId` and `poolId` to the certificate's SAN. Control refuses a pool that doesn't exist, belongs to another tenant, or isn't a `TOOL_HOST` executor pool.
- **Use:** `permits/consume`, the heartbeat, `secrets/deliver` and `effects/report` require that the caller's SAN `hostId` equals the permit's `aud` and the invocation's `lease_holder`. The Tool Host accepts B1 connections only from the Gateway SAN pattern.

| Id | Threat | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-ID-1 | A device or another workload impersonates a Tool Host (E4, E7) | Separate CA pin and SAN grammar; registration bound to the SAN | Compromise of the workload CA | `identity/device-as-host`, `identity/san-mismatch` |
| T-ID-2 | A stolen Tool Host certificate is used elsewhere (E6) | Lifetime of 24 hours or less. Admin disable of the host takes effect at Control on the next call. Consumption also needs a permit addressed to that host. | The window until expiry or disable | `identity/disabled-host` |
| T-ID-3 | Cross-tenant use of one host identity (E7) | The tenant is in the SAN. Control checks it against the permit's tenant and the pool. | None | `identity/cross-tenant` |

### 4.6 Secret delivery (B3, B5)

**Design (ADR 013):**
- **Handles:** `ToolInput.secrets[]` carries handles, never values. A handle is opaque and bound by Control to `(tenant, invocationId, secret name, tool group, pool)`.
- **Redemption:** the extended `POST access/secrets/deliver` with the Tool Host certificate and the fencing token. Control checks `EXECUTING`, the token, the lease holder, the current epoch and grants, as it does for devices today (`EnterpriseOperations.secretAuthority`).
- **Brokered credentials** are redeemed by the broker and held only in its memory for that invocation.
- **`CREDENTIALS_IN_SANDBOX` tools** redeem through the per-sandbox secret socket. The supervisor performs the Control call and returns the value to that sandbox only. The permission is a security delta at review.
- Each redemption appends to the host spool first (plan.md §12.7 row 7).

| Id | Threat | Mitigation | Residual risk | D3 case |
|---|---|---|---|---|
| T-SC-1 | Redemption outside a live invocation (E5, E6) | Control requires `EXECUTING`, a valid fencing token, a matching lease holder and the current epoch | E6 can redeem for invocations running on that host while they run | `secrets/after-terminal`, `secrets/wrong-host` |
| T-SC-2 | Handle reuse across invocations or tenants (E1) | The handle is bound to its invocation and checked at Control | None | `secrets/handle-replay` |
| T-SC-3 | A brokered credential leaks into the sandbox (E1) | Brokered credentials never cross B4. Only declared `CREDENTIALS_IN_SANDBOX` tools receive values, and those are shown at review. | Tools granted `CREDENTIALS_IN_SANDBOX` can exfiltrate to their approved destinations | `secrets/brokered-not-in-sandbox` |
| T-SC-4 | A user-scoped credential is used for another user (E2) | The broker looks the account up by the **permit's** `userId`, never by argument. Namespace and credential ownership rules apply (plan.md §11.7). | None | `secrets/user-scope` |
| T-SC-5 | Secret values in logs or audit (E1 echoes them) | Values never enter audit, approvals or metrics. Tool log frames are redacted for known secret values before spooling, using the existing [redaction](../audit-and-redaction.md) rules. | Transformed values, such as base64 of a secret, escape exact-match redaction | `secrets/log-redaction` |

## 5. Decisions this model forces

All recorded in ADR 013:
1. Control, not the Gateway, signs permits. A compromised Gateway (E5) holds no key that authorises execution.
2. The Gateway holds no signing or MAC key at all. In governed mode, `requestState` is minted and verified by Control.
3. Tool Host and Gateway identities come from a workload CA pinned separately from device enrollment.
4. There is no Tool Host database role. Every state change goes through Control.

## 6. Accepted residual risks

- A compromised Tool Host node (E6) can act for the invocations running on it until their leases or certificates expire. It can't mint permits, consume another host's permits, or reach other pools' credentials.
- Tools granted tunnel destinations or `CREDENTIALS_IN_SANDBOX` can misuse what they were granted, inside those grants. Review is the control.
- Upstream services are outside ToolGate. ToolGate guarantees at most one execution per ledger invocation, not exactly-once effects (plan.md §11.7).
