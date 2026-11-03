# Governing existing MCP servers

ToolGate can put an MCP server you already run behind its policy, approval and audit, without changing the server.
The trust and package boundaries are defined in [ADR 030](../adr/030-mcp-proxy-packages.md).
The shared [definition digest specification](../../tool-sdk/spec/mcp-proxy/README.md)
and [v2 schemas](../../packages/contracts/schemas/v2/mcp-proxy.schema.json) define the wire contract.

## How it works

1. **Register** the server in the tenant console (**Administration → MCP servers**) or with
   `POST /api/control/v1/mcp-servers`:
   - a name (`slug`, 2–31 lowercase letters, digits or hyphens; its tools become `mcp.<slug>/<name>`);
   - its HTTPS URL;
   - an execution binding: its Device Group is the **pool** of Tool Hosts that scan and call the server;
   - a vault secret in exactly that binding's Tool Group and Device Group, sent as a bearer token or a named header;
   - strict mode, to check the server's tool list before every call.

   You need `mcp_server.register` over the binding's Tool Group and Device Group. Control contacts nothing.
2. **Scan.** A Tool Host of the pool connects (verifying TLS against the system roots and recording the server's key
   pin), runs `initialize` and pages through `tools/list` within the limits: 16 pages, 1 MiB per page, 4 MiB, 256
   tools, 64 KiB of schema per tool, depth 32, and a 30 s deadline. Control stores an immutable snapshot with a
   definition digest per tool.
3. **Approve** at most 64 tools of one snapshot, each with a ToolGate local name and an explicit effect classification (READ, WRITE, FINANCIAL or DESTRUCTIVE). Financial and destructive effects require acknowledgement of the unfenced upstream risk. Control generates the package
   `mcp.<slug>` version `1.0.<approval sequence>` and deploys it to the pool. New tools start **disabled**; enable,
   group and grant them like any tool. Tools that a new approval omits are retired at once.
4. **Call.** Agents call the tools through the Gateway as usual. The Tool Host validates the arguments, connects with
   the approved key pins, obtains fresh Control effect authority and calls the upstream through a single-owner send window. Results are marked `io.toolgate/untrusted-upstream` in `_meta`.

## Freshness and quarantine

- Control refreshes each approved server at most every 60 s, from its pool, pinned to the approved TLS key.
- A call checks the upstream inline when strict mode is on, or when the last refresh was not a success for the
  deployed approval within 120 s. If that check fails, the call is refused with `UPSTREAM_UNVERIFIED` and nothing is
  sent to the tool.
- A changed definition or a missing tool quarantines that tool. A changed server name or TLS key quarantines the
  server. A changed server version only asks for review. A `notifications/tools/list_changed` seen during a call
  triggers an immediate refresh.
- Quarantine disables the tools in one audited change and shows the evidence in the console. Nothing recovers on
  its own: scan again, review the diff and approve again.

## Operations

- The pool is administered Device Group membership. A Tool Host's own `poolId` must be one of its Device Groups;
  the default is `default-devices`.
- Unregistering undeploys the package and disables its tools; approvals stay as history, and registering the same
  name again continues the approval sequence.
- Tenant recovery restores registrations, snapshots, approvals and packages exactly, expires open scans and keeps
  quarantines.

## Not in phase 1

OAuth and per-user upstream credentials, upstream resources and prompts, upstream tasks and input requests, more
than 64 tools per approval, and long-lived `list_changed` listeners. See the plan's T9.


## Effect review and upgrade

Reapprove existing tools with an explicit effect policy before using native MCP
business calls. Old approval records remain readable; an absent policy cannot
authorize tools/call. Upstream annotations do not replace the administrator's
review. Control records the reviewing actor and time in the immutable deployment
profile; clients cannot supply those stamps.

Control independently reconstructs the fixed upstream URL, approved tool name
and original admitted arguments before granting an effect. Dispatch requires a
second current-authority check and a single recorded owner. The Unix Tool Host
guards outbound writes with a monotonic window of at most two seconds; response
streaming may continue under the original invocation deadline. Unsupported
platforms fail closed. This transport does not establish an OCI containment
guarantee.

Consent-required policies remain blocked until exact-effect consent is
implemented. A received upstream reply is broker evidence, not proof of an
atomic target commit or a deduplication guarantee. Ambiguous sends cannot be
retried with another native MCP grant for the same effect.


Financial and destructive tools always require exact effect consent, including
older policies whose consent flag was false. Acknowledging unfenced execution
risk does not authorize an individual payment or deletion. Such calls wait for exact-effect review in the Approvals page before dispatch.
Review the mapped outbound values and effect count, then approve the immutable
preview. Any policy ASK obligations also need their independent approvals.
Internal resolved previews and an unfenced-risk acknowledgement alone do not
grant execution authority. Revocation and original expiry are checked again
before the business request is sent.

Mapped numeric preview values must stay within the browser's safe integer range
(absolute value at most 9007199254740991). Larger identifiers need a reviewed
string representation supported by the tool and target; an unrepresentable
numeric preview is refused. Complete previews are capped at 1 MiB, including
the aggregate across all routes.

Exact review follows operation-review independence: the requester and agent
owners need an independent scoped reviewer. The existing verified Super Admin
exception remains. This workflow does not accept unsigned caller self-consent
as a substitute for an application-signed user intent.

Resolved previews contain outbound business values. The requester and eligible
scoped reviewers can read them; operation metadata-read access alone does not
grant access to full preview payloads. Audit refusals retain digests and reason
codes rather than those values.
