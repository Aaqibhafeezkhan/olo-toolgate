# Current contracts and fresh builds

This feature ships fresh breaking builds. Deploy matching current Control,
Gateway, Tool Host, console, Quickstart, client, SDK and contract artifacts.
There is no backward compatibility, mixed-version fallback, legacy runtime
adapter, database migration, backfill or conversion path.

Use the root VERSION for product artifacts and packages/contracts/VERSION for
the generated contract set. Version identifiers select the matching current
artifacts; they do not promise support for earlier formats.

## Prepare a deployment

- Initialize an empty PostgreSQL database or empty Quickstart data directory
  using the current fresh schema. See [fresh database initialization](../control-plane/upgrades.md).
- Verify the current package format, descriptor generation, runtime and
  OS/architecture for every assigned tool. Unsupported inputs fail closed.
- Preserve existing stores and evidence separately. An unsupported store is
  rejected without conversion or deletion; application rollback cannot restore
  authority or reset spent nonces.
- Verify fresh initialization, current-schema recovery and obsolete-format
  refusal on SQLite and PostgreSQL when changing persistence.
- Keep canonical schemas, generated bindings and first-party callers aligned
  through the [schema workflow](../development/schema-workflow.md).

Preserved proposals and archived fixtures are historical evidence. Earlier
migration or compatibility decisions are superseded by the fresh-build rule.
Archives do not define current acceptance or supply runtime fallback behavior.

Use [SDK conformance evidence](../../tool-sdk/spec/sdk-compat/README.md),
[package deployment](../client/package-deployment.md) and
[installer limitations](../client/installers.md) for their declared current
runtime boundaries. Pinned conformance does not establish N/N-1 or all-platform
support.
