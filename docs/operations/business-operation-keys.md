<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Explicit business operation keys

For native Gateway `POST /access/invocations`, preserve the same `Idempotency-Key`
across retries and agent restarts. Keys contain 1–128 ASCII letters, digits,
periods, underscores or hyphens. Use a stable workflow/run/step identifier rather
than a new random key on every attempt. Control returns one stable `op-...`
invocation ID. Preserve that returned ID for subsequent APIs.

For modern MCP tools/call, set the same logical key in call parameters:

```json
{
  "name": "invoice.pay",
  "arguments": {"invoiceId": "invoice-42"},
  "_meta": {
    "io.ololabs.toolgate/businessKey": "run-7.pay-invoice-42",
    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
    "io.modelcontextprotocol/clientCapabilities": {}
  }
}
```

The usual MCP headers, authentication and JSON-RPC envelope still apply. A new
JSON-RPC request ID with the same business key names the same operation. Result
metadata includes the operation's invocation reference. Send the returned `op-...`
ID as `operationId` to `olo/invocations/cancel`; Control verifies ownership.
Malformed business-key metadata is refused rather than treated as an unkeyed call.

Native admission changes the ID namespace from legacy `inv-...` to `op-...`.
Use a coordinated Gateway cutover: finish or reconcile existing legacy operations
through their original IDs before retrying those logical steps on the new path.
Do not mix old/new Gateway admission replicas for one workflow or assume a
pre-upgrade Idempotency-Key has been imported into the business-key table. This
slice does not backfill historical key bindings because Control never stored the
raw keys. Start new workflows after cutover with new workflow-scoped keys.

Upgrade Control and its contracts before rolling out the new Gateway. An older
Control rejects the new closed-schema request field; Gateway does not silently
drop the key or fall back to unkeyed admission. Migration V5 adds immutable window-generation history and stricter key guards.
Upgrade the database/Control and contract readers before publishing policy
metadata; older closed-schema clients cannot read the new optional fields.
Quickstart databases apply V5 on open and older images refuse the newer state.
Keep the original key/operation history during rollback or restore.

Without a policy, keys retain their legacy tenant/tool/agent scope (or verified
user scope for calls without an agent). A reviewed tool definition may declare
the following optional `businessKeyPolicy`:

```json
{
  "required": true,
  "namespace": "TENANT",
  "argument": "paymentKey",
  "retentionMs": 2592000000,
  "upstreamIdempotencyWindowMs": 86400000
}
```

AGENT needs a verified agent. DELEGATED_USER uses the verified human and refuses
SERVICE calls. TENANT uses an empty namespace identity. Arguments cannot assert
that identity or enable result sharing. The original user and agent remain
owners: another owner receives conflict without a result, including another agent
acting for the same user. Cross-agent result reuse needs the later caller
integration; selecting DELEGATED_USER does not grant it implicitly.

A required key must arrive through the header/MCP metadata or the declared
top-level string argument. Argument keys use the same 1–128 ASCII
letter/digit/period/underscore/hyphen rule and tenant hash domain. When both
sources are present they must agree; a malformed present argument is refused
even when a header key is valid. Raw argument values still travel in governed
tool payloads; only reservation/audit key metadata is hashed.

Server deployment derives enforced key settings from its stored package
descriptor; dropping or weakening the directory copy cannot bypass the pinned
server declaration. Client releases include the key policy in the signed
installed authorization profile. Resolved optional AGENT defaults, including
legacy descriptors omitting the namespace, retain the old agent-or-human
explicit-key behavior. Required keys, argument mappings or another declared
namespace opt into enforcement.

The authenticated native catalog exposes current policy. MCP tools/list exposes
it as `_meta["io.ololabs.toolgate/businessKeyPolicy"]`. This is discovery metadata,
not authorization. A caller's policy metadata cannot override Control. Gateway
checks explicit/argument-derived IDs and accepts a Control-selected canonical ID
only for a declared unkeyed window, while checking actor, inputs, code and binding.
MCP results, local submissions and trace correlation use that validated ID.
Direct human calls also use the canonical operation ID for local dispatch and
replay. The console uses a declared argument key as its Idempotency-Key header,
so both sources agree; editing other inputs with that key lets Control reject a
fingerprint conflict instead of creating a different logical operation.

 A different original user or changed input/code/binding returns HTTP
409; it never receives the first caller's result. Current authorization remains
required on replay. Explicit-key retention expiry also returns 409 and never starts a new call.
Control stores only the tenant-separated key hash, not the raw key. Key hashes
are guessable identifiers, not credentials.

Standalone Java SDK task-store recovery also uses immediate SQLite write
transactions, so overlapping old-runtime completions and new-runtime polls do not
fail on deferred lock upgrades. Existing restart tests verify the old runtime
cannot replace a recorded unknown outcome with success.

A keyed operation with potentially sent or unqualified unknown work requires
the existing reviewed reconciliation workflow. A DOWNSTREAM_IDEMPOTENCY
declaration alone cannot authorize replay. Safe failed attempts may instead use
[proof-bound recovery](../control-plane/operation-recovery.md), preserving the
same operation/key and original deadline. Expired unconsumed permit replacement
is recorded as a separate attempt by the
[operation/attempt journal](operation-attempt-journal.md). Do not generate a new
key to bypass uncertainty: that is a new operation with potential duplicate effects.

## Optional dedupe windows and maintenance

A policy with `required: false` may set `dedupeWindowMs` (1 second to 24 hours).
Identical unkeyed intent within that window returns one operation across request
IDs and replicas. The window hash binds sorted Control JSON of caller/chain,
binding, exact input, tool/extractor/package digests and downstream key, excluding
credential renewal, trace IDs and selected device. It uses the versioned
toolgate-business-window-v1 domain, separately from signed JCS HTTP fingerprints.
Input/code/binding changes are different implicit patterns; this is not
package-major-only deduplication. Sensitive workflows should preserve explicit
logical keys rather than rely on a time window.

Elapsed time is not permission to repeat work. A new window generation requires
Control's durable non-legacy resolved operation record and archives the entire
old binding atomically. The current journal proves not-started failure for
unconsumed terminal work; consumed terminal work remains unknown, even when a
worker reports success, until effect-aware finalization supplies stronger proof.
Running, legacy or ambiguous prior work returns conflict after window expiry.
An explicit key never becomes a renewable window key.

Retention defaults to 30 days; configured values range from 1 second to 365 days
and must cover both the optional dedupe window and the declared upstream
idempotency window. Replay cannot shorten or extend the snapshot. The watchdog
processes at most 100 eligible key tombstones per tenant sweep, after expiring
invocations. Only resolved non-legacy operations qualify; running/ambiguous
records stay intact. Explicit tombstones remain bound indefinitely and are never
deleted. An eligible new implicit window may supersede its retained predecessor,
including a tombstoned one, only with the exact immutable history and resolution
proof. History cannot be updated or deleted by the runtime role.

Key-row retention does not purge independent task/result/dispatch payloads.
Cross-agent result reuse and complete effect-aware operation finalization remain
outstanding; proof-bound recovery does not establish verified success. Calls without a declared window and without a
key retain request-ID replay lookup. None of these paths can reopen consumed
unknown attempts through legacy retry; see
[the live progress document](../security/zero-trust-execution-status.md) and
[ADR 034](../adr/034-durable-business-operation-binding.md).

## Proof-bound recovery

[Operation recovery](../control-plane/operation-recovery.md) preserves immutable
attempt history. Exact replay may queue the same logical
operation only after complete Control proof that every predecessor could not
write, with fresh current authority and the original deadline. Never-consumed,
expired permits qualify from the journal; consumed attempts require default-off
operator qualification and every generation released. Old attempts and receipts
remain history. Missing evidence, possible sends, completed writes, cancellation,
denial and elapsed original deadlines cannot authorize a replacement.
