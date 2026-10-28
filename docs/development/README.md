# Develop ToolGate

ToolGate is in Beta Release. See [production and security status](../../README.md#production-and-security-status)
for current limitations and their open enhancement tickets.

Run commands from the repository root. The repository contains working services,
but `make dev`, `make stop` and `make client` are still scaffolds. Use the commands
below and the component guides for the implemented workflows.

## Prerequisites

Use Java 21, Python 3.12+, Node 22, Docker and the Rust toolchain pinned in
`rust-toolchain.toml`. The Gradle wrapper downloads its pinned distribution.
PHP, Helm and other check-specific tools are described in the
[foundation workflow](foundation.md) and [local CI guide](local-ci.md).

Install Python requirements from `tools/requirements.txt` in a virtual environment.
Run `npm ci` to install workspace packages and the repository's commit hooks.
Browser checks need Playwright Chromium; follow the foundation guide for setup.

## Run and verify

| Task | Command or guide |
|---|---|
| Build and verify Quickstart with disposable fixtures | `python tools/quickstart/check.py --build` |
| Persistent Windows debug installation | `debug/start.bat`; [debug guide](../../debug/README.md) |
| Run an evaluation image and retain its state | [Quickstart Compose](../../deploy/compose/QuickStart/README.md) |
| Full repository checks | `python tools/check.py` (or `make check`) |
| Contract checks | `python tools/check.py --contracts-only` |
| Java checks | `./gradlew javaCheck` or `gradlew.bat javaCheck` |
| UI checks | `npm run ui:check` |
| Documentation structure | `make docs` |
| Inspect CI coverage | `python tools/ci/local.py plan` |
| Replay supported CI jobs | [Local CI](local-ci.md) |

Docker-backed integration checks need their documented dependencies and may build
images. Missing tooling is a failure, not a silently skipped check.

## Find the right guide

- [Repository layout](../architecture/monorepo-layout.md) and [component ownership](../architecture/component-map.md)
- [Gradle build](gradle.md), [schema workflow](schema-workflow.md) and [release guide](how-to-release.md)
- [Testing](../testing/README.md) and [debugging](../operations/debugging.md)
- [How to release: two manual jobs](how-to-release.md), [release process](release-process.md) and [development releases](development-releases.md)
- [CI ownership](ci-check-ownership.md) and [CI admission](ci-admission.md)
- [Directory membership](../security/enterprise-access-control.md) and [managed roles](../security/user-roles.md)
