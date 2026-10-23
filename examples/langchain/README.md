<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# LangChain with signed client packages

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
cleanup. An unhealthy device is a startup/executor failure; a failed rollout is
a package activation failure; an empty catalog requires checking grants, package
digests and current device reports. Do not increase timeouts to hide these errors.
Rerun setup for expired credentials or changed Gateway trust; it never silently
adopts a different CA during execution. Stop with `docker compose stop` to retain
state. `docker compose down -v` explicitly deletes this composition's state.

See the [client runtime guide](../../docs/client/local-runtimes.md),
[HotFolder package](../java-tools/hotfolder/README.md) and
[initial groups and permissions](../../config/initial/README.md).
