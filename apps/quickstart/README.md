# Quickstart composition

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited.

See [Device registry and tool-call controls](../../docs/control-plane/device-registry.md).

One non-HA Linux image contains the real Control JVM, Rust Gateway, two Tool Hosts
(direct dispatch and pull mode) and the embedded console. It runs tools only from
verified packages; it has no built-in tools. SQLite and private generated keys live
under `/data`; no PostgreSQL, external identity provider or Docker socket is
required for its primary path.

Tenant identity and display name are separate. Quickstart allocates an immutable UUID
in `/data/state/tenant.json`; `TOOLGATE_DEFAULT_TENANT_ALIAS` is only a lookup alias.
Reusable `TOOLGATE_INITIALIZE_FILE` snapshots should omit `tenantId` and may set
`tenantName` (a non-blank display name of at most 256 UTF-16 code units).
`TOOLGATE_TENANT_NAME` overrides the file name on first installation; the default is
`Quickstart`. The name is persisted with installation state and supplied to Control's
organization metadata. Changing the input after installation does not rename an
existing tenant or change its UUID. The console displays the name and UUID separately;
Quickstart status exposes `tenantId` and `tenantName` and retains `tenant` for compatibility.

Start with the [user guide](../../docs/getting-started/one-minute-quickstart.md).
Build, configure, debug, upgrade and recover using the
[operations guide](../../docs/deployment/quickstart.md). The accepted boundary
and storage exception are in [Quickstart deployment and recovery](../../docs/deployment/quickstart.md).

`python tools/quickstart/check.py --build` exercises the real production image,
including the browser. The SQLite store has JVM integration tests; Linux image
identity and backup tests live in `tests/quickstart`. Publication uses the
protected `quickstart-release` CI environment and the exact tested image.

## Embedded local registry build

The optional workflow installation uses Distribution v3.1.2, built from its
checksum-pinned official source in the Quickstart Dockerfile. Its published
binary still carries affected dependencies. This composition builds with Go
1.27.4 and pins go-jose/v4 4.1.6, klauspost/compress 1.18.7 and x/net 0.62.0.
The source checksum, compiler image digest and module versions are explicit;
module checksums/notices are retained under
`/usr/share/licenses/olo-toolgate/registry/`. No vulnerability scan is disabled.
The embedded registry remains installation infrastructure, separate from bots.

See [upstream release](https://github.com/distribution/distribution/releases/tag/v3.1.2)
and the current image verification evidence in the workflow/security status.

The Docker CLI is separately pinned to 29.10.0, built with Go 1.27.4, to repair
the additional stdlib finding from the refreshed vulnerability database. The
Device and debug-device images use that same patched CLI pin.
