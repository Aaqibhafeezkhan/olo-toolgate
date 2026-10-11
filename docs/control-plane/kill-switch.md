# Emergency kill switch

## Purpose

This is the reviewed specification of the emergency kill switch (plan.md §15) behind design gate D2. It covers storage, the authorization-epoch bump, signing, the push protocol, break-glass with 2-of-3 operator keys, recovery and retention.

It is normative. A change after D2 passes needs an amendment to ADR 017. The event shapes are in `packages/contracts/schemas/v2/kill.schema.json`, the proposed endpoints are in `packages/contracts/openapi/proposed/control-v1.proposed.yaml`, and the storage is [V19__kill_switch.sql](migrations-v2/flyway/V19__kill_switch.sql) and [V12.sql](migrations-v2/quickstart/V12.sql).

## 1. Model

- **Events, never edits.** The kill state of a tenant is a grow-only set of immutable signed `KillEvent` and `RecoveryEvent` records. Nothing is updated or deleted. Database triggers refuse it, and the runtime role has no `UPDATE` or `DELETE` grant on these tables.
- **Effective kills:** every `KillEvent` that no `RecoveryEvent` supersedes. When several effective kills cover the same target, `TERMINATE` beats `STOP_NEW`.
- **Merge rule** for every component (Control, Gateway, Tool Host, endpoint client): its state is the union of every valid event it has received from any path, except that a full fetch replaces it (§8). Order and replays don't matter. A replayed or reordered push can add a kill back, which fails closed, but it can never remove one. Only a recovery event the component has seen can do that.
- **Issuers:** `control` for reviewed kills and every recovery, and `control-breakglass` for break-glass kills (§6). Each issuer has its own counter, and `eventId = "<issuer>:<counter>"` is never reused.

### 1.1 Scope matching

| `scope.kind` | Matches an invocation or tool when | `value` |
|---|---|---|
| `PACKAGE` | its package id equals `value` | `PackageId` |
| `PACKAGE_DIGEST` | its package digest equals `value` | `sha256:` digest |
| `PUBLISHER` | the enabled package version's manifest `publisher` equals `value` | publisher id |
| `TOOL` | its `toolId` equals `value` | `ToolId` |
| `POOL` | it runs, or would run, in that execution pool | pool id |
| `RUNTIME_IMAGE` | its runtime digest equals `value` | `sha256:` digest |
| `TENANT` | it is any non-built-in tool | absent |

Built-in tools are never killed: they are how operators inspect and recover.

## 2. Storage

| Table | Holds | Writes |
|---|---|---|
| `control_kill_events` | Every kill and recovery event: issuer, counter, kind, scope, mode, the epoch it created, and the signed document | Insert only |
| `control_kill_supersessions` | One row per (recovery, kill it supersedes). A kill is effective while it has no row here. | Insert only. A trigger checks that the left side is a kill and the right side a recovery. |
| `control_kill_archive` | Superseded kills and applied recoveries that have left the push set (§8) | Insert only, and only for superseded events |
| `control_kill_acks` | Per component, the highest epoch it acknowledged and the path that delivered it | Upsert; the epoch never decreases |

**Counter allocation:** inside the tenant transaction, `max(issuer_counter) + 1` for the issuer. The tenant advisory lock serializes it, and `UNIQUE (tenant_id, issuer, issuer_counter)` is the backstop. Archived events stay in the table, so a counter is never reused.

## 3. Committing an event and bumping the epoch

A kill (`POST kill-events`), a break-glass kill (`POST break-glass/kills`) and a recovery (`POST kill-events/recoveries`) each commit in **one** Control tenant transaction:

1. Authorize the caller (§5, §6, §7).
2. Allocate the event id (§2) and build the event. `issuedAtUnixMs` comes from Control's monotonic approval clock (`control_approval_clocks`), so backward time fails closed.
3. Sign it (§4).
4. Insert the event, plus supersession rows for a recovery.
5. **Bump the epoch.** Increment the tenant revision through the existing directory save. That raises `control_tenants.authorization_epoch` and writes a `control_authorization_outbox` row, exactly as a grant change does today (`PostgresStore.java`, directory save). The new epoch is stored on the event row.
6. **Apply to stored work** (plan.md §15.5), for invocations the kill matches (§1.1):
   - `PENDING_APPROVAL`, `QUEUED`, and `INPUT_REQUIRED` tasks → `CANCELLED`;
   - `TERMINATE` only: `RESERVED`, `DISPATCHED` and `EXECUTING` → `CANCEL_REQUESTED`, and their fencing tokens become invalid ([invocation-state-v2.md](invocation-state-v2.md) §5).

   The `control_invocation_active_package` and `control_invocation_active_tool` indexes serve this step.
7. Write audit and the idempotent response.

**Why this is enough for new work.** Every reserve, permit consumption, secret delivery, heartbeat and effect report already checks the tenant's current epoch, and re-evaluates against the kill set when it moved. So a committed kill stops all new work at once, and there is no Gateway kill list that could go stale (plan.md §15.3). Running work is reached by §5.

## 4. Signing

- **Key:** Control's fleet signing key domain, the key endpoint clients already pin for signed fleet documents (`RsaFleetCrypto`). No new key needs to be distributed to clients. Tool Hosts pin the same public key at registration (ADR 013).
- **Types:** kill and recovery events use the protected header `{alg: RS256, typ: "toolgate-kill-event+jws", kid}`, and pushes use `typ: "toolgate-kill-push+jws"`. Verifiers accept only these `typ` values for kill semantics, so a fleet document signature can never be replayed as a kill, or the other way round.
- **Payload:** the JCS bytes of the event or push without its `signature` member. The `signature` member is the compact JWS with that payload attached. A verifier decodes the payload and requires it to equal the JCS of the members it received; any difference is a rejection.
- **Rotation:** `kid` names the key. Verifiers hold the current key and at most one previous key, as for fleet documents.

## 5. Delivery to running work

**Tool Hosts.** Control sends `KillPush {tenantId, authorizationEpoch, kills[], recoveries[], signature}` over the host's mTLS control stream, at high priority, whenever the epoch moves because of an event.
- `kills` holds every effective kill. `recoveries` holds the recoveries not yet archived (§8).
- The host verifies the push, merges it (§1), acts on it (plan.md §15.5), and replies `KILL_ACK {authorizationEpoch}`. Control upserts `control_kill_acks` with `delivery_path = PUSH`.
- A host that connects, or sees an epoch it hasn't acknowledged on any Control response, fetches the full state with `GET kill-events` (`KillEventPage`) before admitting work, and acknowledges with `delivery_path = POLL`.

**Endpoint clients.** Release-1 clients have no push parser (plan.md §17.2, Phase A). They are covered by Control's own checks: a client can't consume a permit, redeem a secret or report an effect once the epoch moved past a kill that covers it, so running client work stops at its next contact with Control. Phase B clients receive `KillPush` on the socket or with the next check-in, and acknowledge with `delivery_path = CHECK_IN`.

**Gateways** need no push. They read the effective kills with each catalog revision, to hide killed tools, and every new call is refused by Control in any case. The ack row with `component_kind = GATEWAY` records the revision they serve, for operators.

**Components that can't be reached** can't start new work, because every start needs Control. Work already running there continues until its own deadline, bounded by the 15-minute task ceiling. Pools that need stricter behaviour set `selfTerminateOnControlLoss`: a host that hasn't heard from Control within the window terminates its non-built-in sandboxes (plan.md §15.5).

## 6. Break-glass

Break-glass adds kills without the maker/checker review normal changes need. It is for when the review workflow, or the admin identity provider, is unavailable or too slow.

- **Endpoint:** `POST break-glass/kills` on a separate listener that requires an **operator client certificate** (`operatorCertificate`). No administrative bearer token is needed.
- **Operator keys:** exactly three RSA public keys configured externally (`toolgate.control.breakglass.operator-keys`, each with a key id). They are a separate trust domain, reused for nothing else. Control refuses to start if one equals any other configured key.
- **Request:** `BreakGlassRequest {scope, mode, reason, operatorSignatures[2..3]}`. Each signature is a compact JWS with `{alg: RS256, typ: "toolgate-breakglass-approval+jws", kid}` over the JCS of `{tenantId, scope, mode, reason, requestId, notAfterUnixMs}`, where:
  - `requestId` is the request's `Idempotency-Key`;
  - `notAfterUnixMs` is at most 10 minutes after the earliest signature's issue time.
- **Checks:** at least two **distinct** configured `kid`s; every signature covers the same payload; the payload matches the request; `notAfterUnixMs` has not passed; the `requestId` has never been used (Control's idempotency table, which never reuses a key).
- **Effect:** one `KillEvent` with `issuer = control-breakglass`, committed as in §3. The audit records the operator key ids and the client certificate.
- **Limits:** break-glass can only **add** kills. It can't issue recovery events: the schema allows only `control` as a recovery issuer, and the database refuses a `RECOVERY` row from any other issuer.

## 7. Recovery

- **Request:** `RecoveryRequest {supersedes[], reason}`, through the existing enterprise review workflow. It needs two distinct reviewers, and the requester can't be one of them. Their ids become `RecoveryEvent.reviewers`.
- **Quickstart** has one local administrator. There, the second reviewer is the local operator confirming at the host console, recorded as reviewer `local-operator`.
- **Checks:** every superseded id is an effective kill of this tenant. A break-glass kill is lifted the same way as any other.
- **Effect:** the recovery event and one supersession row per kill, committed as in §3. The epoch bump makes every component re-evaluate. Cancelled work stays cancelled; recovery only allows new work.

## 8. Retention and archive

A superseded kill and its recovery are archived (`control_kill_archive`) when every component registered for the tenant has acknowledged an epoch at least as high as the recovery's, or 30 days after the recovery, whichever comes first. Archived events leave `KillPush` and `KillEventPage`. They stay in `control_kill_events` and in audit exports.

A component that missed an archived recovery still holds the kill, which fails closed. It clears it on its next full fetch.

**A full fetch replaces, it doesn't merge.** `GET kill-events` is the one exception to the merge rule in §1. The component fetches it directly from Control over mTLS, never through a relay. It accepts the page only if every event signature verifies and the page's `authorizationEpoch` is at least the highest epoch the component has seen. It then replaces its set with the page. An old page therefore can't remove a newer kill, and a component that missed an archived recovery converges.

## 9. Audit

Every event, push, acknowledgement, break-glass attempt (accepted or refused, with the reason) and recovery is audited. Audit also records which path delivered each event to each component and when (plan.md §15.6), from `control_kill_acks` and the push log.
