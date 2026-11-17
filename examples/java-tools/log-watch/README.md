<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Log watch

Package `examples.log-watch`, version `0.1.0`, export `error-spike`, event type
`examples.logs.match.v1`. This working **client POLL** package observes a UTF-8
log inside the managed device files area every five seconds. It needs only
`DEVICE_FILES_READ`; it cannot read arbitrary host logs, Docker logs or Windows
Event Logs, and does not implement native PUSH watching. Those capabilities
remain separate M7/M8 work.

## Configure and install

Build with `../gradlew :log-watch:check :log-watch:toolgatePackage` from this
directory after publishing the SDK locally and installing the ToolGate CLI.
The output is `build/toolgate/log-watch-0.1.0.olotool`.

Install that reviewed artifact into the selected configuration's `packages/`
folder and pin its package digest in the workflow's `deployment.source`.
The [log-triage profile](../../../deploy/compose/WorkflowStack/configurations/log-triage/README.md)
demonstrates adoption using the common images without changing the Gateway,
client or Compose service list. All assignment configuration belongs to the
workflow's source:

```json
{"file":"logs/app.log","pattern":"ERROR|FATAL","threshold":2,"windowSeconds":60,"cooldownSeconds":60}
```

`pattern` uses [RE2/J syntax](https://github.com/google/re2j), a linear-time
engine; backreferences and lookaround are unsupported. Patterns have at most
256 characters. Threshold is 1–100; window is 1–86400 seconds; cooldown is
0–86400 seconds. Paths are relative to the managed area. There are no secrets,
network connections or credentials in this package.
The device's file-extension allowlist still applies. The current managed-device
policy includes `.log` alongside `.txt`, `.md`, `.json` and `.csv`; older device
images without this policy refuse `.log`. This grants no access outside the
managed root and changes no signed package permissions or byte/count limits.

## Produce and inspect an event

Append newline-terminated text to `logs/app.log` through the device's authorized
managed-file producer. Two `ERROR` lines observed within 60 seconds produce one
event. `INFO` lines do not. The sample profile sends `/sample` to its configured
`log-diagnoser` model agent, stores the workflow result in Control, and writes
the worker's local result under the configuration state folder. It does not
modify or move the log. See the profile README for exact commands and paths.

The payload contains `file`, `startByte`, exclusive `endByte`, `matchCount`,
`sample`, `windowSeconds` and `rewritten`. Offsets count the UTF-8 encoding of
the text returned by the file broker. Inputs must be valid UTF-8. The bounded
sample is untrusted source content and may contain sensitive information;
configure event classification/clearance before using real production logs.
The package never logs source text to its runtime diagnostics.

## Recovery and limits

- Only complete newline-terminated records advance the cursor; CRLF is accepted.
  Partial final records wait for the next append. At most 256 records are scanned
  per poll, and the remainder stays behind the committed cursor.
- The whole managed log must fit 65536 bytes. Oversized/unreadable/missing logs
  fail the check without checkpointing or skipping records. This is a bounded
  observation package, not a large-file streaming tailer.
- The checkpoint holds an offset and SHA-256 of its complete prefix. Truncation
  or a changed prefix restarts observation at zero under a new deterministic
  generation. This catches same-length rewrites of processed content. Identical
  replacements cannot be distinguished without executor file-version keys.
- Rotation can remove unseen records between polls. `STATE_SNAPSHOT` explicitly
  provides no complete log-history guarantee. Failed reads retain the prior
  checkpoint; source downtime cannot recover records already removed by rotation.
- Windows use **observation time**, not timestamps parsed from log lines.
  Pending matches survive restart, expire at the window boundary and are bounded
  by the threshold. Each poll coalesces its matches into at most one alert.
  During cooldown, new records are deliberately consumed without notification.
  Backwards clock movement does not shorten a committed cooldown.
- The existing runtime holds the checkpoint until emitted events are locally
  durable, retries the same emit sequence/identity after retryable rejection,
  and halts without checkpointing on terminal rejection. This does not prove
  exactly-once workflow effects. Loss of the source checkpoint can re-observe data.
- Unknown checkpoint formats or changed source configuration are refused;
  create a new source instance for different observation rules. No conversion,
  legacy alias or automatic state deletion is provided.

Tests cover real SDK stdio/broker behavior, rejection/checkpoint order, restart,
UTF-8 offsets, partial lines, rotation, batching, threshold windows, cooldown,
clock regression, regex resource safety, oversized input and config/state refusal.
`trigger-tests.json` registers this package in the
[parallel mock-test stage](../../../tools/triggers/README.md); each package has
its own CI result and needs no provider credentials.
