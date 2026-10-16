# Device files area and the HotFolder package

A device can offer tools one confined folder, its **files area**. The client knows
nothing about the tools that use it: any signed package tool that declares file
access goes through the same checks ([ADR 027](../adr/027-one-tool-protocol-device-files.md)).
The HotFolder file tools are such a package, `examples.hotfolder`
([source](../../examples/java-tools/hotfolder/README.md)), installed like any other
Marketplace package. There are no built-in tools in the client.

See [Device registry and tool-call controls](../control-plane/device-registry.md)
and [managed local runtimes](local-runtimes.md) for how package tools run.

The service runs independently of interactive login. Windows uses an automatic
LocalSystem service, macOS uses a system LaunchDaemon, and Linux uses a root
systemd service enabled for multi-user boot. A locked or logged-out session does
not stop it. Shutdown, sleep or unavailable network prevents live protected work.
There is no arbitrary scheduler or background job engine: the service maintains
its identity heartbeat and runs signed package tools when Control sends them.

## Installation and enrollment

Open the Control container's `/` home page; it redirects to `/console/`. The
anonymous connection page includes downloads for Windows, macOS and Linux when
the administrator deploys a verified bundle. Select your CPU architecture,
verify SHA-256, extract the archive, and run its `install --server HTTPS_ORIGIN`
command with administrator rights. Linux/macOS use `sudo`; Windows uses an
elevated PowerShell. Restart/reinstall upgrades require stopping the old service
and retaining identity. `uninstall` retains identity; `uninstall --purge` removes
fixed identity files and always preserves the files area. Revoke retired devices.

Run `enroll` from an explicitly authorized OS account, confirm the code and key
fingerprint in the browser, and wait for `health` to become ready. Browser/CLI
sessions can then close. Windows IPC uses impersonated SIDs and Unix IPC uses
kernel UIDs. The CLI verifies a root/LocalSystem server. Never add wildcard peers.
Ordinary users cannot edit the installed service, configuration, credentials or
files area. Root/administrators are trusted deployment operators.
Windows installation rejects caller-writable service directories and ancestors,
even when that caller is running an elevated installer.

## Protected configuration

The files area is part of the administrator-owned `execution` settings in the
protected client JSON (beside `engine`, `stateDirectory` and the managed runtimes,
see [managed local runtimes](local-runtimes.md)). Without `files`, every file
operation is refused. Linux paths shown; use protected ProgramData paths on Windows
and `/Library/Application Support/OLO/ToolGate` on macOS.

```json
{
  "files": {
    "root": "/var/lib/olo-toolgate/hotfolder",
    "maxFileBytes": 65536,
    "maxEntries": 256,
    "extensions": ["txt", "json", "md", "csv"]
  }
}
```

`root` is absolute; `maxFileBytes` is 1 to 65,536; `maxEntries` 1 to 1,024; at most
32 lowercase alphanumeric extensions. The service creates an owner-only root;
symlinks, writable ancestors, reparse points and hard-linked files are rejected.
Linux's unit confines writes to `/var/lib/olo-toolgate` and `/run/olo-toolgate`;
for a root outside that tree, add the exact root to a systemd `ReadWritePaths`
drop-in and restart. Do not remove confinement.

## How a tool reaches files

1. The package declares access with `@DeviceFileAccess` on a `@ClientTool`; its
   descriptor carries `DEVICE_FILES_READ`, plus `DEVICE_FILES_WRITE` for writes.
   Administrators see these permissions when they review and enable the package.
2. Control signs the access into the tool's registration (`fileAccess`: `NONE`,
   `READ` or `WRITE`) in the device's fleet release.
3. A call needs the usual ready device identity and a fresh online ALLOW; ASK must
   be approved and its exact-operation permit consumed. There is no cached ALLOW.
4. The tool runs in the managed sandbox, which has no host mounts or network. It
   asks for files with tool protocol v2 `BROKER_REQUEST` frames (`FILES_LIST`,
   `FILES_READ`, `FILES_INFO`, `FILES_WRITE`, `FILES_APPEND`, `FILES_MKDIR`,
   `FILES_RENAME`). The client answers only when the registration grants that
   access, only for a path or destination equal to a string argument of the
   authorized call, and at most 64 times per call. Anything else is `BROKER_REFUSED`.

Paths are ASCII, relative, lowercase and bounded: no traversal, absolute paths,
backslashes, alternate data streams, encoded aliases, hidden names or Windows
device names, at most sixteen levels. Writes replace atomically and appends are
serialized; renames never overwrite. Delete does not exist.

Self-tests run each tool's examples in a new, empty files area under the state
directory that is removed afterwards, so they never read or change user files.

## The HotFolder package

`examples.hotfolder` provides `hotfolder.list`, `read_text`, `file_info`, `hash`
and `search_text` (read access) and `write_text`, `append_text`, `mkdir`, `move`
and `copy` (write access). A missing file is reported as data (`exists: false`,
`moved: false`); `move` and `copy` refuse an existing destination. Grant it to
callers per tool, for example `tool://examples.hotfolder/` with match `PREFIX`, or
one `EXACT` locator per read tool for read-only access.

## Downloads, deployment and release

`tools/client/package.py` consumes a tested real binary, verifies its executable
CPU header, and deterministically packages README, licenses, service definitions,
dependency SBOM and checksum. Native CI builds six OS/architecture combinations
and performs OS service-manager smoke tests. `tools/client/manifest.py` verifies
three-platform archive/checksum/header integrity and assembles
`deploy/client-assets/release/manifest.json`. Local cross-built Windows x64 uses
GNU; native release CI uses MSVC. Do not substitute a renamed Linux executable.

Build Control with `CLIENT_ASSETS_DIR=deploy/client-assets/release` and
`CLIENT_DOWNLOADS_DIRECTORY=/opt/toolgate/client-downloads`. In Helm enable
`control.clientDownloads.enabled` for that image. The default image/chart keeps
downloads unavailable. Assets are immutable, allowlisted by the canonical
manifest and checked against size and SHA-256 at startup and download. There are
two concurrent transfer slots per replica. No user-upload or proxy-download
endpoint exists. Anonymous assets do not expose tokens, enrollment codes,
private keys or administrative APIs. Standard Control quotas/probes/resources,
non-root read-only runtime and NetworkPolicy still apply. No native-client Helm
workload is added. A failed bundle disables startup rather than serving corrupt
assets; replace the image with the last verified compatible bundle to roll back.
Each image serves its embedded release version. Refresh the home page after a
rollout; historical Control asset URLs are not retained by a newer image.

Tagged publication requires protected CI and exact VERSION/tag matching. No
remote release is performed by local checks. `tools/client/sign.py` is a hook
for external Windows certificate-store, macOS Developer ID keychain or Linux
KMS identities. Signing credentials and macOS notarization infrastructure are
not provisioned by the repository. Development archives are unsigned; enterprise
deployment must apply its signing/notarization policy before distribution.

## Upgrade and troubleshooting

Deploy matching product/contracts/chart release metadata; installations are fresh,
and no earlier tool protocol or client catalog is accepted. Discovery minimum client version uses SemVer minimum
comparison. Control is built with TLS client-auth REQUEST; enabling enrollment
also requires external server TLS and device trust store with insecure HTTP
disabled. Device routes live under `/api/control/v1/endpoint`; discovery remains
`/.well-known/olo-toolgate-client`. Certificate headers never authenticate a device.

If health is unready, check connectivity, signed discovery origin/CA, enrollment
state, revocation and certificate freshness before changing tools. If a file tool
is refused, check that its signed registration grants the access it needs, that
the device has an `execution.files` area, and that each path is one the caller
passed as an argument (both paths for `move` and `copy`). Fix permissions administratively; never
relax custody or TLS. A crash may leave an internal `.write-*` temporary file,
causing inventory to fail closed. Stop the service, inspect and remove only the
verified stale internal file as an administrator, then restart. Preserve user
data and identity during recovery. Current clients can recover an expired
certificate for the exact still-approved registered key; this cannot extend an
approval deadline or bypass disablement. See the
[device registry flow](../control-plane/device-registry.md). Partial installer
repair can still require administrator action.

See [testing and verification](../testing/README.md) for required gates
and the distinction between local evidence and native CI evidence.
