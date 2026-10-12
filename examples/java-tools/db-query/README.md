<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Database query

Tools: `db.tables`, `db.row`, `db.query`. Part of the [Java Tool SDK examples](../README.md).

Lets a model read from a SQL database. `db.tables` lists the tables and columns. `db.row` returns the rows whose column equals a value (the order with id 1042, the customers in Pune) without any SQL. `db.query` runs one read-only `SELECT` or `WITH ... SELECT`, with `?` placeholders bound from `parameters`.

The example uses an embedded [H2](https://h2database.com) database file, so it needs no network and runs the same standalone and under a Tool Host. It is also the example with a third-party dependency: the H2 driver goes into the package's fat jar with the SDK.

## Safety

Read-only is enforced three times over. Keep all three if you adapt the example to another database.

- The connection is opened read-only (`ACCESS_MODE_DATA=r` and `Connection.setReadOnly`), and `IFEXISTS` stops it creating a database.
- The account (`DbUser`) is an ordinary user with `SELECT` grants only. This matters: an H2 admin can read server files through SQL functions such as `FILE_READ`.
- `db.query` accepts one statement that starts with `SELECT` or `WITH`, with no comments and none of the words that write or touch files (`INSERT`, `UPDATE`, `DELETE`, `DROP`, `CALL`, `SET`, `FILE_READ`, `CSVREAD`, `LINK_SCHEMA`, `INTO`, and so on) outside string literals.

`db.row` is the safest pattern: the model supplies a value, never SQL, and the table and column names are looked up in the database's own catalog before they go into the statement. `AllowedTables` limits `db.tables` and `db.row`; `db.query` can read whatever the account's grants allow, so set the grants to match. Rows (`MaxRows`), cell length (1000 characters) and run time (`QueryTimeoutSeconds`) are capped.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `DatabaseFile` | `data/demo` | H2 database file without `.mv.db`. Absolute under a Tool Host. |
| `DbUser` | `reader` | Account with `SELECT` grants only. |
| `DbPassword` (secret) | none | Its password. |
| `AllowedTables` | `*` | Comma-separated tables `db.tables` and `db.row` may use. |
| `MaxRows` | `500` | Most rows one call returns. |
| `QueryTimeoutSeconds` | `15` | Longest a statement may run. |

## Try it

Create the demo database (fictional customers and orders, plus the `reader` account) with the H2 tools inside the packed jar:

```sh
toolgate pack
unzip -q build/toolgate/db-query-0.1.0.tgpkg artifacts/tool.jar -d pkg
java -cp pkg/artifacts/tool.jar org.h2.tools.RunScript -url jdbc:h2:file:./data/demo -user sa -password admin-secret -script demo/seed.sql
cp toolgate.dev.example.json toolgate.dev.json
toolgate dev
```

Things to ask a model connected to it:

- "What tables are there?"
- "Show order 1004."
- "Total order value per city, excluding cancelled orders."

## Another database

For PostgreSQL, SQL Server or Oracle, swap the driver dependency and `connect()`. A database server is reached over TCP, so declare it with `@TcpDestination` and give the driver `context.tcp().socketFactory(id)`; TCP destinations work in standalone mode today and are not yet available under a Tool Host ([guide, section 6](../../../TOOL-SDK-GUIDE.md#6-calling-other-services)).
