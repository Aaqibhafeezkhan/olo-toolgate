<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Profile-driven workflow stack

This directory contains the complete local stack: Compose files, deployment
helpers, profiles, monitoring assets and private generated state/data/models.
The main `.env` contains only the default selection:

```dotenv
CONFIGURATION=text-summary
```

Each `configurations/<selection>/workflow-profile.json` is the source of truth.
Inside `workflows.workflows[]`, its `deployment` block declares the runtime,
model engine/artifact, platform images/ports, signed trigger, folders and monitoring.
Agent definitions, model deployments, model groups, generation parameters and
vault references are in that same profile and linked to the workflow by ID.
There is no separate use-case configuration file or model setting in `.env`.

| Profile | Trigger input | Confirmed output |
| --- | --- | --- |
| [Issue triage](configurations/issue-triage/README.md) | `data/issue-triage/inbox/*.txt` | `data/issue-triage/results/*.result.json`; original retained |
| [Text summary](configurations/text-summary/README.md) | `data/text-summary/inbox/*.txt` | Original in `processed/`, generated summary in `summaries/` |

## Start

On Windows, run `start.bat`. It lists every configuration with a serial number
and asks which one to start. Press Enter to use the displayed `.env` default.
Invalid numbers are rejected; cancelling does not start a deployment. Missing
source images are built automatically, while digest-pinned images are pulled.
The model file and prerequisites below must be available.

```bat
start.bat
start.bat --configuration text-summary
start.bat --build
```

The explicit `--configuration` form skips the menu for unattended startup.

Use Docker's Linux engine with Compose, Git, and Python 3.12 or newer. The helper
builds Java/Rust inside Docker and needs this source checkout. Install its TLS
certificate dependency if absent:

```powershell
cd D:\git\olo-toolgate\deploy\compose\WorkflowStack
python -m pip install cryptography==50.0.1
Copy-Item .env.example .env
```

Put an instruct GGUF in `models/model.gguf`, matching the selected workflow's
`deployment.model.modelFile`. To select a different model, change that profile:
its model file, engine image, context size, threads, model ID/origin, generation
parameters and secret reference. Model files must stay within `models/`.
The local model server must support `json_schema` response-format decoding.

```powershell
python manage.py deploy --build
python manage.py status
```

Windows may use `manage.bat`; POSIX hosts may use `sh manage.sh`.
`--build` builds the images named by the profile from this checkout. Later starts
use `python manage.py deploy`. Source-build profiles use tag references; compatible
reviewed release-image profiles can use prebuilt images without `--build`.
This change does not itself publish release images to a public registry.

Text summary exposes the console at http://127.0.0.1:48091/console/; issue triage
uses http://127.0.0.1:58091/console/ and can run alongside it.
Ports and service images are declared in each workflow's `deployment.platform`.
The helper enrolls the device, reviews/provisions packages, creates identities and
grants, resolves trigger UIDs, and reviews/publishes the effective workflow profile.
It waits for a fresh device check-in and current available trigger before starting
the listener/worker. Generated credentials are private and are not printed.

Use the selected profile's README to drop an example file and inspect its output.
The continuous listener and worker process subsequent files without a demo driver.

## Switch or edit

```powershell
python manage.py stop
# Set CONFIGURATION=text-summary in .env, then:
python manage.py deploy
```

`--configuration issue-triage` overrides the default selection for any manager
command. Each selection gets a `toolgate-workflow-<selection>` project and its own
volumes. State and files are retained when stopping or switching.

Model/runtime/trigger/folder/monitoring settings come from the selected profile on
every invocation. `state/<selection>/compose.env`, the bootstrap trigger profile and
collector configuration are derived artifacts: edit the profile, not those files.
Restart retains and verifies the sealed trigger package and exact reviewed native
read image pin. It never replaces an immutable package version with a rebuilt SDK.
Package upgrades require a new reviewed release and explicit qualification.

One workflow deployment is enabled per stack (`deployment.enabled: true`). A profile
may retain other disabled workflow definitions when combining configurations.
The helper deploys only the enabled workflow and its declared agent/model/group/
dispatch dependency closure; it leaves the source document intact. Keep IDs unique
when combining scopes. Change workflow version IDs for immutable definition edits.
Stop a deployment before removing its active workflow/profile. Removing an inactive
scope does not activate or execute it. Deleting configuration does not delete data
or historical run evidence.

## Remove the stack and storage

```bat
stop.bat
```

**Destructive:** this removes all current WorkflowStack deployments belonging to
this directory, their containers (including scoped native children), networks,
Docker volumes and anonymous volumes, and the complete generated `state/` and
`data/` trees. This includes credentials, databases, inbox files, processed files,
summaries/results and archived demo state. It does not prompt for confirmation.
Profiles, scripts, `.env`, supplied `models/`, private vendor credential inputs
and shared Docker images remain, so the stack can be brought up fresh.

Remove only one deployment with `stop.bat --configuration text-summary`.
Use `manage.bat stop --configuration text-summary` to merely stop containers and
retain all storage. Cleanup does not need a valid profile, model file or `.env`;
it verifies Docker ownership and refuses foreign projects and linked storage.
There is no global Docker prune.

## Agent stages

`agentStages` pins ordered configured agents. The first receives the admitted
message, each later stage receives the previous completed result, and the last
result completes the workflow unchanged. `CONTEXT` runs only that agent's configured
read-only context sources, without inference. `MODEL` runs its model/tool loop.
References must close within the workflow, agents must remain currently eligible,
and stages count against the handoff budget. Supervisor output cannot skip stages.
Profiles without stages retain model-directed routing.

The text-summary profile uses a HotFolder CONTEXT agent followed by a MODEL
summarization agent. Only the file agent has file-tool authority. Its trusted
delivery adapter independently verifies Control's successful result, profile,
workflow and admitted path before writing the summary and archiving the exact
original. Model output never selects host paths. Failed/uncertain work remains
unprocessed; the journal does not automatically resend workflow effects.

## Inspect

```powershell
python manage.py status
python manage.py logs
```

For a targeted direct command, use the derived environment:

```powershell
docker compose --env-file state/text-summary/compose.env logs --tail 50 workflow-worker folder-agent
```

[MONITORING.md](MONITORING.md) explains Langfuse, LangSmith, OpenTelemetry,
Prometheus, Grafana, Tempo, Datadog, New Relic, logs and audit records. Monitoring
settings and collector exporters belong to the workflow profile. Actual secrets
remain in private custody or ignored `monitoring/credentials.env`, referenced by
name rather than embedded in the profile. Default logs exclude input text,
prompts, generated contents, credentials and provider bodies.

This is a local evaluation deployment. The identity refresher/native device use a
trusted Docker socket; the adapter uses a current tenant-administrator result
credential. These are operator components, outside the untrusted native-tool
sandbox. Native writes remain unqualified; delivery writes do not change that
boundary. Local enrollment approval lasts 24 hours and the model leaf seven days.
Production identity lifecycle and complete security/roadmap acceptance remain
outstanding. Earlier demo artifacts are preserved under `state/prior-demo/` and
`data/prior-demo/`; the final bundle uses isolated workflow projects.
