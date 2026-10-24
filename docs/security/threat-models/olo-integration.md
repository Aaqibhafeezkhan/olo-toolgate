# Threat model: OLO platform integration

**Status: proposed, for review; partly implemented.** This model describes ToolGate's side of the OLO integration
contracts (olo-labs/olo-mono `docs/toolgate-integration/m2`). User credentials, the reserved service principal,
wildcard refusal and the executor principal are implemented (see
[OLO user credentials](../../control-plane/olo-user-credentials.md)). Section 5 lists what is still missing.

## Purpose

OLO is an AI orchestration platform that uses ToolGate as its authority for three kinds of calls:

| Path | What | Subject | Mode |
|---|---|---|---|
| A | Conversation memory and RAG platform operations, run deterministically by OLO | The signed-in OLO user | `HUMAN` |
| B | Business tools selected by a model | The user, with an OLO agent as delegated actor | `DELEGATED` |
| C | Scheduled indexing and maintenance | A reserved platform service principal | `SERVICE` |

Memory and RAG data live in separate OLO services (`olo-memory`, `olo-rag`) behind ToolGate executors. ToolGate
governs access to them; it is not their storage engine.

This model extends the platform [threat model](../threat-model.md) and the
[Tool Host threat model](tool-host.md), and does not repeat them.

## 1. System and trust boundaries

```text
 OLO backend ──(O1)──► ToolGate token endpoint            OLO runtime ──(O2)──► Gateway ──► Control
 (verified user)        user-bound credential             (Temporal worker)    MCP /mcp      decisions, permits
                                                                                   │
                                                                                   ▼
                                                          executor (Tool Host) ──(O3)──► olo-memory / olo-rag ──► DB
                                                          olo.memory / olo.rag             verifies service permit,
                                                          packages                          consumes it at commit
```

| Boundary | Between | What crosses it | Trust |
|---|---|---|---|
| O1 | OLO backend → ToolGate credential issuance | The verified user's session assertion, out; a short-lived user-bound token, back | ToolGate trusts only the OLO backend's workload identity to request tokens, and only for tenants it serves |
| O2 | OLO runtime → Gateway | User-bound, delegated or service credentials; MCP requests | The OLO runtime is **not** trusted to assert identity: identity comes only from a credential ToolGate verified |
| O3 | Executor → OLO service | Requests with a ToolGate service permit | The service trusts Control's signature and Control's consumption decision, not the executor's word |

## 2. Assets

- **A1 User data:** conversation history, documents, chunks and embeddings in the OLO services
- **A2 Identity:** user-bound credentials, the platform service credential, `sessionEpoch` and `authorizationEpoch`
- **A3 Execution authority:** effect permits and service permits, nonces, consumption records
- **A4 Effect integrity:** at most one effect per logical operation, and honest `OUTCOME_UNKNOWN`
- **A5 Isolation:** no cross-tenant or cross-user access to A1
- **A6 Audit:** subject, actor, resource, operation, decision and policy version for every governed call

## 3. Adversaries

| Id | Adversary | Capability assumed |
|---|---|---|
| E-O1 | Prompt-injected OLO agent | Chooses tools and arguments within its catalog; may put any user or resource id into arguments |
| E-O2 | Malicious OLO workflow author | Edits workflow definitions, including the agent name an AGENT node uses |
| E-O3 | Compromised OLO worker | Holds in-memory user-bound tokens for in-flight runs, and the worker's own workload identity. **No token-issuance authority.** |
| E-O4 | Compromised or buggy executor | Holds a valid executor identity; may send any scope or body to an OLO service |
| E-O5 | User whose access was revoked | Had valid credentials and context moments ago |
| E-O6 | Network attacker inside the platform | Can reach service IPs. Can't break TLS. |

## 4. Threats and mitigations

### 4.1 Identity (O1, O2)

| Id | Threat (adversary) | Mitigation | Residual risk | Conformance case |
|---|---|---|---|---|
| T-OI-1 | The OLO runtime asserts an arbitrary user (E-O3) | The Gateway accepts `HUMAN` mode only from a user-bound credential minted at O1. A `userId` in arguments, headers or the body is never identity. | Tokens already in a compromised worker's memory, until they expire (5 min or less) | `olo/forged-user-argument` |
| T-OI-2 | The worker mints user tokens (E-O3) | Only the OLO backend's workload identity may call the token endpoint; the worker's identity is refused | Compromise of the OLO backend | `olo/worker-cannot-mint` |
| T-OI-3 | A token is used for another tenant, session or audience (E-O3, E-O6) | The token binds `aud` (the Gateway), `tenantId` and `sessionEpoch`. A session revocation bumps the epoch, and older tokens are refused. | Use within the lifetime, before revocation | `olo/token-wrong-tenant`, `olo/token-old-session` |
| T-OI-4 | A workflow author selects a privileged agent identity (E-O2) | Path B actors are limited to agents mapped to the tenant and workflow in OLO's server-side mapping. The path C principal is reserved, flagged as non-delegable, and refused as an actor. | Mapping misconfiguration, which needs admin review | `olo/service-principal-as-actor` |
| T-OI-5 | Agent capability used to exceed the user's own (E-O1) | Delegated execution requires both the user's grant and the agent's capability. Agent-only access is denied (enterprise access control, test row 35). | None beyond grant misconfiguration | `olo/agent-without-user-grant` |
| T-OI-6 | Silent fallback to the service principal when a user session expires (E-O3) | User-scoped operations fail or pause for re-authentication. Control refuses a `SERVICE` credential on a resource or operation granted only for `HUMAN` purpose. | None | `olo/no-service-fallback` |

### 4.2 Resource authorization

| Id | Threat (adversary) | Mitigation | Residual risk | Conformance case |
|---|---|---|---|---|
| T-OR-1 | A substituted conversation or document id (E-O1) | The `olo.memory` and `olo.rag` resource extractors build `CUSTOM` locators `olo://{tenant}/…` from arguments. Control checks tenant and kind. The service checks ownership and sharing against the permit's `sub`. | None, because both checks must allow | `olo/substituted-resource` |
| T-OR-2 | A cross-tenant locator (E-O1) | The locator's tenant segment must equal the verified tenant. Control refuses before lookup, and the service refuses again. | None | `olo/cross-tenant-locator` |
| T-OR-3 | Wildcard grants widen access (E-O2 via a tenant admin) | `match: ANY`, `allActions` and wildcard actions are refused for the OLO packages | Over-broad PREFIX rules, which need admin review | `olo/wildcard-grant-refused` |
| T-OR-4 | Query permission used to delete (E-O1) | Each operation is a separate action, with no implication between them | None | `olo/query-cannot-delete` |

### 4.3 Service permits (O3)

| Id | Threat (adversary) | Mitigation | Residual risk | Conformance case |
|---|---|---|---|---|
| T-OS-1 | An executor sends a scope it wasn't granted (E-O4) | A Control-signed service permit (RS256, `typ: olo-service-permit+jws`) binds `aud`, `tenantId`, `sub`, `operation`, `resources`, `requestFingerprint` and `exp`. The service verifies every claim against the request it received. | None | `olo/permit-scope-mismatch` |
| T-OS-2 | The body is swapped after the permit was issued (E-O4) | `requestFingerprint` is the sha256 of the canonical body, and the service recomputes it | None | `olo/permit-body-mismatch` |
| T-OS-3 | A permit is replayed to another service (E-O4, E-O6) | `aud` names one service | None | `olo/permit-wrong-audience` |
| T-OS-4 | A permit is used after revocation (E-O5) | Writes: the service consumes the nonce at Control inside its transaction, before commit; Control's `fresh()` re-checks `authorizationEpoch` and re-decides. Reads: permit lifetime of 15 s or less, plus a revocation feed, and permits older than the latest epoch are refused. | Reads within at most the revocation bound (30 s) | `olo/revoked-before-commit`, `olo/read-after-epoch-bump` |
| T-OS-5 | A permit is held across a queue, an approval or a long job (E-O5) | Permits are issued at dispatch only. Long jobs consume per chunk, and a revocation stops the next chunk. | None | `olo/revoked-while-queued`, `olo/revoked-mid-index` |
| T-OS-6 | A service is reached without ToolGate (E-O6, E-O3) | Services accept only mTLS from executor identities, and NetworkPolicy blocks the OLO worker and backend. A permit is required even with a valid mTLS identity. | Compromise of the executor's CA | `olo/direct-service-call` |

### 4.4 Effect integrity

| Id | Threat (adversary) | Mitigation | Residual risk | Conformance case |
|---|---|---|---|---|
| T-OE-1 | Changed arguments under an existing operation id (E-O3) | Dedup is looked up by tenant + subject + logical-operation id, with the request fingerprint stored. A mismatch is a conflict and never executes. | None | `olo/same-id-new-arguments` |
| T-OE-2 | Concurrent duplicate submissions (E-O3) | An atomic create on the dedup key | None | `olo/concurrent-submit` |
| T-OE-3 | Blind retry after an unknown outcome | `OUTCOME_UNKNOWN` is never redispatched automatically. The executor reconciles through `downstreamIdempotencyKey` or a status lookup. | Operator decisions for `manual` executors | `olo/unknown-not-redispatched` |
| T-OE-4 | Approval resumes a different operation | The approval binds `invocationId`, `requestDigest` and `authorizationEpoch` (existing `EnterpriseApproval`). Resume re-authorizes at dispatch. | None | `olo/approval-binding` |

### 4.5 Audit

| Id | Threat | Mitigation | Residual risk | Conformance case |
|---|---|---|---|---|
| T-OA-1 | An effect can't be attributed to a user and actor | Audit records hold subject, actor, workload, resource, operation, decision, policy version and invocation correlation as separate fields | None once implemented | `olo/audit-fields` |

## 5. Gaps against current code

Each item is evidence from the source, and none is a design claim. Details are in olo-mono
`docs/toolgate-integration/m2/gap-matrix.md`.

| Needed by | Gap | Current code |
|---|---|---|
| T-OI-1, T-OI-3 | ~~No user-bound Gateway credential~~ **Closed** | The Gateway verifies Control-issued `toolgate-user-credential+jws` (pinned kid, RS256, at most 300 s) as a `HUMAN` `JWT` context (`apps/gateway/src/user_credential.rs`). Control accepts `HUMAN` submissions only with that issuer and a credential digest, then checks the identity binding's session epoch. |
| T-OI-2 | ~~No token-issuance endpoint for OLO~~ **Closed** | `POST /api/control/v1/oauth/token-exchange` (RFC 8693) and `/oauth/sessions/revoke`, role `toolgate-olo-token-exchange` (`UserCredentialResource`). The worker has no such role. |
| T-OI-4 | ~~No reserved, non-delegable service principal~~ **Closed** | `toolgate.control.olo.reserved-agents`: such agents have only `SERVICE` workload bindings, and `DELEGATED` submissions naming them as agent or chain hop are refused (`OloProtections`). |
| T-OR-1 | ~~Resource extraction for package tools~~ **Closed** | Package tools declare an action and locator templates; Control builds `olo://{verified tenant}/…` resources from opaque-id arguments, so a substituted id stays in its tenant and kind, and the service checks ownership. |
| T-OR-3 | ~~Wildcard grants~~ **Closed** | `toolgate.control.olo.protected-tool-groups` refuses `allActions` and `match: ANY` in grants and delegations reaching those groups, including through the tool-group wildcard. |
| T-OS-1 – T-OS-5 | **Implemented and locally qualified (M4.5–M4.6)** | Control-derived downstream permits, Tool Host key-bound mTLS, service request verification, single-use online write consumption, authenticated tenant epoch snapshots, and credential-deadline caps. See [service bridge qualification](../../control-plane/olo-service-permits.md). Application service qualification remains M6/M8. |
| T-OE-1, T-OE-2 | ~~Conflict on a fingerprint mismatch, and atomic create~~ **Closed** | The dedup key is tenant, mode, subject, agent and chain plus the caller's key; credential digests, epochs and expiry are not part of it, nor of the request fingerprint, so renewal and rotation replay the same invocation, with the current epochs rechecked. A changed fingerprint is a 409 and never runs; concurrent submissions insert one invocation and the others get it. Invocations are never purged, which exceeds the 37-day minimum; a future purge must keep tombstones. |
| T-OA-1 | ~~Audit fields~~ **Closed** | Invocation audit rows carry `ControlAuditEvidence` (subject, actor, chain, workload, tool, operation, resources, decision, state, authorization epoch, policy revision), and the Tool Host admission record the same identity facts. |
| Executor context | ~~The SDK `Invocation` has no tenant or resource~~ **Closed** | The Tool Host passes the permit's principal as the reserved `toolgate` claim. The Java and Python SDKs expose tenant, user, agent, chain, operation and resources, and `requireUser()` / `require_user()` refuse without a verified user, including standalone runs. The session epoch stays with Control. |
| Delegated users | ~~Fixed-user delegated bindings~~ **Closed** | On-behalf-of bindings: the user credential plus the agent's workload token (`X-ToolGate-Actor-Credential`) form a `DELEGATED` context, accepted only from the trusted issuer, and still need both the user's and the agent's grants. |

Residual risk added by user credentials: a compromised Gateway can present any `HUMAN` context for its tenant.
Control still requires the configured issuer, an enabled user, a current identity binding and team grants. A
registry of issued credential digests would remove the residual and is a follow-up.

## 6. Conformance

The `olo/*` cases above are to be written with the implementation. They run against a real Gateway, Control, Tool
Host and stub OLO services, and are listed in the OLO plan's M3, M4 and M8 acceptance criteria.
