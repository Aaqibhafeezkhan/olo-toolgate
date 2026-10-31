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
drop the key or fall back to unkeyed admission. No storage migration is needed: the
reservation uses the already shipped `control_business_keys` table.

Keys are scoped by tenant, tool and authenticated agent (or user for calls without
an agent). A different original user or changed input/code/binding returns HTTP
409; it never receives the first caller's result. Current authorization remains
required on replay. Retention expiry also returns 409 and never starts a new call.
Control stores only the tenant-separated key hash, not the raw key. Key hashes
are guessable identifiers, not credentials.

Standalone Java SDK task-store recovery also uses immediate SQLite write
transactions, so overlapping old-runtime completions and new-runtime polls do not
fail on deferred lock upgrades. Existing restart tests verify the old runtime
cannot replace a recorded unknown outcome with success.

If a keyed operation is OUTCOME_UNKNOWN, reconcile it through the existing
reviewed reconciliation workflow. Retrying cannot reopen it even when the tool
has declared DOWNSTREAM_IDEMPOTENCY. Failed/expired operations also remain bound;
this conservative slice has no automatic attempt rebinding. Do not generate a new
key to bypass uncertainty: that is a new operation with potential duplicate effects.

This protection currently requires an explicit key. Calls without keys keep
legacy request-ID replay behavior. Generalized requiresBusinessKey enforcement,
separate attempts and effect-level recovery remain outstanding; see
[the live progress document](../security/zero-trust-execution-status.md) and
[ADR 034](../adr/034-durable-business-operation-binding.md).
