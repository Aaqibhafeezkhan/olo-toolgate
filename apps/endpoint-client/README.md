# Endpoint Client

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited. Clients keep sending signed-key presence pings in every state and follow Control's enrollment, check-in or wait directive. Deleted live devices request fresh approval with their existing ID and key. Expired approvals remain listed as Approval required; protected access resumes only after reapproval.

See [Device registry and tool-call controls](../../docs/control-plane/device-registry.md).

The native protected service provides enrollment, check-in and signed package tools,
run in [managed local runtimes](../../docs/client/local-runtimes.md): Control-built
Java SDK images on an approved JRE base, in a sandbox on a system-accessible Linux
OCI engine. It has no built-in tools, and host interpreters are never a fallback.
Tools speak the one tool protocol, v2 over stdio; a device brokers only its
confined [files area](../../docs/client/hotfolder.md) to tools whose signed
registration grants it ([ADR 027](../../docs/adr/027-one-tool-protocol-device-files.md)).

Download the Windows, macOS or Linux archive from the Control home page without
signing in. Check its SHA-256 against the displayed manifest before extracting.
Use an elevated PowerShell on Windows (`.\olo-toolgate-client.exe install --server
https://control.example.com`) or `sudo ./olo-toolgate-client install --server
https://control.example.com` on Linux/macOS. Installation requires administrator
rights; downloading does not grant enrollment or tool permission.

The installer registers an automatic **system** service: Windows LocalSystem,
macOS system LaunchDaemon, or Linux systemd multi-user service. It continues when
all users log out and while the screen is locked. The computer must remain powered
on and awake. Enrollment initially requires a person to confirm the browser code;
after enrollment, heartbeat and configured protected calls need no logged-in user.
Network loss, revoked identity and missing authorization block tool execution.

Build with `cargo build -p olo-toolgate-client --locked`; test with
`cargo test -p olo-toolgate-client --locked`. The service uses the shared
canonical contracts. Read [the enrollment decision](../../docs/adr/007-endpoint-enrollment.md).

An administrator runs `olo-toolgate-client install --server https://control.example.com`.
The installer registers systemd on Linux, launchd on macOS, or a LocalSystem
Windows service. The ordinary user runs `olo-toolgate-client enroll` and confirms
the public code and key fingerprint in the browser. The enroller's signed IdP
token needs the `toolgate-enroller` role, `tenant_id`, and an enabled `user_id`.
The browser never receives the device code or private key.

Installer setup verifies v2 discovery over authenticated HTTPS and persists `tenantId`
with the canonical Control origin. Setup also accepts `--tenant-id <canonical-UUID>`
as an optional assertion. Browser setup always carries its verified UUID into the elevated
installer; a fresh HTTPS discovery fetch must still match before installation writes state.
The protected service anchors this configuration
and generates a P-256 key locally. Verified v2 discovery and its key ring are persisted before enrollment;
the service then submits a signed PKCS#10 request. Control verifies possession, binds the key to the approved user/device,
and signs a client-only certificate for at most 24 hours. Heartbeats renew it
when less than 12 hours remain. The active device and owner are checked on every
check-in. Administrative revocation blocks protected access and renewal. Revoked
clients ping at each local X:00:00 hour boundary, including after a failed ping,
so an admin state change is discovered at the next scheduled ping without a push.
The tray's **Repair gateway connection** and **Switch gateway → Configuration**
actions check Control once immediately, including while revoked. They follow its
current state without requesting approval or resetting enrollment. An unchanged
revocation keeps the hourly schedule; a changed state updates the client and timer.
Use `olo-toolgate-client request-approval` (or **Request device approval** in the
Windows tray) to request a fresh admin review immediately. The admin console also
offers **Request approval again**; both flows retain the device ID and key and
require fresh approval before protected access resumes.

`olo-toolgate-client power on|off|status` (tray **Power on / Power off**) is the
owner's local switch. Off sends one final report, then stops every gateway
connection and refuses tool requests until the owner switches it on; enrollment
is kept. See [the owner power switch](../../docs/client/health-and-inventory.md#owner-power-switch).

`health` and `check-in` are bounded IPC commands. Unix sockets use kernel peer
UIDs; Windows pipes use OS impersonation SIDs and reject remote clients. The CLI
verifies that its IPC server is root or LocalSystem. Allowed peers are explicitly
listed in the administrator-owned configuration. There is no credential export,
arbitrary path, shell, execution or configuration mutation operation.

Unix state is owner-only and rejects writable ancestors and symlinks. Windows
keys use account-scoped DPAPI and protected ACLs; privileged installed files are
administrator-owned. The exclusive service lease and durable pending report
prevent duplicate processes from advancing the sequence independently. A lost
response retries the exact stored report. Offline, expired or revoked clients
are unready. Offline state grants no runtime permission. Readiness becomes stale
after two minutes without a successful check-in.

Configuration fields are `serverUrl`, absolute `stateDirectory`, `ipcEndpoint`,
`authorizedPeers`, optional absolute `caCertificatePath`, and
`requestTimeoutSeconds` (1–15). Linux uses `/etc/olo-toolgate/client.json` and
`/var/lib/olo-toolgate`; macOS uses `/Library/Application Support/OLO/ToolGate`;
Windows uses `C:\ProgramData\OLO\ToolGate`. Enterprise HTTPS roots may be added
through the protected CA file setting. Redirects, proxies and insecure TLS are
disabled. Discovery CA changes require administrator-led recovery; no automatic
trust replacement is implemented.

Control enrollment is opt-in (`toolgate.control.endpoint.enabled=true`). Set
`tenant-id`, `server-id`, `organization`, `control-url`, `gateway-url`,
`private-key-path` (PKCS#8 RSA, at least 3072 bits), and `ca-certificate-path`.
The device CA must differ from IdP and policy keys. Direct HTTPS must disable
insecure requests, request client certificates and configure a PKCS#12 device
CA trust store. Forwarded certificate headers are never identity evidence.
The existing PostgreSQL migration V5 holds bounded enrollment rows, durable
identity tombstones, and ordered check-in reports. Database rollback requires
backup restore; older Control versions do not support the new identity tables.

The Helm `control.endpoint` settings reference existing external device CA,
server TLS and device trust-store Secrets. No key is generated or stored in
chart values. Browser TLS and client mTLS must reach Control directly; ingress
TLS termination cannot authenticate device certificates. Apply ingress quotas
in addition to the server's 32 active enrollment records per tenant and five
second polling limit. Existing Control readiness/metrics and HA remain in use.

Uninstall stops/removes the OS service and retains identity by default.
`uninstall --purge` explicitly deletes only the fixed protected identity/state files.
Documents outside the fixed state directory are preserved. A complete installer
purge removes local data and the device key inside that directory. A new organization
gets a new key after explicit purge. The service journal uses format version 2 and
binds one configured origin and one verified tenant UUID; unsupported pre-release
state is rejected unchanged. Saved background profiles are never connected.
Revoke the identity in Control before retiring a machine. A failed partial
installation requires administrator recovery of the fixed service/config paths;
partial installation repair may require administrator action. Current clients
recover an expired certificate for the exact still-approved key without changing
its owner, enablement or connection deadline. See the
[device registry flow](../../docs/control-plane/device-registry.md).

CI builds six native OS/architecture combinations. `tools/client/package.py`
produces deterministic tar/zip archives, SHA-256 checksums and a dependency SBOM.
Archives contain third-party license notices and the same install/uninstall CLI
on all three operating systems. Native CI tests the real service manager and
builds x64 and ARM64 packages. A protected tagged-release job publishes verified
packages and provenance; signing hooks require externally provisioned OS/KMS
identities. Local development packages are unsigned and macOS notarization is
not configured. See [installer limitations](../../docs/client/installers.md) and
[remaining qualification work](../../docs/planning/README.md).

Module 09 uses immutable signed descriptors and monotonic device generations.
See the [fleet usage, upgrade and debug guide](../../docs/client/package-deployment.md) for external trust/store
configuration, health-gated activation, rollback/uninstall and client compatibility.
## Custom tool tests

The protected service polls organization-signed designated-client authoring jobs
through its existing fleet mTLS identity. Source runs only in the confined OCI
engine. A successful test grants no ordinary tool invocation permission. See
[the builder guide](../../docs/client/tool-builder.md).

For interactive system installation see [unsigned installers](../../docs/client/installers.md).

Windows tray and maintenance uninstall resolve the registered Inno uninstaller,
including `unins001.exe` after reinstall. Cleanup starts after confirmation;
cancelling preserves the tray and service. Removal waits for the service to stop
and disappear from SCM before deleting the fixed runtime/config files. Retrying
after partial cleanup tolerates already absent files/services. Installer uninstall
purges tenant enrollment, local data and the device key.
Failures appear in the installer/tray instead of silently doing nothing.

The protected service and user-session tray have separate lifetimes. A connected
device in the console reflects service check-ins. Closing the tray does not
disconnect the service; the Run registration starts the tray at the next sign-in.
