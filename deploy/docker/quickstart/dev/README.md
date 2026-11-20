<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO community Quickstart — published development images

This is the Docker-image equivalent of [debug/profiled](../../../../debug/profiled/README.md).
It pulls/reuses application images instead of compiling OLO. Both entry points
share provisioning, Admin custody, device setup and optional model/collector logic;
this installation has its own project, volumes, settings and credentials.

Startup refreshes mutable development image tags and checks required
Quickstart/device entrypoints and runtime modules before initializing the
installation. A registry tag alone does not establish a working deployment.
Select matching reviewed image versions/digests in `.env`; use the
[source-built installation](../../../../debug/profiled/README.md) when developing
the applications. This launcher does not silently build or substitute old images.

Private configuration belongs to this installation. `.env` selects application
images and ports; `installation.env` configures runtime/model settings, and
`credentials/observability.json` holds optional monitoring destinations. Start
from their checked-in examples and the shared
[observability template](../../../../debug/profiled/observability/credentials.example.json).
Do not copy Admin/device identity, generated tokens, ownership markers or another
installation's database. Transfer bots with `.olo` and configure destination
secrets, devices and providers independently.

The debug and published-image installations have separate storage but share
default ports. Stop the other installation or change ports before starting.

## Application images

| Service | Default image | Responsibility |
|---|---|---|
| Quickstart | `ololab/olo-toolgate-quickstart:dev` | Gateway, backend, Admin UI, SQLite, embedded package registry |
| LangGraph | `ololab/olo-toolgate-langgraph-runtime:dev` | Private graph API and execution worker |
| Device | `ololab/olo-toolgate-device:dev` | Persistent enrolled device, tools and trigger reconciliation |

Use a matching release for all three, or explicit digests in `.env`. Development
tags are mutable; inspect **About** after startup. Startup refreshes `:dev` and
`:dev-*` aliases from their registry even if cached locally. Cached versioned
tags and digest pins are reused; missing images are pulled. Use explicit matching
versions/digests for repeatability or offline reuse. An unavailable registry or
failed capability check stops startup; there is no stale-image/build fallback.
Unsupported stores are preserved and refused,
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

Local Ollama setup uses the shared non-interactive preload helper: each model
reports loading/completion and has a bounded timeout. Existing imported weights
are reused.
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

## Invoke a bot from another application

Postman, a Rust HTTP client or a trusted backend can submit a message using the
same **Control API** as the console's Run action. There is currently no Rust
Gateway endpoint for starting a bot. Gateway's `/v1/chat/completions` performs
model inference within an already admitted run; it is not a bot/chat entrypoint.

**Current limitation:** submission, progress and result APIs require a live
tenant Super Admin. Ordinary-user execution with explicit bot/resource grants,
live event streams and registered webhook callbacks are **not implemented**.
The required replacement is specified in the
[external application integration plan](../../../../docs/plans/workflow-application-integration.md).
Do not give external chat users an Admin token to work around this restriction.

### Postman: authenticate and select a published bot

Use this installation's loopback HTTP origin, `http://127.0.0.1:18090`, as the
Postman variable `baseUrl` (adjust it if `HTTP_PORT` differs). These examples use
the Quickstart login/proxy, not device TLS port `18450`. Remote integrations
require reviewed ingress with verified HTTPS; keep installation credentials
private. Store passwords/tokens in private local variables, not shared collections
or exported environments.

Create `tenantId` using the **Tenant ID** shown in this installation's console
banner. For the installation shown in this example:

```text
tenantId = 48558ebe-a9a8-4680-8da8-129deba2e65c
```

Supply this header on the **login request**:

```http
Toolgate-Tenant-ID: {{tenantId}}
```

Use the UUID, not the tenant display name `Quickstart`. Other installations have
their own tenant IDs. The signed access token issued after login already includes
the installation's UUID in its `tenant_id` claim. Subsequent bot discovery,
submission and progress/result requests use Bearer authorization; they do not
need a separate tenant header. Control checks the verified token tenant against
the routed tenant. An optional tenant header must match and cannot override the
token. The planned stream/subscription APIs must use the same token-bound scope.

The Quickstart-local login does not currently validate the supplied tenant header;
it issues tokens for its own configured tenant. Mandatory tenant selection and
validation at login remain part of the integration plan. Do not treat a supplied
header as permission to access another installation or tenant.

1. Create a `POST` request to `{{baseUrl}}/api/quickstart/v1/login`, with
   `Toolgate-Tenant-ID: {{tenantId}}`, `Content-Type: application/json` and a
   **raw JSON** body:

   ```json
   {"username":"{{username}}","password":"{{password}}"}
   ```

   For the initial Admin login, obtain these values from the private
   `credentials/admin/username.txt` and `credentials/admin/password.txt` files.
   Save the response's `accessToken` as private variable `accessToken`.
   On subsequent requests use Postman's **Bearer Token** authorization with
   `{{accessToken}}`. Runtime worker/device credentials cannot replace a human
   login token. Authenticate again if the token expires.

2. Send `GET {{baseUrl}}/api/control/v1/workflow-profiles/published?limit=50`.
   The response is an array. Select the intended publication and copy its
   `profileId`, `digest` and `generation` into variables `profileId`,
   `profileDigest` and `expectedGeneration`. To page further, pass
   `afterProfileId` using the last returned profile ID.

3. Send
   `GET {{baseUrl}}/api/control/v1/workflow-profiles/published/{{profileId}}/{{profileDigest}}`.
   This returns the published workflow catalog. Choose `workflowId` from an
   entry in `workflows[].id`, and `entryAgentId` from that workflow's `agents[]`
   where `entryEligible` is `true`. Do not substitute the bot's display name.

The bot must already be reviewed, prepared and published, with its models,
agents, service grants, packages and devices ready. API submission does not
provision dependencies or grant resource access.

### Submit input and retrieve the response

Send the following Postman request. Set `idempotencyKey` to a new UUID for a new
logical submission and keep that value with the request until its outcome is known.

```http
POST {{baseUrl}}/api/control/v1/workflow-profiles/{{profileId}}/workflows/{{workflowId}}/runs
Authorization: Bearer {{accessToken}}
Content-Type: application/json
Idempotency-Key: {{idempotencyKey}}

{
  "profileDigest": "{{profileDigest}}",
  "expectedGeneration": {{expectedGeneration}},
  "entryAgentId": "{{entryAgentId}}",
  "message": "Summarize this support request: explain why the device is offline."
}
```

Postman substitutes `expectedGeneration` as a JSON **number**. `message` is bot
input, not an instruction to upload a file; use a message suitable for the selected
bot. If dispatch routes require disambiguation, add `dispatchBindingId` with an
ID from this published catalog's matching `dispatchBindings[]`.

HTTP `201` returns `runId`, `profileId`, `profileDigest`, `workflowId` and
`state: "QUEUED"`. Save `runId`. This confirms admission, not successful execution.

With the same tenant-bound Bearer authorization, poll these read-only endpoints:

| Request | Response |
|---|---|
| `GET {{baseUrl}}/api/control/v1/workflow-runs/{{runId}}/progress?after=0` | `items` and `nextCursor`; pass the returned cursor as `after` on the next page |
| `GET {{baseUrl}}/api/control/v1/workflow-runs/{{runId}}` | Run identity, `state`, input `message`, and `outcome` when available |

Poll about once per second while queued/running. Read the final `outcome` when
the run reaches `SUCCEEDED`, `FAILED`, `AWAITING_APPROVAL`, `CANCELLED` or `EXPIRED`.
Only `SUCCEEDED` represents success. An approval state is not permission to
resubmit and bypass review. Progress events are structural observations; the
result endpoint supplies the response, not model-token streaming.

If submission's network response is lost, retry with the **same key and identical
body** to recover the receipt. Reusing the key with different input returns a
conflict; using a new key can create another run. A delivery retry does not justify
repeating an uncertain tool effect. HTTP `401` requires fresh authentication;
`403` indicates insufficient authority/readiness; `409` can indicate a changed
publication, conflicting key reuse or ambiguous dispatch. Resolve the cause
before starting a new logical submission.

### Third-party chat hooks and event subscriptions

A trusted operator backend can currently call the HTTP flow above from Rust or
another language. OLO does not currently provide a generic chat-provider webhook
receiver, run SSE stream or registered run callback service.

For an operator-controlled adapter, the flow is:

```text
Chat provider -> verified backend adapter -> Control run submission
                                         -> poll progress/result
                                         -> provider's reply API
```

Verify the provider's signature/authentication and replay rules before accepting
an event. Map trusted caller/channel identity to allowed bot IDs on the server;
do not let event text choose privileged profiles or resources. Persist a mapping
from the provider event ID to a valid idempotency key, request and run ID, so a
redelivery observes the same run. Acknowledge the hook within the provider's
deadline and process/poll asynchronously. Keep all OLO credentials in the trusted
backend. This operator-only pattern does not provide end-user delegated access.

The required V1 community integration uses username/password authentication:
provide the tenant ID at login, authenticate an existing enabled OLO user, acquire
a user/tenant-bound access token, then use that token to
submit a bot request or subscribe to permitted progress/trigger status. The user's
current roles must allow workflow execution and every required agent, model,
tool, device and resource in the flow. A valid token alone grants no resource
access. Progress, results and trigger subscriptions must also be filtered by that
user's observation/subscription permissions; trigger status access cannot grant
workflow execution or another user's private results.

OAuth integration is deferred to a later version. V1 does not require OAuth or
a refresh-token flow: log in again to obtain a new token after expiry. Send the
password only at login, over verified HTTPS for remote access; subsequent calls
use the Bearer token with its signed tenant identity.

Resumable live events and registered signed HTTPS callbacks must retain those
checks, including revocation during delivery. These are requirements for the
planned integration, not capabilities of the current operator-only recipe.
Implementation and acceptance remain in the linked plan; there are no working
workflow event subscription-registration URLs to configure in Postman yet.

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
`renew-runtime.bat` (Windows) or `python manage.py provision-runtime`; keep service
renewal outside containers. LangGraph remains live during expiry (`AUTH_REQUIRED`)
or dependency outages (`BACKOFF`) and reconnects after replacement without a
container restart. Docker checks process liveness; execution readiness can be
unavailable independently. Interrupted tool effects are never automatically replayed.

Runtime service credentials default to seven days. Copy
`installation.env.example` to this folder's `installation.env` before startup
and set `TOOLGATE_RUNTIME_CREDENTIAL_TTL_SECONDS=604800` (1..2592000 seconds).
If the file already exists, edit it in place and retain its other settings.
This does not extend Admin sessions, execution leases or step grants. Applying a
changed container environment is an explicit deployment; it affects newly issued
credentials only. Subsequent credential replacements need no recreation.
Private credentials, device identity, data and volumes remain unchanged. No
automatic host renewal task is installed; schedule the external helper if desired.

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
images were published. See [workflow architecture](../../../../docs/architecture/workflows.md)
for tested scope, actual checks and remaining qualification.
