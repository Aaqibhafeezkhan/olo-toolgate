# Admin UI

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited. The combined list refreshes every two seconds. Tooltip information is available by hover, focus and the details button; unknown availability is not green.

See [Device registry and tool-call controls](../../docs/control-plane/device-registry.md).

React 19 / TypeScript / Vite console, embedded in Control at `/console/`.
Dashboard and paginated users, teams, tools, policies, clients/device records and
agents use real Control v1 APIs. User create/edit/delete supports optimistic
revisions and exact-request idempotency. Other sections provide readable inspection.
The approvals queue supports approve once, temporary approval and denial for
Gateway-created ASK requests. It shows the exact operation binding, authoritative
state, revision and deadlines, with explicit human confirmation before a decision.

## Quickstart login links

A demo link can prefill the username with `/console/#login?username=public`.
External integrations can also supply a URL-encoded `password`, in either the
login fragment or query string. The console copies valid values into the login
fields and removes credential parameters with `history.replaceState` before
mounting the UI or starting its requests. Sign-in still requires a click, and
an explicit login link does not restore a different existing session.

Prefer username-only links. A password in a query string reaches the initial
HTTP request and may already be recorded by infrastructure; clearing the address
bar cannot undo that. Fragments avoid that request exposure, but should still
not be generated or shared with passwords. Values are not saved in browser storage.

## Human approval

Connect using an organization-issued token with a verified tenant and stable
human identity. Control resolves the enabled directory user and current Team
roles. Scoped operation reviewers require management authority and membership
in the policy's approver Teams; Super Admin has the exception described below.
Approvers use the approval workspace with Overview and their queue.
The UI never decodes tokens to infer roles. Token display claims may supply the
signed-in name and an identity key for browser preferences after authentication;
Control remains the only authorization authority. Approvers start at Overview
or their last accessible browser page, then open Approvals.

## Navigation and lists

Fresh browser history starts at Overview with every menu collapsed. The last
page and expanded menus are kept in local storage for the same origin, issuer,
tenant and subject. Explicit deep links take priority. No navigation state is
saved on Control, and browser preferences contain no tokens or passwords.
Administration is the last navigation group and contains Server, Device,
Backup/Restore and About. Navigation and workspace scroll separately; the name
and approval bell remain at the bottom of the sidebar. The bell is green for
pending scoped operation, configuration or device approvals, and gray when
clear or unavailable. Its menu opens the relevant queues and refreshes every
15 seconds and after approval decisions.

All tables support ascending/descending header sorting and page-local checkbox
selection. Entity lists provide bulk enable, disable and confirmed delete;
read-only lists export selected rows. Enablement uses red/green switches, and
action icons have accessible names and tooltips. Sorting and selection affect
only the displayed server page. Failed and pending bulk changes are reported
per record; configuration drafts never appear as already applied changes.

The main panel fills the available workspace width on every screen, with standard
padding around panels and forms. Record names are plain text: use the **Edit**
icon to open an editor. **Add** actions, client approval/group actions and tool
draft editors also open a modal inside the console. Its header keeps **Close**
visible while the body scrolls independently. Close or Escape returns focus to
the opening control; a group picker closes before its parent editor on Escape.
Closing is disabled while the editor saves a request.

An active Super Admin Management Role on an enabled Team supplies all tenant
management actions. Super Admin directory changes apply immediately and are
audited; they may also manage and approve any existing configuration request,
including their own, without a second reviewer. Other Management Roles retain
scoped ceilings and independent review. Runtime authorization still requires
all current grants, policies and device checks. See [ADR 022](../../docs/adr/022-super-administrator-review-exception.md).

Deletion removes an active entity completely. Required default groups, internal
Quickstart executors, valid references and the last active Super Admin remain
protected. Retired identifiers and credentials cannot restore access: onboard a
new record, assign groups and credentials, and complete the required approvals.

The queue and details refresh every 15 seconds. Page controls fetch up to 50
records at a time; the pending filter applies to the current page. Review tenant,
requester, agent, device presence, exact tool/action/resource, arguments digest
and policy version. Approve once covers one execution. Temporary approval offers
a maximum of ten or thirty minutes, capped by Control at current policy expiry.
The UI displays digests rather than raw arguments or credentials.

Every decision uses the displayed revision and an idempotency key retained for an
exact retry after a connection failure. A changed decision or revision gets a new
key. A conflict reloads the current record before any further decision. Expired,
denied and consumed records have no decision controls. The requester retries
through Gateway; a recorded approval cannot bypass a changed policy or an
unavailable approval service. Notifications are optional and require no chat
integration to use this queue.

## Develop and verify

Requires Node 22.12+ (Node 22 CI), Java 21, Python tooling and Docker for isolated
PostgreSQL. From the repository root:

```bash
npm ci --ignore-scripts
npm run ui:build
npm --workspace @olo-labs/toolgate-admin-ui test
npx --no-install playwright install --with-deps chromium
python tools/ui/check.py
```

The browser gate builds Control, generates isolated signed test identities,
starts real PostgreSQL, exercises embedded production assets and removes its owned
infrastructure. No company credentials are needed. For a local interactive
workspace run `python tools/ui/check.py --serve`, open the printed loopback URL
and read the temporary credential file's `admin` or `reader` field. Tokens expire
after five minutes; restart for fresh tokens. Ctrl+C removes owned resources.
This developer harness is not Quickstart.

`npm --workspace @olo-labs/toolgate-admin-ui run dev` serves the development
console at `/console/` and proxies only the versioned API to loopback port 8082.
It requires an independently running Control; it supplies no fake API.

## Contracts and packaging

Wire types come from `@olo-labs/toolgate-contracts`. Generate canonical OpenAPI
operation bindings with `python tools/ui/generate.py`; `--check` detects drift.
The transport wraps generated routes/shared types and centralizes cancellation,
timeouts and safe errors. No policy evaluator or server validation is copied
into the UI.

`./gradlew :control-plane:build` explicitly builds npm assets and embeds them under
`META-INF/resources/console`. Docker uses a pinned Node stage, verifies prebuilt
product/contracts versions and packages the JRE-only runtime.
`-PprebuiltAdminUi=true` is for this controlled pipeline and fails when matching
assets are missing. `node tools/ui/build.mjs` also creates release metadata and
runtime notices. `python tools/ui/package.py` produces a reproducible asset tar
and checksums in `build/ui/`. `make check` includes unit, drift and browser gates.

See [authentication and operations](../../docs/control-plane/admin-ui.md),
[ADR 004](../../docs/adr/004-embedded-admin-console.md) and
[approval architecture](../../docs/adr/006-ask-approvals.md).
## Custom tools

The **Tool builder** route authors bounded code/schema/AI metadata, displays
designated-client results and prepares immutable packages for signed deployment.
See [production/debug instructions](../../docs/client/tool-builder.md).
