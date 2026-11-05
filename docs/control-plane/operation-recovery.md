<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Proof-bound operation recovery

A replay keeps its logical operation ID, business-key owner, fingerprint and
original deadline. Control may return the same operation queued for a new
attempt only after proving that every predecessor could not have performed a
write. A retry receives a new single-use execution nonce; old attempts, leases,
dispatch snapshots and late evidence remain immutable history. There are at most
64 execution attempts per operation. Attempts do not extend key retention.

Control can prove never-started work from its complete journal: all issued
execution permits expired without consumption. Live unconsumed permits block
replacement. Missing records, lost spools, a worker's not-started report or silence
are insufficient. Cancelled, denied, completed, partially completed, legacy and
deadline-expired work cannot reopen. Current grants, credentials, authorization
epoch, code/binding and every applicable approval are checked again. A denied
retry does not create a recovery record.

## Qualified broker-only recovery

Consumed work stays fenced by default. The optional
TOOLGATE_CONTROL_RECOVERY_QUALIFIED_EXECUTORS setting is a comma-separated list
of authenticated-device@administered-pool@sha256:image-digest pins (64 lowercase
hexadecimal digest characters), with at most 1024 entries. Invalid entries refuse
startup. Configure the same qualified pins on all Control replicas.

A pin is an operator declaration that this exact device/pool/image runs the
qualified containment and effect-ledger deployment. It is not remote attestation,
a worker-reported tier or acceptance of a complete roadmap phase. Establish the
actual rootless OCI containment and broker effect-ledger evidence before adding a
pin. Removing a pin prevents future qualified admissions; already admitted
attempts retain their original immutable scope. Revoke device/operation authority
when withdrawing an existing execution's authority.

Control independently restricts qualification to pinned Python OCI server tools
with no secret, HTTP credential, tunnel or unmediated permission declarations and
only nonstreaming supported OLO memory/RAG routes. The current scope covers
memory.read/append/delete and rag.query/ingest/update/delete via their exact
HTTPS brokered service-profile paths. It does not qualify general HTTP targets,
native tools or other runtimes. Package/runtime/descriptor/profile digests and
profile revision are captured before permit consumption.

Every authorization generation from every consumed predecessor must be RELEASED.
Control atomically releases an AUTHORIZED generation only if it has not crossed
SENDING and belongs to a qualified attempt with a matching v2 execution lease.
The previous grant can then never enter SENDING. A replacement's same logical
effect uses a fresh authorization nonce and generation, retaining its effect ID
and exact request binding. SENDING, CONSUMED, AMBIGUOUS, completion or incomplete
history blocks recovery, including when a later attempt looks harmless.

## Deployment and investigation

Apply V6 migrations using the separate migrator before deploying Control. Update
Tool Hosts together: pull-mode refusal receipts carry the claimed permit nonce
for correlation. That field is not proof of signature validation or consumption.
After recovery Control rejects unbound/stale admission receipts. Authenticated
late results for known consumed predecessor nonces are retained as evidence and
cannot overwrite the current dispatch or invocation result.

The immutable control_operation_recoveries table records prior revisions, exact
predecessor IDs, proof, original deadline and archived invocation/lease/dispatch
documents in the same tenant writer transaction as rebinding and its audit.
The control_attempt_recovery_scopes table records admission-time scope. Runtime
roles cannot update/delete either table; PostgreSQL enforces tenant RLS. A storage
failure rolls the transaction back with no replacement authority. Preserve the
records when investigating an ambiguous effect; do not edit them to force a retry.

This scoped no-write proof is not effect-verified success. Complete aggregate
operation finalization and caller history remain S10 work. General routes, broker
credential separation, production qualification/SLOs and the full security
acceptance matrix remain tracked in the [authoritative progress document](../security/zero-trust-execution-status.md).
