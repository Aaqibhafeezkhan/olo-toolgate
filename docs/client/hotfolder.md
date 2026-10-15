# HotFolder and native client operations

Quickstart HotFolder uses local-hotfolder for device-bound policy filters and checks its enabled owner, its own enablement and the fixed executor before effects. Installed-client HotFolder retains the enrolled client identity and the same client approval gates.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

The service runs independently of interactive login. Windows uses an automatic
LocalSystem service, macOS uses a system LaunchDaemon, and Linux uses a root
systemd service enabled for multi-user boot. A locked or logged-out session does
not stop it. Shutdown, sleep or unavailable network prevents live protected work.
There is no arbitrary scheduler or background job engine in Module 07: callers
use authenticated local IPC, and the service maintains its identity heartbeat.

## Installation and enrollment

Open the Control container's `/` home page; it redirects to `/console/`. The
anonymous connection page includes downloads for Windows, macOS and Linux when
the administrator deploys a verified bundle. Select your CPU architecture,
verify SHA-256, extract the archive, and run its `install --server HTTPS_ORIGIN`
command with administrator rights. Linux/macOS use `sudo`; Windows uses an
elevated PowerShell. Restart/reinstall upgrades require stopping the old service
and retaining identity. `uninstall` retains identity; `uninstall --purge` removes
fixed identity files and always preserves HotFolder data. Revoke retired devices.

Run `enroll` from an explicitly authorized OS account, confirm the code and key
fingerprint in the browser, and wait for `health` to become ready. Browser/CLI
sessions can then close. Windows IPC uses impersonated SIDs and Unix IPC uses
kernel UIDs. The CLI verifies a root/LocalSystem server. Never add wildcard peers.
Ordinary users cannot edit the installed service, configuration, credentials or
tool root. Root/administrators are trusted deployment operators.
Windows installation rejects caller-writable service directories and ancestors,
even when that caller is running an elevated installer.

## Protected configuration

Installation starts with tools disabled. An administrator supplies the optional
`tools` object in the protected client JSON, restarts the system service, and
provisions an expiring Gateway credential bound to the enrolled device. The
credential comes from existing Gateway identity configuration; the client does
not mint it. The following illustrates Linux paths; use protected ProgramData
paths on Windows and `/Library/Application Support/OLO/ToolGate` on macOS.

```json
{
  "hotfolder": {
    "root": "/var/lib/olo-toolgate/hotfolder",
    "maxFileBytes": 65536,
    "maxEntries": 256,
    "extensions": ["txt", "json", "md"]
  },
  "gatewayUrl": "https://gateway.example.com",
  "gatewayTokenPath": "/etc/olo-toolgate/gateway-token",
  "gatewayCaPath": null,
  "deviceId": "device-ENROLLED_ID",
  "webSearchTokenPath": null,
  "webSearchAllowedDomains": []
}
```

This object belongs under `tools`, alongside existing `serverUrl`,
`stateDirectory`, `ipcEndpoint`, `authorizedPeers`, `caCertificatePath` and
`requestTimeoutSeconds`. Device ID must match the enrolled certificate identity.
All configured paths are absolute. Create missing protected parent directories
first. The service creates an owner-only HotFolder root; symlinks, writable
ancestors, reparse points and hard-linked files are rejected. Credentials are
private files (Unix 0600, directory 0700; Windows SYSTEM/Administrators ACLs).
Never put credential contents into policy arguments, chart values or logs.

Linux's unit confines writes to `/var/lib/olo-toolgate` and `/run/olo-toolgate`.
For a root outside that tree, an administrator must add the exact protected root
to a systemd `ReadWritePaths` drop-in and restart; do not remove confinement.
The client fixes its runtime at two workers and at most eight blocking workers,
so high-core-count machines remain within the installed service's task budget.

Gateway policy needs the fixed catalog action plus resource kind/locator for
each operation. File tools use FILE and the portable path relative to HotFolder;
list/events and pure utilities use locator `hotfolder`. Utilities use CUSTOM.
The Gateway extractor registry uses `/path` for these tools. Copy/move requires
separate grants for both source and destination. Path spellings are ASCII,
relative, lowercase and bounded: no traversal, absolute paths, backslashes,
ADS, encoded aliases, hidden names or Windows device names. Policy path matching
is exact; uppercase names are rejected to prevent aliases on case-insensitive filesystems.

Every call needs ready device identity and a fresh online ALLOW. ASK must already
be approved and its exact-operation permit must be consumed online. ASK pending,
denial, expiry, identity mismatch, Control staleness or Gateway outage blocks.
There is no cached offline ALLOW. Authorization occurs before effects, with
bounded HTTPS timeouts and a readiness/certificate deadline checked after grants.

## Fixed tools and limits

`olo-toolgate-client tools` lists canonical schemas for all enabled tools.
`olo-toolgate-client tools hotfolder.read_text '{"path":"notes.txt"}'` invokes
one tool. Use a PowerShell single-quoted JSON string on Windows. Scripts must
check the exit code and the canonical response; busy IPC may reject during
check-in, so retry reads with bounded backoff. Never blindly retry a mutation
after losing its response: inspect the authorized state first.

HotFolder provides list, UTF-8 read/write/append, mkdir, search in one authorized
file, hash, copy, move, file information, and event polling. Delete is absent.
Atomic replacement and serialized append prevent partial writes and lost
concurrent appends. Destination overwrite in copy/move is prohibited. Files are
at most 64 KiB, inventory at most 1,024 entries, path depth at most sixteen.
Each installation can reduce file and entry limits; allowed extensions are
explicit. Search uses literal text and returns at most 100 line numbers.

Pure tools provide bounded arithmetic, text transformations, JSON validation,
SHA-256 and OS/architecture/version information. No shell, scripts, network
fetch, command execution, package installation or process control is exposed.
Tool inputs are closed schemas; wire frames are bounded to 128 KiB and output
to 120,000 serialized bytes. Calculator depth is at most 32 and nonfinite results
fail. Structured logs record tool, request, argument digest and outcome rather
than contents or credentials. Gateway records authorization audit events.

`hotfolder.watch_events` and `hotfolder.list_events` are bounded polling of
service-observed successful mutations, not filesystem monitoring of arbitrary
external changes. The ring retains 256 records. Pass `after` and the previous
`epoch`; a restart, cursor gap or epoch mismatch sets `reset`. Re-read authorized
inventory after reset. Events are transient, never durable security evidence.
The Control audit and Gateway audit retain their existing durable/sink behavior.

`web.search` is disabled until `webSearchTokenPath` names a private Brave Search
provider credential. Only the fixed HTTPS Brave endpoint is contacted; redirects,
proxies and result fetching are disabled. Queries are at most 256 characters,
responses 128 KiB, results five. `webSearchAllowedDomains` can restrict returned
links to exact domains/subdomains. Results are untrusted data, never executable
instructions. A deployment without a credential makes no provider calls.

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

Deploy matching product/contracts/chart release metadata. IPC protocol 1 remains
frozen; tools use additive protocol 2. Current device management requires the
complete Control Flyway series through V11. Discovery minimum client version uses SemVer minimum
comparison. Control is built with TLS client-auth REQUEST; enabling enrollment
also requires external server TLS and device trust store with insecure HTTP
disabled. Device routes live under `/api/control/v1/endpoint`; discovery remains
`/.well-known/olo-toolgate-client`. Certificate headers never authenticate a device.

If health is unready, check connectivity, signed discovery origin/CA, enrollment
state, revocation and certificate freshness before changing tools. If tool calls
block, inspect protected token expiry, enrolled device binding, policy action,
extractor and both paths for copy/move. Fix permissions administratively; never
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
