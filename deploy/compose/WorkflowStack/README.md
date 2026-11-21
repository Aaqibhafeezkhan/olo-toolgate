<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Profile-driven workflow stack

This directory contains the complete local stack: Compose files, deployment
helpers, profiles, monitoring assets and private generated state/data/models.
The main `.env` contains only the default selection:

```dotenv
CONFIGURATION=text-summary
```

Each `configurations/<selection>/workflow-profile.json` bootstraps a fresh installation.
Inside `workflows.workflows[]`, its `deployment` block declares the runtime,
model engine/artifact, platform images/ports, signed trigger, folders and monitoring.
Agent definitions, model deployments, model groups, generation parameters and
vault references are in that same profile and linked to the workflow by ID.
There is no separate use-case configuration file or model setting in `.env`.

## Adopt a trigger package

The fresh stack uses `examples.hotfolder-watch/new-file` for the supplied file
profiles. There is no old-name alias or store conversion. Preserve needed old
files and use the explicit selected-stack teardown before deploying this version.
Old stack/image/bootstrap formats are refused intact.

The workflow's `deployment.source` declares `packageId`, exact `version`,
`triggerId`, `deviceGroupId`, opaque `config`, `eventFilter`, `assignment` and
`subscription`. Control validates configuration against the selected package's
signed trigger descriptor. A dispatch binding in the same profile selects the
workflow/version, actor and message pointer; event content cannot select them.
There are no fixed watch IDs, versions, config keys or event filters in the
deployment operator or canonical source model.

To adopt an external package using supported executor capabilities, place its
`.olotool` in `configurations/<selection>/packages/` and pin its `packageDigest`
(`sha256:` plus the SHA-256 of its `CHECKSUMS.sha256` bytes) in `deployment.source`.
Use the digest emitted by `toolgate pack`. The selected package/version must
match exactly. Only the selected artifact enters bootstrap custody. Review the
package before this privileged operator action; a digest is not publisher proof.
The common deployment images and Compose services stay the same.

`deployment.fileAgent` is optional. Omit it for a non-file workflow: events reach
the model/agents through the configured message pointer, results are recorded in
`state/<selection>/workflow/results/`, and no folder-agent or file-tool agent is
provisioned. For a file profile, keep the explicit file-delivery adapter settings.
Package changes on an existing stack are refused; choose a fresh stack scope.
Changed immutable subscription filters require a new subscription name and
workflow version, rather than silently keeping the previous filter.

This adoption path covers supported package/executor capabilities. It does not
implement arbitrary network access or every requested connector. The
[trigger catalog](../../../docs/architecture/trigger-package-catalog.md) records
webhook, log, health, email, chat, ticket, queue, CDC, schedule and Git-event work
and the required generic executor/broker gates. No watch-specific sidecar or
platform name switch substitutes for those gates.

| Profile | Trigger input | Confirmed output |
| --- | --- | --- |
| [Issue triage](configurations/issue-triage/README.md) | `data/issue-triage/inbox/*.txt` | `data/issue-triage/results/*.result.json`; original retained |
| [Text summary](configurations/text-summary/README.md) | `data/text-summary/inbox/*.txt` | Original in `processed/`, generated summary in `summaries/` |
| [Managed-log triage](configurations/log-triage/README.md) | Two matching records in device-managed `logs/app.log`; install the pinned external package first | Control workflow result and `state/log-triage/workflow/results/`; source log unchanged |

## Start

On Windows, run `start.bat`. It starts the selected `.env` bootstrap configuration
or an empty installation when no selection exists. Open **http://127.0.0.1:48190/**;
it serves the same Control console as the deployment's own HTTP port. Use the
existing Control admin login. The local deployment operator remains separate at
http://127.0.0.1:48190/operator for explicit deployment lifecycle actions.

```bat
start.bat
start.bat --configuration native-hotfolder
start.bat --blank
```

In **Workflows**, open the destination working profile (or import directly on an
empty installation), then use **Workflow import / export (.olo)**.

- Each ZIP-compressed `.olo` contains **one workflow**, its referenced agents,
  model/group definitions, prompts, schemas, context, inert deployment hints,
  directory configuration, named trigger declarations and exact required tool/trigger
  `.olotool` archives. No profile README, container images, model weights, databases,
  human accounts, private keys or credential stores are added. Embedded package
  contents are preserved byte for byte. The limit is 128 MiB compressed, with
  Control's existing 64 MiB ceiling per tool archive.
- A working profile may hold multiple workflows. Import adds a workflow or
  replaces the selected/matching workflow. The editor rereads the staged content
  and refreshes automatically. Review and publish to activate; publication
  refreshes the displayed revision too. These actions never call Docker or rotate
  admin/user credentials.
- Trigger subscription/assignment **names** are resolved to this installation's
  UIDs. The bundle retains the declarations for missing triggers. After import,
  preview referenced directory data and prepare its ordinary reviewed MERGE when
  needed. Existing shared definitions are not overwritten. Owner IDs can be
  mapped to existing users without changing those users or their memberships.
  Restore trigger connections and stage the resolved mappings before publication.
- Import embedded packages through the displayed action before their normal
  deployment/activation. The destination supplies provider secrets, approved
  devices and trusted native graph factories/model services. Missing dependencies
  remain readiness/setup blockers; structural import does not prove execution.
  No Python code from an uploaded file is installed or executed.
- Removing a workflow prunes its unshared profile definitions and mappings while
  keeping other workflows and shared data. Existing identities, provisioned
  assets and immutable revisions/audit are retained rather than treated as
  workflow-owned garbage.

There is one application-level transfer implementation at both console URLs.
Repository authoring/build sources are in [profiles](../../../bot/README.md),
with versioned outputs under `bot/bin/`. Tool archives use `.olotool`; workflow/profile archives use
`.olo`. Only profile bundle format 2 is accepted.
The earlier configuration-only and whole-deployment formats are unsupported; neither is converted
nor loaded into Docker. Existing old archive files remain untouched.

**Factory reset** is an explicit destructive action in `/operator`: enter
`FACTORY RESET`. It removes the active deployment's generated state/data and
starts a fresh Control installation with only the existing `admin` login and
minimal installation roles/groups. Its password verifier is preserved; no old
workflow, model/group configuration, actor, database, session or keys are restored.
The startup selection is cleared. The new empty console is ready to import a
workflow `.olo`; runtime/provider prerequisites still require their normal setup.
Factory reset is separate from workflow import and may recreate the deployment.

Referenced-data import follows the existing Control review policy. The existing
Super Admin exception may apply it immediately; it does not create or replace
human accounts. A second preview reports remaining differences.

The deployment operator credentials stay under
`state/administrator/credentials/admin/`. Control's existing login files remain
under `state/<selection>/credentials/admin/`, or `state/blank/credentials/admin/`
after reset. Passwords are never printed. Runtime deployment details remain in
private `state/administrator/operation.log` and the selected service logs.

Use Docker's Linux engine with Compose and Python 3.12 or newer. Published-image
deployment needs this WorkflowStack directory, its selected profile and model;
it does not need Git, Java, Rust or the rest of the source checkout. Install its TLS
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
`python manage.py deploy --build --configuration <name>` builds the images named by the profile from this checkout. Later starts
use `python manage.py deploy`. Source-build profiles use tag references; compatible
reviewed release-image profiles use prebuilt images without `--build`. Set the
profile's `deployment.platform.quickstartImage`, `deviceImage` and
`deployment.runtime.image` to the corresponding published references (prefer
immutable digests). Quickstart contains the common native packages, bootstrap
initializer and provisioning operator. Startup extracts these from that exact
image and mounts derived bootstrap data read-only; Compose has no image builds.
Switching the mounted profile changes the selected workflow and its dependencies,
without a profile-specific Dockerfile or image. Explicit source builds still
require this checkout. Signed native-package provisioning can build reviewed client
tool images inside Control; that is separate from rebuilding the deployment images.
This change does not itself publish release images to a public registry.

Text summary exposes the console at http://127.0.0.1:48091/console/; issue triage
uses http://127.0.0.1:58091/console/ and can run alongside it. The Admin UI HTTP
port is published on `0.0.0.0`, so another machine can use
`http://<docker-host-ip>:48091/console/` (summary) or port `58091` (triage), subject
to the host firewall. The TLS and private registry ports remain loopback-bound.
Ports and service images are declared in each workflow's `deployment.platform`.
The helper enrolls the device, reviews/provisions packages, creates identities and
grants, resolves trigger UIDs, and reviews/publishes the effective workflow profile.
It waits for a fresh device check-in and current available trigger before starting
the listener/worker. Generated credentials are private and are not printed.

Admin login files are under `state/<selection>/credentials/admin/`:
`username.txt` contains the login name and `password.txt` the generated password.
The separate `reviewer-1/` and `reviewer-2/` folders contain their own login files.
Do not copy these into profiles, `.env`, Git or logs. Machine tokens, TLS keys and
workflow secret custody remain in their narrowly mounted private state folders.

If Docker volumes have been replaced while host state remains, startup refuses
the changed Gateway trust. Preserve needed files first, then use
`destroy.bat --configuration <selection>` and `start.bat --configuration <selection>`
for a fresh deployment. That reset deletes the selected generated state/data.
Startup never silently adopts a replacement Gateway or converts old stores.

Use the selected profile's README to drop an example file and inspect its output.
The continuous listener and worker process subsequent files without a demo driver.

From the repository root, verify a real drop with:

```powershell
python tools/workflows/verify_stack_demo.py --configuration text-summary
python tools/workflows/verify_stack_demo.py --configuration issue-triage
```

The driver creates one unique input from the selected `example.txt`, independently
checks Control's profile/workflow/result and admitted source, confirms configured
agent execution, verifies the output and (when enabled) byte-identical archive,
and records private verification metadata under `state/<selection>/verification/`.
It performs no workflow-effect retries. Non-file packages use their own real
event producer instead of this file-demo driver.

## Switch or edit

Use **Switch profile** in http://127.0.0.1:48190/. It uses the same configuration
directories and provisioning operator as the explicit command-line deployment.

`--configuration issue-triage` selects a configuration on initial startup or for
direct manager commands. Once the portal is running, use its switch action.
Each selection gets a `toolgate-workflow-<selection>` project and its own
volumes. State and files are retained when stopping or switching.

Model/runtime/trigger/folder/monitoring settings come from the selected profile on
every invocation. `state/<selection>/compose.env`, the bootstrap trigger profile and
collector configuration are derived artifacts: edit the profile, not those files.
Restart retains and verifies the sealed trigger package and exact reviewed native
read image pin. It never replaces an immutable package version with a rebuilt SDK.
Package upgrades require a new reviewed release and explicit qualification.

One workflow deployment is enabled per stack (`deployment.enabled: true`). A profile
may retain other disabled workflow definitions when combining configurations.
The helper initially bootstraps the enabled workflow and its declared dependency
closure. Subsequent starts preserve the application-managed published profile;
they do not overwrite it with the source JSON. Keep IDs unique
when combining scopes. Change workflow version IDs for immutable definition edits.
Stop a deployment before removing its active workflow/profile. Removing an inactive
scope does not activate or execute it. Deleting configuration does not delete data
or historical run evidence.

## Stop and resume

```bat
stop.bat --configuration text-summary
manage.bat deploy --configuration text-summary
```

`stop.bat` stops the selected Compose deployment and preserves its containers,
volumes, credentials and generated state/data. Without `--configuration`, it uses
the selection in `.env`. The local administration server remains available;
resume through its deployment action or `manage.bat deploy` as above.
On Linux/macOS use `sh manage.sh stop --configuration text-summary` and
`sh manage.sh deploy --configuration text-summary`.

## Remove the stack and storage

```bat
destroy.bat
```

**Destructive:** this removes all current WorkflowStack deployments belonging to
this directory, their containers (including scoped native children), networks,
Docker volumes and anonymous volumes, and the complete generated `state/` and
`data/` trees. This includes credentials, databases, inbox files, processed files,
summaries/results and archived demo state. It does not prompt for confirmation.
Profiles, scripts, `.env`, supplied `models/`, private vendor credential inputs
and shared Docker images remain, so the stack can be brought up fresh.

Remove only one deployment with `destroy.bat --configuration text-summary`.
On Linux/macOS use `sh manage.sh destroy --configuration text-summary`, or omit
the selection to remove all owned deployments. Cleanup does not need a valid profile, model file or `.env`;
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
