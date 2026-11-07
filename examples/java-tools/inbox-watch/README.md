<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Inbox watch

Package `examples.inbox-watch`. Trigger `new-file` (`examples.inbox-watch/new-file`), event type `examples.files.observed.v1`. Part of the [Java Tool SDK examples](../README.md).

A client trigger in POLL form ([ADR 058](../../../docs/adr/058-triggers.md)). Every minute it lists one folder of the device's confined files area and reports each file version present there that it has not reported before. The package has no tools: it exports one trigger, which an administrator assigns to a device group with a config such as `{"folder": "inbox", "extension": ".pdf"}`.

## What it promises

The source is a `STATE_SNAPSHOT`: an event means "this file was present at check time with this size and had not been reported", never "this file was created".

- A file created and deleted between two checks, or while the device was off, is never reported.
- A version is the file's path and size, the two facts the files area reports in this release. A rewrite that keeps the size is not reported again; the PUSH form of this sample, with executor-computed version keys (file ID, size and modification time), comes with PUSH mode.
- Each event has a stable id, `file:<path>:<size>`, so a version re-reported after a restart or a lost checkpoint is stored once.
- The checkpoint holds the reported version of every file still present. A removed file's entry is pruned, so the checkpoint stays as small as the folder; a folder whose entries no longer fit the 16 KiB checkpoint makes the check report `UNHEALTHY (CheckpointTooLarge)` without advancing.
- A listing that fails commits nothing: the check reports `DEGRADED` and the next one starts from the same checkpoint.

## Safety

- `@DeviceFileAccess` without `write`: the descriptor carries `DEVICE_FILES_READ` only, and the trigger reads through `FILES_LIST` and `FILES_INFO` broker frames. A trigger's file access is read-only.
- A `@ClientTrigger` has no routes, TCP destinations or secrets, so it runs only on devices (`executors: ["CLIENT"]`).
- `folder` and `extension` are validated against their patterns before the first check.

## Try it

```sh
toolgate check    # the unit tests and both @TriggerExample self-tests
toolgate pack     # build/toolgate/inbox-watch-0.1.0.tgpkg with triggers/new-file.json
```

[`InboxWatchTest`](src/test/java/io/ololabs/examples/inboxwatch/InboxWatchTest.java) drives the trigger with `ToolHarness.trigger("new-file")` through the real trigger runtime and its stdio frames: several checks over a changing folder, a failed listing, retryable and terminal rejections, and a restart from the committed checkpoint.
