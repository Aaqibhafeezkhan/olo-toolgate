<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO community Quickstart — published development images

This is the Docker-image equivalent of [debug/profiled](../../../../debug/profiled/README.md).
It pulls/reuses application images instead of compiling OLO. Both entry points
share provisioning, Admin custody, device setup and optional model/collector logic;
this installation has its own project, volumes, settings and credentials.

**Publication:** the new matching development images are not published yet.
This launcher is prepared for their publication; it does not substitute older
images, build missing applications, or claim an unavailable image is runnable.
Compatibility probes fail before starting the installation if required service
entrypoints are absent. The root README screenshots come from the debug stack.

## Application images

| Service | Default image | Responsibility |
|---|---|---|
| Quickstart | `ololab/olo-toolgate-quickstart:dev` | Gateway, backend, Admin UI, SQLite, embedded package registry |
| LangGraph | `ololab/olo-toolgate-langgraph-runtime:dev` | Private graph API and execution worker |
| Device | `ololab/olo-toolgate-device:dev` | Persistent enrolled device, tools and trigger reconciliation |

Use a matching release for all three, or explicit digests in `.env`. Development
tags are mutable; inspect **About** after startup. Existing local images are
reused; missing images are pulled. To refresh a tag, pull it explicitly before
starting a fresh installation. Unsupported stores are preserved and refused,
not migrated. Image updates are separate from hot-swapping bot configurations.

Ollama plus its private TLS bridge and the observability collector are optional.
Device package executors can create additional containers; watchers can remain
running. Three core services does not mean exactly three containers in total.

## Start on Windows

Clone/download this repository and open this folder. Docker Desktop must be
running its Linux engine with current Compose support for volume subpaths.
Node, Java, Rust and a host Python installation are not needed. `start.bat`
reuses Python 3.11+ or installs a checksum-verified private Python runtime under
`.dev/profiled`, without administrator rights, PATH changes or host pip.

```powershell
.\start.bat
```

Answer the two prompts: install/reuse Ollama, and create/reuse the collector.
Unattended start:

```powershell
.\start.bat -WithoutOllama -WithoutMonitoring
# Or, with NVIDIA Docker GPU support and Internet for model downloads:
.\start.bat -WithOllama -WithMonitoring
```

Validation only (does not pull images or initialize users):

```powershell
.\manage.ps1 validate
```

Linux/macOS require Python 3.11+ and Docker/Compose. From this folder:

```sh
sh start.sh --without-ollama --without-monitoring
```

## Ports, credentials and blank installation

First startup copies `.env.example` to ignored `.env`. Default ports bind only
to loopback: Admin UI `http://127.0.0.1:18090/console/`, HTTPS API `18450`, package
registry `18552`. Edit `HTTP_PORT`, `TLS_PORT`, `REGISTRY_PORT` **before first
startup** if another stack uses them. Stop the debug stack first when using the
same defaults; this launcher never stops another project. Nothing requires
opening an inbound firewall port for local hot-folder/log watchers.

The initial workspace has only the Admin human login and installation defaults,
with no bot imported and no implicit runtime access. A connected device may
appear pending approval. Credentials are private and ignored:

```text
credentials/admin/username.txt
credentials/admin/password.txt
credentials/admin/gateway-ca.crt
```

Windows can read the password without resetting it:

```powershell
Get-Content .\credentials\admin\password.txt
```

Restart/start verifies and reuses the current Admin password. A changed password
must be supplied in that file; scripts do not reset the account to manufacture
access. Previous local credential files are retained under `credentials/admin/history/`
when a newly selected installation provides valid initial custody.

## Import and run a bot

1. Open the console, sign in as Admin and import a built/released `.olo` in **Bots**.
   The bundle carries referenced configuration and exact tool/trigger packages.
2. Review the owned device enrollment fingerprint, approve/enable it, and assign
   every device group declared by the bot. Configure the allowed file root and
   extensions in **Administration > Device**. No device approval/group membership
   is imported implicitly. API scripts are in [tools/admin](../../../../tools/admin/README.md).
3. Supply destination model credentials and approve its endpoint. Local Ollama
   users run `./manage.ps1 ollama-vault` after importing the referenced groups.
4. Resolve the red readiness bullets, review, provision packages, prepare graphs
   and publish the exact revision. Native file tools require the explicit
   qualification described in the [device guide](../../../../docs/help/device.md).
5. Use **Run** for a text-only bot, or place the exact file/content documented in
   the selected [bot README](../../../../bot/README.md). Progress/result remain in
   the console. Use a new input filename for a new watcher event.

Bot import/switch does not restart containers, rotate login credentials, change
host mounts, or seed/clean device files. Inputs, outputs, archives and filesystem
permissions remain the operator's responsibility. Stop a retired assignment when
it should no longer watch its folder.

For the existing bot file commands, open PowerShell at the **repository root**
and select this installation before following the bot's README:

```powershell
$compose = @('compose','-p','olo-quickstart-dev','-f','deploy/docker/quickstart/dev/state/compose.json')
# Inspect enrollment/readiness; no automatic approval:
docker @compose exec -T device cat /run/olo-toolgate/device-status.json
# Explicit example folder preparation for hotswap-demo-a:
docker @compose exec -T device python3 -c "from pathlib import Path; r=Path('/var/lib/olo-toolgate/files'); [(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in [r/'inbox-a',r/'processed',r/'summaries']]"
```

Files must be owned by the device broker and use mode 0600. See the exact
Windows/Linux host permission commands in the device guide when using bind
mounts. Mounts, webhook ingress ports and firewall changes are never made by a
bot import. The operator must provide the required access/network configuration.

## Models

Ollama opt-in exposes all NVIDIA GPUs, loads `olo-qlora-hotfolder:e376fb40` and
`qwen3:0.6b`, and keeps weights in a separate named volume. The QLoRA model is
prepared from pinned public adapter/base weights using an isolated Docker CPU
builder on first use. That optional model conversion builds a helper image;
it does not compile an OLO application or install host ML dependencies. Allow
several GB of downloads/disk and up to 8 GB builder RAM. Subsequent starts reuse
models. Without compatible NVIDIA Docker support, use another provider or edit
the optional GPU deployment explicitly.

Run from this folder after importing model reference groups:

```powershell
.\manage.ps1 ollama-vault
```

For another provider, configure its HTTPS origin/CA in local `installation.env`
and its scoped secret in the Admin vault. Model IDs, ordered groups, agent
selection, generation/reasoning settings and optional premium activation belong
in bot JSON. See [model setup](../../../../docs/help/models.md). Premium OpenRouter
is unattached standby and receives no automatic paid calls.

Installation environment changes require an explicit service reconfiguration;
ordinary bot import does not. After editing `installation.env`, from this folder:

```powershell
$stack = @('compose','-p','olo-quickstart-dev','-f','state/compose.json')
docker @stack stop langgraph
docker @stack up -d --no-deps quickstart
.\manage.ps1 provision-runtime
docker @stack up -d --no-deps langgraph
```

## Langfuse, LangSmith, Phoenix and other monitoring

Select the collector at startup. First use creates
`credentials/observability.json` with empty provider settings. Put your existing
project/space URLs and keys there, then rerun:

```powershell
.\start.bat -WithoutOllama -WithMonitoring
```

The shared [observability guide](../../../../docs/help/observability.md) lists
credential consoles, fields and endpoints. For this installation use this
folder's `credentials`, not `debug/profiled/credentials`. Existing keys are
retained. With no keys the collector runs locally. Generated `collector.json`,
`monitoring.env` and `runtime-observability.env` stay private. Vendor keys go only
to the collector; LangGraph receives only its private OTLP endpoint. No collector
host port is opened. This exports structural spans, not prompts, answers, tool
arguments, credentials or private thinking. Model-group IDs are not invented
usage/cost or selected-model attribution.

Other collectors/monitoring can be configured manually in `installation.env`
and `credentials/runtime-observability.env`; installation settings are outside
`.olo` bundles. Actual exporter changes may recreate LangGraph once. Bot changes
never reconfigure exporters. Keep backups of private configuration separately.

## Stop, restart and explicit removal

```powershell
.\stop.bat -WithOllama -WithMonitoring
.\start.bat -WithoutOllama -WithoutMonitoring
# Or restart the existing core service processes:
.\restart.bat
```

`stop.bat` preserves all volumes, files, models and credentials. Companion flags
select which companions to stop; omitted companions are left alone. Restart and
start use external backend APIs to refresh scoped runtime service credentials,
without rotating Admin. Refresh an expired runtime credential explicitly with
`./manage.ps1 provision-runtime`; keep service renewal outside containers.

**Factory reset** in Admin clears bot/workflow data while retaining the Admin
login. For a destructive removal of this core installation's storage, first
export needed bots and back up credentials. Stop companions, then explicitly:

```powershell
# Remove only stopped companions from this installation, so custody is no longer mounted:
docker container prune --force --filter label=com.docker.compose.project=olo-quickstart-dev-model
docker container prune --force --filter label=com.docker.compose.project=olo-quickstart-dev-observability
docker compose -p olo-quickstart-dev -f state/compose.json down --volumes --remove-orphans
```

This deletes this core project's named data/device/registry/custody volumes. It
does not delete host credential files or the external model volume. Do not reuse
an old device state volume with a newly created Gateway. Old schemas are refused;
there is no automatic conversion, deletion, credential replacement or migration.

## Source and release boundary

The launcher shares scripts under `debug/profiled` and `tools`; keep the repository
layout intact. It is not a separate single-folder download. No host build toolchain
is required for published application startup. Maintainers can override image refs
in ignored `.env` for local verification; such tests are not evidence that those
images were published. See [live workflow status](../../../../docs/architecture/workflow-v1-status.md)
for tested scope, actual checks and remaining qualification.
