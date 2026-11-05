<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Declared containment and interface conformance

This matrix is for Linux x86_64 Python server artifacts on the rootless
OCI_SANDBOX tier. It combines actual isolated processes with production component
and native-process checks. The authoritative delivery/claim status remains
[zero-trust-execution-status.md](zero-trust-execution-status.md).

| Boundary | Required observations | Scope limit |
|---|---|---|
| Admission | Native host refuses image-bound artifacts; foreign signed tenant permits reach no Control consume; isolated host refuses tunnel/streaming/oversized/ambiguous profiles before consume | No native or weaker-tier fallback |
| State | Concurrent identities/scratch differ; sequential calls retain no file, environment or memory state; fresh broker owners | Workers are always single use; a reusable-worker feature is disabled |
| Kernel | Mount/ptrace/fork/namespaces/keyring/devices denied; filesystem/scratch/resource bounds; TCP/Unix/DNS/UDP/DoT/QUIC/metadata probes get EPERM | Shared host kernel and trusted container runtime |
| Private channels | Worker private-authority impersonation contacts no Control; undeclared handles fail; live supervisor descriptor and platform credential files are absent | Broker and supervisor proxy remain trusted |
| Worker framing | Strict schemas/UTF-8/duplicate keys, deterministic mutations, bytewise fragments, count/byte/depth limits, one terminal, no reused request IDs; twelve actual OCI attack vectors | A bounded mutation corpus is not an external fuzz/red-team exercise |
| In-flight lifecycle | Forged early result cannot finish pending call; cancellation and diagnostic flood close a real target socket; broker loss, cancellation/deadline/lease and restart reclaim the pair | Generalized signed TERMINATE/push and deployment SLOs remain separate |
| Shared TLS compatibility | Nine actual MCP TLS tests preserve changed-key refusal, scan/check behavior and untrusted output | Native MCP earns no OCI worker qualification |
| Egress | Complete DNS answer and literal-address checks; no redirect target contact; verified leaf pins plus standard TLS; fresh HTTP/1.1; negative/false send gate contacts no mTLS target | No TCP/tunnel/Unix-socket transport implementation |

The historical egress catalog includes optional TCP/tunnels, same-host automatic
redirect following, Kubernetes NetworkPolicy and generalized signed kill events.
They are not silently reported as passing. This tier refuses tunnels/streaming,
denies worker network/Unix socket creation, never follows a redirect and uses
local namespace isolation instead of claiming a Kubernetes policy. Other runtime,
OS and VM tiers do not inherit this qualification. Full original catalog and
roadmap/release acceptance remain outstanding in their owning stages.

## Execute

Freeze source first. Build the current broker as the unprivileged service user:

~~~sh
python tools/security/broker_image.py
~~~

Then execute the actual delegated rootless gate using that exact image/report,
as documented in the [operator guide](../operations/rootless-tool-host.md).
The gate must execute all thirteen named cases exactly once and retain the source,
known parent revision, images, broker binary digest and architecture.

Finally, as the development/service user:

~~~sh
python tools/security/containment.py
~~~

The matrix runner refuses stale source/revision or incomplete OCI reports, runs
the required production component/native-process checks, and verifies every
named witness. It writes conformance.log and conformance.json under
build/security/containment/. Its report explicitly says securityAccepted=false
and originalCatalogsAccepted=false: a declared-tier stage delivery is not a full
C1-C8 assurance or original-roadmap acceptance. The foundation job runs both gates
and retains their separate evidence.


Managed deployment evidence is a separate source-bound gate:
tools/security/deployment.py records two weak-unit refusals and three supervisor
crash/automatic-restart cycles, with actual kernel memberships and monotonic local
timings. The shared OCI gate also requires the completed-but-unacknowledged engine
case to retain its journal and fence the pool. These observations do not accept
production SLOs or the original complete roadmap catalogs.
