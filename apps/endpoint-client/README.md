# Endpoint Client and HotFolder

The current Clients table combines registered devices and pending requests, with device names, registered users and detail tooltips. Enable/Disable and Approve/Deapprove are independent; approval may be timed or explicitly unlimited. Quickstart separately registers the fixed executor, HotFolder and REST forwarding. Clients keep sending signed-key presence pings in every state and follow Control's enrollment, check-in or wait directive. Deleted live devices request fresh approval with their existing ID and key. Expired approvals remain listed as Approval required; protected access resumes only after reapproval.

See [Device registry and tool-call controls](../../docs/control-plane/device-registry.md).

Module 08 adds opt-in [managed local runtimes](../../docs/client/local-runtimes.md).
Approved images contain Python and other dependencies and can be provisioned
automatically on first use. A system-accessible Linux OCI engine is required;
Batch/CMD and WASM currently report UNSUPPORTED. Host interpreters are never a fallback.

The native protected service provides enrollment, check-in and eighteen fixed
built-in tools and signed local tools within the confined managed engine.
Gateway credentials are provisioned externally.
See [HotFolder configuration and operations](../../docs/client/hotfolder.md) and
[ADR 008](../../docs/adr/008-hotfolder-builtins.md).

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

The protected service discovers its configured HTTPS origin, verifies its signed
discovery document, generates a P-256 key locally and submits a signed PKCS#10
request. Control verifies possession, binds the key to the approved user/device,
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
HotFolder documents are preserved, including when they reside inside the state directory.
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
not configured. Module 06's historical completion report remains unchanged;
current verification and any open gates are recorded in the Module 07 report.

Module 09 uses immutable signed descriptors and monotonic device generations.
See the [fleet usage, upgrade and debug guide](../../docs/client/package-deployment.md) for external trust/store
configuration, health-gated activation, rollback/uninstall and client compatibility.
## Custom tool tests

The protected service polls organization-signed designated-client authoring jobs
through its existing fleet mTLS identity. Source runs only in the confined OCI
engine. A successful test grants no ordinary tool invocation permission. See
[the builder guide](../../docs/client/tool-builder.md).

For interactive system installation see [unsigned installers](../../docs/client/installers.md).
