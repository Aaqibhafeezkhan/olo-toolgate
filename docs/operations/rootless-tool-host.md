<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Rootless OCI Tool Host pools

`OCI_SANDBOX` is available for Python server artifacts on Linux with systemd
254+, local rootless Podman/crun and cgroup v2. Qualified workers have separate
user, PID, mount, network, IPC, UTS and cgroup namespaces. They have no network
route or resolver, no network socket creation or connect authority, no
capabilities and no new privileges. Private Unix socket pairs are allowed for
Python's event loop; socket/bind/connect/listen/accept remain denied.
Their image and artifact are read-only. Scratch is disposable bounded tmpfs;
memory, swap, CPU, PID and block-I/O limits are checked by an actual worker
before the host becomes ready or registers its tier. Supervisor comparison uses
its proc identity and namespace witnesses; host-root cgroup files are not
container limit witnesses. Each worker and broker must supply all required
resource files from its private cgroup mount. Missing files refuse readiness
and identify the unavailable probe path. Qualification holds attached probe
clients through removal of both containers, then reaps the clients. An engine
timeout still fences the pool and retains its owned journal until managed
restart; diagnostics preserve the attempted engine operation.

## Prepare the service

Provision a dedicated unprivileged `toolgate` account with at least 65,536
subordinate UIDs and GIDs. Up to 32 concurrent slots each use two disjoint ranges of
1,024 subordinate identities (one per worker and broker); larger pools require
at least maxConcurrent × 2,048 subordinate UIDs and GIDs; the service user's host identity is never mapped
into a worker. Install Podman, crun and the distribution's rootless UID-mapping
helpers. The supervisor needs those helpers; do not set `NoNewPrivileges` on
the service itself. Worker processes always have `no_new_privs` enabled.

Do not run native workers or a second supervisor under this account, or share
its state/runtime directories. Recovery names are scoped to the tenant and host;
foreign or corrupt journals refuse startup.

Use the [service unit](../../deploy/systemd/olo-toolgate-tool-host.service).
`Delegate=yes`, `DelegateSubgroup=supervisor`, `KillMode=control-group`, `KillSignal=SIGKILL` and `SendSIGKILL=yes` are
required. Workers live beneath that service's delegated cgroup, so systemd also
kills them if the supervisor crashes. The CPU, memory, PID **and I/O** controllers
must be available to the unit. A user-manager service may need administrator
configuration of its parent delegation. Missing controllers refuse startup;
removing an I/O limit to make startup succeed changes the security boundary.

Prepare the administrator-approved Python image as the service user, with its
configured `HOME` and `XDG_RUNTIME_DIR`. Resolve and review an immutable reference
such as `docker.io/library/python@sha256:<manifest digest>`, then explicitly pull
that exact reference. The backend never pulls an image during admission or
execution, and never uses a remote engine or Docker/Podman API socket.

## Bind packages and hosts

Add this to a Python project's existing `[tool.toolgate]` table:

```toml
runtime_image_digest = "sha256:<the reviewed 64-character lowercase manifest digest>"
```

`toolgate pack` includes it as `RuntimeDeclaration.baseImageDigest`. Control's
signed `target.runtimeDigest` binds the entire declaration. Changing the image
therefore changes the runtime pin. The configured image must provide
`/usr/local/bin/python3`; the qualification probe verifies the actual restrictions.

Add a `sandbox` object to the existing Tool Host configuration:

```json
{
  "sandbox": {
    "podmanPath": "/usr/bin/podman",
    "cgroupParent": "/system.slice/olo-toolgate-tool-host.service",
    "pythonImage": "docker.io/library/python@sha256:<the same manifest digest>",
    "brokerImage": "localhost/olo-toolgate-invocation-broker@sha256:<reviewed broker manifest digest>",
    "ioDevice": "8:0",
    "ioBytesPerSecond": 67108864,
    "scratchBytes": 8388608
  }
}
```

Set `ioDevice` to the whole major:minor block device backing the service user's
actual rootless container storage. Linux rejects partition IDs for cgroup I/O
limits; the example is not device discovery. The qualification runners inspect
local rootless Podman `graphRoot` as that service account and resolve a filesystem
partition to its parent whole block device through sysfs. They do not infer
storage from the home directory or guess a device number. Missing/virtual
filesystem mappings fail closed; overlay or stacked storage needs an explicitly
verified whole-device `--io-device` override. An explicit partition ID is rejected.
The selected store and device are included in qualification evidence.
 Each
worker gets one CPU, 32 PIDs, no swap, the invocation's capped memory, and separate
scratch-size caps on `/tmp` and `/work`. Image environment defaults are cleared.
The verified `.pyz` is copied into the stopped container and its root is mounted
read-only before launch. No host artifact, identity, state or socket directory is
mounted into a worker. Runtime-generated container metadata and the seccomp
policy stay under supervisor control.

The host advertises only `OCI_SANDBOX` after live qualification. It refuses
unqualified execution, missing or mismatched image pins and unsupported languages.
Control rejects incompatible package/registered-tier deployments and rechecks
that compatibility before issuing a Tool Host permit.
Native hosts refuse image-bound runtimes. Native execution requires an explicitly
native host and current runtime metadata. Deploy matching current Control,
contracts and host artifacts on fresh stores; no older-format upgrade or runtime
fallback is supported. Result evidence includes the required `executionTier`
field; registered-tier checks and qualified scope remain independent Control
decisions.

## Teardown and recovery

Deadline, lease loss, cancellation, output exhaustion and normal completion all
force removal of both exact owned containers. Broker exit or private-channel
failure also stops the worker; neither role is restarted within an invocation. The lifecycle task outlives a dropped
invocation future and confirms absence before releasing its identity slot.
Engine commands have five-second timeouts; failed or unconfirmed teardown fences
the entire pool. Do not describe that failure as successful worker termination.

`workDir/sandbox-spool` records both container names before either is created. Older
single-container records are reconciled as legacy worker cleanup; they do not
supply separated-broker qualification. A restart
reconciles those names before registration. Corrupt or foreign records refuse
startup. Preserve the directory alongside the admission spool and result outbox;
deleting a record is not evidence that a worker or effect was absent. Result
reports retain the selected tier as executor-reported evidence, including when
admission recovery reports an unknown outcome.

## Execute conformance

From a prepared Linux host, as an administrator launching the dedicated test user:

```sh
python tools/security/sandbox.py --service-user <test-user> \
  --cargo /absolute/path/to/cargo --image <prepared-name@sha256:digest> \
  --broker-image <prepared-broker-name@sha256:digest> \
  --broker-image-evidence /absolute/path/to/image.json
```

An ordinary user can omit `--service-user` if their user manager delegates all
required controllers. No runtime or controller absence is skipped by this gate.
The foundation Rust job executes it on a real delegated service. Its log and JSON
report are under `build/security/sandbox/`, with the image pin, source digest,
revision and executed test names. Standard Rust tests list the privileged-host
cases as explicitly ignored; their passing status comes only from this separate
mandatory gate.

This tier shares the host kernel and trusts the supervisor/broker and container
runtime. The broker's existing destination checks remain the egress boundary;
a dedicated per-invocation broker runs in a disjoint subordinate identity and
private network namespace. Its only egress is the private supervisor transport
proxy, which retains the workload TLS identity and enforces the approved target.
The broker cannot select a general Control URL or export that identity. Other languages, VM-backed isolation, per-effect authorization,
exact effect consent and target fencing are separate roadmap work. See
[ADR 033](../adr/033-rootless-tool-host-containment.md) and the
[claim inventory](../security/zero-trust-execution-status.md).


## Prepare the trusted broker image

The current builder qualifies Linux x86_64. Build as the same unprivileged service
user, with a prepared Rust musl target,
musl-gcc, readelf and local rootless Podman available:

~~~sh
rustup target add x86_64-unknown-linux-musl
python tools/security/broker_image.py
~~~

The builder owns the locked static build, checks that source stayed unchanged,
and creates a scratch image without a shell, operator configuration, workload
identity or mounted runtime socket. It emits the immutable reference and report
under build/security/broker-image/. Review that artifact and configure brokerImage
with its exact digest. A tag, missing image or missing pin refuses startup;
existing OCI configurations must add brokerImage during this upgrade. Native
hosts retain their explicit separate tier.

Qualification holds trusted worker and broker probes alive simultaneously and
compares their UID/GID maps and network/user namespace identities with each other
and the supervisor. Both roles have the same syscall, read-only filesystem and
cgroup restrictions; the broker has a fixed 128 MiB memory cap and two runtime
threads. The worker can only use its own ordinary v2 stdio channel. The broker's
private authority channel has no worker-accessible socket or shared mount.

The private protocol refuses separated-tier policies above 2 MiB per declared
request/response, non-NONE streaming and oversized route collections; route-body
limits are checked before admission consumption. Each frame is at most 4 MiB,
request/response headers at most 64 KiB (8 KiB per value), with 4,096 frames and
64 MiB per direction per invocation. Unsupported policies are refused rather than
silently truncated. Existing native transport ceilings remain unchanged.

The thirteen-case OCI runner requires the builder's report to match the current
source digest, known revision, binary digest and image reference. Build the image
after source is frozen, then execute conformance without edits between them.
It covers approved target contact, denied worker authority, broker crash,
cancellation, lease revocation, deadline, flooding, private identities and paired
restart cleanup. These are component containment checks, not a production
connectivity SLO, a generalized effect grant or a compromised-kernel guarantee.
See [ADR 049](../adr/049-isolated-invocation-brokers.md) and the
[live status](../security/zero-trust-execution-status.md) for stage acceptance.


## Qualified worker frames and TLS pin upgrades

The strict worker boundary is enforced for the qualified isolated tier. Valid v2
stdout frames must match the canonical schemas, have unique object keys, valid
UTF-8 and at most 64 levels of nesting. The invocation accepts at most 1,000
nonblank frames, one terminal result, no later frames and no reused broker request
IDs. A malformed frame cannot be repaired by printing a later successful result.
Aggregate stdout/stderr and deadline limits still apply. S13 passes the declared-tier matrix; consult the live progress document for
executed evidence and the remaining deployment/full-catalog limitations.

The SPKI verifier now requires ordinary certificate validation and an allowed
verified-leaf key pin together. A private/self-signed service must supply its
public trust anchor in caBundle. Replace intermediate-only spkiPins with leaf
pins before upgrading; the old pin-only trust path is removed. Hostname,
validity, chain and TLS handshake signatures are checked before HTTP delivery.
This shared TLS security correction applies to native broker clients too; it
does not qualify native worker containment. See [ADR 051](../adr/051-verified-leaf-spki-pins.md).


After the current-source actual OCI gate, run python tools/security/containment.py
without editing source between the two. The [declared-tier matrix](../security/qualified-containment-matrix.md)
records the native/component witnesses, disabled features and original-catalog
limitations. The final matrix now also covers live supervisor descriptors and
sequential state isolation. Diagnostic/output failure cancels an outstanding
authority/target future immediately; it does not wait for the target response.

For MCP proxy upstreams, which use the host trust store rather than an inline
per-upstream CA field, install the reviewed private CA in the dedicated host's
trust store or configure its trusted SSL_CERT_FILE/SSL_CERT_DIR environment.
Pins remain leaf-only and cannot substitute for certificate trust. Worker OCI
environments do not inherit those supervisor environment variables.


## Managed deployment qualification

Before OCI readiness the supervisor now reads the actual service manager's
properties and requires its exact delegated cgroup, control-group SIGKILL and
SendSIGKILL=yes. Adopt the updated service unit before upgrading the host.
Immediate group kill also stops workers that deliberately ignore TERM.
Unavailable, ambiguous or weaker service properties refuse the pool.

An engine timeout or indeterminate command I/O fences the pool. Both removal
attempts still run, but an absence query after uncertainty cannot release the
identity reservation or erase its journal. Restart the managed service to kill
old descendant commands before recovery; deleting a journal is not that proof.

The owned deployment drill passes the declared local profile. It starts only temporary test
units under the unprivileged image-owning account, verifies actual process/cgroup
and namespace membership, kills the supervisor while the worker ignores TERM,
and observes automatic restart plus journal reconciliation without a new worker.
It requires current-source broker image evidence and at least three cycles.
Local monotonic timing observations are not production connectivity-class SLOs.
See ADR 052 and the authoritative status for executed evidence.


After freezing source and preparing the current broker image, an administrator
can run the owned deployment qualification:

~~~sh
python tools/security/deployment.py --service-user <test-user> \
  --cargo /absolute/path/to/cargo --image <prepared-worker@sha256:digest> \
  --broker-image <prepared-broker@sha256:digest> \
  --broker-image-evidence /absolute/path/to/image.json
~~~

The default three cycles include automatic service restart; two additional
negative units prove weak mode/signal refusal. Every touched unit has a generated
name, an absence check before creation and a controller-specific description
checked before cleanup. Unit processes stop before temporary state is removed;
uncertain cleanup retains the owned test directory. Reports under
build/security/deployment/ include platform/service properties, process
membership, source/images, 25 ms polling and monotonic observed times. Local
assertion ceilings are 5 s for pair termination and 25 s for restart recovery,
including controller command latency. They do not accept production SLOs or
broader original security catalogs.
