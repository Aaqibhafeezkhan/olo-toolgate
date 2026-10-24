# OLO user credentials

ToolGate runs OLO tools for a verified OLO user, not for the OLO worker. The OLO backend exchanges a short-lived
session assertion for a ToolGate user credential, the worker presents that credential to the Gateway, and Control
authorizes the call as that user. Everything here is off by default.

## Flow

1. The OLO backend signs a **session assertion** for a verified user: RS256, header exactly
   `{"alg":"RS256","typ":"olo-session-assertion+jwt","kid":<pinned>}`, claims `iss`, `aud`, `sub` (the OLO user id),
   `iat`, `exp`, at most 300 s.
2. The backend calls `POST /api/control/v1/oauth/token-exchange` (RFC 8693, form encoded) with its own Control
   service JWT carrying the `toolgate-olo-token-exchange` role:
   `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`,
   `subject_token_type=urn:ietf:params:oauth:token-type:jwt`, `subject_token=<assertion>`, optional
   `audience` and `requested_token_type`.
3. Control verifies the assertion against one pinned key and maps `(iss, sub)` to a ToolGate user. An unknown user is
   registered **disabled**; an administrator enables it. Control returns
   `{access_token, issued_token_type, token_type: "Bearer", expires_in}` with `Cache-Control: no-store`. The
   credential (`typ: toolgate-user-credential+jws`) carries `iss`, `aud`, `sub` (ToolGate user id), `tid`, `sep`
   (session epoch), `bid` (execution binding), `iat`, `exp`, `jti`. It is signed with a dedicated key and lives at most
   300 s and never beyond the assertion.
4. The Gateway verifies the credential and builds a `HUMAN` context with `credentialKind: JWT`. Control accepts a
   `HUMAN` submission only from that issuer with a credential digest and no agent, workload or chain, then requires
   the user enabled, the identity binding at the same session epoch, and a team grant.
5. When OLO revokes a session it calls `POST /api/control/v1/oauth/sessions/revoke` with `{"subject":"<OLO user id>"}`.
   Control bumps the identity binding's session epoch, so outstanding credentials stop at the next authorization, and
   refuses assertions issued before the revocation.

Refusals use RFC 6749 errors (`invalid_grant`, `invalid_request`, `invalid_target`, `unsupported_grant_type`). A
disabled user is `403`.

## Delegated calls (path B)

An agent acting for the run's user sends the user's credential as the bearer and its own workload token in
`X-ToolGate-Actor-Credential`. The agent's workload binding is an **on-behalf-of** binding: `DELEGATED`, with neither
`delegatedUserId` nor `delegatedSessionEpoch`. The Gateway builds a `DELEGATED` context with the user and session from the
verified user credential, and the agent, workload and credential epoch from the workload token. Control checks the workload token's
digest against the binding and accepts on-behalf-of contexts only from the configured user-credential issuer. The evaluator
requires the user's team grant, a delegation, the agent's capability and the user's current session epoch, so a tool the
agent may use but the user may not is denied.

An on-behalf-of token never works alone, and a duplicated or malformed actor header is refused rather than treated as a
`HUMAN` call.

## Audit evidence

Invocation audit rows (`INVOCATION_SUBMIT`, `PERMIT_RESERVE`, `PERMIT_CONSUME`, `EFFECT_*`, watchdog and cancel rows) carry
`evidence` (`ControlAuditEvidence`): mode, subject user, actor agent, delegation chain, workload binding, tool, operation,
resources (or only their count and digest beyond 16), decision, state, authorization epoch and policy revision. Arguments,
payloads and credentials are never recorded.

## Resources and permissions (contract 2.3)

A package tool can declare its single permission and the resources each call acts on (`@Authorization` in Java,
`@authorization` in Python). Its Control tool then has exactly that action (for example `memory.read`), and its extractor
builds `CUSTOM` resources from the declared templates, such as `olo://{tenant}/conversation/{conversationId}`: `{tenant}` is the
verified tenant, never an argument, and each id must be one opaque path segment. Deployment refuses templates that name an
argument that isn't a required string. Grants then use exact actions and a `PREFIX` locator such as
`olo://<tenant>/conversation`; ownership, membership and sharing of each object stay with the memory or RAG service. Tools
that declare nothing keep `invoke` on `tool://<toolId>`.

## Replays, unknown outcomes and approvals (M5.3–M5.5)

- **Dedup key.** The Gateway names an invocation by tenant, mode, subject, agent and delegation chain plus the caller's key
  (the JSON-RPC `id`, or `Idempotency-Key`). Renewing or rotating a credential keeps the key; Control refreshes the stored
  session and credential epochs and decides again, so a revoked session or retired credential never resumes.
- **Fingerprint.** The same key with different arguments, tool, resources or downstream idempotency key is `409 CONFLICT`
  and never runs. Concurrent submissions of one key create one invocation.
- **Ended invocations.** A replayed `tools/call` whose invocation ended returns its state (`OUTCOME_UNKNOWN`, `FAILED`,
  `CANCELLED`, `EXPIRED`, `PARTIAL`) as an error result and is never dispatched again. `OUTCOME_UNKNOWN` goes to
  reconciliation.
- **Reconciliation method.** Every tool with side effects (`IDEMPOTENT`, `NON_IDEMPOTENT`) declares how an unknown outcome is
  settled: `DOWNSTREAM_IDEMPOTENCY` (needs `@Idempotency` or an idempotent effect), `STATUS_LOOKUP` (needs a `@Reconcile` tool)
  or `MANUAL`. SDK builds and deployment refuse a missing or inconsistent declaration; read-only tools declare none, and an
  undeclared `UNKNOWN` effect is `MANUAL`. Each invocation records the method. Replaying the key of an `OUTCOME_UNKNOWN`
  `DOWNSTREAM_IDEMPOTENCY` call queues it again under the same invocation and arguments, so the target deduplicates;
  `STATUS_LOOKUP` and `MANUAL` calls stay unknown, and the reply names the method.
- **Approvals.** `toolgate.control.approval.expiry-ms` (at most 7 days; OLO uses 604800000) bounds how long an operation
  waits. Approval moves the original invocation to `QUEUED` with a fresh dispatch window, and replaying its key runs it.

## Control settings

| Property | Default | Meaning |
|---|---|---|
| `toolgate.control.user-credential.enabled` | `false` | Turns on the exchange and Gateway `HUMAN` submissions |
| `toolgate.control.user-credential.private-key-path` | | PKCS#8 RSA key (2048, 3072 or 4096 bits) used only for user credentials; Control refuses the effect key |
| `toolgate.control.user-credential.key-id` | | `kid` the Gateway pins |
| `toolgate.control.user-credential.issuer` | `toolgate-control` | Credential `iss` |
| `toolgate.control.user-credential.audience` | `toolgate-gateway` | Credential `aud` |
| `toolgate.control.user-credential.lifetime-seconds` | `300` | 1 to 300 |
| `toolgate.control.user-credential.binding-id` | | Execution binding the credentials use |
| `toolgate.control.user-credential.subject.issuer` | | Assertion `iss` (the OLO backend) |
| `toolgate.control.user-credential.subject.audience` | | Assertion `aud` |
| `toolgate.control.user-credential.subject.key-id` | | Assertion `kid` |
| `toolgate.control.user-credential.subject.public-key-path` | | The backend's SPKI PEM public key, never fetched from a URL |
| `toolgate.control.olo.reserved-agents` | empty | Comma-separated agents that act only as `SERVICE` (the OLO worker's own principal); never a delegated agent or chain hop |
| `toolgate.control.olo.protected-tool-groups` | empty | Comma-separated tool groups whose grants and delegations must name actions and must not use `match: ANY` |

## Gateway settings

```json
"userCredentials": {
  "issuer": "toolgate-control",
  "audience": "toolgate-gateway",
  "keys": [{"keyId": "user-credential-1", "publicKeyPath": "/run/secrets/user-credential-1.pem"}]
}
```

Up to eight keys, selected by exact `kid`, allow rotation. Without `userCredentials`, three-part bearers are not
accepted and `HUMAN` stays impossible; static workload credentials are unchanged.

## In the tool

The Tool Host passes the principal Control signed into the permit as the reserved `toolgate` claim; pass-through
claims cannot use that name. The Java SDK lifts it into `Invocation`: `tenantId()`, `userId()`, `agentId()`,
`delegationChain()`, `operation()` and `resources()`; the Python SDK has the same accessors (`tenant_id`, `require_user()`, ...). A tool that acts on a user's data calls
`invocation.requireUser()`, which refuses with `CLAIMS_UNAVAILABLE` when there is no verified user, including every
standalone run.

See the [threat model](../security/threat-models/olo-integration.md) for the residual risks.

## Running invocations and downstream services

Control persists the verified Gateway credential deadline separately from invocation/approval lifetime. A verified
renewal can extend that non-secret deadline without changing the dedup key or fingerprint. All effect freshness
checks reject expired credential authority. The OLO backend limits its credentials to 15 seconds; downstream service
permits are capped by the same deadline, so successive permits cannot extend stale authority beyond that lifetime.
See [OLO service permits](olo-service-permits.md) for the executor-to-service bridge, authenticated revocation snapshots,
key discovery, transaction/reconciliation rules, new-install network policy and cancellation extension.
