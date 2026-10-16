# How to debug ToolGate

For HTTP 423, inspect device enablement, connection approval and its deadline. Retain protected identity and fix the blocking registry/filter; do not weaken TLS or custody.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

For managed runtimes, start with `olo-toolgate-client runtimes status` and
`runtimes prepare`. Check exact image/version, local engine access and resource/
seccomp capability, plus the [runtime troubleshooting guide](../client/local-runtimes.md#limits-health-and-recovery).
A runtime kind other than `JAVA_SDK` cannot be fixed by enabling host execution.

Start with the [execution and configuration guide](execution-guide.md). Collect
the software version, UTC time, component, response status/error code and server
request ID before changing configuration. Correlate request/trace IDs in sanitized
logs and audit. Keep credentials, enrollment codes, permits, private keys, file
contents and raw arguments out of tickets and shared terminal output.

## Local debugging

Run `python tools/ui/check.py --serve` for an interactive real Control/UI database
environment. Its loopback URL and private temporary credentials are printed.
Control logs are in `build/ui/control-private.log`. Restart after the five-minute
test tokens expire. Ctrl+C removes its temporary database; use a separately
provisioned development database for data you need to retain.

For frontend changes:

```sh
npm --workspace @olo-labs/toolgate-admin-ui run dev
```

Vite prints its URL and serves `/console/`; its current proxy targets only
`/api/control/v1` on `127.0.0.1:8082`. The UI harness chooses a dynamic Control
port, so configure a local Vite proxy target to that printed port or start your
independent development Control on 8082. Anonymous downloads and discovery are
not covered by that proxy. Use the embedded Control UI when debugging those routes.
Browser DevTools Network shows response status and request IDs. Tokens are not
persisted; reconnect after a reload. Avoid exporting authenticated HAR files.

For Java breakpoints, build Control and launch its packaged application with a
loopback-only JDWP listener (Java 21):

```sh
./gradlew :control-plane:build
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 -jar apps/control-plane/build/quarkus-app/quarkus-run.jar
```

PowerShell uses `.\gradlew.bat` and quotes the complete `-agentlib:...` argument.
Before launching, supply the required database/auth environment variables from
[Control configuration](../control-plane/configuration.md), bind HTTP/management
to loopback, and use distinct migration/runtime users. The debugger waits at 5005
until your IDE attaches. Use only isolated test credentials; do not attach JDWP
to production. `TOOLGATE_CONTROL_DEVELOPMENT_MODE=true` permits isolated non-TLS
test PostgreSQL, but does not remove authentication or database privilege checks.

For Rust, `cargo build -p olo-toolgate-gateway --locked` and
`cargo build -p olo-toolgate-client --locked` produce debug binaries under
`target/debug` (unless `CARGO_TARGET_DIR` changes it). Attach your platform's Rust
debugger to an isolated instance with the same protected configuration and
identity requirements. Client foreground `service --config /absolute/client.json`
is available on Unix for an administrator; stop the installed service first to
avoid lease/IPC conflicts. Windows `service` is an SCM entry point, so debug the
installed service on a disposable Windows test machine. Do not run an ordinary
user process with production device keys or weaken ACL/UID checks for debugging.

## Production inspection

Discover actual workload/Service names first; replace angle-bracket placeholders:

```sh
kubectl -n toolgate get deployments,pods,services
kubectl -n toolgate describe pod <pod>
kubectl -n toolgate logs <pod> --since=15m
kubectl -n toolgate logs <pod> --previous
kubectl -n toolgate get events --sort-by=.metadata.creationTimestamp
kubectl -n toolgate rollout status deployment/<deployment>
```

Check image digest, rollout age, restarts, OOM/probe events and Secret mount names.
Read Secret metadata without printing `.data`. ConfigMaps contain policy and
configuration and still require controlled handling. A `kubectl describe` or
log capture should be reviewed before sharing.

Temporarily forward a private management Service onto loopback; Kubernetes RBAC
must authorize the operation. Stop port forwarding after inspection:

```sh
kubectl -n toolgate port-forward --address 127.0.0.1 service/<control-management-service> 19092:9092
```

In another terminal:

```sh
curl --fail http://127.0.0.1:19092/q/health/live
curl --fail http://127.0.0.1:19092/q/health/ready
curl --fail http://127.0.0.1:19092/q/metrics
```

Gateway management uses port 9091 and `/v1/health/live`, `/v1/health/ready`, `/v1/metrics`;
forward to a distinct loopback port. Inspect each replica, especially bundle state
and sequence, rather than inferring fleet readiness from one successful request.
Enable sanitized OTLP export only to a trusted collector through the documented
Control tracing settings; keep request/body logging disabled.

## Client service inspection

Run the installed/extracted CLI's `version`, `health`, `check-in` and `tools`
commands from an authorized OS account. Service control requires administrator
rights; restarting temporarily interrupts protected execution.

Linux:

```sh
sudo systemctl status olo-toolgate-client.service
sudo journalctl -u olo-toolgate-client.service --since '15 minutes ago'
sudo systemctl restart olo-toolgate-client.service
```

Windows, elevated PowerShell:

```powershell
Get-Service OloToolGateClient
sc.exe qc OloToolGateClient
Restart-Service OloToolGateClient
```

The Windows service uses structured stderr logs but currently has no dedicated
file/Event Log sink. SCM status and the System event log diagnose service startup;
they are not a replacement for client tool audit. Use a disposable test machine
and debugger for failures needing process-level evidence.

macOS:

```sh
sudo launchctl print system/io.ololabs.toolgate.client
sudo launchctl kickstart -k system/io.ololabs.toolgate.client
```

Check LaunchDaemon status first; the current plist does not configure dedicated
stdout/stderr log files. Add approved log collection operationally if required.
Do not assume an absent log file means no requests occurred.

## Diagnose by symptom

| Symptom | Check and recovery |
|---|---|
| UI unavailable | Correct `/console/` origin, Control pod readiness, ingress/TLS and NetworkPolicy; `make dev` does not start the UI |
| UI/API 401 | Token expiry, signed claims, issuer/audience, mounted RSA public key and UTC clock; obtain a fresh IdP token |
| API 403 | Tenant, dedicated role and enabled directory user; admin alone does not authorize approval or enrollment |
| API 409 | Refresh authoritative revision/state, references, quota and idempotency scope; a spent approval cannot be replayed |
| Control unready/503 | Database DNS/TLS/CA, runtime/migration role separation, Flyway, pool pressure and allowed DB egress |
| Download cards unavailable | Image contains verified manifest; `control.clientDownloads.enabled` true; manifest API reachable; default image intentionally has no archives |
| Download 404/503 | Allowlisted current filename, changed release URL, two-transfer capacity, startup hash/size verification; deploy a verified bundle rather than editing live assets |
| Gateway won't start | Valid bounded JSON, required files, no unknown/duplicate fields, current credential/policy deadlines and extractor bindings |
| Gateway authority unavailable | Check the configured Control origin, service credential, TLS/DNS/egress, readiness and current directory authority; cached bundles cannot restore execution |
| ASK remains blocked | Current published ASK policy, enabled approver, deadline, exact operation binding, online Control/DB and unused permit; refresh authoritative state |
| Client enrollment fails | Exact HTTPS origin, signed discovery, device CA and server trust, enroller role/code deadline, IPC authorized peer and fingerprint confirmation |
| Client health unready | Check-in connectivity, revoked owner/device, certificate expiry and stale readiness; offline state grants no permission |
| Protected tool blocked | Device-bound Gateway token/expiry, ready identity, catalog action, `/path` extractor, exact policy resource; copy/move requires both path grants |
| IPC unavailable/CONFLICT | System service running, correct socket/pipe, OS peer authorization, exclusive service lease; bounded retry for reads only |
| File operation rejected | Lowercase relative path, allowed extension/size/depth, private owner/ACL, no symlink/reparse/hardlink; fix the actual path/custody issue |
| HotFolder inventory fails after crash | Stop service, inspect verified stale internal `.write-*` file and remove only that file administratively; preserve user documents |
| Web search disabled | Private configured Brave credential; fixed provider endpoint/egress; optional allowed-domain filter; no credential means no provider calls |

Do not restore readiness by disabling TLS, signature checks, default BLOCK,
permit consumption or path custody. A Gateway restart begins with EMPTY in bundle
mode; it does not load a trusted disk cache. A Helm rollback does not rewind
database history or policy sequence. Use the
[bundle recovery procedures](../control-plane/policy-bundles.md) and
[database upgrade/recovery guide](../control-plane/upgrades.md).

## Verify a fix

Run the narrow gate for the changed component first:

```sh
python -m unittest discover -s tests/contracts -v
cargo test -p olo-toolgate-client --locked
cargo test -p olo-toolgate-gateway --locked
npm --workspace @olo-labs/toolgate-admin-ui test
python tools/control/check.py
python tools/ui/check.py
```

Use `python tools/check.py` for the full required gate and
`python tools/check.py --scans` for dependency/license/secret scans. Windows can
set `$env:TOOLGATE_DOCKER_TOOLS = '1'` to supply Rust/PHP/Helm through Docker;
Java/Node/Python still run on the host. Full integration requires Docker and
the prerequisites in the [foundation workflow](../development/foundation.md).
Record the exact version/digest, command, exit code and redacted evidence. Native
service boot/logout behavior must be verified on the target operating system.
