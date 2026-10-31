<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 033: Rootless OCI containment for Tool Host workers

**Status:** Accepted for the Python containment slice of issue #230.

## Decision

An opt-in Tool Host pool delegates disposable containers to local rootless
Podman with crun. No daemon or remote socket is used. The supervisor stays an
unprivileged service user. Each concurrent worker receives a disjoint subordinate
UID/GID range, private namespaces, a read-only root, bounded scratch tmpfs,
default-deny seccomp, no capabilities, no new privileges, and cgroup v2 memory,
swap, CPU, PID and block-I/O limits. The stdio broker remains outside the worker.

The Tool Host owns its seccomp profile. It permits private AF_UNIX socket pairs
for Python's event loop and operations on existing descriptors, while denying
socket creation, bind, connect, listen, accept and descriptor-passing syscalls.

The first supported runtime is Python. Its administrator-prepared immutable
image reference must match `RuntimeDeclaration.baseImageDigest`, which the signed
permit already binds through `target.runtimeDigest`. Images are never pulled
during admission or execution. The verified package artifact is copied into a
stopped container before execution; no package, host, identity or runtime socket
directory is mounted into the worker.

The host qualifies the actual runtime with a sandbox self-test before advertising
`OCI_SANDBOX`. Missing isolation or resource controls refuse the tier. A native
host refuses image-bound runtimes; an OCI host refuses unpinned and unsupported
runtimes. There is no cross-tier fallback. Result evidence records the configured
tier. Native execution remains available only on explicitly native hosts.

Container identities are durably journaled before creation. Cancellation requests
survive dropping the invocation future: an independent lifecycle task completes
setup or stops it, forcibly removes the exact owned container, confirms absence,
then acknowledges teardown. Uncertain teardown poisons the pool and retains the
journal; restart reconciles those names before registration. A systemd service
unit must use `KillMode=control-group` and place worker cgroups below its delegated
unit so supervisor death also kills its workers.

## Limits and validation

This does not separate the trusted broker into another OS user/network namespace;
its existing destination enforcement remains part of the trusted supervisor.
Kernel/runtime compromise and side channels are outside this boundary. Other
languages and VM-backed tiers require their own runtime qualification.

Execute real malicious-worker tests for raw sockets, host and cross-invocation
files, read-only mounts, namespace/process creation, limits, cancellation,
deadline and restart cleanup. A missing rootless runtime is a failed integration
gate, not evidence of containment. Keep native compatibility tests separate.
