# Tenant data isolation foundation

This implementation is under development. It does not yet qualify shared production hosting. The remaining registry/authority split, UUID provisioning, lifecycle checks and ownership foreign keys are tracked in [the implementation plan](../../plan.md).

PostgreSQL V1 enables and forces row-level security on all 38 customer-data tables. `PostgresStore.transaction(...)` sets `app.tenant_id` with transaction-local `set_config` before tenant SQL, for both read and write transactions. Commit and rollback clear the context before the pooled connection is reused. SQLite retains application scoping and explicitly requires a non-null tenant ID on every application table; it does not implement PostgreSQL RLS.

Each protected PostgreSQL table has a permissive access policy and a restrictive tenant fence with both `USING` and `WITH CHECK`. Existing table privileges still determine which operations are available. The restrictive policy prevents an additional permissive policy from widening the tenant boundary. Missing or empty context reveals no customer rows and cannot insert customer data. A selected tenant cannot insert data for another tenant or change a row's tenant ownership.

The runtime database identity must remain separate from the schema owner, without superuser or `BYPASSRLS` authority. It cannot disable policies or use `TRUNCATE`. Forced RLS also scopes ordinary schema-owner queries. PostgreSQL superusers remain privileged infrastructure identities.

The checked inventory is [schema-ownership.json](../../apps/control-plane/src/main/resources/db/schema-ownership.json). It records every application table's classification, tenant column/type, primary key, current foreign keys, RLS status and runtime table privileges. Flyway history, SQLite migration checksums and SQLite internal sequence counters are explicit metadata exceptions without customer payloads. CI compares this inventory with both V1 schemas and the deployed PostgreSQL catalog, including actual runtime privileges. Adding a table requires an explicit ownership decision and matching policies.

There is one temporary exception to the RLS rollout: `control_tenants` still combines registry identity and mutable authority counters. Its current runtime access and unscoped enumeration are explicitly marked pending in the inventory. The next foundation change must split platform registration from tenant authority, remove implicit tenant creation, add lifecycle gating and enforce canonical UUID identity. This exception must be closed before the shared release.

Two non-Java SQL boundaries also establish transaction-local scope:

- The Quickstart supervisor's database context manager scopes its local identity reads and audit writes. It closes the connection if context setup fails.
- The separately authorized, offline PostgreSQL restore procedure scopes quarantine work to the independently reviewed tenant. Runtime connectivity stays revoked throughout that procedure.

RLS contains accidental query-filter omissions. It does not authenticate a tenant or protect against a fully compromised application that can deliberately set another context. Application authorization, authenticated device binding, secret custody and the remaining release gates still apply.

Verification uses real PostgreSQL runtime credentials and a pool limited to one connection. Tests deliberately omit SQL filters, use colliding secret names, attempt cross-tenant inserts/updates and ownership changes, add a permissive policy, and check context cleanup after commit, rollback and read-only reuse. The schema audit is included in `python -m unittest discover -s tests/ci -v`; the database checks run with the Control unit and enterprise suites against the disposable PostgreSQL fixture.
