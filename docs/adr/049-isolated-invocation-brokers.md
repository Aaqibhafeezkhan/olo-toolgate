<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 049: Isolated per-invocation brokers

**Status:** Accepted and delivered for the qualified Linux x86_64 Python OCI tier; source/image-bound eight-case acceptance is recorded in the authoritative status document.

## Problem and boundary

The qualified OCI worker already has a subordinate identity, private namespaces
and no network. Its broker still executes inside the supervisor and shares that
process's Control client, identity and other invocation memory. Worker containment
alone does not satisfy the separate broker boundary in revision 10 section 4.4.

## Decision

Run the trusted broker as a separate single-invocation process in a dedicated
rootless OCI container, with a disjoint subordinate UID/GID range, private user,
PID/mount/IPC/UTS/network namespaces, read-only verified image and bounded cgroup.
Do not put the supervisor's workload certificate, private keys, enrollment/store
paths, runtime socket or general Control client inside it. A broker receives only
its immutable invocation/profile context and bounded private supervisor IPC.

The worker keeps its own stdio channel, owned by the supervisor. Worker protocol
messages cannot address the privileged broker/supervisor channel. No discoverable
broker network API or reusable cross-invocation token is introduced. Deny malformed,
oversized, unknown, stale and cross-invocation IPC before contacting Control or a
target. Treat IPC loss as ambiguous after dispatch; never reconnect and resend.

The broker has no direct network route. Approved egress goes through a narrow
supervisor transport delegate over its private IPC: destinations come from this
invocation's immutable profile, DNS/address restrictions remain enforced, and the
buffered HTTP request is rebuilt and fingerprint-checked before its single send.
This is an explicit implementation of restricted broker-namespace egress via a
proxy, rather than giving an isolated process a general host-network connection.
The supervisor retains the device TLS key; application credentials remain scoped
to the in-flight invocation. The delegate is part of the trusted computing base.

Keep Control as the independent effect authority. Bind secret/grant/send requests
to the supervisor's immutable operation, execution fence, profile and broker owner.
Use bounded per-handler state: one grant result, one owner send transition and one
transport send. Worker fields cannot select another operation/fence/profile,
borrow another broker's grant or grant a second send after an uncertain response.
Keep immutable preparation, no redirect/retry behavior and target claim binding.

Broker and worker have one lifecycle: create/qualify both before authority-bearing
work, cancel/kill both on deadline, lease loss, result or protocol failure, retain
both owned names in the durable lifecycle journal, and reconcile both after restart.
Unconfirmed cleanup fences the pool; old journals cannot silently gain a broker
qualification. Missing image, subordinate IDs, cgroup or IPC qualification refuses
the pool with no in-process broker fallback for the separated tier.

## Incremental implementation and verification

First introduce the invocation-scoped authority port and test refusal before any
Control contact. Then implement bounded private IPC, immutable transport delegation,
isolated broker execution and coupled lifecycle/recovery. Qualify the actual process
identity/namespace matrix, attempted worker impersonation/Control-secret access,
approved and denied target contacts, cross-invocation and repeated-send refusal,
crash/partition/teardown behavior and source/image-bound OCI cases.

S12 criteria passed the declared-tier source/image-bound actual OCI gate. See
[stage evidence and limitations](../security/zero-trust-execution-status.md#s12-complete--isolated-invocation-brokers-2026-11-02).
Other runtime tiers and the full containment phase remain separately gated. The supervisor/transport delegate and broker remain
trusted; compromised kernels/runtimes are excluded, and no production CNI/SLO or
generalized route-class qualification follows from component tests.
