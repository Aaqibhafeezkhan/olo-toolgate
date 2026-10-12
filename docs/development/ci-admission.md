# CI pause and RC admission

The explicitly dispatched **Manual development release (build and publish only)**
is the sole exception. It runs only on `main`, does not require `RC:` or enable
automatic CI, and builds/publishes without tests or CI verification. See
[the manual trigger](development-releases.md#one-manual-trigger-build-and-publish-only).

The shared admission workflow has no runner allocation unless the repository
variable `TOOLGATE_CI_ENABLED` is exactly `true`. The single RC admission rule is
that the PR title starts with the exact, case-sensitive prefix `RC:`.
Leave the variable absent or set it to `false` to pause automatic CI. There is
no date-based pause or automatic re-enable schedule.

To enable CI, open GitHub Settings → Secrets and variables → Actions →
Variables and set `TOOLGATE_CI_ENABLED=true`. To pause again, set it to `false`.

**ToolGate CI and release** (`ci.yml`) is the single main/version-tag push/dispatch workflow.
It admits the commit once, runs preflight, then foundation and six native platform
jobs in parallel. The image stage consumes their artifacts, and the release stage
requires all three stages to succeed. Each identical check has one owner; see
[the ownership map](ci-check-ownership.md). Pull requests and other branch pushes
do not trigger this workflow. Matching `v<VERSION>` tags use the same complete
gate graph. Preflight rejects a mismatched version before expensive builds;
publication verifies that the tag resolves to the exact checked commit, including
annotated tags. The `main_only` input remains available for compatibility callers.
Obsolete component publishers and the separate foundation tag workflow are retired.

Manual dispatch on another branch can create a skipped
workflow entry; its admission and build jobs do not run.

- Push: GitHub's commit-to-PR API resolves the merged PR that introduced the
  pushed commit to `main`. Its title must start with `RC:`. Normal merge, squash
  and rebase commits use the same rule; the commit message is not checked.
  A direct push without an associated merged PR is not admitted.
- Pull request: opening, reopening, updating commits or editing the title does
  not trigger CI. Feature-branch code runs only after it is merged into `main`.
  Ordinary issue/PR comments do not trigger a run.
- Manual dispatch: admission resolves the merged PR for the selected commit and
  checks its title. No comment input is required or used as an override.
- Version tag: admission resolves the checked commit's merged RC PR; an annotated
  tag object's SHA cannot replace that commit. Tags run their own validation context.
- Common release: same-run publication verifies the exact SHA, main branch or version tag,
  workflow and successful prerequisite jobs, including all six native targets.
  Failed/cancelled/skipped or missing gates cannot publish.
- Reusable workflows stay behind the caller's admission dependency. Foundation,
  endpoint client and common release have no independent automatic trigger.
  Legacy component workflow bodies remain callable for compatibility and are
  outside the main call graph.

With CI disabled, admission and all dependent jobs are skipped without allocating
a runner. Branches other than `main` are rejected before runner allocation.
Enabled push and manual events use a short admission job to resolve the PR title;
non-RC or missing PRs skip the dependent build/release jobs. The lookup uses
`GITHUB_TOKEN` with Pull requests read permission. Lookup errors fail admission
and prevent publication.

These changes take effect on GitHub only after the workflow files are pushed.
Reusable workflow calls must grant every permission requested by the called
workflow. The release caller explicitly includes `pull-requests: read`; omitting
that scope from its job-level permissions sets it to `none` and causes GitHub to
reject the entire workflow before admission starts. Focused ownership tests
validate each main caller against its reusable workflow's requested scopes.
They do not stop already-running jobs. Local `debug/start.bat` remains usable
while CI is paused and does not commit or push.
