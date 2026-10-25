# Contributing to OLO ToolGate

Start with the [development guide](docs/development/README.md), the
[component map](docs/architecture/component-map.md), and your component's README.
You do not need to understand the entire platform to fix one issue.

## Set up and choose work

```sh
git clone https://github.com/olo-labs/olo-toolgate.git
cd olo-toolgate
```

Follow the [implemented setup and verification commands](docs/development/README.md).
`make dev` is still a scaffold; it does not start the application.
Use [good first contributions](docs/contributors/good-first-contributions.md)
or open GitHub issues to choose a focused task.
Discuss large changes in an issue before implementation.

## Design and implementation

- Keep authorization, administration and execution responsibilities within the
  [documented boundaries](ARCHITECTURE.md). Create an [ADR](docs/adr/README.md)
  before changing trust, cryptography, package formats, persistence or public compatibility.
- Put cross-component contracts in `packages/contracts/schemas/` and OpenAPI in
  `packages/contracts/openapi/`. Regenerate bindings through the
  [schema workflow](docs/development/schema-workflow.md).
- Follow the existing component style. Keep changes focused and independently reviewable.
- Preserve [security invariants](docs/security/security-invariants.md). Policy
  errors, unavailable authority, malformed input and invalid signatures must not grant access.
- Keep credentials, private data and local machine state out of examples and logs.

## Before a pull request

- Add your contributor details and release-note changes as a versioned YAML
  [fragment](.changes/README.md) under `.changes/v0.1.0-beta.1/`.
- Run the relevant [verification commands](docs/testing/README.md), including
  `python tools/check.py` for the full repository check. Java changes also use
  `./gradlew javaCheck` (`gradlew.bat javaCheck` on Windows).
- Add or update tests for changed behavior; cover integration and actual denied
  effects when crossing a trust boundary.
- Update generated contracts and document compatibility or migration impact.
- Update the user/operator guide in the same change. Use the
  [documentation guide](docs/contributors/documentation-guide.md).
- Explain what changed, why, and which checks passed. Identify checks not run.

For security review, examine malformed input, dependency outages, permission
expansion, credential disclosure and signature bypass. An approval or healthy
device must never substitute for current runtime permission.

## Community

Use descriptive commit subjects such as `gateway: reject stale authority` or
`docs: explain device enrollment`. Follow the [Code of Conduct](CODE_OF_CONDUCT.md).
See [Support](SUPPORT.md) for help and [Security](SECURITY.md) for vulnerability reports.
