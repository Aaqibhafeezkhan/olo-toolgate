<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Persistent profile-driven debug stack

This community runbook starts a blank installation and demonstrates changing its
trigger, graph configuration, agent model and output paths while the same core
containers and device keep running. Run commands in **PowerShell from the
repository root**, for example `D:\git\olo-toolgate`.

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

## 3. Build the portable workflow bundles

Use signed tool/trigger artifacts from the locally built Quickstart image,
keeping packages aligned with the stack without host SDK dependencies:

```powershell
New-Item -ItemType Directory -Force build/hotswap-packages | Out-Null
docker @compose cp quickstart:/opt/toolgate/workflow-stack/packages/. build/hotswap-packages
& $debugPython bot/build.py --configuration hotswap-demo-a --packages-dir build/hotswap-packages --no-sdk-build --version 1.0.0-demo.a
& $debugPython bot/build.py --configuration hotswap-demo-b --packages-dir build/hotswap-packages --no-sdk-build --version 1.0.0-demo.b
```

Outputs:

- `bot/bin/hotswap-demo-a-hotswap-demo-1.0.0-demo.a.olo`
- `bot/bin/hotswap-demo-b-hotswap-demo-1.0.0-demo.b.olo`

Each embeds its exact `.olotool` hotfolder tools and `hotfolder-watch` package,
workflow/agent/model JSON and referenced data. Accounts, device membership,
tokens, model secrets and installation settings are excluded. Source JSON and
separate READMEs are in `bot/configurations/hotswap-demo-a` and
`bot/configurations/hotswap-demo-b`.

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
& $debugPython tools/admin/import_workflow.py bot/bin/hotswap-demo-b-hotswap-demo-1.0.0-demo.b.olo @api
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
& $debugPython tools/admin/import_workflow.py bot/bin/hotswap-demo-a-hotswap-demo-1.0.0-demo.a.olo @api
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
docker @compose logs --tail 80 worker listener device
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
  exact `--device DEVICE_ID --group ReadAndWriteDeviceGroup`, each
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

## 8. Use OpenRouter instead of the mock

OpenRouter uses `https://openrouter.ai/api/v1/chat/completions` ([official
integration guide](https://openrouter.ai/docs/quickstart)). The current Gateway
accepts an HTTPS **origin** and sends `/v1/chat/completions`; it cannot accept
`/api` in that origin. Do not configure `https://openrouter.ai/api` directly.
Use your external LLM router, or the following explicit debug bridge. It changes
only the separate model service; core/device containers and bot file data stay
untouched. An actual OpenRouter account/key is required; vendor ingestion and
live inference have not been verified without your credentials.

First complete manual preparation above so destination TLS custody exists. Then
stop the mock and attach the external bridge using that same debug-only TLS
identity. The bridge forwards the vault bearer over verified HTTPS to OpenRouter;
it has no API key in its configuration and does not log request contents.

```powershell
docker pull nginx:stable-alpine
$nginxImage = docker image inspect --format '{{index .RepoDigests 0}}' nginx:stable-alpine
$custody = (Get-Content debug/profiled/state/compose.json -Raw | ConvertFrom-Json).volumes.custody.name
$bridge = (Resolve-Path debug/profiled/openrouter-bridge.conf.example).Path
docker stop toolgate-profiled-demo-provider
docker network disconnect toolgate-profiled_default toolgate-profiled-demo-provider
docker run -d --name toolgate-profiled-openrouter --network toolgate-profiled_default --network-alias demo-provider --user 65532:65532 --read-only --cap-drop ALL --security-opt no-new-privileges:true --tmpfs /tmp:rw,noexec,nosuid,size=16m,mode=1777 --mount "type=volume,source=${custody},target=/custody/provider,readonly,volume-subpath=provider" -v "${bridge}:/etc/nginx/nginx.conf:ro" --entrypoint nginx $nginxImage -g 'daemon off;'
```

Store the OpenRouter key through the authenticated vault API, with its private
input file under credentials. Do not put keys in `bot.json`, shell history or a
bundle. The destination already has the demo tool/device groups:

```powershell
New-Item -ItemType Directory -Force debug/profiled/credentials/openrouter | Out-Null
$keyFolder = (Resolve-Path debug/profiled/credentials/openrouter).Path
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls $keyFolder /inheritance:r /grant:r "*${sid}:(OI)(CI)F" | Out-Null
$secureKey = Read-Host 'OpenRouter API key' -AsSecureString
$key = [System.Net.NetworkCredential]::new('', $secureKey).Password
@{name='openrouter-key';value=$key;toolGroupId='default-tools';deviceGroupId='default-devices'} | ConvertTo-Json | Set-Content -Encoding utf8 "$keyFolder/vault-request.json"
& $debugPython tools/admin/control.py @api --method POST --path /api/control/v1/vault --json-file "$keyFolder/vault-request.json"
Remove-Variable key,secureKey
```

Create a separate source configuration, choose a current model ID from
[OpenRouter models](https://openrouter.ai/models), and select a provider supporting
JSON-schema structured output. The model must return the Gateway's decision
schema, not free text. The configured 512-token limit bounds each response.

```powershell
Copy-Item bot/configurations/hotswap-demo-a bot/configurations/openrouter-summary -Recurse
$botPath = 'bot/configurations/openrouter-summary/bot.json'
$bot = Get-Content $botPath -Raw | ConvertFrom-Json
$bot.workflows.modelDeployments[0].modelId = Read-Host 'Exact OpenRouter model ID'
$bot.workflows.modelDeployments[0].secretName = 'openrouter-key'
# Origin remains https://demo-provider:9443, now the external bridge.
$bot | ConvertTo-Json -Depth 100 | Set-Content -Encoding utf8 $botPath
& $debugPython bot/build.py --configuration openrouter-summary --packages-dir build/hotswap-packages --no-sdk-build --version 1.0.0-openrouter.1
& $debugPython tools/admin/import_workflow.py bot/bin/openrouter-summary-hotswap-demo-1.0.0-openrouter.1.olo @api
docker logs --tail 30 toolgate-profiled-openrouter
```

Update the copied README for your chosen model. Use the A input/output folders;
preserve and clear an existing archive destination before another input. Real
summary text differs from the mock's exact prefix. Check successful Control
outcomes and the two output files. Keep provider routing/trust and secrets out
of portable bundles. For production, use separately managed TLS and a reviewed
external model router rather than the demo custody/bridge.

## 9. Monitoring: local logs, Insights and external collectors

Monitoring belongs to installation settings, **not bot JSON or `.olo`**.
The generated composition passes `installation.env` to Quickstart/Control and
Gateway; it does not automatically create an observability stack or forward
arbitrary exporter environment into runtime/device containers. Worker/listener
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

### Langfuse and LangSmith

They receive optional **operational OTLP traces** through the collector. Native
LangSmith content tracing is explicitly disabled inside the governed runner;
`LANGSMITH_TRACING=true` or `LANGCHAIN_TRACING_V2=true` does not enable prompt,
response or graph-content export. Automatic token/cost/generation tracing and
Langfuse SDK callbacks are not implemented by this stack. Instrument separately
reviewed custom applications if you need those, with an explicit data policy.

Use a private `debug/profiled/credentials/monitoring.env` for exporter keys. For
Langfuse, `LANGFUSE_BASIC_AUTH` is Base64 of `public-key:secret-key` (still secret).
For LangSmith, set `LANGSMITH_API_KEY`. Select your account's regional endpoints.
Add only desired exporters to the collector's **traces** pipeline:

```yaml
exporters:
  otlphttp/langfuse:
    endpoint: https://cloud.langfuse.com/api/public/otel
    headers:
      Authorization: "Basic ${env:LANGFUSE_BASIC_AUTH}"
      x-langfuse-ingestion-version: "4"
  otlphttp/langsmith:
    endpoint: https://api.smith.langchain.com/otel
    headers:
      x-api-key: "${env:LANGSMITH_API_KEY}"
      Langsmith-Project: "toolgate-community-debug"
```

The HTTP exporter appends `/v1/traces`; do not append it twice. Workspace/agent
headers depend on the LangSmith account. Verify current regional/auth requirements
in the [Langfuse OTLP guide](https://langfuse.com/integrations/native/opentelemetry)
and [LangSmith OTLP guide](https://docs.langchain.com/langsmith/trace-with-opentelemetry).
Do not add trace-only exporters to a metrics pipeline. Restart only the external
collector after changing its config/keys. Run a real file, check collector export
errors and delivery counters, then check actual spans in the vendor project;
valid configuration is not proof of account ingestion. No live vendor-account
acceptance is claimed here.

### Datadog, New Relic and other destinations

Use [Datadog](../../deploy/datadog/README.md),
[New Relic](../../deploy/newrelic/README.md), or the shared collector's
`datadog.yaml`, `newrelic.yaml` and `both.yaml`. Set `DD_API_KEY`/`DD_SITE` or
`NEW_RELIC_LICENSE_KEY`/`NEW_RELIC_OTLP_ENDPOINT` in the collector's private
environment, not the bot bundle or Control. Other OTLP backends use the same
receiver/fan-out boundary. Check supported signals, current exporter syntax and
actual account ingestion. Metrics close by minute, so allow at least a minute
before expecting exported aggregates.
