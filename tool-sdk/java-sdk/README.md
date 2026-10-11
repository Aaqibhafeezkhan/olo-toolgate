<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Tool SDK for Java

## Purpose

Java 17+ implementation of the [authoring contract](../spec/authoring/README.md), exactly as [authoring/java.md](../spec/authoring/java.md) names it. Milestone M0 part 2 added the authoring API and the annotation processor that turns it into semantic descriptors; part 3 adds the standalone runtime, a plain MCP 2026-07-28 server over stdio; part 4 adds the Tasks extension (adapter A2); part 5 adds Streamable HTTP, `subscriptions/listen` and progress notifications; part 6 adds MRTR `requestState` (adapter A3), so `requestInput()` works in sync calls; part 7 adds the identity verifier (adapter A4); part 8 adds the routes HTTP client; part 9 adds business keys with a local SQLite reservation store; part 10 adds the SQLite task store. The governed runtime, the legacy 2025-11-25 path, the TCP connector, `ToolHarness`, the Gradle and Maven plugins, typed accessors and the route convenience analyser arrive in later parts.

## Modules

| Module | Artifact | Contents |
|---|---|---|
| [`api/`](api/) | `toolgate-sdk-api` | `io.ololabs.toolgate.sdk.annotations` (the declarations), `.context` (`ToolContext` and the route client interfaces), `.errors` (the [error hierarchy](../spec/authoring/errors.md)) |
| [`processor/`](processor/) | `toolgate-sdk-processor` | `DescriptorProcessor`, a javac annotation processor |
| [`protocol/`](protocol/) | `toolgate-protocol` | `ProtocolCore`, adapter A1 of [protocol-layer.md](../spec/protocol-layer.md) §3 (MCP 2026-07-28); `Tasks`, adapter A2, over the `TaskStore` port with `MemoryTaskStore`; `RequestStates`, adapter A3; `StdioServer` and `StreamableHttpServer` |
| [`runtime-mcp/`](runtime-mcp/) | `toolgate-sdk-runtime-mcp` | `StandaloneRuntime` and its entry point `io.ololabs.toolgate.runtime.Main`; `SqliteTaskStore` |
| [`conformance-server/`](conformance-server/README.md) | not published | The fixture server the official MCP conformance suite runs against |

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

Put the compiled tools, `toolgate-sdk-runtime-mcp` and its dependencies on the class path and run `io.ololabs.toolgate.runtime.Main`. By default it serves newline-delimited JSON-RPC on stdin and stdout; with `TOOLGATE_HTTP_ADDRESS` it serves Streamable HTTP at `/mcp` instead. Diagnostics go to stderr. It is a plain MCP server: there is no enterprise audit in standalone mode, and the banner says so.

| Variable | Meaning |
|---|---|
| `TOOLGATE_MODE` | `production` (the default) or `development`. `test` is refused: it exists only inside `ToolHarness`. |
| `TOOLGATE_GLOBAL_<NAME>` | A global's value as text, coerced to its declared type and validated against its schema. A missing required global refuses startup. |
| `TOOLGATE_SECRET_<NAME>` | A secret's value. A missing secret is a warning; reading it fails the call with `CONFIGURATION`. |
| `TOOLGATE_DESTINATION_<HOSTSETTING>` | The host of a destination, as `host` or `host:port` (see [Routes](#routes)). A missing host is a warning; calls on its routes fail with `CONFIGURATION`. |
| `TOOLGATE_STATE_DB` | The SQLite file for business-key reservations (see [Business keys](#business-keys)) and the SQLite task store, default `.toolgate/state.db` in the working directory. It is opened only when a tool needs reservations or `TOOLGATE_TASK_STORE` is `sqlite`. |
| `TOOLGATE_TASK_STORE` | `memory` (the default) or `sqlite` (see [Tasks](#tasks)). Any other value refuses startup. |
| `TOOLGATE_BUSINESS_KEY_RETENTION` | How long a reservation keeps its result, as an ISO-8601 duration, default `P30D`. |
| `TOOLGATE_DEV_JSON` | The fixture file (default `./toolgate.dev.json`) with `destinations`, `globals`, `secrets` and `claims`. Claims are read only in development. |
| `TOOLGATE_AUTH_*` | The identity verifier (see [Identity](#identity)). Any of these refuses development mode. |
| `TOOLGATE_HTTP_ADDRESS` | `host:port` to serve Streamable HTTP, for example `127.0.0.1:8080`. Development mode binds only to loopback. Production binds a non-loopback address only with an identity verifier. |

A call runs the specified pipeline: input size (1 MiB, 256 properties) and schema validation before the handler, defaults filled in, `maxConcurrency` with a 1 s queue (`CONCURRENCY_LIMIT`), a deadline of `maxDurationMs` capped at 60 s (`DEADLINE_EXCEEDED`), output schema validation and a 4 MiB output cap. Errors follow [errors.md](../spec/authoring/errors.md): declared business codes keep their code and are checked against `dataSchema`, undeclared ones become `UNDECLARED_ERROR`, uncaught ones `INTERNAL_TOOL_ERROR` with a fixed message, `retryable` is downgraded on `NON_IDEMPOTENT` and `UNKNOWN` tools, and secret and sensitive values are redacted from messages, data and logs.

Startup refuses what this runtime cannot serve honestly: client tools (`CLIENT_TOOLS_UNAVAILABLE`), tools that need business-key reservations when the store cannot open, and, in production without an identity verifier, tools that need an identity (a claim, a `USER` secret, `TOKEN_EXCHANGE` or `DELEGATED_USER` auth). `context.tcp()` fails with `CONFIGURATION` for now.

## Streamable HTTP

Every request is a stateless `POST /mcp`. The transport checks, in order: a missing `MCP-Protocol-Version` header (`-32020`), a missing `_meta` version or capabilities (`-32602`), a header that differs from `_meta` (`-32020`), an unsupported version (`-32022`), and `Mcp-Method` and `Mcp-Name` against the body (`-32020`). Unknown methods, the session-era `initialize` and `ping` among them, get `-32601`. These errors are HTTP 400, except `-32601` (404) and `-32603` (500). On a loopback address, requests whose `Host` or `Origin` is not a loopback name get 403, which guards against DNS rebinding. With an identity verifier, a request without a valid bearer token gets 401 before any of these checks. Bodies over 2 MiB get 413, a JSON array gets `-32600`, and a notification gets 202.

A result is JSON, unless the request carries `_meta.progressToken` and accepts `text/event-stream`. Then the response is an SSE stream: `notifications/progress` frames from `progress()` come first, then the result. Over stdio the same notifications are written as lines before the result.

`subscriptions/listen` holds an SSE stream (over stdio, the connection) open. The first frame is `notifications/subscriptions/acknowledged` with the honoured filter. Every frame carries `_meta["io.modelcontextprotocol/subscriptionId"]`, the listen request's id. `toolsListChanged` is honoured. A listener whose request declared the Tasks extension also receives `notifications/tasks` for its owner's tasks on every status or status-message change. The stream ends when the client goes away; on stdio it also ends on `notifications/cancelled` naming the listen request.

Without a verifier, HTTP requests share one task owner, so a task created by one POST can be polled by the next. With one, each verified caller (issuer and subject) owns its own tasks, request states and listen streams.

## Identity

Adapter A4 of [protocol-layer.md](../spec/protocol-layer.md) §3, configured as [runtime-modes.md](../spec/runtime-modes.md) §4 describes. In production, claims come only from a verified credential; nothing else (arguments, other headers, environment, the dev file) can supply them.

| Variable | Meaning |
|---|---|
| `TOOLGATE_AUTH_ISSUER`, `TOOLGATE_AUTH_AUDIENCE` | Matched exactly against `iss` and `aud` (a string, or an array holding the audience). Both are required for JWTs. For introspection they are optional checks on the answer. |
| `TOOLGATE_AUTH_JWKS_URL` | An HTTPS JWKS URL. Keys are fetched on first use and again after an hour, and an unknown `kid` refreshes them at most once per 30 s. A failed fetch keeps the last good keys. |
| `TOOLGATE_AUTH_KEYS_FILE` | A JWKS file of pinned keys, read once at startup. |
| `TOOLGATE_AUTH_ALGORITHMS` | The JWT algorithm allowlist, default `RS256,ES256`. `RS*`, `PS*` and `ES*` are supported; `none` and HMAC refuse startup. RSA keys must be at least 2048 bits. |
| `TOOLGATE_AUTH_INTROSPECTION_URL` | An HTTPS RFC 7662 endpoint, instead of JWTs. It needs `TOOLGATE_AUTH_CLIENT_ID` and `TOOLGATE_AUTH_CLIENT_SECRET` (sent with HTTP Basic, and redacted from diagnostics). |

- Exactly one of the JWKS URL, the keys file and the introspection URL may be set. An incomplete or unsafe setting refuses startup with `CONFIGURATION`.
- A JWT needs a valid signature from an allowed algorithm and a matching key, `exp` and `nbf` within 60 s of skew, and a `sub`. A `crit` header is refused.
- Introspection needs `active: true`, and refuses an expired `exp` or a mismatched `iss` or `aud`. A positive answer is cached for 5 minutes, never past `exp`. Negative answers are not cached, and an unreachable endpoint never admits a token.
- Over HTTP, a missing or refused token gets 401 with `WWW-Authenticate: Bearer`. Over stdio there is no token, so tools that read claims fail with `CLAIMS_UNAVAILABLE`.
- The shared fixtures in [conformance/fixtures/identity](../conformance/fixtures/identity/README.md) drive `IdentityTest` in the protocol module; every SDK runs the same tokens.

## Tasks

The runtime serves the Tasks extension `io.modelcontextprotocol/tasks` (SEP-2663) as [plan.md](../../docs/sdk-plan/plan.md) §12.4 to §12.6 and [execution.md](../spec/authoring/execution.md) §6 describe it, and advertises it in `server/discover`.

- An `async` tool is listed, with `execution.taskSupport: "required"`, only to a request that declares the extension in its own `_meta`; without it a call gets `-32021` naming the extension. Bounded sync mode, an admin opt-in, is not offered standalone.
- With the extension, `tools/call` validates the arguments, creates the task and returns a flat `CreateTaskResult` (`resultType: "task"`, `taskId`, `status`, `createdAt`, `lastUpdatedAt`, `ttlMs`, `pollIntervalMs`). Invalid arguments are refused before any task exists. The deadline is `maxDurationMs`, capped at 15 minutes.
- `tasks/get` returns the status; a completed task carries the tool result under `result` (a tool error is `completed` with `isError: true`), and an internal failure is `failed` with a JSON-RPC `error`. `progress()` sets the status message, bounded to 0..1 and monotonic.
- `requestInput()` moves the task to `input_required` with one form `elicitation/create` request; `tasks/update` answers it. A declined answer ends the tool with `CANCELLED`. In a sync call it uses MRTR instead (see below).
- `tasks/cancel` requests cooperative cancellation: the token is set and the worker interrupted. A handler that has not stopped after 5 s is abandoned, and the task ends `cancelled` either way. A tool declaring `cancelIfAbandoned` is cancelled when no poll arrives for 3 poll intervals.
- Task ids are 128-bit random. A task belongs to the verified caller that created it, or without a verifier to the stdio connection or the shared HTTP owner; any other owner gets `-32602` exactly as for an unknown id. Tasks are kept for `ttlMs` (1 hour); a poll extends retention by 3 poll intervals, at most 24 hours after creation, and expired tasks are purged.
- The task store is in memory by default, so tasks end with the process. With `TOOLGATE_TASK_STORE=sqlite` tasks and their results are kept in the state file (`synchronous=FULL`) and answer `tasks/get` after a restart. Owners must match as before: verified callers and the shared HTTP owner carry over, a stdio connection's owner does not. A task that was working or waiting for input when the process stopped ends `failed` (`-32603`) with `data.code` `OUTCOME_UNKNOWN`, since its effect may have happened. A store that cannot open refuses startup; a call whose task cannot be stored gets the retryable `TASK_STORE_UNAVAILABLE` and nothing runs.

## Input in sync calls (MRTR)

In a sync call `requestInput()` follows SEP-2322, the stateless multi-round flow, with `requestState` as adapter A3 of [protocol-layer.md](../spec/protocol-layer.md) §3 specifies it:

- The handler runs from the start on every round. Its n-th `requestInput()` answers from the round's input response `input-n`. When that answer is missing, the round ends with `resultType: "input_required"`, one form `elicitation/create` request under `inputRequests`, and a `requestState`. The retry sends the same arguments, the new answer and that state. Code before a `requestInput()` therefore runs once per round and must be safe to repeat. A handler that catches the exception `requestInput()` throws still ends the round asking for input.
- `requestState` is a compact JWS, HS256, signed with a random 256-bit key made at startup, so a token from another process never verifies. It binds the tool, the JCS digest of the arguments and the caller, lives 10 minutes, carries the answers of earlier rounds, and is accepted once. Any refusal is `-32602 Invalid requestState`, checked before anything runs.
- Answers sent without a state are the client's own answers and are used as they are. Unknown keys are ignored. `inputResponses` that is not an object of objects is `-32602`.
- A client that does not declare `elicitation` gets `-32021` with `data.requiredCapabilities.elicitation`. A declined answer ends the tool with `CANCELLED`, and an answer that does not match the requested schema ends it with `INVALID_ARGUMENT`.
- An `async` tool still runs as a task and asks through `input_required`.

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

## Routes

`context.http()` is the routes HTTP client of [http.md](../spec/authoring/http.md). It calls only the routes the tool lists, exactly as the descriptor declares them; anything else fails the call with `ROUTE_REFUSED` before a byte is sent. In standalone mode this is developer feedback, not a network boundary ([runtime-modes.md](../spec/runtime-modes.md) §3).

- **Destinations.** Each destination's host comes from `TOOLGATE_DESTINATION_<HOSTSETTING>` or `destinations.<HostSetting>` in `toolgate.dev.json`, as `host` or `host:port`, and calls use HTTPS. The port must be the declared one. In development a loopback host may use any port and plain `http://`, for example `http://127.0.0.1:8080`, so a local mock can stand in for the upstream; production refuses both at startup.
- **TLS.** The system trust store, or the destination's `spkiPins` when it declares any: then a server is trusted only when a certificate in its chain has a SubjectPublicKeyInfo whose SHA-256 is one of the pins, written `sha256/<base64>`. A destination with a `caBundle` fails its calls with `CONFIGURATION` for now.
- **The call.** Every path variable must be set and fully match its pattern, and a value never contains `/`. Query names and headers must be in the route's allowlists; `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `X-Forwarded-*` and the auth header are always refused. A `null` query value is omitted. Bodies are JSON when the route accepts `application/json`, and `byte[]` goes as is. `timeout()` can only shorten the route timeout, and the invocation deadline caps both. Request and response sizes are capped at `maxRequestBytes` and `maxResponseBytes`.
- **Convenience form.** `http().get(destination, template)` and its siblings match method, destination and template (variable names only) against the tool's routes and must find exactly one. The build-time analyser that proves this before packaging arrives in a later part.
- **Credentials** come from the auth's secret: `API_KEY` sends the text secret in the auth's header; `BASIC` takes `{username, password}` or `user:password`; `OAUTH2_CLIENT_CREDENTIALS` takes `{tokenUrl, clientId, clientSecret}`, asks the token endpoint with the auth's scopes and audience, and caches the token until 30 s before it expires. The token is redacted like a secret. `TOKEN_EXCHANGE`, `CONNECTION` and `CUSTOM` fail the call with `CONFIGURATION` in standalone mode.
- **Failures.** A non-2xx status raises `UpstreamError` with `UPSTREAM_4XX` or `UPSTREAM_5XX`, the status and any `X-Request-Id`. A timeout is `UPSTREAM_TIMEOUT`; a refused connection, failed TLS handshake, reset or oversized response is `UPSTREAM_CONNECTION`. `effectUncertain` is true once the request was handed over in full: after the last body byte for a request with a body, once connected for one without. The client never retries and never follows redirects, and cancellation aborts the call in flight.
- **`OUTCOME_UNKNOWN`.** When an uncaught `UpstreamError` with `effectUncertain` escapes a `NON_IDEMPOTENT` or `UNKNOWN` tool, the result's error code is `OUTCOME_UNKNOWN` (not retryable, with the upstream code in `data.upstreamCode`), and a task ends `failed` with that error in `error.data`.

## Business keys

A tool that requires a business key (by default `NON_IDEMPOTENT` and destructive) or declares `businessKeyArgument` runs only after the runtime reserves its key in a local SQLite store (execution.md §4, plan.md §11.7). The key comes from that argument or from the `Idempotency-Key` header over HTTP; when both are present they must agree.

- **No key.** A tool that requires one gets `INVALID_ARGUMENT` naming the argument and the header, and nothing runs. Standalone mode has only the default `REJECT` policy.
- **Namespaces.** `AGENT` keys belong to the caller, `TENANT` keys to the whole runtime, and `DELEGATED_USER` keys to the verified user. A `DELEGATED_USER` call without one gets `BUSINESS_KEY_NAMESPACE_MISMATCH`. The caller is the verified identity (in development, the fixture `sub`), or the single local caller when there is none. Identities never come from arguments.
- **Repeats.** A repeat with the same arguments returns the first result, or the original task to a client that declares Tasks, and never runs again. Different arguments get `IDEMPOTENCY_CONFLICT`, and another caller of a `TENANT` key gets `IDEMPOTENCY_KEY_IN_USE`, which reveals nothing else. A repeat while the first call still runs gets `IN_PROGRESS` (retryable). A key whose retention has passed leaves a tombstone, and reusing it gets `EXPIRED_KEY`.
- **Rebinding.** A key is rebound to a new invocation only when the earlier one never started (`CONCURRENCY_LIMIT`) or ended its round asking for input (MRTR). A failure is replayed like a success.
- **The fingerprint** is the canonical digest of the arguments, the package, the effect class, the scope kind `standalone` and, outside `DELEGATED_USER`, the verified user.
- **Upstream.** With `@Idempotency(header, scope = BUSINESS_KEY)` the runtime adds the header to every route call whose route allows it: the raw key for `TENANT`, `a.<hash>.<key>` for `AGENT` and `u.<hash>.<key>` for `DELEGATED_USER`. The hash is an HMAC of the identity under a key kept in the store. Without a reserved key, or with `scope = PER_INVOCATION`, it sends `i.<invocationId>`.
- **Failure.** A store that cannot open refuses startup, and a store error during a call returns the retryable `BUSINESS_KEY_STORE_UNAVAILABLE` without running the tool. Work that was running when the process stopped answers `OUTCOME_UNKNOWN` afterwards. One runtime process should own a state file at a time.

## Tests

`./gradlew :sdk-java-processor:test` compiles fixture sources with the processor. `DescriptorVectorTest` compares the output with the [descriptor vectors](../spec/descriptor/vectors/) byte for byte; `DeclarationTest` covers the declaration rules. `./gradlew :sdk-java-runtime-mcp:test` compiles the fixtures under `runtime-mcp/src/test/resources/fixtures` and drives the runtime through the protocol core; `:sdk-java-protocol:test` drives `StdioServer` over piped streams and `StreamableHttpServer` over loopback HTTP, and `HttpTest` drives the runtime over HTTP; `TasksTest` runs the task fixtures under `fixtures/demo` through the Tasks methods, and `MrtrTest` runs sync `requestInput()` through MRTR rounds; `BusinessKeysTest` runs the `fixtures/keys` tools through reservations and `ReservationsTest` covers the store's rules; `RoutesTest` runs the `fixtures/routes` tools against a local upstream, including an HTTPS server checked by SPKI pin; `RequestStatesTest` covers the token itself. Both modules have an `IdentityTest`: the protocol one runs the shared identity fixtures, and the runtime one runs production mode with bearer tokens over HTTP. Each module's `ConformanceTest` lists which test runs which [conformance case](../conformance/) for the `java` target. Those cases also target the other SDKs, so they stay `authored` until every SDK passes them.

## Known gaps

- Typed accessors (`OrderToolsVars.global(context)`), compile errors for undeclared names in `get*Value` calls, undeclared literal error codes, and the convenience-route analyser need the javac Tree API and arrive with the runtime.
