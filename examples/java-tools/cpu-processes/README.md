<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# CPU and processes

Tools: `processes.cpu`, `processes.top`, `processes.find`. Part of the [Java Tool SDK examples](../README.md).

`processes.cpu` measures whole-machine CPU use over a short sample. `processes.top` lists the processes using the most CPU over the sample, like `top` or Task Manager. `processes.find` answers "is X running?" by executable name, with each match's pid, owner, start time and CPU time.

## Safety

- Read-only.
- Processes are shown by executable name only. Command-line arguments are never returned, because they often hold passwords and tokens.

## Settings

None.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/cpu-processes-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Why is the CPU high? Show the top 5 processes."
- "Is nginx running?"
- "Is sqlservr.exe running on this host?"
