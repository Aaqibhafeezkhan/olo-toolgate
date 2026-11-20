# Trigger architecture

Triggers are signed package capabilities that observe an approved source and
emit events. Control owns assignment, lifecycle, event acceptance and routing.
An event is untrusted input; it never supplies tool or workflow authority.

## Current execution path

A trigger descriptor belongs to an exact package version. An assignment selects
the approved executor/device group, configuration, filter and required
capabilities. Configuration is validated against the signed descriptor.
The executor reconciles desired instances and reports lifecycle/health through
authenticated trigger synchronization. A lease and current device/package
authority constrain execution; device approval alone does not authorize a source.

The current managed-file examples use client POLL execution:
[hot-folder watch](../../examples/java-tools/hotfolder-watch/README.md) and
[managed-log watch](../../examples/java-tools/log-watch/README.md).
They operate within the device's protected files area. They do not grant access
to arbitrary host logs, Docker logs or Windows Event Logs.

Accepted events enter Control's tenant-scoped routing and subscription path.
Subscribers use authenticated delivery/cursor/ACK protocols. A configured
workflow dispatch binding selects the admitted workflow and actor; the source
payload cannot replace them. See [workflow execution](workflows.md).

## Identity and safety

- Definitions, assignments, instances, events and subscriptions belong to a tenant.
  Revisions, lease checks, ownership and current authorization prevent cross-tenant
  writes or forged executor reports.
- Device identity comes from the authenticated connection, not event fields.
- Credential references resolve through scoped custody. Secrets are excluded
  from event bodies, portable workflow bundles and diagnostic logs.
- Filters and deduplication reduce duplicate admissions; neither establishes
  exactly-once external effects.
- A source retry or delivery replay must not automatically repeat an uncertain
  tool effect. Stop, revocation and result handling retain the normal execution
  authority and evidence rules.

Use [trigger management](../control-plane/trigger-assignments.md) for console/API
operation and [package authoring](../../tool-sdk/java-sdk/README.md) for the SDK.
The [source catalog](trigger-package-catalog.md) lists available examples.

## Supported scope

Client managed-file POLL is the community example path. Server placement,
general PUSH sources, provider-specific connectors, Python trigger parity and
scheduling are not implied by a structurally valid descriptor. They require
their own runtime and broker capabilities and qualification.

Read [remaining trigger work](../plans/triggers.md) before adding a source.
Generic package adoption, tenant boundaries and current execution authority
must be reused; a source-specific sidecar or ambient credential is not a substitute.
