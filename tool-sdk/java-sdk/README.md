<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Tool SDK for Java

## Purpose

Java 17+ implementation of the [authoring contract](../spec/authoring/README.md), exactly as [authoring/java.md](../spec/authoring/java.md) names it. Milestone M0 part 2 added the authoring API and the annotation processor that turns it into semantic descriptors; part 3 adds the standalone runtime, a plain MCP 2026-07-28 server over stdio; part 4 adds the Tasks extension (adapter A2). The governed runtime, MRTR `requestState`, the identity verifier, the legacy 2025-11-25 path, Streamable HTTP, the route HTTP client, `ToolHarness`, the Gradle and Maven plugins, typed accessors and the route convenience analyser arrive in later parts.

## Modules

| Module | Artifact | Contents |
|---|---|---|
| [`api/`](api/) | `toolgate-sdk-api` | `io.ololabs.toolgate.sdk.annotations` (the declarations), `.context` (`ToolContext` and the route client interfaces), `.errors` (the [error hierarchy](../spec/authoring/errors.md)) |
| [`processor/`](processor/) | `toolgate-sdk-processor` | `DescriptorProcessor`, a javac annotation processor |
| [`protocol/`](protocol/) | `toolgate-protocol` | `ProtocolCore`, adapter A1 of [protocol-layer.md](../spec/protocol-layer.md) §3 (MCP 2026-07-28); `Tasks`, adapter A2, over the `TaskStore` port with `MemoryTaskStore`; and `StdioServer` |
| [`runtime-mcp/`](runtime-mcp/) | `toolgate-sdk-runtime-mcp` | `StandaloneRuntime` and its entry point `io.ololabs.toolgate.runtime.Main` |

All compile with `--release 17`. The API and the processor have no runtime dependencies; the protocol and runtime modules depend on Jackson.

## Using the processor

```kotlin
dependencies {
    implementation("io.ololabs.toolgate:toolgate-sdk-api:<version>")
    annotationProcessor("io.ololabs.toolgate:toolgate-sdk-processor:<version>")
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Atoolgate.descriptorGeneration=1")
}
```

The generation setting is required and has no default (descriptor spec §1, case `descriptors/generation-required`); the Gradle and Maven plugins will set it from `descriptorGeneration`.

For each tool the processor writes `META-INF/toolgate/tools/<tool local name>.json`: the generation-1 semantic descriptor as its canonical bytes (NFC, then RFC 8785), so `sha256` of the file is the `toolDescriptorDigest`. It also writes `META-INF/toolgate/package.json` with the package id, name and publisher and, per tool, the digest, the handler class and method, and the parameter order the runtime binds arguments in.

## Running a package standalone

Put the compiled tools, `toolgate-sdk-runtime-mcp` and its dependencies on the class path and run `io.ololabs.toolgate.runtime.Main`. It serves newline-delimited JSON-RPC on stdin and stdout and writes diagnostics to stderr. It is a plain MCP server: there is no enterprise audit in standalone mode, and the banner says so.

| Variable | Meaning |
|---|---|
| `TOOLGATE_MODE` | `production` (the default) or `development`. `test` is refused: it exists only inside `ToolHarness`. |
| `TOOLGATE_GLOBAL_<NAME>` | A global's value as text, coerced to its declared type and validated against its schema. A missing required global refuses startup. |
| `TOOLGATE_SECRET_<NAME>` | A secret's value. A missing secret is a warning; reading it fails the call with `CONFIGURATION`. |
| `TOOLGATE_DEV_JSON` | In development, the fixture file (default `./toolgate.dev.json`) with `globals`, `secrets` and `claims`. Claims are read only in development. |
| `TOOLGATE_AUTH_ISSUER` | Naming a production issuer refuses development mode. |

A call runs the specified pipeline: input size (1 MiB, 256 properties) and schema validation before the handler, defaults filled in, `maxConcurrency` with a 1 s queue (`CONCURRENCY_LIMIT`), a deadline of `maxDurationMs` capped at 60 s (`DEADLINE_EXCEEDED`), output schema validation and a 4 MiB output cap. Errors follow [errors.md](../spec/authoring/errors.md): declared business codes keep their code and are checked against `dataSchema`, undeclared ones become `UNDECLARED_ERROR`, uncaught ones `INTERNAL_TOOL_ERROR` with a fixed message, `retryable` is downgraded on `NON_IDEMPOTENT` and `UNKNOWN` tools, and secret and sensitive values are redacted from messages, data and logs.

Startup refuses what this runtime cannot serve honestly: client tools (`CLIENT_TOOLS_UNAVAILABLE`), tools that require a business key (the reservation store is not here yet), and, in production, tools that need an identity (a claim, a `USER` secret, `TOKEN_EXCHANGE` or `DELEGATED_USER` auth) until the verifier arrives. `context.http()` and `tcp()` fail with `CONFIGURATION` for now.

## Tasks

The runtime serves the Tasks extension `io.modelcontextprotocol/tasks` (SEP-2663) as [plan.md](../../docs/sdk-plan/plan.md) §12.4 to §12.6 and [execution.md](../spec/authoring/execution.md) §6 describe it, and advertises it in `server/discover`.

- An `async` tool is listed, with `execution.taskSupport: "required"`, only to a request that declares the extension in its own `_meta`; without it a call gets `-32021` naming the extension. Bounded sync mode, an admin opt-in, is not offered standalone.
- With the extension, `tools/call` validates the arguments, creates the task and returns a flat `CreateTaskResult` (`resultType: "task"`, `taskId`, `status`, `createdAt`, `lastUpdatedAt`, `ttlMs`, `pollIntervalMs`). Invalid arguments are refused before any task exists. The deadline is `maxDurationMs`, capped at 15 minutes.
- `tasks/get` returns the status; a completed task carries the tool result under `result` (a tool error is `completed` with `isError: true`), and an internal failure is `failed` with a JSON-RPC `error`. `progress()` sets the status message, bounded to 0..1 and monotonic.
- `requestInput()` moves the task to `input_required` with one form `elicitation/create` request; `tasks/update` answers it. A declined answer ends the tool with `CANCELLED`. In a sync call `requestInput()` still fails with `CONFIGURATION` until MRTR `requestState` (adapter A3) arrives.
- `tasks/cancel` requests cooperative cancellation: the token is set and the worker interrupted. A handler that has not stopped after 5 s is abandoned, and the task ends `cancelled` either way. A tool declaring `cancelIfAbandoned` is cancelled when no poll arrives for 3 poll intervals.
- Task ids are 128-bit random. A task belongs to the connection that created it until the identity verifier arrives; any other owner gets `-32602` exactly as for an unknown id. Tasks are kept for `ttlMs` (1 hour); a poll extends retention by 3 poll intervals, at most 24 hours after creation, and expired tasks are purged.
- The store is in memory, so tasks end with the process. The SQLite store, and `notifications/tasks` over `subscriptions/listen`, come with the Streamable HTTP transport.

## What the processor checks

A violation is a compile error at the declaration:

- identifier grammars, the 128-character `toolId`, and the reserved `toolgate.`, `hotfolder.` and `builtin.` prefixes;
- exactly one `@ToolPackage`, unique tool names, and route, destination, auth and TCP ids unique per package;
- `title` and `text` exclusive, required descriptions, length limits, and `maxDurationMs` within the platform ceiling (60 s sync, 15 min async);
- variable names unique per tool across inputs, globals, secrets and claims, ignoring case, and no tool-level declaration with the name of a package- or class-level one;
- client tools without routes, TCP, secrets or `@HttpAuth`;
- routes: declared destination and auth, no `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `X-Forwarded-*` or auth header in the allowlist, path variables that are variable names with patterns other than `.*` and `.+`;
- a reachable auth's secret declared by the tool, `businessKeyArgument` naming an input, at most 8 examples;
- constraint keywords that fit the value type, defaults that parse as the type, and patterns that compile.

## Declaration scope

Globals, secrets and claims on the `@ToolPackage` class apply to every tool; on a handler class, to the tools in that class; on a handler (or in `@ToolVariables`), to that tool. Destinations, auth, routes and TCP destinations may be declared on any class and are resolved per package. A tool's descriptor carries only the routes it lists and the destinations and auth they reach.

## Tests

`./gradlew :sdk-java-processor:test` compiles fixture sources with the processor. `DescriptorVectorTest` compares the output with the [descriptor vectors](../spec/descriptor/vectors/) byte for byte; `DeclarationTest` covers the declaration rules. `./gradlew :sdk-java-runtime-mcp:test` compiles the fixtures under `runtime-mcp/src/test/resources/fixtures` and drives the runtime through the protocol core; `:sdk-java-protocol:test` drives `StdioServer` over piped streams; `TasksTest` runs the task fixtures under `fixtures/demo` through the Tasks methods. Each module's `ConformanceTest` lists which test runs which [conformance case](../conformance/) for the `java` target. Those cases also target the other SDKs, so they stay `authored` until every SDK passes them.

## Known gaps

- Descriptor vector tool-03 declares the route path `/orders/{orderId}/refunds`, a variable without a pattern, which [http.md](../spec/authoring/http.md) §1 and case `route-analysis/path-variable-regex` refuse. The vectors are normative, so the processor accepts a variable without a pattern for now and refuses only `.*` and `.+`. The vector or the rule needs an amendment.
- Typed accessors (`OrderToolsVars.global(context)`), compile errors for undeclared names in `get*Value` calls, undeclared literal error codes, and the convenience-route analyser need the javac Tree API and arrive with the runtime.
