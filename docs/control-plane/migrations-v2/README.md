# Prospective migration specifications (design gate D2)

Invocation/task storage is included in the PostgreSQL and SQLite V1 schemas under
`apps/control-plane/src/main/resources/db/`. Its constraints are verified directly
against those initial schemas.

The remaining `flyway/V2__kill_switch.sql` and `quickstart/V2.sql` specify the
unimplemented [kill switch](../kill-switch.md). Nothing loads them from this
directory at runtime. When implemented, copy them into the product trees and
raise the Quickstart runner cap to 2. If another migration ships first, assign
the next version to both specifications before shipping.

`tests/contracts/test_migration_specs_v2.py` applies the current initial schemas
plus these prospective files and runs the same accept/refuse scenarios on SQLite
and, when `TOOLGATE_D2_POSTGRES=host:port` is set, PostgreSQL.
