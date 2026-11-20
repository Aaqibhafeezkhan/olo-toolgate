<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 080: Runtime availability and installation service credentials

Status: accepted for implementation, 2026-11-20.

## Context

The stateless LangGraph supervisor exited when its worker lost an expiring
15-minute service credential. Docker then restarted the entire service with the
same rejected credential, also discarding the graph API's disposable cache.
The operator requires stable containers through authentication/network outages,
external credential custody, and a configurable seven-day demo credential.

## Decision

The worker reconnects admission/presence with capped exponential backoff and
jitter. It rereads the externally managed credential file for each request;
authentication refusal reports `AUTH_REQUIRED`, transport failures `BACKOFF`.
Neither condition exits the service. The supervisor restarts failed children
individually with backoff, preserving healthy siblings. Initial provisioning
waits without terminating the service. No Admin credential or issuer key enters
LangGraph, and it never renews/mints credentials.
The worker probes the existing Gateway `/v1/health/ready` management handler
through its configured TLS origin before claiming and during presence renewal.
Quickstart forwards only that fixed read-only health path on its existing TLS
port; production reverse proxies must route it. Failure never grants admission
or drains queued work while only Control remains reachable.

`/health/live` measures process availability; `/health/ready` additionally requires
fresh authenticated worker presence and a listening graph API. Docker uses
liveness. Readiness diagnostics contain only bounded states and are not authority.
Every model/tool boundary still requires current authorization. Loss of worker
presence refuses additional model/tool sends. An admitted run is never replayed
or resumed by this reconnect loop; uncertain effects retain existing outcome and
reconciliation rules. Cached graphs do not grant access and remain rebuildable
from pinned configuration in Quickstart/Control.

The Quickstart workflow demo issues runtime-only service credentials with
`TOOLGATE_RUNTIME_CREDENTIAL_TTL_SECONDS`, default 604800 (seven days), bounded
to 1..2592000 seconds. Control's dedicated workflow-runtime service boundary
enforces the configured maximum age/lifetime and tenant fence. Quickstart sets
its JWT parser age and maximum-lifetime ceilings to at least that lifetime
(`MP_JWT_VERIFY_TOKEN_AGE` and `SMALLRYE_JWT_TIME_TO_LIVE`). Human identities and all
other service boundaries retain their 900-second limits; the public Admin
session verifier rejects machine credentials. Run leases, grants and per-step
tokens are unchanged. Outside Quickstart, Control defaults to 900 seconds;
operators explicitly configure the parser ceiling and runtime limit together.

The external `renew-runtime.bat`/`provision-runtime` command calls the existing
Super Admin-protected issuance API and atomically replaces the private runtime
file. It preserves Admin credentials, containers, device membership and bot
configuration. Operators schedule external renewal if desired; no renewal
sidecar, background container loop or automatic host task is installed.

## Consequences and limits

Seven-day service tokens have a larger exposure window than the prior demo
tokens. They grant only the workflow runtime role, not Admin access, and all
run/step/tenant/policy checks remain live. Operators can shorten the lifetime;
replacing a token does not revoke an earlier signed token before its expiry.
Real process/host failure can still require a Docker restart. Software upgrades
require explicit image replacement; credential/network recovery does not.
No schema, migration, compatibility alias or package/wire conversion is added.
Acceptance evidence and outstanding qualification live in the workflow/security
status documents; these decisions do not complete their roadmaps.
