<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 064: Qualified device file reads and private workflow results

Status: Implemented for bounded qualification; full roadmap security acceptance remains separate.

## Decision

An unqualified native executor report remains `OUTCOME_UNKNOWN`. A signed
`READ_ONLY` label alone does not prove complete effect scope. For the Linux
managed Java device executor only, an operator may qualify an exact device,
administered device group and managed OCI image after verifying its confinement
and read-only broker. The default qualification list is empty.

Control records bounded evidence from the verified client package: only CLIENT,
synchronous, closed-schema exports with exactly DEVICE_FILES_READ and no network,
secrets, claims or globals are candidates. Mutations and open-world declarations
are excluded. Reservation checks current directory membership, the installed
catalog and exact managed digest, and the explicit image pin. Complete effect
scope is frozen through the existing immutable admission-scope ledger before
authority consumption. The existing finalizer, manifest closure, authenticated
reports, lease fencing, current-device checks and outcome grades remain in use.
The scope's pool ID denotes the administered device group for this qualification
domain. Server HTTP qualification continues to use administered Tool Host pools.

This extends S10's bounded complete-scope qualification to one declared read-only
device tier. It does not qualify native file mutations, Windows/macOS, arbitrary
native executors or compromised device hosts, and is not posture attestation.
Existing recovery APIs still require server deployment qualification; this does
not implement device invocation replay or retries. A lost/unconfirmed response
stops the workflow. Operator pins must be removed when the qualified executor
configuration changes. No compatibility reader or store conversion is added.

Workflow completion now commits a bounded result with terminal state in the same
tenant transaction. Results use the existing private document store, AES-GCM
tenant vault custody and a distinct AAD domain bound to tenant/run/profile/state
and key version. Inputs are retained separately. Runtime credentials cannot read
completed results; the operator endpoint requires a current tenant Super Admin.
Successful results must match the pinned workflow result schema and current
service authority. Raw content is excluded from default worker metadata logs.
The demo explicitly retrieves and logs its fictional result under private file
permissions, showing which file, workflow, agent and run produced it.

## Verification and limits

Context sources may bind a tool argument to `/message`, the immutable admitted
workflow message. Static arguments and bound arguments cannot overlap; no other
pointer or executable expression is supported. The runner applies bindings before
agent inference and still requires current tool discovery and read-only authority.
The hot-folder profile uses this to read the exact event path before triage.

The authoritative #230 and #295 status documents record executed evidence and
limitations. Qualification must cover the real managed container, broker read
and confinement refusals, unsupported declarations/pins and tenant boundaries.
Result verification covers encryption, foreign/stale authority, schema/size
refusal and atomic completion on fresh SQLite and PostgreSQL. A working demo
does not complete the full S22 matrix or any original roadmap phase.
