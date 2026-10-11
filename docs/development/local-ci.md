# Run CI locally

The workflow files in `.github/workflows/` are the source of truth. The local
runner reads their job bodies at execution time, including exact commands,
actions and setup versions. Changing a workflow step changes the local run too.
Changes inside existing validation scripts affect both callers. There is no
separate local list of build/test commands.

## Setup

Use Python 3.12 or newer and Docker running Linux containers. Install validation
dependencies in your own virtual environment:

```sh
python -m venv .venv
# Activate .venv using your shell's activation command.
python -m pip install -r tools/requirements.txt
```

For replay, install [act](https://nektosact.com/installation/) on your PATH or
supply its executable with `--act PATH`. No release credentials are needed.
The runner refuses implicit `.actrc` configuration and supplies empty
environment, variables, inputs and secrets files to act.

## Commands

```sh
# Discover actual triggers, dependencies, matrices and coverage gaps.
python tools/ci/local.py plan

# Run shared metadata/admission regression tests and pinned actionlint.
python tools/ci/local.py audit

# Replay the exact preflight job, including browser checks.
python tools/ci/local.py run --job preflight.metadata

# Replay selected full job bodies.
python tools/ci/local.py run --job foundation.check --job gateway.container-and-replicas

# Attempt all supported bodies and report every remaining gap.
python tools/ci/local.py run
```

The default is `plan`; it needs Python and PyYAML. `audit` and `run` need Docker;
`run` also needs act and starts with the audit. The first replay builds
`tools/ci/local.Dockerfile`; Rust is installed using `rust-toolchain.toml`, while
workflow actions install Java, Node and Python. This can download several GB;
full build jobs can take as long as hosted CI. `--image IMAGE` uses a preinstalled
custom Ubuntu 24.04 amd64 runner image. Source, including uncommitted files, is
exported to a disposable Git snapshot and copied into the container. Ignored
credentials/build state stay out; Git index scans test the exported source.
Job builds do not bind-mount and overwrite the checkout.

Replay uses a privileged runner container with a private Docker daemon and a
separate network namespace. The host daemon's socket is not mounted. Nested
Docker gates can see their fixtures and containers without sharing the host's
test containers. The extra daemon startup step is local environment preparation;
the subsequent workflow steps are copied unchanged. Native system service
tests still require real VMs.

Reports and logs are under `build/local-ci/`. `plan.json` records the actual
workflow declarations, including matrix and job conditions. `report.json`
records each executed, unverified or unselected job and hashes the working tree
before and after replay. `audit-report.json` records the audit separately;
starting a replay resets previous success before auditing. A source change
invalidates the result.

| Exit code | Meaning |
| --- | --- |
| 0 | The requested scope passed: inventory, audit, or selected job bodies. |
| 1 | A check failed, a prerequisite is missing, or coverage needs review. |
| 2 | Requested jobs include work that could not be verified locally. |

All-job execution currently returns **2**, even if supported jobs pass.
New executable jobs fail inventory until their coverage policy is assigned.
New context expressions, conditional steps, actions or runner types in
replayable jobs need review; they cannot silently be discarded or skipped.

## Triggers and limits

`plan` reads declarations rather than assuming branch names. Current automatic
workflows run on PRs, `main` pushes and `v*` tag pushes. There are no `master`,
`dev` or `release/*` push triggers. PRs have no base-branch filter. Development
publication follows a completed `control` run on `main`, with further success,
source-repository and admission checks; both development-release workflows
also support manual dispatch.

Replay deliberately runs the **job body**, independently of admission and
upstream dependencies, using a synthetic dispatch workflow. Real GitHub job
conditions and event payload routing are recorded in the plan and are not
represented as locally verified. Shared offline admission tests cover RC
messages, PR head commits, manual inputs, upstream conclusions, the pause
variable and the IST resume boundary. Replay does not change
`TOOLGATE_CI_ENABLED`, enable automatic CI, or bypass those rules on GitHub.

The client matrix needs six genuine OS/architecture VMs. Service/installer
checks require systemd, Windows services or launchd, which a Linux container
cannot reproduce. Jobs consuming these artifacts are unverified rather than
supplied with fabricated packages. Release jobs require protected environments,
credentials and registry/GitHub writes; the local runner does not execute them,
including jobs mixing build and publish steps. Supported standalone bodies are
preflight, foundation, gateway and enterprise policy compatibility.

A local pass cannot guarantee every GitHub trigger succeeds: hosted runner
images, GitHub APIs, permissions, approvals, artifacts, registries and upstream
dependencies can differ or fail. The report always sets
`allGitHubTriggersVerified` to `false`. Use hosted CI on the final commit, with
the actual native matrix and release environments, to close these gaps. See
[RC admission](ci-admission.md) for the current automatic CI pause.
