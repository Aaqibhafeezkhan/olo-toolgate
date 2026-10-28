<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# File cleanup

Tools: `cleanup.old-files`. Part of the [Java Tool SDK examples](../README.md).

Finds files older than a number of days that match a name pattern (such as `*.log` or `export-*.csv`) in an approved directory, optionally in sub-directories, and lists them oldest first with their total size. With `dryRun` false it deletes them.

## Safety

- Off until the administrator sets `CleanupRoots`; the directory must be one of them or inside one, and `..` is refused.
- A dry run is the default. Deletes need an age of at least `MinAgeDays`, whatever the caller asks, and stop after `MaxDeletesPerCall` files.
- Each file is checked again just before it is deleted. Links are not followed and directories are never removed.
- The tool is declared `destructive`, so an administrator sees that when approving it, and its `useWhen` tells the model to show a dry run first.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `CleanupRoots` | `none` | Comma-separated directories the tool may clean. `none` disables it. |
| `MinAgeDays` | `7` | Youngest age a file may have to be deleted. |
| `MaxDeletesPerCall` | `500` | Most files one call deletes. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/file-cleanup-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "What .log files older than 30 days are in /var/log/myapp?"
- "Delete them."
