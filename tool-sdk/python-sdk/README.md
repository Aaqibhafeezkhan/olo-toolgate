<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ToolGate Python SDK

Python 3.10+ authoring, descriptors, standalone MCP, governed execution, test
harness and deterministic packaging. The Java SDK is the behavioral reference.

From the checkout, install `python -m pip install -e tool-sdk/python-sdk`.
Install `toolgate-sdk[identity]` for standalone JWT verification or SPKI pins.
The shared Rust CLI must be on PATH (`cargo build -p toolgate-cli --locked`).

```text
toolgate init orders --lang python
cd orders
python -m pip install -e .
toolgate check
toolgate dev
toolgate dev --http 127.0.0.1:8080
toolgate pack
```

The CLI selects the project's `.venv` interpreter, `TOOLGATE_PYTHON`, or Python
on PATH. Direct equivalents are `python -m toolgate check`, `dev`, `stage`,
and `pack`. The Python console command is `toolgate-python-sdk`; `toolgate`
belongs to the shared Rust CLI. `[tool.toolgate].descriptor_generation = 1`
is required, along with `packages` or `modules` naming the tool code.

Decorators, context getters, error classes and route APIs follow
[the Python authoring API](../spec/authoring/python.md). Both normal and
`async def` handlers work; `async_=True` declares a protocol Task independently
of whether the handler is a coroutine. `device_file_access(write=True)` is the
Python equivalent of Java's DeviceFileAccess, only for ClientTools.

Standalone configuration uses the same `TOOLGATE_MODE`, `TOOLGATE_GLOBAL_*`,
`TOOLGATE_SECRET_*`, `TOOLGATE_DESTINATION_*`, `TOOLGATE_AUTH_*`,
`TOOLGATE_STATE_DB`, `TOOLGATE_TASK_STORE` and `TOOLGATE_BUSINESS_KEY_RETENTION`
settings as Java. Development reads `toolgate.dev.json`; keep it git-ignored.
Test mode is available only through `toolgate.testing.ToolHarness`. Production
claims come only from a verified identity. HTTP serves `/mcp`, checks routing
headers and loopback Host/Origin, supports progress/subscription SSE, and requires
identity verification before binding outside loopback.

`ToolHarness(modules_or_build, fixtures=..., exchanges=...)` is a context manager.
Use `call(local_name, arguments)`, `result.output`, `result.error_code`,
`route_calls()` and `run_examples(project_root)`. Test routes always use recorded
exchanges and never resolve credentials or contact an upstream.

Packaging emits one `artifacts/tool.pyz` containing the tool, SDK and generated
index, then delegates archive validation and digests to the Rust packager.
The executable works with only its declared Python interpreter. Third-party
tool dependencies require `[tool.toolgate].requirements_lock`: a pip requirements
file containing the full transitive wheel closure pinned with `==` and SHA-256
hashes. An optional `wheelhouse` directory allows offline resolution. Native
wheels must match the target interpreter and OS. At startup the executable
extracts its contents to a private temporary directory so native extensions work.
Sorted ZIP entries and fixed timestamps make repeated builds reproducible for
the same sources, lockfile, wheels, SDK and toolchain.
Install `toolgate-sdk[build]` when building projects with locked third-party dependencies.

With `TOOLGATE_PROTOCOL=2`, the executable runs one governed invocation with
the executor's ID/deadline. It emits LOG/PROGRESS/RESULT and exchanges
BROKER_REQUEST/BROKER_RESPONSE on stdio. Route authentication belongs to the
host broker; device files belong to the device broker. No socket broker or
local business-key reservation is used in governed mode. As with Java, TCP
under a Tool Host and the legacy MCP session protocol are outside this runtime.

Control's device-image release path requires a separately approved digest-pinned
Python base: set `TOOLGATE_CONTROL_CLIENT_IMAGES_PYTHON_BASE`, or Helm's
`control.clientImages.pythonBase`, to the mirrored image reference. The base
must expose `/usr/local/bin/python3` and declare `PYTHON_VERSION=3.10.x` or later.
Control creates a signed `PYTHON_SDK` registration; the device verifies the exact
interpreter version and uses the same no-network sandbox and files broker as Java.

Run SDK tests with `python -m unittest discover -s tool-sdk/python-sdk/tests`.
The e2e test additionally requires `TOOLGATE_CLI` pointing to the built Rust CLI.
