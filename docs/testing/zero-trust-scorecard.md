<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Zero Trust Execution scorecard

Run from the ToolGate checkout:

```sh
python tools/security/scorecard.py --execute
```

The runner inventories all security/integration conformance definitions and emits
JSON and Markdown under ignored `build/zero-trust-scorecard/`. It records the
actual Git revision and the full tracked/untracked nonignored source digest.
Changing source during execution invalidates the result. Old JSON/Markdown
acceptance is invalidated before a producer starts.

The current supported observation runs the actual Rust broker mTLS single-send
test against scripted Control/target. A refused gate contacts no target; an
allowed gate sends once. A zero-test result, ignored test, failed process, missing
tool or timeout is not a passing observation. This is useful scoped evidence,
not the entire C3b/C8 assertion or actual Control authorization test.

## Read the result

The strict command exits **1** while required cases/claim checks are incomplete,
and **2** for invalid input, source changes or report-generation errors.
Inventory-only execution (omit `--execute`) also exits 1 and credits no observations.
A supported observation may fail while the overall result remains incomplete;
read its explicit FAILED status. Observation failure still exits 1; the CI
publication verifier separately makes a failed/missing observation a job failure. No mode turns missing coverage into acceptance.

C1–C8 expand into ten checks because C3 has a, b and c. Each row names attacker
classes and required cases. All 209 current security/integration definitions are
included, even if their authored metadata says passing. Their complete
case-specific execution adapters remain missing in this first harness version.
The report says MISSING, not that 209 tests executed or failed. A scoped passing
observation never promotes a complete case or claim. Additional per-claim
requirements (such as exact consent, external anchors and production SLOs) remain
explicit alongside the case coverage; future adapters must prove their entire
assertions and target matrix.

Source digests use sorted Git paths, NUL, and each file's raw SHA-256 bytes,
matching the rootless runner's recipe. Source links are refused. Log hashes bind
the captured observation; they are not signatures or protection against a
compromised CI runner, repository author or Control operator.

## CI publication

The reusable `zero-trust-scorecard.yml` workflow is called after preflight by
automatic and manual CI. It executes the available observation, uploads the
scorecard/logs and writes the full coverage table to the Actions summary.
The scorecard step's expected nonzero incomplete result does not prevent artifact
upload. The final diagnostic check still fails on producer errors, changed
revision/source, omitted cases/claims, missing/failed observations, changed logs
or inconsistent Markdown. Read-only repository permission and pinned actions
are used; no credentials or publication authority are needed.

This is explicitly a **diagnostic job**, not a release security acceptance gate.
Its green job result means diagnostic evidence was produced and validated,
not that the product passes C1–C8. Required case implementations belong to their
owning delivery stages. Final release acceptance must require complete,
revision/deployment-bound case and claim evidence; that integration remains
outstanding. Hosted workflow execution has not been established by a local run.

See the [adopted security model](../security/zero-trust-boundaries.md) and
[live stage/claim inventory](../security/zero-trust-execution-status.md).
