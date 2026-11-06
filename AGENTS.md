<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Repository instructions

## Issue #230: Zero Trust Execution progress

For work implementing or fixing issue #230 and its attached execution plan:

- Maintain `docs/security/zero-trust-execution-status.md` as the authoritative
  live progress document. The revision-10 proposal in
  `docs/plans/zero-trust-tool-execution.md` remains the preserved plan.
- Update the overall phase table, first vertical-slice acceptance table and
  affected security claims with every implementation change. Include completed
  scope, validation actually executed, limitations and the next outstanding work.
- Commit the progress update in the same commit as its implementation changes.
  Push the code and progress together to `feature/zero-trust-execution-plan`,
  unless the user explicitly requests a different branch or delivery workflow.
- Distinguish a delivered slice from a completed roadmap phase. Do not claim a
  phase or security guarantee is complete until its full exit criteria have
  executed evidence. Do not invent progress percentages or test results.
- Record a known tested revision or source digest. Do not invent the SHA of the
  commit being created, or add a separate status commit solely to refer to itself.

Follow `CONTRIBUTING.md` and the component's verification requirements as well.

## Issue #274: Triggers progress

For work implementing issue #274 (server and client triggers):

- `docs/architecture/triggers-implementation-status.md` is the authoritative live progress document. The design is
  `docs/architecture/device-triggers-plan.md`; its section 24.2 defines the modules, weights and *Done when* criteria.
- One branch and one PR per module: branch `feature/triggers-m<NN>-<slug>` from the latest `main`, PR back to `main`.
  Modules on independent tracks (plan section 24.5) may have PRs open at the same time.
- Within a module, iterate on small changes and focused tests without intermediate commits. When the module meets
  its *Done when*, run its full verification (module tests on SQLite and PostgreSQL where relevant, cross-tenant
  negatives, contract regeneration checks, the component gates from `CONTRIBUTING.md`, and the M12 scenarios it
  owns), fix failures, then make one commit with code, tests, docs and the progress update, and open the PR.
- Every PR description contains the module progress table from the status document, with this module shown as
  "In this PR" and the overall percentage before and after merge. A module earns its weight only when merged.
- Record only checks actually run, with their results. Record scope or weight changes explicitly under *Baseline
  changes*; never change the denominator silently. Do not invent the SHA of the commit being created.
- Triggers are unreleased: plan section 0 forbids backward compatibility, database migrations and legacy code. The
  fresh-build constraint below applies to #274 as well.

## Fresh breaking-build constraint

For issue #230, current and future implementation uses current contracts and
fresh schemas only. Do not add backward compatibility, database migrations,
upgrade/backfill/conversion paths, compatibility constructors, legacy aliases
or legacy runtime support. Reject unsupported existing stores and obsolete
wire/package formats without converting or deleting them. Fold schema changes
into fresh initialization; recovery within the current schema remains required.
This user constraint supersedes migration/compatibility proposals in preserved
planning documents.

## Stage delivery workflow

For issue #230, follow the weighted S01–S33 baseline and completion criteria in
the authoritative status document. Within a stage, repeat small focused code
changes, focused tests and fixes without intermediate commits. Once the complete
stage scope is implemented, run thorough applicable stage-level verification,
fix failures and rerun affected checks before committing. Then make one commit
per affected repository containing its code,
tests, documentation and progress updates; push before taking the next stage.
Do not commit intermediate stage fragments or separate progress-only follow-ups.
Preserve existing history. Partial stages earn no weight; full roadmap phases
still require all original exit criteria. Record any baseline scope/weight change
explicitly rather than silently changing the percentage denominator.
