<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 066: Adopt watches through packages and workflow profiles

Status: Accepted for the fresh-stack deployment contract; executor extensions
remain subject to the #274 module gates.

The deployment operator must not select behavior by a watch name. A reviewed
package supplies its trigger exports, mode, configuration/event schemas and
capability declarations. A workflow-local source selects an exact package
version/digest, trigger, configuration, subscription filter and dispatch binding.
The binding selects the workflow/version and message pointer. An event cannot
select its own workflow, actor, model, credentials or execution authority.

The generic platform verifies/provisions packages, assigns supported executors,
validates configuration against the signed descriptor, supervises sources and
leases, applies quotas, ingests/deduplicates events, admits dispatch, and records
results. It does not contain branches for hotfolder, webhook, log, email or other
watch identities. Adding a package that uses existing supported primitives and
its profile must not require a deployment image rebuild, Compose service, source
code edit or watch-specific environment variable.

WorkflowStack accepts a selected artifact from its reviewed image catalog or
the selected configuration's `packages/` directory. External artifacts require
an exact package-digest pin in the profile. The operator copies only the selected
artifact into sealed custody; Control still performs canonical package and
descriptor validation. Hash matching alone is not package/runtime qualification.
Local package selection is an explicitly privileged operator bootstrap action,
not publisher authentication or an independent security review. It must never
be exposed as an untrusted event-upload or public package-install endpoint.
An immutable package change is refused on redeployment. Subscription changes
require a new subscription identity rather than silently retaining an old filter.

The folder-delivery adapter is optional and specific to file-delivery profiles.
Non-file workflows use event data through their configured message pointer and
write the normal private workflow result. They do not provision a file-tool agent
or require a `sourcePath` field.

Only current contracts and fresh stores are accepted. There are no old-name
aliases, migrations or automatic store conversion. The default file-watch package
is `examples.hotfolder-watch`, exporting `new-file`.

This contract does not grant arbitrary host/network access. A new fundamental
capability must be implemented and qualified as a generic executor/broker
primitive once. Unsupported modes, placements, capabilities or provider
authentication schemes must fail closed. In particular, package discovery does
not implement the still-pending PUSH/server/secret/ingress/schedule modules.
The [source catalog](../architecture/trigger-package-catalog.md) separates those
requirements from implemented source packages and acceptance evidence.
