# Gradle Build

## Purpose

OLO ToolGate uses Gradle Kotlin DSL for Java modules.

## Java Modules

```text
:contracts-java
:control-plane
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
```

The Java modules then resolve:

```text
io.github.olo-labs:toolgate-contracts:0.1.0-SNAPSHOT
```

from Maven Local instead of the workspace project.

This test proves that repository separation will not require Java source import changes.

## Java coverage and SonarLint

JaCoCo 0.8.16 is applied to every Java module in the root Gradle build.
Running a module's `test` task also generates its coverage report. Run all
Java checks with `./gradlew javaCheck`, or one module with
`./gradlew :contracts-java:jacocoTestReport` (use `gradlew.bat` on Windows).
Reports are written inside each module at
`build/reports/jacoco/test/html/index.html` and
`build/reports/jacoco/test/jacocoTestReport.xml`.
No minimum coverage threshold is enforced.

For editor analysis, install the recommended `SonarSource.sonarlint-vscode`
extension from VS Code's Extensions panel. SonarLint is now named SonarQube
for IDE. In IntelliJ IDEA, install SonarQube for IDE from the plugin marketplace.
Local analysis does not require a Sonar server. Connected mode can be configured
in the IDE when a server and project are available; do not commit access tokens.

## Wrapper Bootstrap

The initial repository uses a checksum-verifying bootstrap script because the architecture scaffold was created before a generated wrapper JAR was available.

Maintainers should replace it with Gradle's standard generated wrapper files as soon as possible and commit the official `gradle-wrapper.jar` after verifying its published SHA-256 checksum.
