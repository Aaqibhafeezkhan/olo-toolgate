<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 021: Client check-in settings and the Configuration menu

**Status:** Accepted (design gate D1), on merge of the PR that adds this file

## Amendment (2026-10-15)

Quickstart now defaults to 2000 ms with no jitter to reduce recurring container
load. Explicit environment overrides remain supported; the enterprise default is
unchanged. The 500 ms Quickstart values below describe the original decision.
See [container performance](../operations/performance.md).

## Context

Endpoint clients poll Control for jobs. Control returns a fixed check-in interval (500 ms when the client sends `X-ToolGate-Poll-Interval-Unit: milliseconds`, otherwise 2 s, `EndpointService.java:190`), the client obeys `nextIntervalMs` within 500 ms to 1 h, and the WebSocket opens only after a poll delivers a job (ADR [012](012-client-mcp-polling-relay.md), platform-requirements.md §2.1). One value therefore controls liveness, job pickup latency and load: about 20,000 requests/s at 500 ms for 10,000 idle devices. Rahul asked for it to be configurable.

In the console, Gateway name, device approval and configuration import/export all sit under Audit > Configuration reviews, and two settings areas edit one `ControlServerSettings` record (`formatVersion` 1) through a full-record `PUT` with `If-Match`, `Idempotency-Key` and `409` on a stale revision. A startup import overwrites settings.

[platform-requirements.md](../contracts/platform-requirements.md) §17 and §18 (register rows D, AD, AJ, R9) decide both changes. Neither is built before design gates D0 to D2 pass.

## Decision

1. **Five settings, not one** (platform-requirements.md §17.1): heartbeat interval, job notification transport, job pickup timeout, execution deadline and offline threshold. A WebSocket disconnect is not unavailability: the device becomes `DEGRADED` for a grace period with fallback polling. Leased non-idempotent jobs are never rescheduled because of a socket drop; they go to `OUTCOME_UNKNOWN` at lease expiry. Only queued, unleased jobs may move, and only if the binding allows it.
2. **Phase A, no client change, built in M1** (platform-requirements.md §17.2). Control reads:

   | Variable | Quickstart | Enterprise default |
   |---|---|---|
   | `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS` | 500 | 2000 |
   | `TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT` | 0 | 20 |
   | `TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS` | 120000 | max(120000, 6 x check-in) |

   Helm exposes them as `control.clientCheckIn.*`, added to `values.schema.json` (which keeps `additionalProperties: false`). Control refuses to start when check-in plus jitter plus a 5 s execution margin exceeds the 30 s sync deadline; longer client work uses tasks. The 250 ms burst guard becomes a fraction of the configured check-in. In Phase A the check-in value is the job pickup latency for idle devices.
3. **Env-owned.** These values are env-owned in the precedence model of ADR [020](020-deployment-binding.md): always read from env, never overwritten from the database or the startup import, and shown read-only under Configuration > Device with their effective values.
4. **Phase B, needs a client release** (platform-requirements.md §17.3): persistent WebSocket (`TOOLGATE_CONTROL_CLIENT_SOCKET_MODE=persistent`) with push for jobs, permission changes and kill entries, plus `_HEARTBEAT_MS`, `_FALLBACK_POLL_MS`, `_JOB_PICKUP_TIMEOUT_MS` and `_SOCKET_GRACE_MS`. Older clients keep Phase A behaviour. Permission deltas are revisioned, signed, carry the digest of the resulting document, and fall back to full snapshots (platform-requirements.md §17.4).
5. **Configuration menu** (platform-requirements.md §18). A new sidebar group **Configuration** with pages Server (`#config-server`, Gateway name), Device (`#config-device`, device approval and read-only check-in values) and Backup/Restore (`#config-backup`, configuration import/export and the settings file import/export). Contents move unchanged; Audit > Configuration reviews keeps only the review queue; old `#configuration` links redirect.
6. **Field-level PATCH.** Each page saves only its own fields:

   ```text
   PATCH /api/control/v1/settings
   Content-Type: application/merge-patch+json
   If-Match: "<revision>"
   Idempotency-Key: <key>
   ```

   A stale revision returns `409` with the current record, and the UI shows "changed elsewhere, review and retry". Env-owned fields are rejected in a PATCH body.
7. **`ControlServerSettings` `formatVersion` 2**, defined in `packages/contracts/schemas/v2/settings.schema.json` together with the read-only client check-in settings. Env-owned values are excluded from the startup configuration import and from exports.
8. **The existing full-record `PUT /api/control/v1/settings` stays** for import, with strict compare-and-swap on the revision.

## Amendment (M1): group-level patch and the review route

Implementing decision 6 in M1 changed two details. A PATCH body replaces each group it names (`gateway`, `deviceApproval`) rather than merging fields inside a group, because the patch schema has no `null` for optional fields and a page must be able to clear the device approval owner. A stale revision returns the standard `409` error, and the console then reads the current record itself. The review queue moved to `#config-reviews`, so `#configuration?id=<id>` redirects there and a bare `#configuration` redirects to `#config-server`. The settings PATCH is now in the served `control-v1.yaml`. Records saved in formatVersion 1 reset to the defaults, since nothing is in production.

Contract surface: the PATCH and the check-in settings are specified in `packages/contracts/openapi/proposed/control-v1.proposed.yaml` and `quickstart-v1.proposed.yaml`. The served `packages/contracts/openapi/control-v1.yaml` and `quickstart-v1.yaml` change only when M1 implements each endpoint, so the served spec never advertises an unimplemented endpoint.

## Alternatives Considered

- **Keep one hard-coded interval.** Rejected: 500 ms does not scale and 2 s is too slow for Quickstart.
- **Make check-in a console setting.** Rejected: it drives fleet-wide load and must be set by the operator with the deployment; the console shows it read-only.
- **Separate settings records per page.** Rejected (row D): splits one document that import and export treat as a unit.
- **Full-record PUT from each page.** Rejected: two pages would overwrite each other's fields.
- **Ship the menu and variables before the design gates.** Rejected (rows AD, R9).

## Security Impact

- No trust boundary changes. Check-in values only affect latency and load, and invalid combinations are refused at startup.
- Socket drops cannot cause duplicate execution of non-idempotent work.
- PATCH keeps `toolgate-admin`, `If-Match` and `Idempotency-Key`, so concurrent edits cannot silently overwrite each other.
- Env-owned values cannot be changed through the console, API or an imported file.

## Operational Impact

Operators can tune idle load (about 5,000 requests/s at 2 s for 10,000 devices instead of 20,000 at 500 ms) through env or Helm. Enterprise installs get a 2 s default on upgrade, which raises idle job pickup latency; Quickstart keeps 500 ms. Admins find server settings under a dedicated menu.

## Compatibility Impact

No client change in Phase A: clients already obey `nextIntervalMs`. `ControlServerSettings` moves to `formatVersion` 2; `formatVersion` 1 documents remain importable through `PUT` and are upgraded on read. Bookmarks to `#configuration` redirect. Helm values gain `control.clientCheckIn.*`.

## Consequences

- Positive: load and latency become operator choices; two console pages edit one record safely.
- Negative: in Phase A, pickup latency equals the check-in interval; real push needs Phase B and a client release.
- Negative: the settings record now has env-owned fields that the console and import must handle specially.

## Validation

- `settings.schema.json` with valid and invalid fixtures (including `formatVersion` 1 upgrade) passes the contract checks.
- Control tests: defaults per profile, startup refusal when check-in plus jitter plus margin exceeds 30 s, env-owned values untouched by the startup import, burst guard scaling.
- API tests: PATCH merge semantics, `409` with the current record on a stale `If-Match`, idempotent replay, rejection of env-owned fields; PUT import with strict compare-and-swap.
- Console tests for the three routes and the `#configuration` redirect, against the menu specification produced in D1.
- Review of `control-v1.proposed.yaml` and `quickstart-v1.proposed.yaml` against this ADR before M1.
