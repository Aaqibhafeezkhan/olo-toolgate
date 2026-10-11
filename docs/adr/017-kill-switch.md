<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 017: Emergency kill switch

**Status:** Accepted (design gate D2), on merge of the PR that adds this file

## Context

A malicious or broken package, publisher, runtime image or pool must be stoppable at once, across Gateways, Tool Hosts and clients, including work already running (plan.md §15). Earlier plan revisions gave the Gateway a freshness grace and its own break-glass list. Since every new invocation needs Control (a decision, a permit, and nonce consumption), a kill recorded in Control is effective for all new work as soon as it commits. Only work already running needs delivery.

Control already has the pieces this needs: an authorization epoch, bumped on every directory revision with an outbox row; epoch checks on reserve, consume, secret delivery and effect report; and a signed fleet document domain that endpoint clients pin.

## Decision

1. **Grow-only signed events.** Kills and recoveries are immutable `KillEvent` and `RecoveryEvent` records (`kill.schema.json`), identified as `<issuer>:<counter>`. They are stored insert-only in `control_kill_events` and `control_kill_supersessions` (Flyway V19 and Quickstart V12 specifications), with triggers and grants that refuse updates and deletes.
2. **Merge rule.** A component's state is the union of the valid events it has seen. Effective kills are those that no recovery supersedes, and `TERMINATE` beats `STOP_NEW`. A direct full fetch from Control replaces the set, after checking signatures and that the page's epoch is not older than one the component has seen.
3. **One transaction per event.** Authorize, allocate the counter, sign, insert, bump the tenant revision (which raises the authorization epoch and writes the outbox row), and cancel matching stored work, all in one commit ([kill-switch.md](../control-plane/kill-switch.md) §3).
4. **Signing.** Events and pushes are signed in Control's fleet signing key domain with their own `typ` values (`toolgate-kill-event+jws`, `toolgate-kill-push+jws`) over the JCS of the record without its signature.
5. **Delivery.**
   - **Tool Hosts:** `KillPush` over their mTLS control stream, with an acknowledgement recorded in `control_kill_acks`.
   - **Endpoint clients (release 1):** Control's epoch checks stop them at their next contact. Phase B clients get the push.
   - **Gateways:** they read effective kills with each catalog revision. No Gateway grace or Gateway kill list exists.
6. **Break-glass.**
   - **Endpoint:** `POST break-glass/kills` on an operator mTLS listener. No administrative token is needed.
   - **Authorization:** 2 of 3 externally configured operator RSA keys, a separate trust domain. Each signs `typ: toolgate-breakglass-approval+jws` over `{tenantId, scope, mode, reason, requestId, notAfterUnixMs}`, valid at most 10 minutes, with `requestId` single-use.
   - **Effect:** a kill with issuer `control-breakglass`, audited. Break-glass can't issue recoveries; the schema and the database both refuse it.
7. **Recovery.** A reviewed change through the enterprise review workflow, with two distinct reviewers who are not the requester. In Quickstart, the second reviewer is the local operator at the host console. Recovery only allows new work; cancelled work stays cancelled.
8. **Archive.** A superseded kill and its recovery leave the push set once every registered component has acknowledged the recovery's epoch, or after 30 days. The rows stay.

## Alternatives Considered

- **A single mutable kill list or sequence.** Rejected: a lost or reordered update could remove a kill. Grow-only events with supersession make replays harmless.
- **A Gateway-side kill list with a freshness grace.** Rejected (plan.md §15.3): it can go stale, and Control already gates every start.
- **Break-glass that can also lift kills.** Rejected: emergency access must only reduce what runs.
- **A new signing key for kill events.** Rejected: clients already pin the fleet key, and `typ` separation stops cross-use. Break-glass operator keys are new, because they authenticate people, not Control.

## Security Impact

- A committed kill stops new work everywhere at once, through checks that already exist.
- Replay or reordering can only add kills, which fails closed.
- Break-glass needs two of three operator keys and a separate certificate, can only add kills, and is always audited.
- Recovery needs two-person review and can't be performed through break-glass.
- Residual: a partitioned host runs existing work to its deadline, unless its pool sets `selfTerminateOnControlLoss`.

## Operational Impact

Operators provision three break-glass operator keys and the operator listener's certificate, and keep the keys offline. Kill and recovery appear in the console with delivery status per component. Every kill bumps the epoch, which makes components re-evaluate cached authority, a cost comparable to a grant change.

## Compatibility Impact

Additive. New tables, endpoints and push messages. Release-1 endpoint clients need no change: the kill reaches them through Control's existing checks. Phase B adds the client push.

## Consequences

- Positive: one simple invariant. A kill holds until a separately authorized recovery supersedes it.
- Positive: there is no Gateway freshness logic to get wrong.
- Negative: work on unreachable components isn't stopped before its deadline without `selfTerminateOnControlLoss`.

## Validation

- `tests/contracts/test_migration_specs_v2.py` covers V19 and V12: grow-only rows, unique event ids, no break-glass recovery, supersession and archive guards, and monotonic acknowledgements, on both databases.
- `kill.schema.json` valid and invalid fixtures, including a break-glass request with one operator.
- D3 conformance cases: terminate running work, a partitioned host, replayed pushes that can't remove a kill, a stale full fetch refused, break-glass with one key, reused or expired operator approvals.
