# Gradle Build

## Purpose

OLO ToolGate uses Gradle Kotlin DSL for Java modules.

## Java Modules

```text
:contracts-java
:control-plane
:marketplace-api
:marketplace-worker
```

## Requirements

- Java 21.
- No global Gradle installation is required when using `./gradlew`.

Pinned Gradle:

```text
9.7.1
```

## Commands

```bash
./gradlew projects
./gradlew javaCheck
./gradlew build
./gradlew :contracts-java:build
./gradlew :contracts-java:publishToMavenLocal
```

## Workspace Contracts

Default:

```bash
./gradlew :control-plane:build
```

uses:

```text
project(":contracts-java")
```

## Simulate Split Repository

First publish contracts locally:

```bash
./gradlew :contracts-java:publishToMavenLocal
```

Then:

```bash
./gradlew \
  -PusePublishedContracts=true \
  -PcontractsVersion=0.1.0-SNAPSHOT \
  :control-plane:build \
  :marketplace-api:build \
  :marketplace-worker:build
```

The Java modules then resolve:

```text
io.github.olo-labs:toolgate-contracts:0.1.0-SNAPSHOT
```

from Maven Local instead of the workspace project.

This test proves that repository separation will not require Java source import changes.

## Wrapper Bootstrap

The initial repository uses a checksum-verifying bootstrap script because the architecture scaffold was created before a generated wrapper JAR was available.

Maintainers should replace it with Gradle's standard generated wrapper files as soon as possible and commit the official `gradle-wrapper.jar` after verifying its published SHA-256 checksum.
