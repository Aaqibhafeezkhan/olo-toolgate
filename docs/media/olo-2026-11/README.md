<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Current OLO Admin UI — November 19, 2026

These are unaltered browser screenshots from the existing `debug/profiled`
installation at port 18090, serving UI source revision `ed13f0f7`. The new
published-image deployment is not their source: its matching containers are
not yet published. Bot/device/policy data is local demonstration configuration,
not a blank installation. No password, API key or private model thinking is captured.

| View | Original |
|---|---|
| OLO branding and workspace overview | [Overview](overview.png) |
| Published bot revisions and Run | [Bots](bots.png) |
| Live model progress | [Running bot](bot-running.png) |
| Real Ollama result and terminal timeline | [Run result](bot-result.png) |
| Agent identities | [Agents](agents.png) |
| Connected device | [Devices](devices.png) |
| Trigger assignment administration section | [Assignments](triggers.png) |

The captured text-only `ollama-summary` run succeeded using `qwen3:0.6b`.
The exact run ID, UTC capture times, URLs and image checksums are in
[captures.json](captures.json). The trigger capture includes only the actual
administration section; agent-facing consumer views require separate runtime grants.

Maintainers can regenerate with installed repository Playwright dependencies and
a configured debug bot. From the repository root:

```powershell
$env:OLO_CAPTURE_SOURCE_REVISION = 'the-actual-running-ui-revision'
node tools/docs/capture_olo_console.mjs
```

The tool signs in using the private password file, invokes the published text bot,
waits for real progress/result and captures browser pixels. It does not alter UI
text/styles or import synthetic screenshot data. Optional `OLO_CONSOLE_URL`,
`OLO_ADMIN_PASSWORD_FILE` and `OLO_CAPTURE_DIRECTORY` select another explicitly
prepared installation. Keep capture metadata tied to its actual running source.
