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
