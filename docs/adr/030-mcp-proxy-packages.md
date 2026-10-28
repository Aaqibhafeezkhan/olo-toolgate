# ADR-030: Existing MCP servers become tenant-owned MCP_PROXY packages

**Status:** Accepted.

## Context

Organizations already run MCP servers that ToolGate did not build. They want the same governance for them as for
ToolGate packages: grants, approvals, audit, a pinned execution location and protection against a server that
changes its tools after review. ToolGate is package-first: tools come only from verified packages, and the
Gateway, Control and the Tool Host admit a call only when a permit binds its package, descriptor, runtime and
artifact digests (ADR 013, ADR 027). Multitenancy (ADR 029) adds that shared Control never does customer-directed
work and that every tenant has its own execution pools.

## Decision

- **An approved MCP server is a generated package.** A tenant administrator registers a server by URL with an
  execution binding and a vault secret, scans it, and approves at most 64 of its tools from one immutable snapshot.
  Control generates a package of kind `MCP_PROXY`, id `mcp.<slug>`, version `1.0.<approval sequence>`, with the Tool
  Host's native `mcp-proxy` runtime and one artifact, `mcp-proxy.json`. That document pins the URL, the credential
  reference, the server identity, the TLS SPKI pins and every approved tool's definition digest. The package goes
  through the ordinary archive, compatibility, import and deploy pipeline, so permits, admission and catalogs need no
  special case. Only Control generates and deploys these packages.
- **Upstream contact happens only in the tenant's pool.** The pool is the binding's Device Group: administered
  membership, read live, never a host's claim. Scans and calls run on a Tool Host of that group. Shared Control
  never connects to an upstream and never decrypts an upstream secret for itself.
- **Scans are permitted like calls.** A scan permit (`typ: toolgate-mcp-scan-permit+jws`, the effect-signing key, a
  10 s admission window and a signed 30 s execution deadline) is consumed by its lease holder, which alone may redeem
  the secret, of exactly the binding's Tool Group and Device Group, and report the result once, before the deadline.
  Refreshes are always pinned: a changed TLS key fails before the credential is sent.
- **Change is quarantine, never silent.** Control recomputes the definition digest (`tool-sdk/spec/mcp-proxy`) of
  every scanned tool. A refresh at most every 60 s compares the server with its approval: a changed or missing tool
  quarantines that tool, a changed server name or TLS key the whole server. Calls carry the most recent check as
  `mcpVerification` in their permit; unless it is a success for the deployed approval at most 120 s old, or when the
  registration is strict, the Tool Host checks the upstream inline and refuses the call if the check fails.
  Quarantine reports from hosts are bound to one invocation or scan they hold and apply only to the current
  approval. Recovery is a re-scan and re-approval.
- **Upstream output is untrusted.** Results carry `provenance: UNTRUSTED_UPSTREAM`, and the Gateway marks them with
  `io.toolgate/untrusted-upstream` in the MCP result `_meta`, also on replays.

## Alternatives Considered

- **A Gateway-side MCP-to-MCP proxy.** Rejected: it would bypass package digests and permits and run customer-directed
  egress in the shared data plane.
- **Trusting `tools/list` at call time without approval.** Rejected: an upstream could widen a tool's effect between
  review and use.
- **Comparing descriptions only.** Rejected: schemas and annotations change behaviour too; the digest covers every
  definition member.

## Security Impact

Tenant-owned tables with forced RLS hold registrations, snapshots, approvals and scans. Registering, scanning and
approving require `mcp_server.register` over the binding's Tool Group and Device Group, so a registrar cannot send a
secret of other groups to an upstream of their choice. Same-metadata behaviour changes of an upstream remain
undetectable by digests; packaged, digest-pinned servers are the planned complete defence (plan T9).
