# OLO ToolGate Shared Contracts

This directory is a publishable contract set used by every ToolGate component.

## Canonical Sources

```text
schemas/   JSON Schema and other language-neutral data contracts
openapi/   HTTP API contracts
events/    event/webhook envelopes
```

## Generated / Publishable Bindings

```text
rust/        crate: olo-toolgate-contracts
java/        Maven: io.github.olo-labs:toolgate-contracts
typescript/  npm: @olo-labs/toolgate-contracts
php/         Composer: olo-labs/toolgate-contracts
```

## Contract set versions

v1 and v2 bindings ship side by side in the same artifacts. v2 models live in their own namespace:

| Language | v1 | v2 |
|---|---|---|
| Rust | crate root | `olo_toolgate_contracts::v2` |
| Java | `io.ololabs.toolgate.contracts` | `io.ololabs.toolgate.contracts.v2` |
| TypeScript | `@olo-labs/toolgate-contracts` | `@olo-labs/toolgate-contracts/v2` |
| PHP | `OloLabs\ToolGate\Contracts` | `OloLabs\ToolGate\Contracts\V2` |

In v2, constant fields (for example `schemaVersion: 2`) are literal types in TypeScript and are rejected on decode in Rust, Java and PHP when they differ. Enum values that are not identifiers (`control-breakglass`) become `ControlBreakglass` in Rust and `control_breakglass` in Java and PHP, with the wire value unchanged.

## Development Rule

Applications in the monorepo should resolve these packages from the workspace.

Application source must import the same public package identity it would use after repository separation.

## Release Rule

All language artifacts for one contract set share the same SemVer.

Read [`../../CONTRACTS.md`](../../CONTRACTS.md).
