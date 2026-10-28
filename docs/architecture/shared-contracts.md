# Shared Contract Libraries

## Purpose

Ensure every ToolGate component can consume common protocol/data contracts locally today and from published packages after repositories are separated.

## Model

```text
Canonical JSON Schema / OpenAPI / Event Contracts
                    |
                    v
             deterministic generation
                    |
        +-----------+-----------+-----------+
        |           |           |           |
       Rust        Java      TypeScript     PHP
        |           |           |           |
      crate        JAR          npm       Composer
```

## Stable Package Identities

```text
Rust:
  olo-toolgate-contracts

Java:
  io.github.olo-labs:toolgate-contracts

TypeScript:
  @olo-labs/toolgate-contracts

PHP:
  olo-labs/toolgate-contracts
```

Components use those identities from day one.

The monorepo supplies them through local workspace resolution.

A future split repo supplies them through a package registry.

## Example: Rust

Monorepo:

```toml
olo-toolgate-contracts = { path = "../../packages/contracts/rust", version = "0.1.0" }
```

Separate repository:

```toml
olo-toolgate-contracts = "0.1"
```

Rust source code remains:

```rust
use olo_toolgate_contracts::...;
```

## Example: Java

Both monorepo and separated repo use:

```xml
<dependency>
  <groupId>io.ololabs.toolgate</groupId>
  <artifactId>toolgate-contracts</artifactId>
  <version>${toolgate.contracts.version}</version>
</dependency>
```

In the monorepo, Gradle resolves the local `:contracts-java` project.

After separation, Gradle resolves the same Maven coordinates from the configured artifact registry.

## Example: TypeScript

Monorepo:

```json
"@olo-labs/toolgate-contracts": "workspace:*"
```

Separate repository:

```json
"@olo-labs/toolgate-contracts": "^1.4.0"
```

Application imports remain identical.

## Example: Drupal / PHP

Monorepo uses a Composer path repository pointing to:

```text
packages/contracts/php
```

After separation, Composer uses the published registry package.

Namespace/imports remain identical.

## Rule

No component may create an unofficial duplicate contract model merely because it is convenient.

If the data crosses a component boundary, first ask whether it belongs in the canonical contract set.
