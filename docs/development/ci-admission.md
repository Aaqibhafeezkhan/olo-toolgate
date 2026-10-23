# CI pause and RC admission

**ToolGate CI** (`ci.yml`) is the automatic branch-push validation entry point.
It does not publish development packages. Admission runs once, then preflight,
foundation, six native platform builds and production image checks retain their
existing validation gates. See [check ownership](ci-check-ownership.md).

The shared admission workflow allocates a runner only when repository variable
`TOOLGATE_CI_ENABLED` is exactly `true`. On main it requires the merged PR title to start
with the exact, case-sensitive prefix `RC:`. Leave the variable absent or set it
to `false` to pause automatic CI; there is no automatic re-enable schedule.

Set the variable under GitHub Settings > Secrets and variables > Actions > Variables.

- Main push: the commit-to-PR API finds the merged PR for the exact pushed commit
  and checks its title. Merge, squash and rebase commits use the same rule; the
  commit message is not checked. A direct push without a merged PR is not admitted.
- Manual CI dispatch: the same merged-PR title check applies to the selected main
  commit. Other branch pushes and manual dispatches run checks without a merged PR.
  There is no comment input or override in this workflow.
- **ToolGate CI - Manual** (`ci-manual.yml`) is an explicit dispatch-only entry point.
  It bypasses both RC title and CI enablement admission while running the same
  preflight, foundation, native and image checks. Select the source branch in
  **Run workflow**; this entry point does not publish artifacts to registries.
- Pull requests, comments and version tags do not trigger CI. A merge's push
  triggers one run, avoiding a duplicate pull-request run.
  Product releases use the separate [two manual jobs](how-to-release.md), with
  their own immutable-source selection and complete validation gates.
- Reusable component workflows have no independent automatic triggers. Compatibility
  admission/tag helpers remain callable but are outside main's publication graph.

When CI is disabled or admission rejects the ref, all dependent build jobs are
skipped. Enabled push/dispatch events use a short admission job to look up the PR;
lookup errors fail closed. This needs `GITHUB_TOKEN` with Pull requests read.
Every reusable caller must grant the scopes requested by its called workflow;
omitted scopes in a job-level permission map become `none`. Ownership tests
validate these permissions before hosted execution.

[Manual development release](development-releases.md#run-a-development-release)
is explicitly dispatched using workflow controls from any repository branch. Its
only input, `source_branch`, accepts any repository branch and pins its latest
commit once. It does not require `RC:` or enable automatic CI. It builds
and publishes without tests, scans or CI verification; compilation and packaging
checks still apply. Main CI never invokes its publisher.

These changes take effect on branches containing the updated workflow files. Existing runs are not
stopped. Local `debug/start.bat` remains usable while CI is paused and does not
commit or push.
