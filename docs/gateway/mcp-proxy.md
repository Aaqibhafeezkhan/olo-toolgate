# Governing existing MCP servers

ToolGate can put an MCP server you already run behind its policy, approval and audit, without changing the server.
The design is [ADR 030](../adr/030-mcp-proxy-packages.md); the full plan is
[docs/planning/mcp-proxy/plan.md](../planning/mcp-proxy/plan.md).

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
3. **Approve** at most 64 tools of one snapshot, each with a ToolGate local name. Control generates the package
   `mcp.<slug>` version `1.0.<approval sequence>` and deploys it to the pool. New tools start **disabled**; enable,
   group and grant them like any tool. Tools that a new approval omits are retired at once.
4. **Call.** Agents call the tools through the Gateway as usual. The Tool Host validates the arguments, connects with
   the approved key pins, and calls the upstream. Results are marked `io.toolgate/untrusted-upstream` in `_meta`.

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
