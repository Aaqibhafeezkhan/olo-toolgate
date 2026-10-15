# Console feature permissions

Management permission identifiers come from `packages/contracts/management-permissions.json`. A permission is a feature and action, such as `device.approve`, `device_group.manage_members`, or `role.manage_permissions`. Routes are presentation metadata; stored rules never refer to pages.

`ManagementAccess` resolves live enabled User, Team and Role relationships, group boundaries, conditions, and target evidence. The backend checks both existing and proposed targets. Direct changes and configuration imports share mutation authorization. Membership replacement checks every changed source/destination group and allocation ceiling. A grant to view a feature grants no mutation or runtime execution right.

The console consumes `AdminSession.permissions` and per-device `permissions` returned by Control. It does not derive authority from user names, ownership labels, browser extension presence or a cached Super Admin flag. Capability refresh follows session verification and periodically refreshes the console. API checks remain authoritative after any role, membership, session or device change.

## Role editor and scopes

Management Roles show feature sections with action checkboxes. Each scope has a group boundary, selected groups, conditions and optional delegation ceilings. Global features require the permission over all four group dimensions. Super Admin checkboxes are checked and locked; the central resolver allows every known management permission for a live Super Admin. Unknown actions fail closed, including for Super Admin.

`OWNED` grants device actions for associated devices in the configured placement group. `CURRENT` adds proof that the same device key belongs to the current console session. These scopes apply only to Device actions and require one explicit placement group. Multiple assigned roles combine grants; an unchecked box means that role grants no permission.

## Current-device verification

Chrome Connect obtains a short-lived random challenge from Control. The native service signs a domain-separated nonce with the existing protected device key after checking the console origin against its configured console or authenticated discovery manifest. The private key never crosses IPC or enters the browser.

Control verifies the signature against the recorded CSR and binds the context to the tenant, authenticated console JWT/session, user, device and key. Challenges expire, are single-use, and are checked again against live ownership and role authority. Session expiry, ownership/key replacement, revoked identities and expired pending requests invalidate the context. The proof may associate a pending request; it never approves access or issues a certificate.

Upgrade the Control service, console, Chrome extension and native client together before enabling a CURRENT scope. An older installed client reports proof unavailable; it does not receive a weaker fallback. The demo3 profile deliberately retains explicit OWNED scope for its existing one-device scenario until installed Chrome acceptance is completed.

## Setup identity and authority preservation

The common store commit guard protects the immutable recorded setup user ID. That exact login cannot be deleted, disabled, demoted, stripped of its effective enabled Super Admin path, or lose its last existing enabled sign-in identity. Renaming does not alter protection, and another administrator does not replace it.

If no setup identity is recorded, a transition must retain an enabled effective Super Admin and must not remove the last existing supported Super Admin sign-in path. Transactions serialize authority changes so concurrent removals cannot each remove the other administrator. An invalid recorded setup identity never activates the fallback. Historical reserved-account lists are removed; reviewers and other users are governed by these same rules. Recovery is outside this change.

## Configuration and upgrades

Role permissions are directory data and round-trip through configuration backup, merge/replace restore, and initialization profiles. Session-device proofs and cached permission decisions are never exported. The destination keeps its own setup-identity protection; an imported file cannot clear or reassign that marker.

PostgreSQL V24 and SQLite V17 convert stored legacy actions once. Unknown actions abort the upgrade rather than silently disappearing. Legacy ownership restrictions become explicit OWNED rules, and broad device reads in the same legacy role are removed to preserve private visibility. Role/document revisions, tenant authorization epochs and the authorization outbox advance with the conversion. New writes reject generic legacy verbs and `ownDevices`.

For operator-owned JSON profiles, run `python tools/access/migrate_management.py --write <profile.json>` and review the resulting feature grants before applying them. This is a file migration tool, not a runtime compatibility layer. The three marketplace demo profiles and standard initialization roles already use the canonical model. Check them with `python ../deploy/docker/demo-permissions.py --check` and `python tools/access/permissions.py --check` from the ToolGate checkout.

Server and Device Configuration have separate read/write permissions and settings views omit unreadable groups. Workload-token rotation requires its explicit action and target scope. Snapshot publication uses access-snapshot authority, without requiring backup export. Sensitive runtime credentials remain outside exported configuration.

## Validation and deployment

Regression coverage includes PostgreSQL and SQLite stored upgrades, feature/action independence, direct/import mutation guards, setup identity and concurrent removal, device proof signature/session/replay/expiry checks, native origin enforcement, browser extension nonce/origin behavior, console read-only and revocation handling, and account/profile initialization. The UI builds into a separate validation output directory.

No local Docker image build or running demo reset is required by this change. Source validation does not replace acceptance using the installed Windows client and Chrome extension. Complete that installed flow before declaring the original live enrollment issue resolved.
