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

The five CI workflows (`foundation`, `endpoint-client`, `control`, `gateway`
and `quickstart`) run automatically only for pushes to `main` and PRs targeting
`main`. Their manual dispatches and reusable calls also require `main`, or a PR
targeting `main`, through shared admission. Development publishing also requires
an originating `main` run. The `main_only` input disables the separate tag release
path; branch runs always require `main`.
Other branch pushes, PR targets and tag pushes do not trigger these five.
The separate `release-foundation` workflow retains its `v*` tag trigger.
Tag publishing in `control`, `gateway` and `quickstart` is unavailable while
these five workflows are restricted to `main`; their publish jobs require a
tag and are skipped on `main`, even if a manual dispatch sets `publish=true`.

GitHub shows PR runs under the PR's source branch in Actions, even when the
target is `main`. Manual dispatch on another branch can create a skipped
workflow entry; its admission and build jobs do not run.

- Push: GitHub's commit-to-PR API resolves the merged PR that introduced the
  pushed commit to `main`. Its title must start with `RC:`. Normal merge, squash
  and rebase commits use the same rule; the commit message is not checked.
  A direct push without an associated merged PR is not admitted.
- Pull request: its title alone must start with the exact, case-sensitive `RC:`
  prefix; the head commit message is not checked. Opening, reopening, updating
  commits or editing the title triggers admission, still requiring
  `TOOLGATE_CI_ENABLED=true`. Ordinary issue/PR comments do not trigger a run.
- Manual dispatch: admission resolves the merged PR for the selected commit and
  checks its title. No comment input is required or used as an override.
- Development release: admission resolves the merged PR for the successful
  originating push's exact SHA and checks its title. Failed/cancelled/skipped
  upstream runs and runs from other repositories cannot publish.
- Reusable workflows stay behind the caller's admission dependency. Calling
  the client workflow directly also runs its admission check.

With CI disabled, admission and all dependent jobs are skipped without allocating
a runner. PR events with non-RC titles also skip admission before runner allocation.
Enabled push and manual events use a short admission job to resolve the PR title;
non-RC or missing PRs skip the dependent build/release jobs. The lookup uses
`GITHUB_TOKEN` with Pull requests read permission. Lookup errors fail admission
and prevent publication.

These changes take effect on GitHub only after the workflow files are pushed.
They do not stop already-running jobs. Local `debug/start.bat` remains usable
while CI is paused and does not commit or push.
