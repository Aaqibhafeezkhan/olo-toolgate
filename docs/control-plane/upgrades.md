# Fresh database initialization

This breaking build supports only the current fresh PostgreSQL and SQLite
schemas. Current and future feature stages use current schema and execution
contracts only. There is no database upgrade, backfill or backward compatibility path.

Start PostgreSQL with an empty database or Quickstart with an empty data
directory. PostgreSQL initializes the current V1 schema and validates its
checksum. SQLite atomically initializes the current V1 schema and stores its
identity/checksum in quickstart_schema. An existing database from an earlier
stage, an incompatible checksum or a missing current identity is rejected
without conversion or data deletion.

Deploy matching current Control, Gateway, Tool Host, managed client, console,
SDK and contract artifacts. Old wire formats and historical capability bindings
are unsupported. Current-operation journals, crash recovery and late evidence
remain part of the new runtime; they do not constitute historical format support.

Use separate initialization and runtime database roles. Runtime credentials
cannot change schema or remove immutable evidence. Application rollback does
not convert a database. Any new breaking schema requires another fresh database.
