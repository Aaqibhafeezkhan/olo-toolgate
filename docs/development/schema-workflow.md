# Schema and Shared Contract Workflow

## Purpose

Keep every ToolGate component aligned on one canonical cross-language contract set.

## Source of Truth

```text
packages/contracts/
  schemas/
  openapi/
  events/
```

## Bindings

Generated/publishable packages:

```text
packages/contracts/rust
packages/contracts/java
packages/contracts/typescript
packages/contracts/php
```

## Workflow

```text
edit canonical contract
       |
       v
generate bindings
       |
       v
format
       |
       v
compile every language package
       |
       v
current-format and obsolete-input rejection tests
       |
       v
CI verifies no generated drift
```

Generated bindings should be checked in.

## Dependency Rule

Within the monorepo use workspace/local package resolution.

After repository separation use published SemVer packages.

Do not change application import names during the split.

See:

- [Shared contracts](schema-workflow.md)
- [Release operator guide](how-to-release.md)

## Published package identities

| Language | Package |
|---|---|
| Rust | `olo-toolgate-contracts` |
| Java | `io.github.olo-labs:toolgate-contracts` |
| TypeScript | `@olo-labs/toolgate-contracts` |
| PHP | `olo-labs/toolgate-contracts` |

Use the checked-in schema and generator subset, not a parallel handwritten wire
model. Product versions come from `version.json`; wire-schema versions identify
contracts, not deployment compatibility windows. Keep canonical schemas, generated
bindings, fixtures and language round-trip checks in the same change.
