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
