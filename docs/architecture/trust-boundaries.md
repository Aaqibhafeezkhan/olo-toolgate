# Trust boundaries

## Current authority

Control evaluates current group grants, policy obligations, identities and resource
constraints. It issues short-lived permits; Gateway holds no effect-signing key
and cannot fall back to offline ALLOW. A permit requires online single-use
consumption and the applicable current checks at the executor.

## Device and package trust

Device identity comes from a verified TLS peer or composition-owned credential,
never a caller-supplied header or tool argument. Owner/device enablement and
installed-client connection approval remain required independently of tool grants.

A signed organization assignment identifies an approved package and target.
It does not authorize every tool invocation. Uploaded code stays outside Control;
builder tests execute only on an explicitly designated enrolled endpoint with
a bounded signed lease. See [tool authoring](../client/tool-builder.md).

## Execution isolation

Managed endpoint OCI runtimes enforce the limits described in
[local runtimes](../client/local-runtimes.md). Tool Host Java execution currently
has a clean environment, permit checks and a broker, but container/network
isolation is unfinished. Do not infer confinement from a route declaration alone.
See [Tool Host limitations](../../apps/tool-host/README.md#not-yet).

## Administrative authority

Scoped management uses independent review. Super Admin has an audited
[review exception](../adr/022-super-administrator-review-exception.md), but still
needs runtime grants and cannot override effect authorization or retired identity.

## Planned distribution boundary

Marketplace signing is planned provenance for immutable community releases.
It will remain separate from organization deployment signing and runtime
authorization. See [the backlog](../planning/README.md).
