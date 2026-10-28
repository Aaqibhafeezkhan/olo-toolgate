# ToolGate Tool SDK

Build tools that can run standalone over MCP or be packaged for governed execution.
The Java authoring API, descriptor processor, runtime, build integrations and CLI
are implemented. Java server tools also run through the Tool Host and Gateway;
host isolation and reliable result retry still have unfinished scope.
The Python SDK implements the same authoring, runtime, testing and packaging path;
see [Python SDK](python-sdk/README.md). TypeScript ([#242](https://github.com/olo-labs/olo-toolgate/issues/242)) and .NET ([#243](https://github.com/olo-labs/olo-toolgate/issues/243)) implementations remain planned.

| Task | Guide |
|---|---|
| Build your first tool | [Tool SDK guide](../TOOL-SDK-GUIDE.md) |
| Java API, runtime and tests | [Java SDK](java-sdk/README.md) |
| Initialize, validate and package | [CLI](cli/README.md) |
| Run server tools | [Tool Host](../apps/tool-host/README.md) |
| Understand the authoring contract | [Specification](spec/README.md) |
| Check required behavior | [Conformance](conformance/README.md) |

Conformance requirements refer to [platform requirement IDs](../docs/contracts/platform-requirements.md)
and the component specifications. Requirements are distinct from passing runtime evidence.
