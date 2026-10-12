<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# System health

Tools: `health.system`. Part of the [Java Tool SDK examples](../README.md).

One call that answers "is this machine healthy?": CPU load, memory, swap and every disk volume, each rated OK, WARN or CRITICAL against thresholds the administrator sets, with the worst one as the overall status. Also reports host name, OS, processors, load average and uptime. Pure Java, so the same jar works on Linux, Windows and macOS.

## Safety

- Read-only, takes no arguments, and reads only operating-system counters.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `CpuWarnPercent` | `85` | CPU load that warns. |
| `MemoryWarnPercent` | `90` | Memory or swap use that warns. |
| `DiskWarnPercent` | `85` | Disk use that warns. |
| `CriticalPercent` | `95` | Use at which any check is critical. |
| `DiskPaths` | `*` | Comma-separated paths whose volumes are checked, or `*` for all. |

Deploy it to every Tool Host (leave *Tool Hosts* blank) and the same question works on any machine in the fleet.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/system-health-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Is this server healthy?"
- "Are any disks close to full?"
