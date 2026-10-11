# Local CI tools

Run CI job bodies locally with [`local.py`](local.py). The runner reads commands,
actions and setup versions directly from `.github/workflows/`, so a workflow
change also changes the local replay. Shared validation scripts are used by
both local and hosted CI.

Run the following commands from the **repository root**.

## Setup

- Python 3.12 or newer.
- Docker running Linux containers for `audit` and `run`.
- [act](https://nektosact.com/installation/) for `run`, installed on your PATH
  or supplied with `--act PATH`.

Create an environment and install the validation dependencies.

On Windows PowerShell:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r tools/requirements.txt
.\.venv\Scripts\python.exe tools/ci/local.py plan
```

On Linux or macOS:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r tools/requirements.txt
python tools/ci/local.py plan
```

The examples below use `python` from an activated environment. On PowerShell,
replace `python` with `.\.venv\Scripts\python.exe` to use the environment without
activating it.

## Usage

```sh
# List actual GitHub triggers, job dependencies, matrices and local coverage.
python tools/ci/local.py plan

# Validate CI metadata, admission rules and workflow syntax.
python tools/ci/local.py audit

# Audit, then replay the preflight job, including browser checks.
python tools/ci/local.py run --job preflight.metadata

# Select several jobs by repeating --job WORKFLOW.JOB.
python tools/ci/local.py run --job foundation.check --job gateway.container-and-replicas

# Replay all supported jobs and report every remaining coverage gap.
python tools/ci/local.py run

# Replay only the supported bodies; the RC: commit gate uses this scope.
python tools/ci/local.py run --replayable

# Use an act executable that is not on PATH.
python tools/ci/local.py run --job preflight.metadata --act /path/to/act
```

Omitting the command runs `plan`. `plan` requires Python and PyYAML; it does not
run tests. `audit` checks configuration and admission rules without replaying
job bodies. `run` always starts with the audit.

Supported job bodies are `preflight.metadata`, `foundation.check`,
`gateway.container-and-replicas` and `policy.enterprise-access`. Use `plan` to
see the current inventory before selecting jobs.

On GitHub, the five CI workflows run automatically for pushes to `main` and
PRs targeting `main`. Manual runs must select `main`. PR admission uses the
`RC:` title plus `TOOLGATE_CI_ENABLED=true`; push admission uses the latest
commit's `RC:` message, and manual admission uses the `RC:` comment input.
The existing resume date also applies. Actions displays PR runs under their
source branch even when the target is `main`. Local replay runs selected job
bodies independently of these hosted trigger rules.

The first replay builds [`local.Dockerfile`](local.Dockerfile), downloads toolchains
and can use several GB. Replay runs in a privileged Linux container with its own
Docker daemon. It exports the current working tree, including uncommitted source,
to a disposable snapshot; ignored files are excluded. Keep source unchanged
during execution, since a change invalidates the result. To use a preinstalled
Ubuntu 24.04 amd64 runner image, add `--image IMAGE`.

If Make is available, `make ci-plan`, `make ci-audit` and `make ci-local` call the
same runner. Set `PYTHON` to your environment's executable if needed.

## Commit checks

Install the repository hooks with `npm ci`, or explicitly with
`npm run hooks:install` after an install using `--ignore-scripts`.

Every commit keeps the existing smaller pre-commit checks: extension tests,
staged secret scanning, TypeScript, UI unit tests and browser downloads. When
the **commit subject starts exactly with `RC:`**, the `commit-msg` hook also
runs `python tools/ci/local.py run --replayable`. All locally supported jobs must
pass before Git creates the commit. Other subjects use only the smaller checks.

```sh
git add <changed-files>
git commit -m "RC: Validate this change with local CI"
```

For an RC commit, stage every source change and stage or ignore untracked files
first. The hook rejects unstaged source, a failed or incomplete replay, missing
tooling, and changes to the source or Git index during the checks. Ignored build
outputs and the virtual environment do not need staging. This gate can take as
long as the supported hosted jobs; it reruns for every RC commit, including
amends. Native and publication jobs remain outside its verified scope.

The hook uses `.venv` automatically if present, otherwise Python on your PATH.
Set `TOOLGATE_CI_PYTHON` to a Python executable or `TOOLGATE_CI_ACT` to an act
executable if needed; both accept executable paths, not shell commands.

With a custom hooks directory, keep `node tools/ci/precommit.mjs` in your
`pre-commit` hook and add `node tools/ci/commit-msg.mjs "$1"` to `commit-msg`.
The installer preserves custom hook paths and reports the required integration.

## Results

Inspect `build/local-ci/plan.json`, `audit-report.json`, `report.json` and the
per-job logs in `build/local-ci/`.

| Exit code | Meaning |
| --- | --- |
| 0 | The requested inventory, audit or selected job bodies passed. |
| 1 | A check failed, a prerequisite is missing or coverage needs review. |
| 2 | Requested jobs include work that could not be verified locally. |

An all-job run currently returns **2**, even when the supported bodies pass.
Native OS matrices, artifact-dependent jobs, event routing and publishing still
need hosted CI. The report always sets `allGitHubTriggersVerified` to `false`;
a local pass cannot guarantee every GitHub trigger will succeed.

See the [full local CI guide](../../docs/development/local-ci.md) for trigger
coverage, execution isolation and limitations, and
[CI admission](../../docs/development/ci-admission.md) for RC message rules and
the automatic CI pause. Local replay does not enable automatic CI or publish
releases.
