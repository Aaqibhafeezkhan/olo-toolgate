# Admin UI

## Purpose

The administration console lives at `/console/`; root `/` redirects there.
React/TypeScript/Vite assets ship in the Control image and use generated operation
metadata from the canonical API. UI and versioned API share one administrative origin.
See [device registry and tool-call controls](device-registry.md) for the current
enrollment, approval and execution flow.
The **Configuration** menu has Server, Device and Backup/Restore pages; each page saves
only its own settings group. See [Configuration menu](configuration-menu.md).

## Authentication

Use HTTPS outside loopback development; token entry is disabled on insecure
non-loopback origins. Supply a short-lived token from the external organization
IdP with the claims required by [Control configuration](configuration.md).
The shell verifies connectivity through the backend and does not decode claims
or decide permissions. Organization access tokens stay in memory; refresh,
disconnect or backend 401 clears them and aborts outstanding requests. The masked
input clears on connection. Tokens never enter browser storage, URLs, exported
configuration or app logs.

Quickstart password login also creates an HttpOnly, SameSite=Strict browser-session
cookie scoped to its API path and public port. Refresh restores its existing
15-minute signed session through the same-origin session API, then verifies access
with Control; it does not save the password or extend token expiry. Disconnect
clears the cookie, and expired tokens or stale password generations cannot restore.
Cookies alone cannot authorize Control or tool operations, which still require
the verified Bearer token. The cookie is Secure on TLS; loopback Quickstart HTTP
uses the documented local development exception. Organization tokens retain
their external identity-provider lifecycle.

Readers browse; the server rejects their mutation attempts with a visible error.
Directory users do not provision external IdP accounts. Managed directory roles
are still bounded by verified identity and server authorization. Production uses
externally issued tokens. Quickstart additionally supplies its local bootstrap
login and an explicitly configured loopback password-free debug mode; these do
not replace a production identity provider. Do not deploy developer test keys or
the isolated test harness as one. See [Quickstart deployment](../deployment/quickstart.md).

## Management and failures

Dashboard values are first-page counts capped at 50, with `+` for further pages.
They are directory metadata and never grant access. Directory pages support
record inspection and loading/error/empty states. Policies are edited separately
from explicit signed publication to Gateway; saving a policy does not publish it.
Tool schemas render as text. Advanced protocol fields remain behind an optional disclosure.

**Devices → Enroll device** automatically lists unexpired requests for authorized
enrollers to review by public code and fingerprint. **Devices → Clients** combines
pending requests and registered devices, refreshing every two seconds. Device name
and registered user are table columns; row details support hover, keyboard and the
information button. Pending requests show **Awaiting registration**.

Installed clients have independent **Enable / Disable** and **Approve / Deapprove**
controls. Approval may end at a chosen local date/time or use **Unlimited time**.
Changing approval uses its own stable revision, independent of check-ins. Neither
enablement nor approval alone grants tool access. Connection status reflects fresh
authenticated reports, not just approval. Temporary suspension preserves the key;
permanent key revocation remains a separate API operation.

Quickstart separately lists `local-builtins`, `local-hotfolder` and
`local-rest-forwarding` under **Local tool requester**. These server-managed
devices need no client approval and appear green while ready and enabled.
Their disable controls gate real execution paths; existing tool/resource filters
and target-client approval still apply.

User create/edit/delete uses server validation and optimistic revisions. Conflict
preserves inputs and offers Reload current record before resubmission. No policy
or reference rules duplicate the backend. An exact retry retains its idempotency
key; changed values get a new key. Mutations are never retried automatically.
Deletion requires confirmation; retired IDs cannot be reused. Navigation or
disconnect drops unsaved form state.

Requests stop after 15 seconds; response memory is bounded at 2 MiB. Redirects
cannot carry tokens to another host. Errors show fixed safe wording and validated
request references. Raw exception bodies are never displayed. There is no browser
telemetry exporter.

## Accessibility

Semantic landmarks, skip link, labeled inputs, table headers, status/alerts,
visible keyboard focus, native disclosure controls and editor focus support
keyboard/screen-reader use. Playwright/axe checks WCAG A/AA tags on pages,
errors and forms. Desktop and 390px layouts, screenshots and keyboard focus are
verified. Automated checks are not a comprehensive accessibility certification.

## Deploy and operate

Enable the existing Control Helm workload and TLS root-path ingress.
`/console/` and `/api/control/v1/` must reach the same origin. Keep private 9092
management probes/metrics separate. Existing replicas, resources, NetworkPolicy,
external PostgreSQL/key Secrets, readiness and shutdown apply. No separate UI
service, container, CORS setting, secret or Helm value is required.

Static responses use strict CSP (self-hosted scripts/styles/API; no inline
execution or external frames), nosniff, no-referrer and disabled device APIs.
HTML/release metadata use no-store; hashed assets cache immutably. Preserve these
headers through proxies. Source maps and Vite's internal manifest are excluded.
Runtime MIT copyright/license notices are included. Rebuild UI and backend
together from the lockfile. Product version is injected into the console; shared
wire contracts remain v1. The normal version tool synchronizes console release
metadata. Deploy matching UI, Control and generated contracts; PostgreSQL migration
V11 supports reversible device-control and identity-recovery audit events.

During rolling upgrades, old HTML may reference assets absent on a new replica.
Refresh the console after rollout. Retain previous asset sets at the proxy if
uninterrupted old-document reloads are required. There is no service worker or
offline token cache. Existing Helm rollback restores matching UI/API images.

For blank pages check JS/CSS responses, CSP/proxy rewriting and artifact version.
For 401 obtain a fresh correctly issued token; for 403 check IdP roles; for 409
reload current data. Correlate safe backend logs with the displayed request ID.
Database outages produce safe retryable errors and backend readiness failure.

## Packaging plan

Protected Control CI builds/tests/scans the embedded assets, runs real browser
E2E, and collects asset checksums and a production npm CycloneDX SBOM. Existing
GHCR Control and OCI chart naming apply. Quickstart packaging reuses these
assets and origin and supplies local identity bootstrap and routing. See
[developer commands](../../apps/admin-ui/README.md)
and [ADR 004](../adr/004-embedded-admin-console.md).
