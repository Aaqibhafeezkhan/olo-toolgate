# Split Repository Readiness

## Goal

The monorepo is convenient now.

Future separation should not require architecture rewrite.

Potential future repos:

```text
olo-toolgate-gateway
olo-toolgate-control
olo-toolgate-client
olo-toolgate-marketplace
olo-toolgate-contracts
```

## Requirements

Shared contracts use published identities from day one.

External integration occurs through:

```text
API
event
package
library
```

not source-directory reach-through.

## Java

Monorepo:

```kotlin
implementation(project(":contracts-java"))
```

Separated:

```kotlin
implementation("io.github.olo-labs:toolgate-contracts:<version>")
```

Imports unchanged.

## CI Test

Maintain a test mode that publishes contracts locally and builds services against the artifact rather than the project dependency.
