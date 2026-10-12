<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Script runner

Tools: `scripts.run`, `scripts.list`. Part of the [Java Tool SDK examples](../README.md).

Runs a script an administrator has put in an allowlisted directory and returns its exit code and the last lines of its console output (stdout and stderr together). Works with Windows batch files (`.bat`, `.cmd`), PowerShell (`.ps1`) and shell scripts (`.sh`).

## Safety

- The caller names a script, never a command line. It must sit directly in `ScriptsDir`; names with a directory, `..` or a link out of the directory are refused.
- When `AllowedScripts` is not `*`, only the scripts it lists run.
- Arguments are passed as separate argv entries and may hold only letters, digits, spaces and `. _ : / \ = @ , + -`, so nothing can be chained even through `cmd.exe`.
- Scripts start with a minimal environment (`PATH`, and `SystemRoot`/`ComSpec` on Windows), never the tool's own.
- A run past its deadline (`timeoutSeconds`, at most `MaxRunSeconds`) is killed with all its child processes. Only the last `lines` lines are kept in memory.
- The tool is declared `NON_IDEMPOTENT`: a script may change things, so ToolGate will not retry it.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `ScriptsDir` | `scripts` | Directory holding the scripts. Absolute under a Tool Host. |
| `AllowedScripts` | `*` | Comma-separated file names, or `*` for every script in the directory. |
| `MaxRunSeconds` | `50` | Upper bound on a run. |
| `ShellPath` | `/bin/sh` | Interpreter for `.sh`. |
| `CmdPath` | `C:\Windows\System32\cmd.exe` | Interpreter for `.bat` and `.cmd`. |
| `PowerShellPath` | Windows PowerShell 5.1 | Interpreter for `.ps1`; point it at `pwsh.exe` for PowerShell 7. |
| `ScriptPathVariable` | `default` | `PATH` the script sees. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/script-runner-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Run nightly-report.bat and show me the last 30 lines."
- "Which scripts can you run?"
