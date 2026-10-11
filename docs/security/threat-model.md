# Threat Model

A pending registry row, enabled directory metadata or public CSR cannot grant execution. Reapproval preserves key/owner binding; exact-key certificate recovery rechecks enablement and approval. Browser details expose public review metadata, not private enrollment codes, credentials or tool payloads.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

## Purpose

Threats include malicious internet users, compromised AI agents, malicious MCP servers, compromised endpoint processes, stolen devices, malicious community packages, admin compromise and supply-chain attacks.

Server-tool execution (Tool Host, broker, egress proxy, transports, workload identity and secret delivery) has its own model: [threat-models/tool-host.md](threat-models/tool-host.md).

## Enterprise authorization boundaries

Control is the sole authority for current group grants and durable invocation
state. Gateway discovery caches and signed adoption snapshots are metadata, not
effect permits. Human identity comes from verified tenant/issuer/subject and a
current session epoch; service and delegated credentials bind a configured
workload and individual Agent. Caller-supplied identity fields, tool descriptions,
marketplace instructions and returned content cannot add or alter authority.

The evaluator retains complete Team, Agent Group, Tool Group, Device Group,
binding, action and resource witnesses. It rejects cross-tenant references and
scope bridging. Management Roles supply no runtime grants; ownership, account
enablement, installation and device approval supply no implicit execution rights.
Defaults prevent orphans and carry no permissions. Unknown verified humans start
disabled; invalid authentication cannot create an identity.

Independent configuration and operation reviews bind exact digests, revisions and
expiry. Live grant, session, device, workload and approval checks run before permit
consumption and again at protected runtime boundaries and result/secret retrieval.
Single-use durable nonces prevent concurrent permit replay. They cannot prove that
an interrupted external effect did not happen: uncertain outcomes remain fenced
until independent reconciliation supplies evidence, without issuing another permit.

Resource extractors authorize every batch member and both source and destination.
Installed profiles bind definition, extractor, package and runtime digests.
Filesystem containment and link checks, public DNS/socket pinning, redirect
rejection, unprivileged OCI confinement and group-scoped secret delivery constrain
supported effects. Arbitrary host shell, raw SQL, unconstrained egress and offline
execution have no supported permission fallback.

Recovery trust and observed monotonic floors live outside backups. Two independent
signatures authorize exact quarantined restore; restored runtime permissions stay
disabled and retired identities/credentials remain retired. Historical individual
ACLs are immutable migration evidence, never a parallel ALLOW source. Operator
cutover requires explicit reviewed group conversion and a recorded shadow report.

Residual trust includes the identity provider, Control signing/custody, protected
audit storage, authorized reviewers, endpoint OS isolation and downstream effect
providers. A compromised privileged endpoint or malicious downstream provider
cannot be repaired by group ACL evaluation alone. Production transport, external
trust provisioning, retention and capacity testing require installation-specific
configuration. See [enterprise operations](../enterprise-access-control/operations.md)
and [executable acceptance evidence](../enterprise-access-control/implementation-status.md).
