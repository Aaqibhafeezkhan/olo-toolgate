<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Log insights

Tools: `logs.search`, `logs.levels`, `logs.errors`, `logs.tail`. Part of the [Java Tool SDK examples](../README.md).

The questions an operator asks of application logs:

- `logs.search`: entries in a time window, optionally at or above a level and containing some text, each with file, line, time, level and stack trace.
- `logs.levels`: how many TRACE to FATAL entries, overall, per file, and per time bucket, to see when errors spiked.
- `logs.errors`: ERROR and FATAL entries grouped by cause (the exception class, or the message with ids and numbers ignored), with count, first and last seen, and the latest stack trace.
- `logs.tail`: the last lines of one file.

An entry starts at a line with a timestamp (ISO 8601 such as `2026-10-12T09:00:00Z`, or logback/log4j `2026-10-12 09:00:00,123`, optionally in brackets); lines without one, such as stack frames, belong to the entry above. The level is the first of TRACE, DEBUG, INFO, WARN/WARNING, ERROR/SEVERE, FATAL/CRITICAL near the start. Times without an offset are in `LogTimeZone`. Times can be given as `from`/`to`, or `lastMinutes` back from now.

## Safety

- Reads only files under `LogDir` (up to 3 levels deep) whose names match `LogFiles`. A named file must resolve inside the directory; links are not followed.
- Each call reads at most `MaxScanMegabytes` of text and says when it stopped early. Files last modified before the window are skipped.
- Results are capped (`limit`), and long lines are cut at 2000 characters.
- A scan considers at most 1000 matching files; level buckets and error groups are also capped at 1000. Reaching a scan, text or aggregation bound sets `truncated`.
- When a file exceeds the scan budget, `logs.tail` returns the last lines of the scanned prefix with `truncated: true`.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `LogDir` | `logs` | Directory holding the logs. Absolute under a Tool Host. |
| `LogFiles` | `*.log,*.log.[0-9],*.txt` | Comma-separated glob patterns of file names. |
| `LogTimeZone` | `UTC` | Zone of timestamps that carry no offset. |
| `MaxScanMegabytes` | `200` | Most text read per call. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/log-insights-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "What errors happened in the last hour?"
- "How many warnings and errors per hour today?"
- "Show the ERROR entries mentioning payment between 14:00 and 15:00."
- "Tail app.log."
