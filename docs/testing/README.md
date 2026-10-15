# Testing and verification

Run checks for the behavior you change, then the required repository/CI gates.
Use the [local CI runner](../development/local-ci.md) to inspect workflows and
replay supported jobs. It reports coverage gaps explicitly.

| Scope | Entry point |
|---|---|
| Full repository | `python tools/check.py` |
| Contract generation and fixtures | `python tools/contracts/generate.py --check`; `python tools/contracts/fixtures.py --check` |
| Contract regression tests | `python -m unittest discover -s tests/contracts -v` |
| Java modules | `./gradlew javaCheck` or `gradlew.bat javaCheck` |
| Rust workspace | `cargo test --workspace --locked` |
| Console | `npm run ui:check`; [UI verification](../../apps/admin-ui/README.md) |
| Quickstart and real enrollment/effects | `python tools/quickstart/check.py --build` |
| Enterprise acceptance | `python tools/enterprise/finish.py`; [evidence and scope](../enterprise-access-control/implementation-status.md) |
| SDK conformance | [Cases and runner contract](../../tool-sdk/conformance/README.md) |
| Performance | [Gateway methodology](../gateway/performance.md) |
| Release checks and scans | [Release process](../development/release-process.md) |
| Documentation | `make docs` |

Use deterministic fixtures for unit tests and real service boundaries for
integration/E2E checks. Run persistence changes against SQLite and PostgreSQL.
The [foundation guide](../development/foundation.md) lists required tools and
container-backed checks; dependencies that are missing must be reported.

## Security-sensitive changes

Prove denied effects as well as successful responses. Cover malformed input,
forged identity, cross-tenant access, replay, expired or revoked authority,
resource traversal, SSRF, secret leakage and invalid signatures.

For devices, cover independent enablement and approval, timed/unlimited approval,
stable revision checks during check-in, exact-key recovery, irreversible revocation
and restart. HTTP 423 suspension must preserve identity without granting access.
For execution, cover crash/retry, duplicate admission, stale fences and unknown
outcomes. Health and deployment state never replace authorization.

## Evidence

Record the source revision, command, environment and result. Keep generated logs
under ignored build output. A conformance case marked `authored` is a requirement,
not a passing test. Historical compatibility records and enterprise evidence
remain useful only with their recorded scope and limitations.
