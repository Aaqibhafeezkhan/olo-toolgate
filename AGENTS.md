<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Repository instructions

Follow [CONTRIBUTING.md](CONTRIBUTING.md) and component verification requirements.

## Current documentation

- Keep one maintained guide per topic: implemented behavior, operating requirements,
  trust boundaries and known limitations.
- Keep only genuinely unfinished work in `docs/plans/`, with remaining tasks
  and acceptance criteria. Remove delivered tasks; do not keep implementation
  diaries, prompt transcripts, progress percentages, completed-stage tables or
  superseded proposals.
- Update owning architecture/operator guides with implementation changes. Repair
  incoming links and documentation checks when moving or removing pages.
  Git history retains previous designs; generated evidence belongs in ignored
  build output, and execution/release results belong in PRs or CI artifacts.
- Do not infer runtime support or security guarantees from structural validation,
  imported configuration, local examples or available image tags. Record only
  checks actually executed and describe their limits.
- Preserve license notices, generated release snapshots, canonical specifications
  and immutable fixtures consumed by release or verification tools.

## Fresh breaking builds

Use current contracts and fresh schemas only. Do not add backward compatibility,
database migrations, upgrade/backfill/conversion paths, compatibility constructors,
legacy aliases or legacy runtime support. Reject unsupported existing stores and
obsolete wire/package formats without converting or deleting them. Fold schema
changes into fresh initialization. Recovery within the current schema remains required.

## Security and delivery

- Control owns authorization; device approval, deployment state and administrator
  privileges never replace current runtime grants or effect authority.
- Preserve denied/unknown outcomes. Do not automatically replay uncertain effects.
- Review trust, signing, persistence and public-contract changes before
  implementation; update current architecture and canonical contracts together.
- Keep code, relevant tests and operating instructions in the same change.
  Run applicable component gates and report missing tools or unexecuted acceptance.
- For #230 implementation, finish the scoped stage and applicable verification
  before one commit containing code, tests and current documentation. Push it to
  `feature/zero-trust-execution-plan` before starting the next stage unless the
  user selects another delivery workflow. Do not commit partial stage fragments
  or separate documentation-only progress follow-ups.
- For #274 modules, use `feature/triggers-m<NN>-<slug>` from current main, with
  one PR per module back to main. The M5/M10/M11/M12 end-to-end qualification
  bundle remains one PR. Keep focused commits local, finish database, cross-tenant,
  contract and component checks, then push/open the completed module PR.
- Target workflow integration PRs at `feature/workflow-enhancement`; keep
  umbrella PR #297 draft until end-to-end acceptance is executed. Reuse current
  trigger/execution interfaces. A local slice does not complete wider qualification.
