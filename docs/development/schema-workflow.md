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
compatibility test
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

- [shared-contracts](../architecture/shared-contracts.md)
- `docs/architecture/shared-contracts.md`
- `docs/development/contracts-release.md`
