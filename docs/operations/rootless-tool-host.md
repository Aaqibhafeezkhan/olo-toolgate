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
before the host becomes ready or registers its tier.

## Prepare the service

Provision a dedicated unprivileged `toolgate` account with at least 65,536
subordinate UIDs and GIDs. Up to 64 concurrent slots each use a disjoint range of
1,024 subordinate identities; the service user's host identity is never mapped
into a worker. Install Podman, crun and the distribution's rootless UID-mapping
helpers. The supervisor needs those helpers; do not set `NoNewPrivileges` on
the service itself. Worker processes always have `no_new_privs` enabled.

Do not run native workers or a second supervisor under this account, or share
its state/runtime directories. Recovery names are scoped to the tenant and host;
foreign or corrupt journals refuse startup.

Use the [service unit](../../deploy/systemd/olo-toolgate-tool-host.service).
`Delegate=yes`, `DelegateSubgroup=supervisor` and `KillMode=control-group` are
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
    "ioDevice": "8:0",
    "ioBytesPerSecond": 67108864,
    "scratchBytes": 8388608
  }
}
```

Replace `ioDevice` with the actual major:minor block device backing the service
user's container storage. The example is not a device-discovery mechanism. Each
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
Native hosts refuse image-bound runtimes. Existing packages without an image pin
continue to require an explicitly native host; they are not silently upgraded or
used as a fallback. Upgrade Control/contracts before the host: result evidence
now has an optional, executor-reported `executionTier` field.

## Teardown and recovery

Deadline, lease loss, cancellation, output exhaustion and normal completion all
force removal of the exact owned container. The lifecycle task outlives a dropped
invocation future and confirms absence before releasing its identity slot.
Engine commands have five-second timeouts; failed or unconfirmed teardown fences
the entire pool. Do not describe that failure as successful worker termination.

`workDir/sandbox-spool` records the container name before creation. A restart
reconciles those names before registration. Corrupt or foreign records refuse
startup. Preserve the directory alongside the admission spool and result outbox;
deleting a record is not evidence that a worker or effect was absent. Result
reports retain the selected tier as executor-reported evidence, including when
admission recovery reports an unknown outcome.

## Execute conformance

From a prepared Linux host, as an administrator launching the dedicated test user:

```sh
python tools/security/sandbox.py --service-user <test-user> \
  --cargo /absolute/path/to/cargo --image <prepared-name@sha256:digest>
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
separating the trusted broker into another OS identity/network namespace remains
future hardening. Other languages, VM-backed isolation, per-effect authorization,
exact effect consent and target fencing are separate roadmap work. See
[ADR 033](../adr/033-rootless-tool-host-containment.md) and the
[claim inventory](../security/zero-trust-execution-status.md).
