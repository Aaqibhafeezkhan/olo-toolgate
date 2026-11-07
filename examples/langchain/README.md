<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# LangChain with signed client packages

On the fresh zero-trust feature build, native HotFolder business finality is
not qualified. Positive smoke and live model runs stop on Control's
OUTCOME_UNKNOWN result; reported output is not a successful business outcome.
Use the current boundary check after setup:

```sh
docker compose run --rm -T agent --target linux --verify-native-boundary
```

It requires current signed-tool discovery and an authenticated non-retryable
OUTCOME_UNKNOWN/REPORTED result. It submits no write, retry or model call.
This is the release gate for the unqualified native boundary, not qualification
of native file-operation completion. Native finality remains outstanding in the
[execution status](../../docs/security/zero-trust-execution-status.md).
Do not retry an unknown operation to make the positive smoke test pass.


The example builds the real `examples.hotfolder` Java SDK package, uploads it to
Control, requests a signed client release and assigns it to an enrolled device.
A LangChain agent discovers its granted package tools through the Gateway and
reads/writes the device's confined files area. No built-in tools or exported
authorization profiles are used. The agent gets neither device storage nor the
Docker socket. Every effect still requires current authorization and a device mTLS permit.

## Requirements

Use Docker with Linux containers and Compose 2.24+, and host Python 3.11+.
The package is built inside a pinned JDK container using the selected Quickstart's
CLI and this checkout's SDK source. No host JDK, Rust compiler or Marketplace
account is needed. Keep the checkout and selected Quickstart/device versions aligned.

The local evaluation executor explicitly mounts the system Docker socket to run
signed OCI packages. Tools run in separate confined containers with no socket or
host mounts; they access files through the generic broker. The local registry is
bound only to host loopback and shares Quickstart's network namespace. Control
permits plain HTTP only for this loopback development registry. Gateway/device TLS
verification remains enabled. This composition is a local evaluation fixture,
not a production registry or executor-isolation template.

## Deploy and smoke-test

From `examples/langchain`, copy `.env.example` to `.env` and select matching images.
Defaults use ports 18091 (console), 18451 (Gateway) and 18552 (local registry).

```sh
python manage.py prepare
docker compose build agent
docker compose up -d --wait --wait-timeout 180 quickstart linux-device
python manage.py setup --bootstrap-local --approve-linux-device --target linux
docker compose run --rm -T agent --target linux --smoke
```

On Windows, `deploy.bat -Target linux` performs deployment; `execute.bat -Target
linux -Smoke` runs the smoke test against it. `start.bat` combines both steps.

Preparation builds `hotfolder.tgpkg`, mirrors the approved digest-pinned JRE to
the local registry and writes the client-image settings in `.state/client-images.env`.
Setup logs in using the isolated installation's real credentials, verifies the
Linux enrollment fingerprint before approval, copies only PUBLIC release and
organization verification keys to the device, and requests the signed release
through Control's API. It waits for the selected fleet rollout to report READY,
then configures exact `invoke`/`CUSTOM` grants in dedicated package groups and a
24-hour agent credential bound to that selected device. Standard FILE read/write
presets remain unchanged. The
installation owner's audited Super Admin path is used for these explicit local
management changes; execution authorization is not bypassed.

Fresh local passwords are retained privately in `.state/console-passwords.json`.
For an existing installation, provide its current credentials in `.env`.
Preparation never reads private signing keys or manufactures tool profiles.
Existing enrollment and files remain in named volumes. A recreated container
installs the selected image's client and verifies trust before reconnecting.

The smoke test calls the actual package tools `examples.hotfolder/list`,
`examples.hotfolder/read_text` and `examples.hotfolder/write_text`; it validates
the package's `written` result and reads the unique output back. No model or
paid provider call is made. Device activity remains available in the client
diagnostics; it is not a package tool exposed to the agent.

## Run an AI task

Set `OPENROUTER_API_KEY` and optionally `OPENROUTER_MODEL` in `.env` (default:
`openrouter/free`). Then run the agent without `--smoke`, optionally selecting
`--use-case tickets`, `operations` or `inventory`. These inputs are fictional.
The selected input and generated report are sent to your chosen model provider.
The agent restricts reads/writes to that task's exact relative paths and verifies
the report through a real device readback before claiming completion.

Files live inside the selected device at `/var/lib/olo-toolgate/hotfolder/` for
the Linux composition. For example:

```sh
docker compose exec linux-device cat /var/lib/olo-toolgate/hotfolder/langchain-smoke.txt
```

## Hot-folder trigger (in progress, issue #274)

A file dropped into the device's `inbox/` folder triggers a LangGraph flow
([trigger_agent.py](trigger_agent.py)). The flow displays the file's content and
then moves it to `processed/`. The feature is enabled by a deployment profile, not by a flag:
[hotfolder-trigger.profile.json](hotfolder-trigger.profile.json) releases the
trigger-only [`examples.inbox-watch`](../java-tools/inbox-watch/README.md) package to
`ReadAndWriteDeviceGroup` and assigns its `new-file` client trigger there with
`{"folder": "inbox", "extension": ".txt"}`. The HotFolder package stays tools-only, so the
base setup above works without triggers. Any Control that applies the
profile enables triggers and creates (or updates) the assignment, including on
devices that join the group later.

```sh
python manage.py prepare        # also writes .state/package/core-packages.lock.json
docker compose -f compose.yaml -f compose.triggers.yaml up -d --build --wait --wait-timeout 180 quickstart linux-device
python manage.py setup --bootstrap-local --approve-linux-device --target linux
pip install -r requirements.txt
python trigger_agent.py --target linux
docker compose exec linux-device sh -c 'echo "2 x widget" > /var/lib/olo-toolgate/hotfolder/inbox/order-1042.txt'
```

The device polls `inbox/` every minute and reports each new `.txt` file once
(`examples.files.observed.v1`, a `STATE_SNAPSHOT` source). Control stores
each event once, keyed by its source and id. The agent reads it, prints the
content and moves the file with the package's `move` tool. A file that is already
gone is skipped. An operation whose outcome the Gateway cannot confirm is
reported and never retried.

The agent subscribes to `hotfolder-inbox` and receives events over Control's `toolgate.triggers.v1` WebSocket
(`--transport poll` pages the stored-events API instead). It acknowledges each event only after handling it, so a
crash re-delivers rather than loses an event. `python trigger_e2e.py` runs the whole path after setup, and CI runs
it in the LangChain job.

Status (issue #274, draft PR #286):

- **Delivered in that PR:**
  - modules 5 and 10–12: the profile-driven assignment, the device trigger runner, check-in ingest, the event
    router with retention, coarse labels and the subscription socket;
  - unit tests: [test_trigger_agent.py](test_trigger_agent.py), `HotFolderTest`, `TriggerDeliveryTest`,
    `TriggerIngestTest` and `ClientReleasesTest`.

  The Compose run has not been executed locally.
- **Native HotFolder finality** (see the note at the top): until it is qualified, the Gateway reports `read_text`
  and `move` as `OUTCOME_UNKNOWN`. The agent then shows `read-unconfirmed` or `move-unconfirmed` and leaves the file
  in `inbox/`.

## Windows device targeting

Use an x64 Windows client enrolled and approved on this example's Gateway, with
Docker Desktop's Linux engine running on the SAME host as this local registry.
Run Linux setup first to export public deployment trust, then run
`prepare-windows.ps1 -TrustPath .state/deployment-trust.json` as Administrator.
It configures the generic files/package executor and reports the enrolled device
ID. It does not switch Gateways, install tools or approve enrollment.
Use `deploy.bat -Win -DeviceId <enrolled-id>` followed by `execute.bat -Win -Smoke`.
The signed package supports Linux/Windows x64; native Windows execution still
requires separate platform qualification. Remote-host registry deployment and
Windows ARM are not provided by this local fixture.

## Verification and troubleshooting

```sh
python -m unittest discover -s . -p test_manage.py
python -m unittest discover -s . -p test_packages.py
docker compose run --rm -T --no-deps --entrypoint python -v "$PWD/test_agent.py:/app/test_agent.py:ro" agent /app/test_agent.py
docker compose logs --tail 100 quickstart linux-device registry
```

CI retains `langchain-diagnostics` with container status and bounded logs before
cleanup. `command-failures.jsonl` records command categories, exit codes and fixed
failure reasons, without command arguments, credentials or captured output.
After a device restart, setup requires a heartbeat newer than restart completion,
the expected package's READY report, and a fresh ACTIVE local status for the same
device. Only missing status publication or a not-yet-running container is retried;
permission failures, changed identities and malformed status fail immediately.
Metadata export reuses that verified identity so a concurrent health refresh
cannot invalidate setup by temporarily removing its status file. Lifecycle and
package regression tests also run in the early foundation gate.

An unhealthy device is a startup/executor failure; a failed rollout is
a package activation failure; an empty catalog requires checking grants, package
digests and current device reports. Do not increase timeouts to hide these errors.
Rerun setup for expired credentials or changed Gateway trust; it never silently
adopts a different CA during execution. Stop with `docker compose stop` to retain
state. `docker compose down -v` explicitly deletes this composition's state.

See the [client runtime guide](../../docs/client/local-runtimes.md),
[HotFolder package](../java-tools/hotfolder/README.md) and
[initial groups and permissions](../../config/initial/README.md).
