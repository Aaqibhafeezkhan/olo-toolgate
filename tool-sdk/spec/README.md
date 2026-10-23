# Tool SDK specification

## Purpose

This is the normative authoring contract for the ToolGate Tool SDK. Every SDK, in every language and in both runtime modes, **must** implement exactly what these files say. The design rationale is in [platform-requirements.md](../../docs/contracts/platform-requirements.md); where wording differs, these files win for the SDK.

## Rules

- Key words **must**, **must not**, **should** and **may** are normative.
- A file here changes only through an amendment to [ADR 019](../../docs/adr/019-tool-sdk-authoring-contract.md).
- Examples are illustrative; tables and grammar blocks are normative.

## Contents

| File | Defines | Requirement reference |
|---|---|---|
| [authoring/README.md](authoring/README.md) | Language-neutral contract: declarations, `ToolContext`, value types | §5, §6 |
| [authoring/java.md](authoring/java.md), [python.md](authoring/python.md), [typescript.md](authoring/typescript.md), [dotnet.md](authoring/dotnet.md) | Exact API surface per language | §5.3 |
| [authoring/http.md](authoring/http.md) | Named routes, destinations, TCP, convenience-form proof rules | §5.4, §11.8, §11.9 |
| [authoring/errors.md](authoring/errors.md) | Error hierarchy, codes, MCP mapping | §5.7 |
| [authoring/execution.md](authoring/execution.md) | Execution semantics, tasks, cancellation, effects | §5.7, §11.7, §12 |
| [runtime-modes.md](runtime-modes.md) | Standalone and governed modes, the invariant, standalone identity | §5.8, §5.9 |
| [descriptor/README.md](descriptor/README.md) | Descriptor generation 1, canonical form, the three digests, test vectors | §5.6, §7.2 |
| [composition.md](composition.md) | Library fragments and merge rules, with fixtures | §7.7 |
| [protocol-layer.md](protocol-layer.md) | Protocol layer and adapter contracts A1 to A5 | §5.10 |
| [sdk-compat/](sdk-compat/README.md) | Official MCP SDK compatibility record (pinned versions, test results, gaps) | §5.10 |
| [mcp-proxy/](mcp-proxy/README.md) | MCP proxy definition digest of upstream tools, with shared vectors | [Definition digest](mcp-proxy/README.md) |
