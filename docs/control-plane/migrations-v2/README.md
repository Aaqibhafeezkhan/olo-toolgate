# Migration specifications (design gate D2)

## Purpose

These files are the exact database changes behind [invocation-state-v2.md](../invocation-state-v2.md) and [kill-switch.md](../kill-switch.md). They are specifications. Nothing loads them from here.

The milestone that implements each one copies it unchanged into the product tree:
- `flyway/V18__invocation_tasks_v2.sql` and `quickstart/V11.sql` with milestone M1 (shipped; the product copies must stay byte-identical);
- `flyway/V27__kill_switch.sql` and `quickstart/V20.sql` with the kill switch, after the shipped console-device, feature-permission, invocation run-event and package-provisioning migrations.

The Quickstart runner cap in `SqliteState` is raised to match. Only a file's version number may change, if another migration lands first, and then both trees move together.

`tests/contracts/test_migration_specs_v2.py` applies the current product trees plus these files and runs the same accept/refuse scenarios on SQLite (always) and PostgreSQL (when `TOOLGATE_D2_POSTGRES=host:port` is set). Any change here must keep both passing.
