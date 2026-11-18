<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 073: Super Admin settings authority

Status: accepted for implementation.

The installation owner must be able to administer a blank single-admin setup.
Resolve Super Admin from the current directory within the mutation transaction,
including when changing the configuration review threshold. Scoped administrators
continue to use the current threshold and independent review. Cached actor flags
cannot confer this exception. Audit direct settings writes as Super Admin writes.
Revision checks, schema validation and current owner validation still apply.

Reject deleting the authenticated actor's own user through either direct CRUD or
configuration import. Retain the bootstrap account protection and last-sign-in
administrator protection. Management authority does not manufacture independent
execution evidence, target guarantees or successful business outcomes: those
remain subject to the execution contracts. This decision does not qualify native
filesystem mutations or change their current UNKNOWN finality.

This is a fresh current-contract behavior change with no schema changes,
migration or compatibility path.
