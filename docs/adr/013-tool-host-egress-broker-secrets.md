<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 013: Tool Host, permits, egress broker, secrets and transports

**Status:** Accepted (design gate D2), on merge of the PR that adds this file. Supersedes the Gateway permit-signing parts of ADR [006](006-ask-approvals.md).

## Context

Server tools (SDK, `OPENAPI`, `MCP_PROXY` and `CONTAINER` packages, ADR [014](014-tool-package-format-v2.md)) need an execution path outside the endpoint client. The path must run untrusted package code, reach upstream services with tenant credentials, and keep today's invariant: every effect needs current Control authority, and there is no offline ALLOW (plan.md §11).

ADR 006 had the Gateway sign execution permits with its own private key. The enterprise cutover since moved permit signing into Control (`RsaEffectSigner`, `typ: toolgate-effect-permit+jws`), and the Gateway evaluates no policy. Plan revision 10 (decision A) keeps it that way for server tools.

The [Tool Host threat model](../security/threat-models/tool-host.md) lists the threats this ADR answers. Its decisions are numbered here.

## Decision

1. **Tool Host.** `apps/tool-host` is a stateless Rust supervisor, horizontally scaled, registered as system executor `TOOL_HOST`. Admins group hosts into per-tenant pools (Device Groups). A host runs the SDK harness, `OPENAPI`, `MCP_PROXY` and `CONTAINER` executors, and admits work as in plan.md §11.3. It has **no database role**: every state change goes through Control ([invocation-state-v2.md](../control-plane/invocation-state-v2.md) §3).
2. **Control signs permits.**
   - Permits are compact JWS RS256 with protected header `{alg, typ: "toolgate-invocation-permit+jws", kid}` over `InvocationPermitClaims` (`permit.schema.json`).
   - They are signed in Control's existing **effect-signing key domain** (`toolgate.control.effect.*`, the key behind `RsaEffectSigner`). Verifiers distinguish v2 permits from enterprise v1 permits by `typ`.
   - `kid` rotation: verifiers hold the current key and at most one previous key.
   - `exp - iat` is at most 10 s, the existing cap. The permit is valid for admission only, never for the run.
   - Permits are minted only by `POST access/invocations/reserve`. The Gateway holds **no** permit key.
   - This supersedes ADR 006's "dedicated external Gateway private key" and `toolgate-execution-permit+jws`, which no code path uses any more. ADR 005 (Control-signed policy bundles that the Gateway verifies) is unchanged. The implementation plan's mention of "Gateway-signing parts of ADR 005" is corrected here.
3. **Workload identity.**
   - Tool Hosts and Gateways authenticate with X.509 workload certificates from the operator's workload CA, lifetime at most 24 hours. The URI SANs are `spiffe://<trust-domain>/toolgate/tool-host/<tenantId>/<poolId>/<hostId>` and `…/toolgate/gateway/<id>`.
   - Control pins that CA and trust domain separately from the device enrollment CA. Neither kind of certificate can act as the other.
   - `POST tool-hosts/registrations` binds host and pool to the SAN. `permits/consume`, the heartbeat, `secrets/deliver` and `effects/report` require the caller's host id to equal the permit's `aud` and the invocation's lease holder.
   - The Tool Host accepts dispatch only from the Gateway SAN pattern.
4. **Sandbox and isolation.**
   - `OCI_SANDBOX` (the ADR [009](009-managed-local-runtime-sandbox.md) boundary) is the release-1 tier for server tools, and there is never a fallback to a weaker tier.
   - Sandboxes are single-use by default. Reuse is opt-in for `stateless` tools only, within one tenant and identity class (plan.md §11.5).
   - Pools are never shared across tenants.
5. **Egress** (plan.md §11.6):
   - one network namespace per sandbox, whose only routes reach its own listeners;
   - a host firewall that drops raw DNS, other UDP, QUIC and ICMP;
   - two exclusive modes per approved destination. **Brokered** means the broker does TLS and injects credentials. **Tunnel** means CONNECT, the sandbox does TLS end to end, and the SNI must equal the CONNECT host;
   - address checks at connect time and on every redirect;
   - broker and proxy in their own namespace, which can't reach the platform.
6. **Broker request channel** (plan.md §11.8):
   - a per-sandbox Unix socket and a MAC'd capability bound to the invocation, fencing token, sandbox, service profile and expiry;
   - the sandbox selects a route id and never sends a URL;
   - the broker loads the service profile by the permit's digest, strips client auth headers and injects credentials last;
   - it never retries for `NON_IDEMPOTENT` or `UNKNOWN` tools;
   - every decision is audited without bodies or credentials.
7. **Transport matrix** (plan.md §11.9): release 1 supports brokered HTTP(S), HTTP(S) through a CONNECT tunnel, and TCP through a CONNECT tunnel to an approved `(host, port)`. Everything else is **unavailable**, and the packager or enable step says so. Brokered database adapters, gRPC routes and client egress are deferred to a later ADR.
8. **Secrets.**
   - `ToolInput` carries handles bound by Control to `(tenant, invocation, name, tool group, pool)`.
   - Handles are redeemed through the extended `POST access/secrets/deliver` with the Tool Host certificate and the fencing token, under the same `EXECUTING` and grant checks devices get today.
   - Brokered credentials stay in broker memory for one invocation.
   - Only tools approved for `CREDENTIALS_IN_SANDBOX`, a security delta, receive values, through the per-sandbox secret socket.
   - Values are redacted from tool log frames before spooling.
9. **`requestState` in governed mode is minted by Control.**
   - **Where it comes from:** for an ASK outcome to an MRTR client, Control returns `requestState` in `InvocationAdmission`. The Gateway returns it unchanged in the retry's `InvocationSubmission.requestState`, and Control verifies it in the transaction that consumes the approval.
   - **Format:** compact JWS HS256 under a dedicated Control key (`toolgate.control.request-state.key`, at least 256 bits, with `kid`, current plus previous). The payload is `{toolId, argumentsDigest, ownerDigest, approvalId, exp, nonce}`, with `exp` at most the approval's expiry and at most 10 minutes. The nonce is single-use.
   - **Who holds keys:** the Gateway holds no signing or MAC key of any kind. Standalone SDK runtimes keep their per-process key (adapter A3, `tool-sdk/spec/protocol-layer.md`).
   - **Supersedes:** this replaces plan.md §5.10's "Gateway key in governed mode", which contradicted decision 2.

## Alternatives Considered

- **Gateway signs permits (ADR 006 as written).** Rejected: a compromised Gateway replica would hold execution authority. With Control signing, the Gateway holds nothing that authorises an effect.
- **Tool Host with a database role.** Rejected (plan.md §12.8): it would bypass Control's invariants and tenant lock.
- **Device enrollment CA for Tool Hosts.** Rejected: a stolen device certificate must never act as a server executor, and server identities come from the platform's workload CA, not from browser enrollment.
- **Transparent egress interception.** Rejected: an interception CA in every sandbox, and no guarantee for clients that pin certificates. Explicit brokered and tunnel modes are auditable and honest about what is inspected.
- **A new kill or permit key per feature.** Rejected for permits: the effect domain already exists, is rotated, and `typ` separates the formats. A new domain is used only for `requestState` (symmetric) and break-glass operators (ADR [017](017-kill-switch.md)), where reuse would mix purposes.

## Security Impact

- Execution authority stays in Control. Gateway compromise yields no permit or `requestState` key.
- Every admission is bound to one host (`aud`), one attempt (`jti`), one nonce (the fencing token) and exact digests, including the service profile and deployment binding.
- Untrusted code reaches the network only through a broker or proxy that enforces approved destinations outside the sandbox.
- Credentials never enter the sandbox unless review approved `CREDENTIALS_IN_SANDBOX`.
- Residual risks are listed in the threat model §6: node compromise within its lease and certificate lifetime, and misuse within granted destinations.

## Operational Impact

Operators deploy Tool Host pools, a workload CA (cert-manager or SPIFFE), and NetworkPolicy for the broker and proxy. Control must be sized for 2–3 round trips per server-tool call, as today's calls make (plan.md §12.8). New configuration: the workload CA pin, the effect key `kid` set, and the `requestState` key. Startup fails if the `requestState` key equals any other configured key.

## Compatibility Impact

Additive. Enterprise v1 permits (`toolgate-effect-permit+jws`) and device flows are unchanged. The v2 permit is a new `typ`. ADR 006's Gateway signing text is superseded on paper; no running code depends on it. Endpoint clients are unaffected in release 1.

## Consequences

- Positive: one place (Control) mints all execution authority. The threat model's admission threats close at Control and at the host.
- Positive: the transport matrix makes unsupported network use fail at packaging, not at runtime.
- Negative: Control is on the hot path of every server-tool call, and its availability bounds the platform's.
- Negative: tools that need unmodified database drivers can't run in release 1.

## Validation

- Threat model reviewed by security at gate D2. Every threat has a named conformance case, authored at D3.
- Admission cases: forged permit, wrong `aud`, replay on the same and another host, argument and digest swaps.
- Egress and broker cases: no route, UDP dropped, tunnel to a brokered destination, SNI mismatch, rebinding, redirect out, path traversal, auth header refused, credential echo filtered.
- Identity and secret cases: device as host, SAN mismatch, disabled host, redemption after a terminal state or from another host, handle replay.
- `requestState` cases: tampering, expiry, another identity, other arguments, replay (plan.md §21).
