# Invocation run events and the Test panel

Every invocation has one durable, ordered stream of what actually happened while it ran. The administration
console's **Test a tool** panel reads it; nothing about it grants, retries or changes an invocation.

## What the stream contains

| Source | Kind | Written when |
|---|---|---|
| `PLATFORM` | `STATE` | Every ledger transition, in the same transaction that saves it (queued, pending approval, executing, succeeded, failed, cancelled, expired, outcome unknown). |
| `EXECUTOR` | `LOG`, `PROGRESS`, `INPUT_REQUEST` | The executor that admitted the attempt forwards the SDK's redacted frames as the tool emits them. |
| `EXECUTOR` | `RESULT` | Control accepts the executor's result report for the attempt. |

Control assigns each event a cursor, which orders replay. Timestamps are display data only. Contracts:
`RunEventBatch`, `RunEventAck`, `RunEvent`, `RunEventGap` and `RunEventPage` in
`packages/contracts/schemas/v2/protocol.schema.json`.

## Ingestion (executor → Control)

`POST /api/control/v1/access/invocations/{id}/events` with the device certificate. Control accepts a batch only from the
device the invocation was placed on and only under the fencing token its permit consumption returned, so a stale attempt
or another device cannot add frames. Each frame is unique per invocation, fencing token and sequence: redelivery after a
reconnect is acknowledged as a duplicate and never stored twice. The acknowledgement reports the highest contiguous
sequence retained.

The Tool Host reads the tool's stdout line by line, sequences valid SDK frames, posts them in batches of at most 64 frames
or 48 KiB, retries a failed batch with the same sequences, and finishes forwarding before it sends the result report.
Malformed frames, frames over its bound and frames it could not deliver are counted and reported as `droppedFrames`.

## Redaction and bounds

The SDK replaces declared secret and sensitive values before a frame leaves the tool. Control applies a second layer
before persistence: credential-shaped text (assignments to password/secret/token/key names, bearer and basic
credentials, JWTs, PEM private keys) is masked, and terminal escape sequences and control characters are removed.
The console renders tool output as text, never markup.

Per invocation Control retains at most 1,000 events and 1 MiB of event documents; a result frame over 96 KiB is replaced
by an explicit marker. Refused frames make the page `truncated`. Streams are retained for seven days after their last
event, then purged by the Control watchdog; a purged stream reads as `expired` and takes no new frames.

## Replay (console → Control)

`GET /api/control/v1/access/invocations/{id}/events?after=<cursor>&limit=<1..200>` returns events after the cursor with
the authoritative ledger `state`, `terminal`, `more`, `nextCursor`, `truncated`, `droppedFrames`, `expired` and the
`gaps` (missing sequences of the current attempt). Every page is authorized like reading the invocation: the owner's
current access is re-evaluated, so revocation ends a running stream, and anyone else needs `operation.read` on both the
Tool Group and Device Group of the binding.

## Test a tool

The panel lists the tools the chosen target reports for the chosen execution binding, each matching its verified
package definition, so a ClientTool is never offered on a Tool Host. Input starts from the SDK-generated defaults in the
definition's input schema (Hello World: `{"text": "World"}`); required values without a valid default must be entered.
**Test** submits an ordinary human invocation with an idempotency key that is kept for the exact same input, so a retry
after an ambiguous failure or an approval is the same invocation. A tool on a Tool Host receives the same permit and
relayed dispatch the Gateway would create. The panel follows the stream by cursor, resumes after a network failure from
the last cursor without resubmitting, and stops on 401/403/404.

Client-device frames use the same ingestion contract; governed SDK package execution on client devices is not yet
available (see the package-first plan).
