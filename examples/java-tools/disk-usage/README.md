<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Disk usage

Tools: `disk.space`, `disk.largest`. Part of the [Java Tool SDK examples](../README.md).

`disk.space` lists every volume with size, free space and percent used, fullest first. `disk.largest` finds the largest files and immediate sub-directories under a directory, to show what is filling a disk.

## Safety

- `disk.largest` looks only inside `AllowedRoots`, refuses `..`, and does not follow links.
- A call visits at most `MaxFilesVisited` files and says when it stopped early. Unreadable directories are skipped.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `AllowedRoots` | `.` | Comma-separated directories `disk.largest` may look inside. |
| `MaxFilesVisited` | `200000` | Most files one call looks at. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/disk-usage-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Is any disk nearly full?"
- "What is using the space in /var?"
