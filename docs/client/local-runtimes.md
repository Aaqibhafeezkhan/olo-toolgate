# Managed local tool runtimes

The endpoint client runs package tools — every `@ClientTool`, and a `@ServerTool` that needs no routes, destinations,
secrets or claims — inside a disposable OCI sandbox. It has no built-in tools and never launches an interpreter or
untrusted program on the host. Read [ADR 009](../adr/009-managed-local-runtime-sandbox.md) for the isolation decision,
[ADR 026](../adr/026-client-tool-images.md) for how Control builds the images, and
[ADR 027](../adr/027-one-tool-protocol-device-files.md) for the protocol and the device files area.

Execution also follows the current [device registry](../control-plane/device-registry.md). An installed client
requires its enabled matching owner, enabled directory device and current connection approval before any runtime
effect. Unlimited approval does not broaden package permissions or sandbox limits.

## Engine

An administrator provisions a system-accessible **Linux container engine** and its real protected CLI executable. It
is not needed for enrollment, but without it the device runs no tools. The engine must support seccomp and
memory/swap/PID/CPU limits, keep running after logout and at boot, and be reachable only through a local socket or
pipe. No TCP daemon endpoint, host `PATH` discovery or host Java installation is used. On Windows and macOS an
administrator provides an always-on Linux engine or VM; user-session Docker Desktop alone does not establish that.

Images come from Control's client releases: one approved JRE base pinned by digest plus one layer holding the package's
jar. With `allowFirstUsePull: true` the client pulls a missing **digest-pinned** image before first use; set it false
for offline or preloaded deployments, where a missing image blocks. Pulls use an empty dedicated credential
configuration, so a private registry needs images preloaded through an administrator-controlled process.

The managed runtime version is checked exactly before first use. Java fourth-component versions are encoded as SemVer
build metadata (`21.0.12.1` becomes `21.0.12+1`).

## Configuration

Add an `execution` object to the administrator-owned client JSON. `execution.stateDirectory` must equal the client's
`stateDirectory` plus `runtimes`; use a fresh dedicated directory, not a user's Docker configuration. Runtimes and tool
registrations normally arrive in signed fleet releases ([package deployment](package-deployment.md)); the device
accepts them only under the organization keys in `deployment`.

```json
{
  "enginePath": "/usr/bin/docker",
  "engineEndpoint": "unix:///var/run/docker.sock",
  "stateDirectory": "/var/lib/olo-toolgate/runtimes",
  "allowFirstUsePull": true,
  "pullTimeoutSeconds": 180,
  "runtimes": [],
  "tools": [],
  "files": {
    "root": "/var/lib/olo-toolgate/hotfolder",
    "maxFileBytes": 65536,
    "maxEntries": 256,
    "extensions": ["txt", "json", "md", "csv"]
  }
}
```

`files` is optional: it is the device's one confined files area, described in
[the files area guide](hotfolder.md). The Windows engine path is normally the real
`C:\Program Files\Docker\Docker\resources\bin\docker.exe`, with `npipe:////./pipe/docker_engine` or
`npipe:////./pipe/dockerDesktopLinuxEngine`; the server must report Linux. Symlinked or writable executable ancestry,
invalid configuration and inadequate permissions reject. The engine socket grants substantial host authority and
belongs only to trusted operators and service identities, never to a tool container.

There are at most 16 runtimes and 32 tools, with unique ids. The only runtime kind is `JAVA_SDK`; the entry point is
the package jar at `/opt/tool/<packageId>/tool.jar`. Schemas are closed inline objects, at most 8 KiB and depth 16;
external references and regex patterns are rejected.

Every invocation needs a fresh online grant and exact permit consumption; readiness, expiry, denial or outage blocks.
The consumed ASK permit deadline also bounds startup and execution, so keep tool timeouts below the permit lifetime
minus the 3500 ms startup margin. The client never extends a permit or retries a mutation automatically.

## Protocol

There is one protocol for every executor, tool protocol v2 over stdio
(`packages/contracts/schemas/v2/protocol.schema.json`). The client starts
`java -cp /opt/tool/<packageId>/tool.jar io.ololabs.toolgate.runtime.Main` with `TOOLGATE_PROTOCOL=2` and
`TOOLGATE_EXECUTION_TARGET=CLIENT`, then:

- writes one `ToolInput` line (`protocolVersion` 2, the invocation id, tool id, arguments and deadline) to stdin;
- reads `LOG`, `PROGRESS` and `BROKER_REQUEST` frames from stdout and answers each broker request with one
  `BROKER_RESPONSE` line on stdin, in order;
- takes the final `RESULT` frame and returns its structured content after output-schema validation.

A device needs no local Gateway credential for this: Control authorizes each relayed call over the device's own mTLS
identity. Without a `gateway` section, local command-line calls are refused. The LOG and PROGRESS frames of each run
(at most 64) are posted to Control's run-event stream before the result, so the Test panel shows device runs like Tool
Host runs. A device provides only the `FILES_*` broker operations, and only to a registration whose signed `fileAccess` grants
them, for paths that are arguments of the authorized call, at most 64 per call. `ROUTE_CALL`, `SECRET_REDEEM` and
anything else are refused with `BROKER_REFUSED`. An `INPUT_REQUEST` (devices take no interactive input), a tool error,
a malformed frame, stderr content, nonzero exit, schema mismatch or overflow fails closed with a fixed canonical error;
raw stderr and engine output are never reflected. Tool output is data, never instructions or authorization.

## Limits, health and recovery

Each invocation gets a disposable read-only image, non-root UID/GID 65532, no network, no capabilities, no host files,
device keys or engine socket, no inherited environment and no logging driver. `/work` and `/tmp` are independent
8 MiB `noexec` tmpfs mounts. Images declaring writable volumes reject. A custom seccomp allowlist permits threads but
denies fork, vfork, non-thread clone and namespace or kernel escape calls.

Timeout is 100–10000 ms, memory 32–1024 MiB with an equal memory+swap ceiling, input and output 256–65536 bytes,
stderr at most 8 KiB, 64 PIDs/threads, 256 open file descriptors and one CPU. The only persistent files a tool can
reach are the brokered files area. Version probes allow up to 30 seconds for cold container creation and run in the
same sandbox. Image pulls take 5–180 seconds.

Before activating a release the client runs each tool's `@Example` self-test. A tool with file access gets a new,
empty files area under the state directory, removed afterwards, so self-tests never read or change user files.

`runtimes status` reports the latest preparation result and success and failure counters. Logs record fixed results,
runtime and tool ids and correlation ids, never input, output, stderr or credentials. Normal completion and timeout
remove the owned container; restart reaps only this configuration's owner-labelled containers. Keep the engine and
kernel patched and apply cache and disk quotas.

## Build and verify

Run `cargo test -p olo-toolgate-client --locked`. `tests/execution.rs` drives the full protocol, including the files
broker, through a fake engine; the real-engine test is invoked by `tools/client/runtime_check.py` with the Hello World
package on the approved JRE base. See [debugging](../operations/debugging.md) for operations.
