<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# CSV query

Tools: `csv.query`, `csv.files`. Part of the [Java Tool SDK examples](../README.md).

Answers questions from CSV exports (reports, inventories, audit extracts) without a database. `csv.files` lists the files and their columns. `csv.query` filters rows with conditions such as `status=FAILED`, `amount>500` or `customer~acme` (`=`, `!=`, `>`, `>=`, `<`, `<=`, and `~` for contains; numbers compare as numbers), picks columns, sorts, and with `groupBy` returns one row per group with a count and, with `sum`, a total. Handles quoted fields, `;`, tab and `|` delimiters, and a UTF-8 byte-order mark.

## Safety

- Reads only `.csv`, `.tsv` and `.txt` files directly in `DataDir`, up to `MaxFileMegabytes`.
- Returns at most `limit` rows and says when there were more.

## Settings

Globals unless marked. Set them in `toolgate.dev.json` for development, as `TOOLGATE_GLOBAL_<NAME>` (or `TOOLGATE_DESTINATION_<NAME>`, `TOOLGATE_SECRET_<NAME>`) for a standalone jar, or on the deployment under ToolGate.

| Setting | Default | Meaning |
|---|---|---|
| `DataDir` | `data` | Directory holding the CSV files. Absolute under a Tool Host. |
| `MaxFileMegabytes` | `100` | Largest file a call reads. |

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # then edit it; never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/csv-query-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Which regions had the most failed orders last month? Use orders.csv."
- "List the customers with orders over 500 that failed."
