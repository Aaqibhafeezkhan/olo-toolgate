# Compatibility and upgrades

Use the root `VERSION` for product artifacts and `packages/contracts/VERSION`
for the generated contract set. Deploy matching Gateway, Control, console,
Quickstart and managed-client artifacts for the intended release. The group-only
enterprise cutover is a breaking development-version change: old individual ACL
writers and the old Gateway evaluator are not compatibility fallbacks.

## Check before upgrading

- Read [release notes](../../RELEASE-NOTES.md) and [Control upgrades](../control-plane/upgrades.md).
- Verify supported package format, descriptor generation, runtime and OS/architecture
  for each assigned tool. Unknown or unsupported contracts fail closed.
- Preserve identity/custody, spent nonces, audit and retired IDs. Database schema
  history is not reversed by a Helm rollback; use reviewed isolated recovery.
- Validate both SQLite and PostgreSQL migrations when changing persistence.
- Keep generated bindings and canonical schemas in sync through the
  [schema workflow](../development/schema-workflow.md).

The [MCP version decision](../adr/018-mcp-versions-legacy-adapter.md) includes an
amendment withdrawing the legacy adapter. Follow the current decision rather
than assuming older protocol support from an earlier plan.

Use [SDK compatibility evidence](../../tool-sdk/spec/sdk-compat/README.md),
[package deployment](../client/package-deployment.md) and
[installer limitations](../client/installers.md) for their respective support
boundaries. Recorded conformance is tied to its pinned versions and does not
establish a blanket N/N-1 or all-platform support promise.

Closed schemas can reject additive fields or enum values. Review accepted inputs
and security meaning when assigning a contract version; a breaking change needs
an explicit migration/version decision. Preserve frozen compatibility fixtures
instead of rewriting them to hide a breaking change.
