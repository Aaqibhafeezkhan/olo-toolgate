<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO local debug stack

OLO Orchestration runs bots/agents. OLO ToolGate governs tools, devices, triggers
and access. Start with the real local-model path below; the separate mock
reference later is for deterministic transport acceptance.

## Real local-model bots: start here

Run in PowerShell from the repository root. Docker Desktop's Linux engine
is required. Startup probes Docker NVIDIA GPU access and uses CPU execution
when NVIDIA acceleration is unavailable.
Host Node, JDK, Rust, pip, Torch and Transformers are unnecessary. Startup
bootstraps private Python if needed; builds and model preparation use Docker.

```powershell
.\debug\profiled\start.bat -WithOllama -WithMonitoring
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
$env:PYTHONUTF8 = '1'
$compose = @('compose','-p','toolgate-profiled','-f','debug/profiled/state/compose.json')
```

Interactive startup asks independently about Ollama and the collector: **1**
installs/reuses it, **2/Enter** keeps the current setup. The three core containers
are Quickstart, LangGraph and Device. Ollama/TLS and collector are optional
companions. First start leaves a blank console with `admin`; it never seeds bot
data or device files. Existing credentials/storage are reused. Read the password
privately at `debug/profiled/credentials/admin/password.txt`; it is never exported.

Ollama serves **both** `olo-qlora-hotfolder:e376fb40` and `qwen3:0.6b`, with all
available NVIDIA GPUs exposed after a successful probe, and two models permitted
to remain loaded. Startup merges
the real pinned OLO PEFT adapter with its pinned Qwen2.5 base, converts to F16
GGUF in an isolated CPU builder and imports it. First preparation needs Internet,
several GB disk and up to 8 GB builder RAM. Restarts reuse prepared weights.
File agents use QLoRA first, lightweight Ollama on inference failure; other agents
use lightweight Ollama. Premium OpenRouter is unattached standby and is never
called automatically. No model failure retries a tool effect.

Read the two central guides before changing an installation:

- [Device setup, permissions, groups and live updates](../../docs/help/device.md).
- [Models, exact QLoRA revisions, routing, replacements and OpenRouter](../../docs/help/models.md).

Build public bots with `bot\build.bat --version 1.0.0-demo.local`. Outputs go to
`bot/bin/`. To reuse exact locally built `.olotool` archives instead:

```powershell
& $debugPython bot/build.py --packages-dir build/hotswap-runbook-packages --no-sdk-build --version 1.0.0-demo.local --output build/local-model-bots
```

That directory must contain hotfolder, `hotfolder-watch` and `log-watch` packages;
omit `--packages-dir` to build their recipes. Each `.olo` contains one workflow,
its referenced configuration and required packages. No README, account/password,
model weight or credential is inside. Source READMEs remain beside configurations.

| Bot | Exact input/output and permission commands |
|---|---|
| Hot swap A | [README](../../bot/configurations/hotswap-demo-a/README.md) |
| Hot swap B | [README](../../bot/configurations/hotswap-demo-b/README.md) |
| Issue triage | [README](../../bot/configurations/issue-triage/README.md) |
| Log triage | [README](../../bot/configurations/log-triage/README.md) |
| Native hot folder | [README](../../bot/configurations/native-hotfolder/README.md) |
| Native log | [README](../../bot/configurations/native-log/README.md) |
| Text summary | [README](../../bot/configurations/text-summary/README.md) |

Import a public `.olo` in **OLO Orchestration > Bots**. Explicitly approve/enable
the real device and assign every declared package/execution-binding group.
Permit the file root and `.txt`, `.json`, `.log` extensions, provision packages
and qualify native file execution. Then store this installation's model bridge
key through the vault API:

```powershell
& $debugPython debug/profiled/ollama.py vault
```

This uses the saved Admin login and does not rotate its password, import a bot
or touch device files. Resolve every persistent red bot blocker, review,
provision packages, prepare graphs and publish. A gray Publish button means
readiness is incomplete. Use **▶ Run**, enter that README's input, and inspect
the recorded progress/result. Watcher cases run after you explicitly prepare
their folders/permissions and place their exact sample input. Import/switch
never creates, removes or moves folder content. Fixed demo artifact paths must
be inspected/changed/removed explicitly before a repeat.

A/B intentionally share `hotswap-demo`; publishing B changes new admissions
without restarting core/device or changing Admin. Admitted runs stay pinned.
The other cases have independent IDs. Stop a retired assignment explicitly.

For automated acceptance, first build all public bundles into
`build/local-model-bots` with the command above. This explicit test consumer
approves/enables the selected local device, allocates groups, prepares folders
and inputs, qualifies native file execution, imports/publishes and triggers each
case. It checks results/archive contents, progress, core/device process identity
and Admin preservation. It refuses to delete pre-existing demo artifacts.

```powershell
& $debugPython debug/profiled/stack.py provision-runtime
& $debugPython tools/workflows/local_models_demo.py --approve-local-device --prepare-demo-folders --acknowledge-unfenced-files
# To select one case, add: --configuration log-triage
```

Read the native filesystem qualification limitations before accepting that flag.
Evidence is `build/local-model-bots-evidence.json`; only completed cases are
listed. Folder preparation belongs to this explicit test action, never import.
Use `--verify-existing` only to verify matching confirmed completed demo
artifacts without submitting another event. Unsafe/unknown outcomes are never
replayed. Hot-folder source identity is path plus size; the automated consumer
uses unique input names. For manual repeats, use a new `.txt` name or wait for
a watcher poll after removing the old source; inspect fixed outputs separately.

The runtime service credential is separate from Admin; its operator API script
does not change the Admin password. See [community help](../../docs/help/README.md)
for lifecycle, authoring, observability, troubleshooting and current limits.

## Deterministic mock acceptance reference

The following reference uses separate mock-only fixtures for exact transport
assertions. It is not the public seven-bot local-model setup above. Use a
separate blank/prepared test installation; do not replace public sources with
mock configuration.

This community runbook starts a blank installation and demonstrates changing its
trigger, graph configuration, agent model and output paths while the same core
containers and device keep running. Run commands in **PowerShell from the
repository root**, for example `D:\git\olo-toolgate`.

The core composition contains exactly **three** containers:

| Container | Responsibilities |
| --- | --- |
| `quickstart` | Gateway/backend, Admin UI, backend trigger dispatch, embedded demo OCI registry |
| `langgraph` | Private graph API and execution worker; no trigger listener or identity issuer |
| `device` | Persistent device runtime, installed tools and triggers |

The three services are the persistent core. The Device's Docker runtime also
launches isolated package executors. Tool-call executors may be short-lived;
watcher executors remain running while their trigger assignments are active.
An optional external model fixture or monitoring system is separate from the
core stack.

LangGraph uses bounded process-local memory caches only (128 compiled topologies
per process). They are disposable and
are not execution authority. Runs, admissions and results remain in the backend.
An external cache is deferred. There is no new checkpoint/resume guarantee.
Production separates Admin UI from Gateway/backend and supplies external registry
and storage; the LangGraph image is the same. Models/monitoring are external.

This fresh composition rejects the obsolete multi-container stack definition and
custody format. It does not convert or delete existing stores. For an existing
installation, stop its old composition, preserve its storage, and choose fresh
installation storage explicitly before using the new layout. Once the old
project has no containers, this command selects new named volumes and preserves
the previous manifest/storage selection under `state/history/`:

```powershell
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
& $debugPython debug/profiled/stack.py start --fresh
```

It does not delete old volumes or reuse their unsupported custody. Saved Admin
credential files are reconciled to the new installation with the prior files
preserved privately under `credentials/admin/history/`. This explicit creation of
a new installation differs from restarting an existing one.

## 1. Prerequisites and installation settings

Install and start Docker Desktop with its Linux container engine. Allow Docker
to use this checkout and named volumes. Windows PowerShell and Internet access
to the pinned image/Python sources are required for the first build. Host Node,
npm, Java, Rust and pip are unnecessary: builds run in Docker. If Python 3.11+
is missing, `start.bat` verifies and installs a pinned official embeddable Python
under `.dev/profiled`, without changing system PATH or requiring administrator
installation. Docker Desktop itself remains an operator prerequisite.

```powershell
Set-Location D:\git\olo-toolgate
docker info --format '{{.OSType}}'  # must print linux
```

For the supplied external model fixture, create these **installation** settings
before the first start. If the file exists, edit these entries while retaining
your other settings; do not overwrite monitoring or production routing.

```powershell
@'
TOOLGATE_WORKFLOW_MODEL_ORIGINS=https://demo-provider:9443
TOOLGATE_WORKFLOW_MODEL_CA_FILE=/custody/provider/ca.crt
'@ | Set-Content -Encoding ascii debug/profiled/installation.env
```

The demo later starts a separate TLS OpenAI-compatible mock and puts its secret
in destination custody. It returns `demo-a: <input>` or `demo-b: <input>` so
routing and exact results can be verified. This is not a semantic summarization
benchmark. For real inference, supply your own Llama/OpenRouter/router/premium
OpenAI-compatible service, edit the workflow deployment origin/model/secret
reference, and configure approved origins and CA here. No model container is
part of the core stack. Manually configure monitoring/exporter environment here
as required. Installation environment changes can require explicit service
recreation; workflow switches do not change the installation environment.

## 2. Start blank and locate credentials

```powershell
.\debug\profiled\start.bat
. .\debug\profiled\python.ps1
$debugPython = Find-DebugPython
$env:PYTHONUTF8 = '1'
$compose = @('compose','-p','toolgate-profiled','-f','debug/profiled/state/compose.json')
docker @compose ps
```

First start creates only `admin` and installation/trust infrastructure. It does
not select a profile, install scenario packages or seed files. Existing
installations retain their data. Source-qualified local images are reused;
missing images build from production Dockerfiles. Startup uses `--no-recreate`;
it does not replace an existing container to adopt a changed image. After
infrastructure code changes, explicitly recreate the affected owned service
before starting acceptance. Scripts can also be launched from another directory.

Open <http://127.0.0.1:18090/console/>. Username: `admin`. Read the password
locally from `debug/profiled/credentials/admin/password.txt`. Never commit or
share this folder. Initial login requires password rotation. The prepare/demo
command below can rotate and save it privately. If you change it manually in
the UI, update this private file to the same password before operator scripts.

Other private files are `username.txt`, `operator.token` (API bearer, not the
password), and `gateway-ca.crt`. Start/restart verifies saved credentials against
current custody and reuses a matching password. It can refresh from still-valid
initial custody; it cannot recover a changed password from a hash. Unknown trust
changes refuse reuse. Do not delete identity/journals or accept an unknown CA
to fix login. Changed private predecessors are retained in private history.

Admin HTTP, direct Control TLS and registry ports bind to loopback **18090,
18450 and 18552**. This local demo needs no Windows inbound firewall opening.
Remote access, host mounts and extra ports are operator/cloud-ops responsibilities.

### Runtime service credential provisioning

Startup and restart invoke an **external operator script**, which authenticates
through backend APIs using the saved Admin password and installs a dedicated
`toolgate-workflow-runtime` credential. The Admin password is never rotated by
startup, restart or the demo. No renewal sidecar/background renewal loop exists.
LangGraph mounts only its private installation subtree read-only; it receives no
Admin token, backend database or signing key.

The local service credential expires after 15 minutes. Before/after expiry,
explicitly run this command from the repository root to replace it:

```powershell
& $debugPython debug/profiled/stack.py provision-runtime
```

Running workers reload the token file on backend requests. If expiry has already
stopped a required process, Docker restarts the LangGraph service; it does not
retry uncertain tool effects. Service tokens are not printed. Certificate rotation
is an explicit operator operation; it is separate from service-token issuance.

## 3. Build the portable workflow bundles

Use signed tool/trigger artifacts from the locally built Quickstart image,
keeping packages aligned with the stack without host SDK dependencies:

```powershell
New-Item -ItemType Directory -Force build/hotswap-packages | Out-Null
docker @compose cp quickstart:/opt/toolgate/workflow-stack/packages/. build/hotswap-packages
& $debugPython tools/workflows/demo_profiles.py --packages-dir build/hotswap-packages
```

Outputs:

- `build/hotswap-mock/bin/hotswap-demo-a-hotswap-demo-1.0.0-demo.a.olo`
- `build/hotswap-mock/bin/hotswap-demo-b-hotswap-demo-1.0.0-demo.b.olo`

Each embeds its exact `.olotool` hotfolder tools and `hotfolder-watch` package,
workflow/agent/model JSON and referenced data. Accounts, device membership,
tokens, model secrets and installation settings are excluded. Source JSON and
separate READMEs are in `bot/configurations/hotswap-demo-a` and
`bot/configurations/hotswap-demo-b`.

## Optional local Ollama thinking model and all NVIDIA GPUs

Use this instead of the deterministic mock when you want a real generated
summary. The optional companion composition adds **Ollama** and a **TLS proxy**;
Quickstart, LangGraph and Device remain the three core ToolGate services.
`ollama/ollama:latest` serves the real merged OLO QLoRA model plus
`qwen3:0.6b`, a small open-source thinking model
([model](https://ollama.com/library/qwen3:0.6b),
[OpenAI-compatible structured output](https://github.com/ollama/ollama/blob/main/docs/capabilities/structured-outputs.mdx)).
The TLS proxy uses the gateway's existing HTTPS provider contract. Neither
11434 nor 9443 is published to the host. Model weights persist in a dedicated
installation volume, separate from bots and backend data.

Running `start.bat` now asks whether to install/reuse local Ollama or keep
another provider. Choice **2** (or Enter) leaves provider settings untouched.
Choice **1** configures the local allowlist/trust while retaining other settings,
starts the companions and downloads the model. A custom existing CA is preserved
and requires manual trust composition rather than being overwritten.

For unattended scripts use `start.bat -WithOllama` or
`start.bat -WithoutOllama`. The `start-ollama.bat` convenience wrapper selects
Ollama directly. Local installation uses these `installation.env` values:

```dotenv
TOOLGATE_WORKFLOW_MODEL_ORIGINS=https://demo-provider:9443,https://ollama-provider:9443
TOOLGATE_WORKFLOW_MODEL_CA_FILE=/custody/model-ca.pem
```

Start the core stack plus optional model companions:

```powershell
.\debug\profiled\start-ollama.bat
# Equivalent: .\debug\profiled\start.bat -WithOllama
```

The script uses the private debug Python if needed, initializes model-only TLS
custody, starts companions, downloads the model if absent and keeps it loaded.
The first Ollama image/model downloads are large and need Internet/disk space.
Startup leaves an otherwise blank console blank; it does not import a bot or
change the Admin password. Certificates/bridge key are reused across starts,
kept only in installation custody and excluded from exports. Demo TLS expires
in 30 days; use operator-managed certificates for a longer-lived deployment.

Selecting Ollama applies changed provider installation settings once, recreating
Quickstart/LangGraph only if their model environment differs. Admin data and
password stay unchanged. Bot imports never do this. If you edit environment
settings manually instead, apply them explicitly:

```powershell
docker @compose stop langgraph
docker @compose up -d --no-deps quickstart
& $debugPython debug/profiled/stack.py provision-runtime
docker @compose up -d langgraph
```

Activate the supplied self-contained bot through backend Admin APIs:

```powershell
& $debugPython debug/profiled/ollama.py activate
```

This explicit command builds `ollama-summary`, imports its referenced data,
stores the local bridge key through the vault API, prepares and publishes it.
It uses the existing saved Admin login without modifying the password. You can
instead build/import the `.olo` in the console and configure the destination
vault/provider manually. Source files and exact build/input/output instructions
are in [the Ollama bot README](../../bot/configurations/ollama-summary/README.md).

On **Bots**, run the published **ollama-summary** with:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

The Run dialog shows recorded agent/model/node progress and a real generated
JSON `summary`. No input/output folders are needed. Thinking is performed by the
model; private reasoning is not included in progress. This lightweight model's
wording varies and is not a production quality benchmark.

Startup probes `nvidia-smi -L` inside the Ollama image with Docker GPU access.
After a successful probe, it adds `ollama.gpu.compose.yaml`, requesting all NVIDIA
GPUs with `gpus: all` and `OLLAMA_SCHED_SPREAD=1`. Otherwise it starts the base
CPU composition and prints the selected mode. CPU inference may be slower.
NVIDIA acceleration requires Docker GPU passthrough
([Ollama GPU guide](https://github.com/ollama/ollama/blob/main/docs/gpu.mdx)).
Check actual model placement:

```powershell
nvidia-smi --query-gpu=index,name,memory.total,utilization.gpu,memory.used --format=csv
$env:TOOLGATE_PROFILED_ROOT = (Resolve-Path debug/profiled).Path
$env:TOOLGATE_PROFILED_CUSTODY = (Get-Content debug/profiled/state/compose.json -Raw | ConvertFrom-Json).volumes.custody.name
$env:TOOLGATE_PROFILED_OLLAMA_STORAGE = (Get-Content debug/profiled/state/compose.json -Raw | ConvertFrom-Json).volumes.files.name + '-ollama-models'
$modelCompose = @('compose','-p','toolgate-profiled-model','-f','debug/profiled/ollama.compose.yaml')
docker @modelCompose exec -T ollama nvidia-smi
docker @modelCompose exec -T ollama ollama ps
docker @modelCompose logs --tail 100 ollama
```

A small model gains little from splitting across four GPUs; the spreading setting
makes all devices eligible. Driver/runtime compatibility and actual offload must
be verified in the logs. Change the model ID in the bot and pull that model in
Ollama to evaluate a larger model, while respecting the gateway's current
30-second provider deadline. No timeout bypass or automatic inference retry is added.

Stop companions without deleting downloaded weights:

```powershell
& $debugPython debug/profiled/ollama.py stop
.\debug\profiled\stop.bat
```

## Run a bot as Admin from the console

The initial `admin` is Super Admin and may run any published bot. Open
[the Bots page](http://127.0.0.1:18090/console/#workflows), select the published
revision, and click **▶ Run**. Enter **Bot input** and click **Run bot**.
The dialog shows recorded queue, agent/model/tool/node progress and the final
result. Runtime observations are labelled; tool business finality remains in
Control's operation evidence. Closing the dialog leaves accepted execution running.

A persistent **Bot issues** panel shows publication blockers as red bullets for
the selected imported revision. It checks every five seconds and refreshes the
lifecycle status when prerequisites change. It lists missing/scoped model
secrets, unavailable references, package/device installation, trigger readiness,
and missing/stale graph preparation or compatible workers. Draft review/staging
and import/action failures remain visible as bullets too. A failed refresh keeps
the last known issues and shows that current readiness cannot be checked.

These are backend-owned dependency checks, not a model connectivity test or a
remote filesystem probe. The list never exposes secret values or trigger event
contents. Device file permissions, folder creation and provider connectivity
remain explicit installation tasks. A healthy diagnostics response never grants
publication or execution; those operations recheck their own current authority.

A reviewed draft is not executable. Open its revision, finish destination model,
package/device and graph readiness, refresh its lifecycle status, then **Publish
reviewed revision**. Super Admin access permits admission; agents still need the
workflow's configured service grants. A disabled publication action indicates
missing dependencies, not an Admin permission denial. An old revision can remain
in the catalog after a newer revision is published; run the current published one.

For a text-only example without device approval, packages, triggers or folders:

```powershell
& $debugPython bot/build.py --configuration interactive-summary --version 1.0.0-demo.interactive
```

Import `bot/bin/interactive-summary-interactive-summary-1.0.0-demo.interactive.olo`
using **Import bot .olo**. On a blank destination its model secret is still absent.
With the installation origin/CA settings from section 1 already configured, this
explicit operator command starts the external mock and stores its key privately
in the destination vault, after import has created the referenced groups:

```powershell
& $debugPython -c "import json;from pathlib import Path;from tools.workflows.hotswap_demo import login,provider;from tools.admin.import_workflow import call;d=json.loads(Path('debug/profiled/state/compose.json').read_text(encoding='utf-8'));token=provider(d);call(login(),'/api/control/v1/vault',dict(name='demo-provider-key',value=token,toolGroupId='default-tools',deviceGroupId='default-devices'));print('External mock and destination secret ready; key not logged')"
```

Refresh lifecycle status and publish. For **interactive-summary**, enter:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

The deterministic mock returns a JSON `summary` starting with `demo-b:`, followed
by that exact text. It proves routing and execution, not semantic summarization.
For visible in-flight model progress, prefix the text with `slow demo:`, which
makes this mock wait 12 seconds. A real provider uses the configured prompt/model;
see the OpenRouter section below. This example writes no device files and uses
PREPARE mode because it has no configured trigger execution route.

If submission status is unknown, use **Check same submission** to recover the
same idempotent run. **Resume progress** retries reads only. To submit a different
input, close and reopen Run. Do not retry an uncertain external tool effect.
Private inputs/results remain in backend custody; structural progress excludes
prompts, file content, credentials and chain of thought.

## 4. Choose automatic acceptance or manual input

Create the use-case folders **yourself** before either path. Neither bot import
nor switching creates, seeds, moves or deletes device content. Folder settings
select approved paths; permissions and mount preparation remain yours:

```powershell
docker @compose exec -T device python3 -c "from pathlib import Path;r=Path('/var/lib/olo-toolgate/files');[(r/n).mkdir(mode=0o700,exist_ok=True) for n in ('inbox-a','inbox-b','summaries','processed')]"
```

Choose **one** path on a blank installation. Both explicitly approve the named
local device, enable it, allocate the demo groups through Admin APIs, install
embedded packages, prepare/publish the graph, start the external mock, and
qualify installed native file-only images for one day. They also enable the
installation's automatic device approval policy for 30 days. This affects other
new enrollments reaching the installation; review/change it afterwards under
**Configuration > Device**. Profile data cannot grant device authority.

Live Super Admin qualification acknowledges an operator-vetted Linux filesystem
owner and **unfenced** effects (ADR 075); it is not OS attestation or exactly-once
execution. Native intent, durable content evidence and protected scope closure
are required. Missing/conflicting evidence stays UNKNOWN or needs reconciliation;
SDK success alone is insufficient. Uncertain effects are never automatically
retried. Unsupported mutations are refused.

### Automatic A/B acceptance

```powershell
& $debugPython tools/workflows/hotswap_demo.py --approve-local-device
```

This imports A, drops a real file, waits until its configured summarization model
is processing it, imports B while A is in flight, drops B's file, and checks exact
summaries and archived originals. It compares container IDs, start times, restart
counts, host process IDs, device identity and Admin credentials across the swap.
It stops A's trigger after the pinned run drains. Successful evidence is written
to `build/hotswap-demo/evidence.json`; output ends with `PASS` only after every
assertion passes. Failure can leave real files and staged configuration: inspect
before retrying. Never retry an uncertain effect automatically.

### Manual activation and exact input files

On a blank installation prepare A without creating input:

```powershell
& $debugPython tools/workflows/hotswap_demo.py --approve-local-device --prepare-only
$private = Join-Path (Get-Location) 'debug/profiled/credentials/admin'
$api = @('--origin','https://127.0.0.1:18450','--ca-file',"$private/gateway-ca.crt",'--token-file',"$private/operator.token")
```

The bearer token expires independently of the saved password. Before a later
manual switch, refresh it from the current local Admin password using the same
trust-checking login helper. This local operator command needs Docker access;
it does not import a bot, enable devices or change an already rotated password:

```powershell
& $debugPython -c "import sys;sys.path.insert(0,'.');from tools.workflows.hotswap_demo import login;login();print('Local operator token refreshed')"
```

The device root is `/var/lib/olo-toolgate/files` on the stack-owned `files`
volume. The explicit command above creates the four folders with mode 700.
Arbitrary Windows folders are not watched unless you supply a
suitable mount. The commands below put files directly into the approved root
with mode 600. No host permission changes are needed for this named-volume demo.

Create **`inbox-a/source.txt`** with exactly:

```text
Database connections were exhausted. Restarting the pool restored service.
```

```powershell
docker @compose exec -T device python3 -c "import os;from pathlib import Path;p=Path('/var/lib/olo-toolgate/files/inbox-a/source.txt');f=p.open('x',encoding='utf-8',opener=lambda p,flags:os.open(p,flags,0o600));f.write('Database connections were exhausted. Restarting the pool restored service.');f.close()"
```

The trigger is a **60-second polling state snapshot**: allow a poll interval plus
execution time. It reports each path/size version once; same-size rewrites are
not new events, and files created/deleted between checks can be missed. Supply
a complete UTF-8 text file. Expected summary:

```text
demo-a: Database connections were exhausted. Restarting the pool restored service.
```

Check the summary, unchanged archived original and absent inbox source:

```powershell
docker @compose exec -T device cat /var/lib/olo-toolgate/files/summaries/summary-a.txt
docker @compose exec -T device cat /var/lib/olo-toolgate/files/processed/source-a.txt
docker @compose exec -T device python3 -c "from pathlib import Path;print(Path('/var/lib/olo-toolgate/files/inbox-a/source.txt').exists())"
```

The last command must print `False`. Inspect the workflow run and its tool
business outcomes in the Admin console too: files alone do not prove finality.
The file agent reads/writes/moves; the separate `text-summarizer` agent invokes
the configured workflow model.

### Switch to B without restarting

```powershell
& $debugPython tools/admin/import_workflow.py build/hotswap-mock/bin/hotswap-demo-b-hotswap-demo-1.0.0-demo.b.olo @api
```

Wait for `PUBLISHED`. Import uploads/verifies embedded packages, merges
nonconflicting references, stages/reviews, provisions, prepares the graph and
checks readiness. Do not run start/restart or recreate the device to switch.
The same installed image qualification covers these identical tool packages.

Create **`inbox-b/source.txt`** with exactly:

```text
Disk space was low. Removing temporary files restored service.
```

```powershell
docker @compose exec -T device python3 -c "import os;from pathlib import Path;p=Path('/var/lib/olo-toolgate/files/inbox-b/source.txt');f=p.open('x',encoding='utf-8',opener=lambda p,flags:os.open(p,flags,0o600));f.write('Disk space was low. Removing temporary files restored service.');f.close()"
```

Expected summary: `demo-b: Disk space was low. Removing temporary files restored service.`
Check both files and source removal:

```powershell
docker @compose exec -T device cat /var/lib/olo-toolgate/files/summaries/summary-b.txt
docker @compose exec -T device cat /var/lib/olo-toolgate/files/processed/source-b.txt
docker @compose exec -T device python3 -c "from pathlib import Path;print(Path('/var/lib/olo-toolgate/files/inbox-b/source.txt').exists())"
```

Toggle back with:

```powershell
& $debugPython tools/admin/import_workflow.py build/hotswap-mock/bin/hotswap-demo-a-hotswap-demo-1.0.0-demo.a.olo @api
```

Only the current publication admits new runs. Generic import currently leaves
old assignments/subscriptions installed; automatic acceptance explicitly stops
A after draining. Do not put work in retired inboxes. Before repeated manual
runs preserve prior outputs elsewhere and clear the exact old archive
destination: this example refuses to overwrite archived inputs. Allow a poll to
observe source absence or use another filename; same-size rewrites do not
retrigger. Any `.txt` basename is accepted, but each example graph has a fixed
summary/archive destination and is intended for one file at a time.

## 5. Inspect, stop and restart

```powershell
docker @compose ps
docker @compose logs --tail 80 langgraph quickstart device
.\debug\profiled\stop.bat
.\debug\profiled\start.bat
# Explicit infrastructure restart, unrelated to workflow switching:
.\debug\profiled\restart.bat
```

Stop preserves named storage, profiles, device enrollment, applied packages and
credentials; start reuses them. Do not add `down --volumes` to a profile switch.
Private state/credentials are ignored by Git. The separate fixture container
`toolgate-profiled-demo-provider` remains available across normal stop/start.

## 6. Troubleshooting and limits

- No event: inspect **Triggers** assignment/instance health, enabled device/group,
  folder, `.txt` extension, permissions and polling interval. Path/size identity
  is a snapshot, not a filesystem journal.
- Pending preparation: inspect package/provisioning and runtime/graph readiness.
  Pending review does not mean publication. Do not blindly retry effects.
- UNKNOWN tool result: inspect business evidence and native qualification. SDK
  success or Admin authority cannot substitute for it. Qualification expires;
  renew deliberately with `tools/admin/qualify_native_files.py`, the same `$api`,
  exact `--device DEVICE_ID --group hotswap-ReadAndWriteDeviceGroup`, each
  `--tool examples.hotfolder/read_text`, `--tool examples.hotfolder/write_text`,
  `--tool examples.hotfolder/move`, and `--days 1 --acknowledge-unfenced-files`.
- Model failure: inspect fixture health, workflow origin/model/vault reference,
  installation approved origins and CA. The controlled mock delay stays within
  Gateway's 20-second request budget.
- Existing archive: preserve it and clear that exact destination before another
  file. Do not enable automatic retries to bypass reconciliation.
- Login/trust mismatch: preserve state and credentials, select the intended
  installation and supply the current password; do not silently adopt new trust.

The CLI is not a durable deployment reconciler. Failed preparation can leave
inactive candidates/packages/references; candidate readiness is still
profile-keyed. The Bots page now prepares a complete `.olo` through the existing Admin APIs:
embedded packages, referenced configuration, service bindings, trigger connections
and graph preparation. Choose **Bot .olo file**, then **Import bot .olo**. Review
the resulting revision and select **Publish reviewed revision** when ready.
An installation without an enabled device in the declared groups stays staged
and pending; configure the device, then select **Resume bot preparation**. After
a page reload, select the same bundle again to resume preparation. Destination
secrets, approved device access, native qualification and operator-created folders
remain explicit installation setup. Import never populates device files.
Revision-isolated readiness, durable server-side recovery, cross-host/distributed
qualification and release publication remain separate work. This demo does not complete the
#230/#274/#295 roadmaps. See `docs/architecture/workflow-v1-status.md` for actual
acceptance and checks.

## 7. Bot authoring filenames and administrator access

Repository bot sources live under `bot/configurations/<use-case>`; build scripts
are `bot/build.bat`, `bot/build.sh` and `bot/build.py`, with outputs in `bot/bin`.

| File | Purpose |
| --- | --- |
| `bot.json` | Graph, agent/model routing, prompts, packages and dispatch configuration |
| `package-build.json` | Recipes selecting the exact embedded tool and trigger packages |
| `referenced-config.json` | Referenced agents/groups/tools/bindings/grants/policies |
| `trigger-connections.json` | Trigger assignments/subscriptions and dispatch connections |
| `README.md` | Use-case input, output and operator requirements; separate from `.olo` |

The UI navigation is **Overview > Bots > Agents**. Bots includes authoring,
bundle transfer, review, provisioning, graph preparation and publication. The
first `admin` is live Super Admin: management permissions include bot upload,
import/export, publication, tool package import/export and trigger management.
Tools and triggers share the signed `.olotool` package transfer path. Bots run
through their configured triggers and governed runtime; Super Admin management
access does not manufacture effect evidence. The signed-in Admin cannot delete
itself. Underlying API/schema identifiers still use `workflow`/`profile` names;
these are execution contracts, not a second authoring directory or old-path alias.

## 8. Premium OpenRouter activation

OpenRouter is declared as unattached standby in every public bot. It is not
called and requires no key until explicitly attached. Follow the centralized
[model guide](../../docs/help/models.md) to configure its key, HTTPS trust,
agent candidate order, workflow ceiling and reviewed publication. Keep secrets
in the destination vault and process environment outside bot bundles.

## 9. Monitoring: local logs, Insights and external collectors

Monitoring belongs to installation settings, **not bot JSON or `.olo`**.
The generated composition passes `installation.env` to Quickstart/Control and
Gateway; it does not automatically create an observability stack or forward
arbitrary exporter environment into runtime/device containers. LangGraph worker and backend dispatch
stdout and the Admin audit/Operator Insights/result APIs are available locally.
Inspect results with `GET /api/control/v1/workflow-runs/<run-id>` through
`tools/admin/control.py @api --path ...`; private results require live Super Admin.

For platform operational spans/metrics, add these entries to
`debug/profiled/installation.env`, retaining the model settings:

```text
TOOLGATE_INSIGHTS_MODE=DETAILED
TOOLGATE_INSIGHTS_SPAN_SAMPLE_RATIO=1.0
TOOLGATE_INSIGHTS_OTLP_ENDPOINT=http://insights-collector:4318
TOOLGATE_INSIGHTS_OTLP_ENVIRONMENT=community-debug
TOOLGATE_INSIGHTS_OTLP_SERVICE_NAME=olo-toolgate-profiled
```

Use `BASIC` for aggregate metrics, `DETAILED` for sampled operational spans, or
`NONE` to disable collection. Credentials and exporter pipelines belong to a
separate OpenTelemetry Collector. Control sends OTLP/HTTP JSON to `/v1/traces`
and `/v1/metrics` under the base endpoint. Keep port 4318 private. Export includes
allowlisted operational IDs/timings/outcomes, not prompts, file contents, model
keys or tool arguments. Export failures do not turn failed calls into success.

Configure before initial startup. For an existing installation, applying changed
environment is an explicit infrastructure operation, **not a bot switch**:

```powershell
.\debug\profiled\start.bat  # regenerates composition; preserves running services
docker @compose up -d --no-deps --force-recreate quickstart
```

### Collector, Prometheus, Grafana and Tempo

Use the existing [Collector guide](../../deploy/insights/collector/README.md),
[signal/privacy guide](../../docs/control-plane/insights.md), and
[local monitoring configuration](../../deploy/compose/WorkflowStack/monitoring/local/README.md).
For this debug stack, copy collector configuration into an installation folder
such as `debug/profiled/monitoring`, attach services to external network
`toolgate-profiled_default`, and give the collector alias `insights-collector`.
Do not copy the older WorkflowStack guide's profile-derived monitoring settings
into a bot. A minimal private collector can be started as follows after creating
an OTLP receiver/pipeline configuration there:

```powershell
New-Item -ItemType Directory -Force debug/profiled/monitoring | Out-Null
# Local collection needs no vendor account:
Copy-Item debug/profiled/monitoring/collector.example.yaml debug/profiled/monitoring/collector.yaml
# Select a maintained collector image, pull it and use its immutable digest:
docker pull otel/opentelemetry-collector-contrib:latest
$collectorImage = docker image inspect --format '{{index .RepoDigests 0}}' otel/opentelemetry-collector-contrib:latest
$collectorConfig = (Resolve-Path debug/profiled/monitoring/collector.yaml).Path
# Keep exporter credentials outside bots; an empty file suffices for local mode.
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls debug/profiled/credentials /inheritance:r /grant:r "*${sid}:(OI)(CI)F" | Out-Null
if (-not (Test-Path debug/profiled/credentials/monitoring.env)) { New-Item -ItemType File debug/profiled/credentials/monitoring.env | Out-Null }
docker run -d --name toolgate-profiled-insights-collector --network toolgate-profiled_default --network-alias insights-collector --read-only --cap-drop ALL --security-opt no-new-privileges:true --env-file debug/profiled/credentials/monitoring.env -v "${collectorConfig}:/etc/otelcol/config.yaml:ro" $collectorImage --config=/etc/otelcol/config.yaml
docker logs --tail 40 toolgate-profiled-insights-collector
```

Validate your edited config with that image's `validate --config=...` command
before starting; exporter syntax/version support can differ. Choose only the
destinations you actually use. For local tools use an `otlphttp/tempo` exporter
with base `http://tempo:4318`, and a `prometheus` exporter listening on
`0.0.0.0:8889`. Route traces to Tempo, metrics to Prometheus, and scrape
`insights-collector:8889` from Prometheus. Point Grafana datasources at
`http://prometheus:9090` and `http://tempo:3200`. The linked local files include
these configs and a ToolGate dashboard. Publish only UI ports to loopback, for
example `127.0.0.1:49000:3000` and `127.0.0.1:49090:9090`; no firewall rule is
required locally. Store Grafana's initial password in the private credentials
folder; changing an initial-password environment value does not reset an
existing Grafana volume. These are operator-managed external services.

Ready-to-run local Prometheus and Grafana (the data here is deliberately volatile
debug data, cleared when these external containers are removed):

```powershell
Copy-Item deploy/compose/WorkflowStack/monitoring/local/prometheus.yaml debug/profiled/monitoring/prometheus.yaml
$prometheusConfig = (Resolve-Path debug/profiled/monitoring/prometheus.yaml).Path
docker pull prom/prometheus:latest
$prometheusImage = docker image inspect --format '{{index .RepoDigests 0}}' prom/prometheus:latest
docker run -d --name toolgate-profiled-prometheus --network toolgate-profiled_default --network-alias prometheus --read-only --cap-drop ALL --security-opt no-new-privileges:true --tmpfs /prometheus:rw,nosuid,size=128m,uid=65534,gid=65534,mode=700 -p 127.0.0.1:49090:9090 -v "${prometheusConfig}:/etc/prometheus/prometheus.yml:ro" $prometheusImage --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus
if (-not (Test-Path debug/profiled/credentials/grafana.env)) {
  $random = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($random); $rng.Dispose()
  $grafanaPassword = [Convert]::ToBase64String($random)
  "GF_SECURITY_ADMIN_PASSWORD=$grafanaPassword" | Set-Content -Encoding ascii debug/profiled/credentials/grafana.env
  Remove-Variable grafanaPassword,random
}
# Protect this credentials file using the SID/ACL procedure above.
docker pull grafana/grafana:latest
$grafanaImage = docker image inspect --format '{{index .RepoDigests 0}}' grafana/grafana:latest
$datasources = (Resolve-Path deploy/compose/WorkflowStack/monitoring/local/grafana/provisioning/datasources).Path
docker run -d --name toolgate-profiled-grafana --network toolgate-profiled_default --read-only --cap-drop ALL --security-opt no-new-privileges:true --tmpfs /var/lib/grafana:rw,nosuid,size=128m,uid=472,gid=472,mode=700 --tmpfs /tmp:rw,noexec,nosuid,size=16m,mode=1777 --env-file debug/profiled/credentials/grafana.env -p 127.0.0.1:49000:3000 -v "${datasources}:/etc/grafana/provisioning/datasources:ro" $grafanaImage
```

Open <http://127.0.0.1:49090/targets> and confirm the collector scrape is UP.
Open <http://127.0.0.1:49000>, user `admin`, password from the private
`grafana.env`. Explore the ToolGate Prometheus datasource after a real tool run
and a closed metric minute. Import the dashboard JSON at
`deploy/compose/WorkflowStack/monitoring/local/grafana/dashboards/toolgate.json`.
The provisioned Tempo datasource will remain unavailable until you supply a
Tempo service/trace pipeline as described in the linked local guide; metrics do
not require Tempo. No vendor key is passed to Grafana. To remove only these
external debug services, use `docker stop` then `docker rm` with the exact names
`toolgate-profiled-grafana`, `toolgate-profiled-prometheus` and
`toolgate-profiled-insights-collector`; core/device storage is independent.

### Langfuse, LangSmith and Phoenix

Create credentials here (Phoenix is also supported by the cloud trace fan-out):

| Service | Console | Where to create credentials |
|---|---|---|
| Langfuse | [Langfuse Cloud](https://cloud.langfuse.com/) | Project → Settings → API Keys; create a **public key + secret key**. [Guide](https://langfuse.com/faq/all/where-are-langfuse-api-keys) |
| LangSmith | [LangSmith](https://smith.langchain.com/) | Settings → API Keys → Create API Key. [Guide](https://docs.langchain.com/langsmith/create-account-api-key) |
| Phoenix | [Phoenix Cloud](https://app.phoenix.arize.com/) | Open your space → Settings; copy its **API key and collector endpoint**. [Guide](https://arize.com/docs/phoenix/prompt-engineering/quickstart-prompts/quickstart-prompts-ui) |

The runtime now exports optional **structural workflow traces**: workflow root,
agent, model generation and tool spans, plus node-completion events. They carry
OpenInference, Langfuse and LangSmith attributes, timings, state, model-group IDs
and a shared OTEL trace ID / `toolgate.run.id`. LangSmith may assign its own
native trace ID; its metadata preserves `OTEL_TRACE_ID` and `run_id` for lookup.
No prompts, completions, file contents, arguments, credentials or private thinking
are exported. Token usage/cost and exact selected model IDs are not yet supplied
by the governed model response; no estimates or fabricated values are recorded.
Native automatic LangChain/LangSmith content tracing remains disabled.

Keep installation configuration in the ignored private folder:

```powershell
if (-not (Test-Path debug/profiled/credentials/observability.json)) { Copy-Item debug/profiled/observability/credentials.example.json debug/profiled/credentials/observability.json }
notepad debug/profiled/credentials/observability.json
```

**Do not overwrite an existing credentials file.** Copy the example only on a new
installation; otherwise edit the existing JSON. Enter your actual project keys,
regional base URLs and project/space names. Leave unused destinations empty:
only configured destinations are enabled. LangSmith agent-based workspaces use
`agentId` and `agentEnvironment`; project-based workspaces use `project`. Supply
`workspaceId` if required by your key. Phoenix's base URL is its space root
(`https://app.phoenix.arize.com/s/YOUR-SPACE`), without `/settings/api-keys` or
`/v1/traces`. Secrets never belong in `bot.json` or exported bundles.

```powershell
.\debug\profiled\start-monitoring.bat
```

This validates the collector config, starts a private collector on the existing
Docker network and enables the runtime exporter. It generates:

- `credentials/collector.json`: routing, with environment references for keys.
- `credentials/monitoring.env`: collector-only authentication headers.
- `credentials/runtime-observability.env`: private collector endpoint/environment,
  without any vendor key. Only this file is passed to LangGraph.

`start.bat` also asks whether to create/reuse the observability collector.
Choose **1** to validate/create/start it; choose **2** (or Enter) to leave the
current observability setup untouched. With no cloud keys, choice 1 creates the
private template and a local-only collector, so a blank installation needs no
cloud account. Fill the template and choose 1 again to enable cloud destinations;
existing credentials are never replaced. For unattended startup:

```powershell
.\debug\profiled\start.bat -WithoutOllama -WithMonitoring
# Or leave both existing provider and observability settings untouched:
.\debug\profiled\start.bat -WithoutOllama -WithoutMonitoring
```

The collector is an installation companion managed from this startup flow and
attached to the existing stack network. It remains separate from the three
core ToolGate services. Selecting “keep” does not stop an already running
collector; use the monitoring stop command below when you intend to stop it.

The collector fans the same spans to every enabled destination. No new host port
or firewall opening is needed. Applying installation instrumentation can recreate
LangGraph once; bot import/switch does not restart it. Admin credentials and
backend/device data are preserved. Both exporters have bounded queues; telemetry
failures drop observations without granting execution, retrying a tool effect or
changing its result. The default runtime exporter is disabled when no endpoint is
configured. Stopping the collector can drop traces; disable/remove the runtime
endpoint and explicitly recreate LangGraph to stop export attempts.

Run **ollama-summary** through the Admin Run dialog. In each vendor project,
search the run ID shown by the dialog (`toolgate.run.id` / `run_id`). Langfuse shows
generation/agent/chain observations; LangSmith shows the corresponding run tree;
Phoenix uses OpenInference kinds. Do not mistake runtime observations for target
business-effect evidence. A collector's healthy state alone does not prove vendor
ingestion: check exporter errors and actual observations in all enabled projects.

```powershell
docker compose -p toolgate-profiled-observability -f debug/profiled/observability/compose.yaml logs --tail 60 collector
# Stop monitoring only, preserving the core stack:
& $debugPython debug/profiled/observability/manage.py stop
```

Langfuse Cloud v4 reads use **Observations API v2**; older trace-read routes can
return HTTP 410. Query a bounded time range and filter by trace ID. See the
[Langfuse API guide](https://langfuse.com/docs/api-and-data-platform/features/public-api),
[LangSmith OTLP guide](https://docs.langchain.com/langsmith/trace-with-opentelemetry)
and [Phoenix integration](https://github.com/Arize-ai/phoenix/blob/main/packages/phoenix-otel/README.md).
Collector export retries resend telemetry only. They never resubmit bot execution.

### Datadog, New Relic and other destinations

Use [Datadog](../../deploy/datadog/README.md),
[New Relic](../../deploy/newrelic/README.md), or the shared collector's
`datadog.yaml`, `newrelic.yaml` and `both.yaml`. Set `DD_API_KEY`/`DD_SITE` or
`NEW_RELIC_LICENSE_KEY`/`NEW_RELIC_OTLP_ENDPOINT` in the collector's private
environment, not the bot bundle or Control. Other OTLP backends use the same
receiver/fan-out boundary. Check supported signals, current exporter syntax and
actual account ingestion. Metrics close by minute, so allow at least a minute
before expecting exported aggregates.
