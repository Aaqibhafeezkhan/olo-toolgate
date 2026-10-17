# ADR-027: One tool protocol, brokered over stdio, with a generic device files area

**Status:** Accepted. Supersedes [ADR 008](008-hotfolder-builtins.md) (capability-scoped built-ins).

## Context

Tools ran three ways: SDK packages on Tool Hosts (tool protocol v2 with a per-invocation Unix-socket broker), fixed
built-ins compiled into the endpoint client (HotFolder, log reading, utilities, the Quickstart calculator, with their
own IPC catalog and per-tool code), and SDK packages on devices with no broker at all. Each path had its own
authorization shape, limits and tests, and a device-only tool such as HotFolder could not be a Marketplace package. The
product is unreleased and installs fresh, so nothing has to keep working the old way.

## Decision

1. **One protocol for every executor.** Every tool, on a Tool Host or a device, speaks tool protocol v2
   (`packages/contracts/schemas/v2/protocol.schema.json`) over the process's stdio and nothing else:
   - stdin: exactly one `ToolInput` line, then only `BROKER_RESPONSE` lines;
   - stdout: `LOG`, `PROGRESS`, `INPUT_REQUEST` and `BROKER_REQUEST` frames, then exactly one final `RESULT`.

   There is no socket, side channel, environment-passed capability or second protocol version. The executor starts
   the runtime with `TOOLGATE_PROTOCOL=2` and `TOOLGATE_EXECUTION_TARGET` (`SERVER` or `CLIENT`), answers each
   `BROKER_REQUEST {requestId, operation, request}` with one `BROKER_RESPONSE {requestId, result | error}` in order,
   and kills the process at the deadline.
2. **One broker vocabulary.** `BrokerOperation` names everything a tool may ask its executor for:
   `ROUTE_CALL` and `SECRET_REDEEM` (Tool Hosts, for the routes and secrets the permit grants) and `FILES_LIST`,
   `FILES_READ`, `FILES_INFO`, `FILES_WRITE`, `FILES_APPEND`, `FILES_MKDIR`, `FILES_RENAME` (devices). An executor
   refuses an operation it does not provide with error code `BROKER_REFUSED`; it never falls back to another path.
3. **A generic device files area, not tool-specific code.** A device may be configured with one files area
   (`execution.files`: absolute `root`, `maxFileBytes` ≤ 64 KiB, `maxEntries` ≤ 1024, an extension allowlist). The
   client confines every operation to it: lowercase portable relative names only (no absolute paths, `..`, hidden or
   device names, alternate streams), no-follow traversal by directory handle, regular files with one link, owner and
   mode checks, Windows reparse-point checks, atomic write-and-rename, size and capacity limits. Nothing in the client
   knows which tool uses the area or why.
4. **Declared, granted, signed, then enforced.** A package declares file access with `@DeviceFileAccess` (Java SDK;
   only on a `@ClientTool`), which the processor records as descriptor permissions `DEVICE_FILES_READ` and, for
   `write = true`, `DEVICE_FILES_WRITE`. Administrators see those permissions when they review the package. Control
   maps them into the signed `LocalToolRegistration.fileAccess` (`NONE`, `READ`, `WRITE`). The client brokers file
   operations only when the registration grants them, only for a path or destination that equals a string argument
   of the authorized invocation, and at most 64 file operations per invocation. Online authorization, ASK approvals
   and permits are unchanged and still precede execution.
5. **Self-tests never touch user files.** A device runs each tool's `@Example` self-test in a new, empty files area
   under its state directory, with the device's limits, and removes it afterwards. Package examples are therefore
   written to hold in an empty folder. Outside a device (`ToolHarness`, `toolgate check`, `toolgate dev`) the SDK
   provides an in-memory area that applies the same naming rules and starts empty.
6. **HotFolder is a Marketplace package.** `examples.hotfolder` (`examples/java-tools/hotfolder`) provides the file
   tools as ten ClientTools on the generic area. The client built-ins, their IPC catalog contract, the Quickstart
   calculator and its direct-invoke API, the Quickstart "system executor" devices and the per-tool authorization
   profiles are removed, not deprecated.

## Alternatives Considered

- Keep HotFolder as a client built-in beside packages: two execution paths, and every new device capability would
  need client code per tool.
- Give device tools a bind-mounted directory inside the sandbox: the container would see the host directory, so
  confinement would depend on engine mount semantics rather than the client's checks, and per-invocation path
  authorization would be lost.
- Keep the Unix-socket broker on Tool Hosts and add stdio only for devices: two broker transports for one vocabulary.

## Security Impact

The sandbox is unchanged: no network, read-only root, dropped capabilities, no host mounts. The only file access a
device tool has is what the client performs for it after checking the signed grant and the invocation's own
arguments, inside one administrator-configured folder. A tool cannot name another tool's files unless the caller's
authorized arguments do, cannot follow links, and cannot exceed the area's limits. Refusals are explicit errors.
Removing built-ins removes a large block of privileged client code.

## Amendment (2026-10-17)

- Deployment profiles release packages to devices (`deviceGroupIds`): one signed client release per package version,
  rolled out to the active devices of the named groups, including devices that join later.
- Devices run relayed calls without a local Gateway credential (Control authorizes them over the device identity);
  without one, local command-line calls are refused.
- Devices forward each run's LOG and PROGRESS frames to Control's run-event stream, bound to the fencing token.
- Control's directory API never creates a tool or extractor, or changes anything but whether it is enabled: tool
  records come only from installed packages.

