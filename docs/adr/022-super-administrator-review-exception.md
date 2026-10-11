# Super Administrator review exception

Status: Accepted

## Context

The installation owner requires Super Admin to manage all tenant records and approve any request, including requests they created. Restrictive roles continue to use scoped management rules and independent review.

## Decision

Derive the exception from the current directory: an enabled user must belong to an enabled Team containing an enabled Management Role whose portal role is `SUPER_ADMIN`. Token role claims and cached actor flags cannot confer this exception. This role supplies all tenant management actions without scope ceilings.

Super Admin may submit, approve, deny, revoke, cancel and apply any configuration request. One Super Admin approval satisfies its review quorum and may be self approval. Operation approvals still cover each exact policy obligation, but Super Admin may review their own or their agent's request and need not belong to the policy's approver Teams. Super Admin may also propose and check outcome reconciliation.

Direct directory CRUD, membership changes and confirmed configuration imports by Super Admin apply atomically without a separate review draft. This allows a status toggle to change state immediately. The same canonical mutation, validation, revision checks and idempotency safeguards run; a dedicated audit operation records the exception. Scoped administrators continue to create configuration drafts.

All actions remain audited. Revisions, expiration, authorization epochs, exact request digests, valid graph references, credential lifecycle checks, required default groups and the last active Super Admin remain protected. Runtime grants, policy decisions, device authentication and execution permit checks remain required. No automatic runtime grant or policy override follows from portal authority.

## Consequences

Super Admin is a privileged exception to separation of duties. Assign this role only to installation owners; create scoped Management Roles for other administrators. Role removal or disablement immediately removes the exception, including on replay and application. Existing APIs and stored review records suffice; no contract or schema change is necessary. Navigation preferences remain entirely in browser storage.
