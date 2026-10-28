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

## PR and main baseline reports

The `Coverage and Sonar summary` workflow runs on every pull request and push
 to `main`. Its job summary lists per-module and total Java line/branch coverage,
scanner status, and a link to Sonar results when available. HTML/XML reports are
available in the `java-coverage-and-sonar` artifact. Failures are visible but
non-blocking; no coverage threshold or Sonar quality gate is enforced.

In GitHub Settings > Secrets and variables > Actions, add:

- Secret `SONAR_TOKEN`: an analysis token for your Sonar project.
- Variable `SONAR_HOST_URL`: your Sonar server URL or Cloud endpoint.
- Variable `SONAR_PROJECT_KEY`: the project key from Sonar.
- Variable `SONAR_ORGANIZATION`: your organization key, for SonarQube Cloud.

Sonar analysis is skipped until credentials/configuration are supplied, and on
fork PRs without secrets. JaCoCo reporting still runs. Configure Sonar's GitHub
integration to see server-side findings on the PR. SonarLint remains the IDE
extension; CI uses SonarScanner for Gradle. This workflow covers Java modules
included in the root build, not independent example builds or Rust/TypeScript.

## Coverage baseline scope

The reporting workflow collects Java (JaCoCo), Rust (cargo-llvm-cov), Python
(coverage.py), UI TypeScript (Vitest), and Node/contract code (c8). Its Java
runner reuses the isolated PostgreSQL fixture and cleans it up even on failure.
The root Java report includes compiled classes from modules without tests;
Python, UI and c8 source includes retain unexecuted files in their denominators.
Collectors continue independently; missing or failed collection is explicitly
incomplete and must not be read as 100% or as a trustworthy whole-repo total.

Download `java-coverage-and-sonar` for reports and `source-inventory.json`.
The Actions summary lists collector status, module completeness and exclusions.
Independent example builds and unsupported shell/probe languages are named
measurement gaps. Tests, fixtures, frozen probe evidence, generated output and
TypeScript declarations are excluded; documentation/configuration are outside
executable coverage. Production/generated contracts that are compiled as part
of Java remain in that report; exclusions never blanket-remove contract code.

Sonar receives Java XML, Python XML and JS/Rust LCOV. It may still analyze
partial results if a collector fails; review the workflow completeness summary.
Sonar source scope explicitly includes the non-Java production trees alongside
the Java subprojects. Paths are rooted at the repository checkout. No new
coverage threshold is enforced; the existing narrow UI check remains unchanged.
