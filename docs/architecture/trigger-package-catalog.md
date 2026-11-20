# Available trigger examples

| Source | Executor | Supported behavior |
|---|---|---|
| [Hot-folder watch](../../examples/java-tools/hotfolder-watch/README.md) | Enrolled client, POLL | Observe new files inside the protected managed files area |
| [Managed-log watch](../../examples/java-tools/log-watch/README.md) | Enrolled client, POLL | Bounded matching, spike windows, cooldown and explicit offset/rotation limits |
| [Independent adoption fixture](../../tests/fixtures/trigger-packages/signal-watch/README.md) | Test fixture | Verify generic package/configuration/event adoption; not a production connector |

Use exact signed package versions, declared capabilities and reviewed assignments.
Profiles own source configuration, filters and workflow bindings; private custody
owns secrets. Event content cannot supply runtime authority. See
[trigger architecture](triggers.md) and [management](../control-plane/trigger-assignments.md).

Host/container logs, Windows Event Logs, generic server/PUSH sources and provider
connectors are not implied by managed-file examples. Their remaining requirements
belong in [the trigger plan](../plans/triggers.md). New packages need installation,
input/output and failure instructions, positive/negative mock tests through the
[package test matrix](../../tools/triggers/README.md), then runtime qualification.
