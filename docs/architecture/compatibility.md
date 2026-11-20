# Current contracts and fresh schemas

Deploy matching current Control, Gateway, Tool Host, console, Quickstart, client,
SDK and contract artifacts. There is no mixed-version fallback, legacy runtime
adapter, database migration, upgrade/backfill or format-conversion path.

## Initialize storage

Start PostgreSQL with an empty database or Quickstart with empty storage.
PostgreSQL initializes the current V1 schema and validates its checksum.
SQLite atomically initializes its current schema and records identity/checksum.
Unsupported stores, missing identity and mismatching checksums are rejected
without conversion or deletion. Use separate initialization and runtime roles;
runtime credentials must not mutate schema or immutable evidence.

Recovery within the current schema, durable journals and late evidence remain
required. They do not imply support for historical formats. Application rollback
does not rewind authority epochs, spent nonces, identity or database history.
Use [reviewed recovery](../enterprise-access-control/operations.md), not an old
database or evaluator copied over a running installation.

## Prepare a deployment

Pin matching reviewed images/artifacts, validate credentials/custody and render
deployment configuration. Check readiness, authenticated discovery, one permitted
effect and one denied/revoked effect in the intended topology. An authority outage
must not create offline permission. See [production setup](../deployment/production.md).

Immutable protocol/compatibility fixtures are verification evidence consumed by
tooling; they are not an advertised runtime compatibility or migration window.
The [canonical contracts](../../packages/contracts/README.md) and current
[schema workflow](../development/schema-workflow.md) define source ownership.
