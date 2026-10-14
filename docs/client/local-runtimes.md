# Managed local tool runtimes

Module 08 adds an opt-in local execution boundary to the native client. Tool input
and output are JSON; the privileged identity service never launches an interpreter
or untrusted native program directly on the host. Read
[ADR 009](../adr/009-managed-local-runtime-sandbox.md) for the isolation decision.

Execution also follows the current [device registry](../control-plane/device-registry.md).
An installed client requires its enabled matching owner, enabled directory device
and current connection approval before filters, leases and runtime effects.
Unlimited approval does not broaden package permissions or sandbox limits.
Temporary HTTP 423 suspension preserves identity; recovery reuses only the same
still-approved key and does not extend the access grant.

## Installation and first run

An administrator must provision a system-accessible **Linux Docker Engine** and
its real protected CLI executable. Docker is optional for enrollment/HotFolder;
it is required for this execution backend. The engine must support seccomp,
memory/swap/PID/CPU limits. No TCP daemon endpoint, host PATH discovery or host
Python installation is used.

Each approved tool image contains its interpreter, libraries and reviewed source.
With `allowFirstUsePull: true`, the client downloads a missing **digest-pinned**
image during `runtimes prepare` or before the first invocation. Python does not
need to exist on the user's computer. Cached verified images are reused. No
system-wide pip/npm/Java installation or downloaded installer script runs as root.
Set the flag false for offline/preloaded deployments; a missing image then blocks.
First-use pulls use an empty dedicated credential configuration and therefore
require an anonymously readable approved registry. Preload private images through
your administrator-controlled distribution process; credentials stay outside the
client/tool protocol.

For installation-time provisioning, install the client, provision the engine,
write its protected registration/configuration, restart the client service, then
run `olo-toolgate-client runtimes prepare`. Check its exit code and every runtime
state. Enrollment/readiness and Gateway authorization are still required before
`run`. A preparation failure never substitutes a host interpreter.

The engine must continue after logout and at boot. On Linux use an appropriately
managed system/rootless engine with enforced cgroup limits. On Windows/macOS an
administrator must provide an always-on Linux engine/VM accessible to the system
service through the supported local socket/pipe. User-session Docker Desktop
alone does not establish that guarantee. Batch/CMD execution is currently
UNSUPPORTED; no secure Windows-native sandbox backend is delivered. WASM is an
explicit future capability. These limitations prevent claiming all Module 08
deliverables complete; see [verification status](../codex/modules/08-completion.md).

The real seven-adapter image gate currently runs on Linux x64; its pinned
PowerShell Debian fixture is x64. ARM and native Windows/macOS runtime lifecycle
gates remain unverified and must pass before those deployments are certified.

Java fourth-component versions are encoded exactly as SemVer build metadata
(e.g. `21.0.12.1` becomes `21.0.12+1`); the fourth component is not discarded.

## Configuration and organization trust

Add an optional `execution` object to the administrator-owned client JSON.
`tools` remains required because it supplies the existing device-bound HTTPS
Gateway adapter; enable it using the [HotFolder guide](hotfolder.md).
`execution.stateDirectory` must equal the client's `stateDirectory` plus
`runtimes`. Use a fresh dedicated directory, not a user's Docker configuration.
The manager refuses an existing nonempty Docker authentication configuration.

The following Linux example assumes the client state is `/var/lib/olo-toolgate`.
Replace the illustrative image digest and version with a tested approved image:

```json
{
  "enginePath": "/usr/bin/docker",
  "engineEndpoint": "unix:///var/run/docker.sock",
  "stateDirectory": "/var/lib/olo-toolgate/runtimes",
  "allowFirstUsePull": true,
  "pullTimeoutSeconds": 180,
  "runtimes": [
    {
      "id": "python-echo",
      "kind": "PYTHON",
      "image": "registry.example.com/approved/echo@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "version": "3.14.0"
    }
  ],
  "tools": [
    {
      "toolId": "local.echo",
      "action": "execute",
      "runtimeId": "python-echo",
      "entryPoint": "/opt/tool/tool.py",
      "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {"text": {"type": "string", "maxLength": 256}},
        "required": ["text"]
      },
      "outputSchema": {
        "type": "object",
        "additionalProperties": false,
        "properties": {"text": {"type": "string", "maxLength": 256}},
        "required": ["text"]
      },
      "limits": {"timeoutMs": 3000, "memoryMiB": 128, "maxInputBytes": 4096, "maxOutputBytes": 4096}
    }
  ]
}
```

The Windows engine path is normally the real
`C:\Program Files\Docker\Docker\resources\bin\docker.exe`. Supported local
pipe endpoints are `npipe:////./pipe/docker_engine` and
`npipe:////./pipe/dockerDesktopLinuxEngine`; the server must report Linux. macOS
uses an administrator-selected protected executable and local `unix:///...` socket.
Symlink/writable executable ancestry, invalid configuration and inadequate
permissions reject. The engine socket grants substantial host authority and
belongs only to trusted operators/service identities, never to a tool container.

There are at most 16 runtimes and 32 tools. IDs must be unique; built-in IDs cannot
be shadowed. Entry points are fixed lowercase paths under `/opt/tool` without
traversal, hidden components or metacharacters. Schemas are closed inline objects,
at most 8 KiB and depth 16; external/local references and regex patterns are
rejected. No registry credentials are inherited. Preload private-registry images
through an authorized operator procedure; the service's empty Docker config does
not authenticate to private registries.

Local protected registration is an explicit organization deployment decision.
Downloading a client, Marketplace metadata or an image by itself grants nothing.
Module 09 fleet assignment/distribution is not implemented. Review image contents,
dependencies, provenance/signatures and SBOM before registering its immutable
digest; the configured digest pins the tool and its runtime together.

Gateway needs the registered tool/action and a CUSTOM `/path` extractor. The
client fixes the resource to `runtime/<toolId>` and binds semantic input plus
the immutable runtime image in the authorization argument digest. Configure the
corresponding exact policy and publish it when using signed mode. The transport
request nonce is excluded from the operation digest so ASK retries preserve their
human approval scope. Every invocation still needs a fresh online grant and exact
permit consumption; readiness, expiry, denial or outage blocks.
The consumed ASK permit deadline also bounds process startup/execution; configure
its tool timeout below the available permit lifetime, including the 3500 ms
startup margin. Current server permits are capped at 10000 ms: ASK tools must
use a timeout below 6500 ms and leave time for network latency. For example,
PowerShell can use 3000 ms/512 MiB after measuring startup on the target host.
A 10000 ms tool timeout works with ordinary ALLOW but fails closed for ASK.
An insufficient or expiring consumed permit can reject before launch; the
client never extends it or retries a mutation automatically.

## Protocol and adapters

Use `olo-toolgate-client runtimes status`, `runtimes prepare`, and:

```sh
olo-toolgate-client run local.echo '{"text":"hello"}'
```

PowerShell uses `.\olo-toolgate-client.exe` with single-quoted JSON. IPC revision
3 is additive; revisions 1/2 remain unchanged. Callers supply a registered ID and
arguments, never an image, executable, environment or shell command. A tool reads
one UTF-8 stdin document terminated by newline:

```json
{"protocolVersion":1,"requestId":"request-123","toolId":"local.echo","arguments":{"text":"hello"}}
```

It writes exactly one stdout JSON document:

```json
{"protocolVersion":1,"requestId":"request-123","output":{"text":"hello"}}
```

The output request ID must match. Duplicate keys, extra documents/fields, invalid
UTF-8/JSON, stderr diagnostics, nonzero exit, schema mismatch and overflow reject.
The parent returns a fixed canonical error rather than raw stderr/engine output.
Tool output is data; it does not become instructions or authorization.

| Kind | Fixed executable/launch strategy inside approved Linux image |
|---|---|
| NATIVE | `/opt/tool/run`, exposes `--version`; invocation has no argv input |
| PYTHON | `/usr/local/bin/python3 -I -B /opt/tool/tool.py` |
| NODE | `/usr/local/bin/node --no-addons --no-warnings -- /opt/tool/tool.mjs` (or `.js`) |
| POWERSHELL | `/usr/bin/pwsh -NoLogo -NoProfile -NonInteractive -File /opt/tool/tool.ps1` |
| SHELL | `/bin/bash --noprofile --norc /opt/tool/tool.sh`; built-ins only |
| JAVA_JAR | `/opt/java/openjdk/bin/java` with bounded heap/processor settings and `-jar` |
| DOTNET | `/usr/share/dotnet/dotnet exec /opt/tool/tool.dll`; image includes runtime/dependencies |
| BATCH | UNSUPPORTED until equivalent Windows isolation is implemented |
| WASM | UNSUPPORTED future sandbox-port capability |

Version probes allow up to 30 seconds for cold container creation, then run in
the same constrained sandbox with a separate 10-second execution deadline
and 512 MiB memory budget and require exact SemVer. PowerShell registrations
should allow an adequate startup budget (the real fixture uses 10 seconds/512 MiB).
Version preparation is administrative sandbox work; it does not grant a tool
invocation. Build scripts/compilers/package managers run during reviewed image
assembly, never during protected execution. Shell tools cannot spawn jq/curl;
PowerShell/Java/.NET cannot start child programs. Choose a single-process adapter
and vendor its required libraries into the immutable image.

## Limits, health and recovery

Each invocation gets a disposable read-only image, non-root UID/GID 65532, no
network, no capabilities, no host files/device keys/engine socket, no inherited
environment and no logging driver. `/work` and `/tmp` are independent 8 MiB
noexec tmpfs mounts; no user working directory is exposed. Images declaring
automatic writable volumes reject. A custom seccomp allowlist permits threads
but denies fork/vfork/non-thread clone and namespace/kernel escape calls.
JIT memory remains possible inside the constrained container.

Timeout is 100–10000 ms, memory 32–1024 MiB with equal memory+swap ceiling, input
and output 256–65536 bytes, stderr at most 8 KiB, PIDs/threads 64, open file descriptors 256 and CPU one core.
The tool can write only ephemeral sandbox files; use built-ins for authorized
HotFolder access. Engine preparation/management also have fixed deadlines; image
pull is 5–180 seconds. Initial pull can stale device readiness; let check-in finish
and retry after preparation. IPC gives backpressure rather than queuing unlimited
jobs. Never retry a mutation blindly after losing its response.

`runtimes status` reports the latest preparation result and per-service success/
failure counters, distinct from identity `health`. Preparation probes version;
CI additionally tests actual isolation. Logs record fixed result, runtime/tool ID
and IPC request correlation, without input, output, stderr or credentials.
Normal completion/timeout removes the owned container; cancellation marks the
manager dirty. Preparation/restart reaps only its configuration's owner-labelled
containers, and graceful shutdown awaits cleanup after IPC cancellation. Engine
loss makes cleanup uncertain and blocks execution. A hard process/host crash may
leave an isolated container until recovery; monitor/reap owned labels through an
operator procedure. Keep engine/kernel patched and apply cache/disk quotas.

## Build and verify

The [Python example](../../examples/local-runtime-python/README.md) shows image
assembly and registration. Native client archives include the sandbox profile and
runtime guides, not every interpreter's image layers. Managed OCI images are
separate dependencies: record their licenses, SBOM, scans and provenance in your
approved image pipeline. Air-gapped operators preload every exact digest into the
configured engine before preparing the client.

Run `cargo test -p olo-toolgate-client --locked`. The real sandbox test is explicitly
invoked by `tools/client/runtime_check.py`, which builds actual native/Python/Node/
Shell/PowerShell/Java/.NET fixture images and verifies malformed output, injection,
timeout/memory/output limits, child/network restriction, environment leakage,
version mismatch, missing image and online-grant failure. Fixtures are test code,
never production registrations. See [execution/debugging](../operations/debugging.md)
and the completion report for executed evidence and remaining platform gates.
