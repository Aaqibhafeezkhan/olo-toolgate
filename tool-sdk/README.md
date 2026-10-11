# ToolGate Tool SDK

## Purpose

`tool-sdk/` holds the ToolGate Tool SDK: one authoring contract for Java, Python, TypeScript and .NET tools that run as plain MCP servers (standalone mode) or under ToolGate governance (governed mode).

**Status:** milestone M0 ([implementation plan](../docs/sdk-plan/implementation-plan.md)). The normative specification (gate D0) and the conformance cases (gate D3) are here, with the `toolgate` CLI packager and the Java authoring API, descriptor processor and standalone runtime. The Java build plugins and governed runtime, and the other language SDKs, follow in later M0 parts.

## Layout

| Path | Contents | Gate |
|---|---|---|
| [`spec/`](spec/README.md) | Normative authoring contract, descriptor rules, runtime modes, composition, protocol layer, official SDK compatibility record | D0 |
| [`conformance/`](conformance/README.md) | Conformance cases for every suite in plan.md §21, with their fixtures | D3 |
| [`cli/`](cli/README.md) | The `toolgate` CLI: canonical descriptors and digests, `.tgpkg` pack, validate and verify | M0 |
| [`java-sdk/`](java-sdk/README.md) | Java authoring API (`toolgate-sdk-api`), the annotation processor that emits descriptors (`toolgate-sdk-processor`), and the standalone MCP runtime over stdio (`toolgate-sdk-runtime-mcp`) | M0 |
| `python-sdk/`, `typescript-sdk/`, `dotnet-sdk/`, `examples/`, `docs/` | Implementation (added in later M0 parts) | M0 |

## Related Docs

- [Design plan](../docs/sdk-plan/plan.md)
- [Publishing plan](../docs/sdk-plan/publishing-plan.md)
- [Implementation plan](../docs/sdk-plan/implementation-plan.md)
- [ADR 019](../docs/adr/019-tool-sdk-authoring-contract.md)
