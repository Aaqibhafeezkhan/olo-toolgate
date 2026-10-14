# Gradle Project

This commit converts the Java side of OLO ToolGate to a **Gradle Kotlin DSL multi-project build**.

## Included Java Projects

```text
:contracts-java
:control-plane
:marketplace-api
:marketplace-worker
```

## Java

```text
Java 21
```

## Gradle

```text
Gradle 9.7.1
```

## Commands

```bash
./gradlew projects
./gradlew javaCheck
./gradlew build
```

## Shared Contracts

Default monorepo mode:

```text
implementation(project(":contracts-java"))
```

Published/split-repo mode:

```text
io.github.olo-labs:toolgate-contracts:<version>
```

Test the split-repo path:

```bash
./gradlew :contracts-java:publishToMavenLocal

./gradlew \
  -PusePublishedContracts=true \
  -PcontractsVersion=0.1.0-SNAPSHOT \
  :control-plane:build \
  :marketplace-api:build \
  :marketplace-worker:build
```

## Suggested Commit

```text
build: initialize Gradle multi-project Java workspace
```
