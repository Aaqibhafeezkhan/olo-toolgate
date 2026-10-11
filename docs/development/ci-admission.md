# CI pause and RC admission

The explicitly dispatched **Manual development release (build and publish only)**
is the sole exception. It runs only on `main`, does not require `RC:` or enable
automatic CI, and builds/publishes without tests or CI verification. See
[the manual trigger](development-releases.md#one-manual-trigger-build-and-publish-only).

Automatic build and release work is paused. The shared admission workflow has
no runner allocation unless the repository variable `TOOLGATE_CI_ENABLED` is
exactly `true` and the event has an RC candidate. Leave the variable absent or
set it to `false` until you explicitly want CI again. No automatic re-enable is
scheduled. Builds are additionally blocked through October 11, 2026, IST;
the earliest allowed execution is Monday October 12 at 00:00 IST.

To enable later, open GitHub Settings → Secrets and variables → Actions →
Variables and set `TOOLGATE_CI_ENABLED=true`. To pause again, set it to `false`.

- Push: the latest commit message must start with the exact, case-sensitive
  prefix `RC:`, for example `RC: validate Windows installer`. An earlier RC
  commit in the same push does not qualify the latest non-RC commit.
- Pull request: its title alone must start with the exact, case-sensitive `RC:`
  prefix; the head commit message is not checked. Opening, reopening, updating
  commits or editing the title triggers admission, still requiring
  `TOOLGATE_CI_ENABLED=true` and the resume boundary above. Ordinary issue/PR
  comments do not trigger a run.
- Manual dispatch: supply the required `comment` input beginning with `RC:`.
- Development release: the successful originating push must have an `RC:`
  head commit. Failed/cancelled upstream runs cannot publish.
- Reusable workflows stay behind the caller's admission dependency. Calling
  the client workflow directly also runs its admission check.

GitHub cannot filter commit messages in the `on.push` declaration, so it may
display a skipped workflow entry for non-RC or paused events. Their admission
job and all build/release jobs remain skipped, consuming no runner minutes.
Once enabled, a qualifying RC event uses a short admission job to enforce the
date boundary and validate its trigger before any expensive job starts.

These changes take effect on GitHub only after the workflow files are pushed.
They do not stop already-running jobs. Local `debug/start.bat` remains usable
while CI is paused and does not commit or push.
