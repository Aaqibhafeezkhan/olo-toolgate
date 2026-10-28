<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Building MCP tools with the ToolGate Tool SDK

This guide is for anyone who wants to write a tool once and run it two ways:

- **Standalone:** as a plain MCP server that any MCP client can use (Claude Desktop, an IDE, your own agent).
- **Governed:** under ToolGate, where an organisation decides who may call it, with which credentials and against which hosts, and every call is permitted, run and audited by the platform.

The same source code and the same package serve both. You never write MCP plumbing, credential handling or network policy yourself: you declare what the tool needs, and the SDK and ToolGate enforce it.

The guide uses Java. The [Python SDK](tool-sdk/python-sdk/README.md) implements
the same authoring and execution path, with `toolgate init --lang python`.
TypeScript ([#242](https://github.com/olo-labs/olo-toolgate/issues/242)) and .NET ([#243](https://github.com/olo-labs/olo-toolgate/issues/243)) follow the same specified contract
([spec/authoring](tool-sdk/spec/authoring/README.md)).

This file is also bundled in the published SDK: inside the `toolgate-sdk-api` jar as `META-INF/toolgate/TOOL-SDK-GUIDE.md`, and printed by `toolgate guide`.

## Contents

1. [How it fits together](#1-how-it-fits-together)
2. [Install](#2-install)
3. [Create a project](#3-create-a-project)
4. [Write a tool](#4-write-a-tool)
5. [Values: inputs, globals, secrets and claims](#5-values-inputs-globals-secrets-and-claims)
6. [Calling other services](#6-calling-other-services)
7. [Errors](#7-errors)
8. [Effects, retries and business keys](#8-effects-retries-and-business-keys)
9. [Long-running tools and asking the user](#9-long-running-tools-and-asking-the-user)
10. [Test](#10-test)
11. [Run it as a plain MCP server](#11-run-it-as-a-plain-mcp-server)
12. [Package](#12-package)
13. [Run it under ToolGate](#13-run-it-under-toolgate)
14. [Rules that keep a tool compatible](#14-rules-that-keep-a-tool-compatible)
15. [Troubleshooting](#15-troubleshooting)
16. [Reference](#16-reference)

## 1. How it fits together

```text
 your code ──► annotations ──► descriptors ──► .tgpkg package
                (what the tool     (canonical JSON,    (code + descriptors,
                 needs)             digested)           digested, reproducible)

 standalone:  MCP client ──stdio/HTTP──► SDK runtime ──► your handler

 governed:    MCP client ──► Gateway ──► Control (who may call? permit)
                                 │
                                 └──► Tool Host ──► SDK runtime ──► your handler
                                         └── broker: the only way out to the network
```

- **Descriptors** are generated at compile time from your annotations. They describe each tool: its inputs and output, its effect on the world, the hosts and routes it may call, the secrets and settings it reads. A descriptor's SHA-256 is the tool's identity (`toolDescriptorDigest`). Change what a tool may do and its digest changes, so an administrator must approve the new version.
- **A package** (`.tgpkg`) is a deterministic zip of your built code and its descriptors. The same sources give the same bytes on any machine.
- **Standalone mode** is a normal MCP server (protocol 2026-07-28 over stdio or Streamable HTTP). Your declarations are enforced as developer feedback.
- **Governed mode** runs each call in a Tool Host from the exact package an administrator deployed. The host's broker makes every outbound request from the approved routes and adds the credentials itself; your code never sees an organisation's API keys.

## 2. Install

You need Java 17 or later, and either Gradle (the starter carries a wrapper) or Maven.

Until the SDK is published to Maven Central, build it from a ToolGate checkout:

```sh
git clone https://github.com/olo-labs/olo-toolgate.git
cd olo-toolgate
./gradlew publishJavaSdkToMavenLocal          # SDK jars and plugins into ~/.m2
cargo install --path tool-sdk/cli --locked     # the toolgate CLI
toolgate --help
```

The `toolgate` CLI is the one command line you need in any language. It creates projects, runs them, tests them and packs them. It works offline.

## 3. Create a project

```sh
toolgate init orders --id acme.orders --publisher "Acme"     # add --build maven for Maven
cd orders
```

| Option | Meaning |
|---|---|
| `--id` | The package id, lower-case and dotted. Tool `greet` in package `acme.orders` has the ToolGate id `acme.orders/greet`, and is listed to standalone MCP clients as `orders.greet` (the last segment of the package id, a dot, the tool name). Default `example.<name>`. |
| `--publisher` | Who publishes the package. |
| `--lang` | The language; `java` today. |
| `--build` | `gradle` (default) or `maven`. |
| `--sdk-version`, `--sdk-repo` | Pin the SDK version or point at a repository holding it. |

The starter gives you:

```text
orders/
├── build.gradle.kts              plugin io.ololabs.toolgate.sdk, descriptorGeneration pinned to 1
├── src/main/java/.../Orders.java two tools: one reads a setting, one calls an HTTP route
├── src/main/resources/exchanges/ a recorded HTTP exchange for the example
├── src/test/java/.../OrdersTest.java  unit tests with ToolHarness
├── toolgate.dev.json             local values for development (git-ignored)
└── README.md
```

Then:

```sh
toolgate dev      # serve the tools as an MCP server over stdio
toolgate check    # compile, unit tests, and every @Example
toolgate pack     # build/toolgate/orders-0.1.0.tgpkg
```

`descriptorGeneration` is pinned in the build so an SDK upgrade never changes your descriptors (and so their digests) until you choose to move it. It has no default; the build fails without it.

## 4. Write a tool

A tool is a public method with `@ServerTool`, in a public class with a no-argument constructor. One class in the project carries `@ToolPackage`.

```java
@ToolPackage(id = "acme.orders", name = "Orders", publisher = "Acme")
public class Orders {
    public record Greeting(String text) {}

    @ServerTool(name = "greet", title = "Greet", description = "Greets someone by name.",
            effect = EffectSafety.READ_ONLY)
    @Example(arguments = "{\"name\":\"Ada\"}", expectedOutput = "{\"text\":\"Hello, Ada\"}")
    public Greeting greet(@Param(description = "Who to greet") String name, ToolContext context) {
        return new Greeting("Hello, " + name);
    }
}
```

**The handler.** Parameters annotated with `@Param` become the tool's input schema, in order. `ToolContext` is optional and comes last. The return type becomes the output schema: a record, a POJO, a `Map<String, ?>`, or `ToolResult` when you want to write the MCP `content` yourself. A handler may also return `CompletionStage<R>`.

**`@ServerTool` members you will use most:**

| Member | Meaning |
|---|---|
| `name` | Unique in the package. Letters, digits, `.`, `_`, `-`. The prefix `toolgate.` is reserved. |
| `title` or `text` | Exactly one: the short label shown to people. |
| `description` | Required. What the tool does, written for the model that will choose it. |
| `useWhen` | Optional guidance on when to choose this tool over another. |
| `effect` | `READ_ONLY`, `IDEMPOTENT`, `NON_IDEMPOTENT` or `UNKNOWN` (default). See [section 8](#8-effects-retries-and-business-keys). |
| `destructive` | True when the effect deletes or overwrites something. |
| `routes`, `tcp` | The HTTP routes and TCP destinations this tool may use. Nothing else is reachable. |
| `async` | Run as an MCP task ([section 9](#9-long-running-tools-and-asking-the-user)). |
| `maxDurationMs` | Deadline: at most 60 s for a sync tool, 15 min for an async one. |
| `maxConcurrency` | Calls of this tool that may run at once. |
| `errors` | The business error codes this tool may raise ([section 7](#7-errors)). |

**`@Param` constraints** become JSON Schema and are checked before your code runs: `required`, `defaultValue`, `min`, `max`, `minLength`, `maxLength`, `pattern`, `enumValues`, `format`. Mark personal or secret inputs `sensitive = true` and they are redacted from errors and logs.

**`@Example`** gives arguments and the expected output (or `expectedError`). Up to 8 per tool. `toolgate check` runs each one, and they ship in the descriptor so administrators and models can see how the tool behaves.

The annotation processor checks all of this when you compile. A violation is a compile error at the declaration, not a runtime surprise.

## 5. Values: inputs, globals, secrets and claims

A tool reads four kinds of named values. Declare each one; reading an undeclared literal name is a compile error.

| Kind | Declared with | Who supplies it | Read with |
|---|---|---|---|
| Input | `@Param` | The caller, per call | Method parameter, or `context.getStringValue("name")` |
| Global | `@Global` | The administrator, per deployment | `context.global().getStringValue("Greeting")` |
| Secret | `@Secret` | The administrator's vault (or a user's, with `scope = USER`) | `context.secret().getStringValue("ApiKey")` |
| Claim | `@JwtClaim` | The caller's verified identity token | `context.jwt().getStringValue("email")` |

```java
@Global(name = "Greeting", type = ValueType.STRING, defaultValue = "Hello")
@Global(name = "TimeoutSeconds", type = ValueType.INT, defaultValue = "5", min = "1", max = "30")
@Secret(name = "ApiKey")
@JwtClaim(name = "email", claim = "email")
public class Orders { ... }
```

Declared on the `@ToolPackage` class, a value applies to every tool; on a handler class, to that class's tools; on a method (or in `@ToolVariables`), to that tool only.

**Typed accessors.** For each class with tools the processor generates `<Class>Vars`, so you don't type names twice:

```java
String greeting = OrdersVars.global(context).greeting();
int timeout = OrdersVars.global(context).timeoutSeconds();
```

Rules worth knowing:

- Secrets are never logged and never appear in results; their values are replaced with `[REDACTED]` anywhere they would leak.
- Prefer route auth ([section 6](#6-calling-other-services)) to reading a secret yourself. Under ToolGate the broker adds route credentials and your code never sees them.
- Claims come only from a verified token. Arguments, headers, the environment and the dev file can never supply them in production.

## 6. Calling other services

A tool reaches the network only through declared routes. Declare where (`@Destination`), how (`@HttpRoute`) and with which credentials (`@HttpAuth`), then list the routes each tool may use.

```java
@Destination(id = "items-api", hostSetting = "ItemsApiHost")
@HttpAuth(id = "items-key", type = AuthType.API_KEY, header = "X-Api-Key", secret = "ItemsApiKey")
@HttpRoute(id = "items.get", destination = "items-api", method = HttpMethod.GET,
        path = "/items/{id:[0-9]+}", auth = "items-key")
@Secret(name = "ItemsApiKey")
public class Orders {
    public record Item(String id, String name) {}

    @ServerTool(name = "items.get", title = "Get item", description = "Reads one item from the items API.",
            effect = EffectSafety.READ_ONLY, routes = "items.get")
    public Item getItem(@Param(description = "Item id") String id, ToolContext context) {
        return context.http().get("items-api", "/items/{id}").pathVar("id", id).send(Item.class);
    }
}
```

- **The host is configuration, not code.** `hostSetting` names a value the administrator sets per deployment (`ItemsApiHost: items.example.com`). The same package can point at staging or production without a rebuild. Calls use HTTPS on the declared port (default 443).
- **Two ways to call.** `context.http().route("items.get")` by id, or the convenience form `get(destination, template)` (and `post`, `put`, `patch`, `delete`, `head`). The processor proves at compile time that a convenience call matches exactly one route the tool lists.
- **What a route allows.** Path variables must fully match their patterns and never contain `/`. Query names and headers must be in the route's `query` and `headers` allowlists. `Authorization`, `Cookie`, `Host`, `Proxy-Authorization`, `X-Forwarded-*` and the auth header can never be set by code. Bodies and responses are capped (`maxRequestBytes`, `maxResponseBytes`), and `timeoutMs` can only be shortened.
- **Auth types.** `API_KEY` (the secret in a header), `BASIC`, and `OAUTH2_CLIENT_CREDENTIALS` (token fetched and cached for you) work in both modes. `TOKEN_EXCHANGE`, `CONNECTION` and `CUSTOM` are provided by ToolGate in governed mode.
- **Pinning.** `spkiPins` on a destination trusts only servers whose certificate chain carries one of those keys.
- **TCP.** `@TcpDestination` and `context.tcp().connect(id)` (or `socketFactory(id)` for drivers) reach a declared host and port only. TCP is standalone-only for now.
- **Failures.** A non-2xx response raises `UpstreamError` (`UPSTREAM_4XX`, `UPSTREAM_5XX`); timeouts and connection problems are `UPSTREAM_TIMEOUT` and `UPSTREAM_CONNECTION`. The client never retries and never follows redirects.
- **Correlation.** Under ToolGate, `context.invocation()` also gives `executionId()` (new for each retry), `attempt()`, and the caller's `toolCallId()`, `sessionId()` and `agentRunId()` (Python: `execution_id`, `attempt`, `tool_call_id`, `session_id`, `agent_run_id`). Use them in logs; never use them for decisions, because the caller declares the last three. To continue the caller's trace downstream, send `traceHeaders()` (Python: `trace_headers()`) on the call. They hold only W3C `traceparent` and `tracestate`, and the route must list those names in its `headers` allowlist. In standalone runs these accessors are empty and `attempt()` is 1.

In development, point a destination at a local mock in `toolgate.dev.json`; a loopback host may use any port and plain `http://`:

```json
{ "destinations": { "ItemsApiHost": "http://127.0.0.1:8080" } }
```

### Files on a device

A `@ClientTool` runs on the device the caller selects, inside a sandbox with no host files. To read or write files there, declare it with `@DeviceFileAccess` and use `context.files()`: the device performs each operation for the tool, inside its one confined **files area** ([ADR 027](docs/adr/027-one-tool-protocol-device-files.md)).

```java
@ClientTool(name = "read_text", title = "Read a text file", description = "Reads a text file from the device.",
        effect = EffectSafety.READ_ONLY)
@DeviceFileAccess                       // write = true for tools that change files
public Text readText(@Param(description = "File path", maxLength = 1024) String path, ToolContext context) {
    var files = context.files();
    return files.list().contains(path) ? new Text(true, files.readText(path)) : new Text(false, "");
}
```

- `@DeviceFileAccess` adds the descriptor permission `DEVICE_FILES_READ`, and `DEVICE_FILES_WRITE` with `write = true`. Administrators see them when reviewing the package; Control signs them into the device's registration. It is a compile error on a `@ServerTool`.
- `DeviceFiles` offers `list`, `readText`, `info`, `writeText`, `appendText`, `mkdir` and `rename`. Paths are relative, lowercase and portable; the device refuses traversal, links, hidden names and file types it does not allow, and limits sizes and entries. Refusals raise `PlatformError` `FILES_REFUSED`.
- A path you pass must be one of the call's own string arguments, so a tool touches only the files its caller authorized.
- Self-tests run your `@Example`s in an empty files area, so write examples that hold in an empty folder (report a missing file as data). `ToolHarness`, `toolgate check` and `toolgate dev` give you an in-memory area with the same rules; `ToolHarness.builder().deviceFiles(new InMemoryDeviceFiles().file("notes.txt", "..."))` seeds one.

The [HotFolder example](examples/java-tools/hotfolder/README.md) is a complete package.

## 7. Errors

Throw from the hierarchy in `io.ololabs.toolgate.sdk.errors`. Each maps to an MCP tool result with `isError: true` and a structured error, identical in every language and mode.

| Throw | Code | Retryable |
|---|---|---|
| `new ToolInputError("...")` | `INVALID_ARGUMENT` | no |
| `new ToolBusinessError("ORDER_NOT_FOUND", "...", data)` | your declared code | no |
| `new ToolRetryableError("RATE_LIMITED", "...", retryAfter)` | your declared code | yes, for `READ_ONLY` and `IDEMPOTENT` tools |
| `new ToolConfigurationError("...")` | `CONFIGURATION` | no |
| `UpstreamError` (raised by the HTTP client) | `UPSTREAM_*` | as for retryable |
| anything else | `INTERNAL_TOOL_ERROR`, with the fixed message "The tool failed." | no |

Declare your business codes on the tool:

```java
@ServerTool(name = "orders.get", ..., errors = {
    @ErrorDecl(code = "ORDER_NOT_FOUND", description = "No order has that id."),
    @ErrorDecl(code = "RATE_LIMITED", description = "Try again shortly.", retryable = true)})
```

An undeclared code reaches the caller as `UNDECLARED_ERROR`. Stack traces never reach the caller. Messages are capped at 1 KiB and `data` at 8 KiB, and both are redacted.

## 8. Effects, retries and business keys

`effect` tells callers and the platform what a repeat would do:

| Effect | Meaning | Consequence |
|---|---|---|
| `READ_ONLY` | Changes nothing. | Safe to retry. |
| `IDEMPOTENT` | Repeating gives the same end state. | Safe to retry. |
| `NON_IDEMPOTENT` | Repeating repeats the effect (a payment, an email). | Retries are disabled; needs a business key by default when destructive. |
| `UNKNOWN` | Not stated. | Treated like `NON_IDEMPOTENT`. |

State it honestly; an administrator sees it when approving the tool. If a `NON_IDEMPOTENT` or `UNKNOWN` tool loses its connection after sending a request, the result is `OUTCOME_UNKNOWN`, never a silent failure, because the effect may have happened.

**Business keys** make a non-idempotent call safe to repeat. Name the argument that carries the caller's key:

```java
@ServerTool(name = "payments.create", ..., effect = EffectSafety.NON_IDEMPOTENT,
        businessKeyArgument = "paymentReference")
@Idempotency(header = "Idempotency-Key")
```

A repeat with the same key and arguments returns the first result without running again; different arguments get `IDEMPOTENCY_CONFLICT`. `@Idempotency` forwards a derived key to the upstream API as well. Standalone mode keeps reservations in a local SQLite file; under ToolGate, Control keeps them.

## 9. Long-running tools and asking the user

- **Async tools.** `async = true` runs the tool as an MCP task: the client gets a task id and polls for the result. Report progress with `context.progress(0.4, "Exported 4 of 10")` and stop early when `context.isCancelled()` is true.
- **Asking for input.** `context.requestInput(...)` asks the user a question through MCP elicitation. In a sync call the handler re-runs from the start with the answer, so code before `requestInput` must be safe to repeat. In an async tool the task waits in `input_required`.
- **Logging.** Use `context.log()`. Logs are redacted like results.

## 10. Test

`ToolHarness` runs your tools through the real runtime in test mode, with values from the builder and recorded HTTP exchanges instead of a network:

```java
try (ToolHarness tools = ToolHarness.builder()
        .global("Greeting", "Hi")
        .secret("ItemsApiKey", "test-key")
        .claim("sub", "user-1")
        .exchange(RecordedExchange.on("items.get").variable("id", "7")
                .replyJson(200, Map.of("id", "7", "name", "Seventh")))
        .build()) {
    assertEquals("Hi, Ada", tools.call("greet", Map.of("name", "Ada")).output().path("text").asText());
    assertEquals("Seventh", tools.call("items.get", Map.of("id", "7")).output().path("name").asText());
    assertEquals("items.get", tools.routeCalls().get(0).route());
    tools.assertExamples();
}
```

- A route call no exchange answers fails with `ROUTE_REFUSED`, so a test proves exactly which calls a tool makes.
- `RecordedExchange.fail(...)` injects connection failures, so you can test `OUTCOME_UNKNOWN` handling.
- Exchanges can live in JSON files (`src/main/resources/exchanges/item-1.json`) and be attached to an `@Example` with `exchanges = "exchanges/item-1.json"`.

`toolgate check` compiles, runs your unit tests and every `@Example`, and fails if `toolgate.dev.json` is tracked by git (it may hold real secrets).

## 11. Run it as a plain MCP server

**While developing:**

```sh
toolgate dev                          # stdio, values from toolgate.dev.json
toolgate dev --http 127.0.0.1:8080    # Streamable HTTP at /mcp
toolgate dev --digests                # print each tool's descriptor digest
```

To use it from an MCP client that launches servers over stdio, give the client this command, with the project directory as the working directory:

```json
{
  "mcpServers": {
    "orders": { "command": "toolgate", "args": ["dev", "/path/to/orders"] }
  }
}
```

**In production, standalone:** the package's `artifacts/tool.jar` is one self-contained executable: your code, the SDK runtime and every dependency in a single fat jar. A machine needs only a Java 17+ runtime to run it.

```sh
unzip -q build/toolgate/orders-0.1.0.tgpkg artifacts/tool.jar -d orders-pkg
TOOLGATE_GLOBAL_GREETING=Hello \
TOOLGATE_DESTINATION_ITEMSAPIHOST=items.example.com \
TOOLGATE_SECRET_ITEMSAPIKEY=... \
java -jar orders-pkg/artifacts/tool.jar
```

| Variable | Meaning |
|---|---|
| `TOOLGATE_GLOBAL_<NAME>`, `TOOLGATE_SECRET_<NAME>`, `TOOLGATE_DESTINATION_<HOSTSETTING>` | Values, by upper-cased name. |
| `TOOLGATE_HTTP_ADDRESS` | Serve Streamable HTTP at `/mcp` on `host:port` instead of stdio. |
| `TOOLGATE_AUTH_ISSUER`, `TOOLGATE_AUTH_AUDIENCE`, `TOOLGATE_AUTH_JWKS_URL` (or `_KEYS_FILE`, or `_INTROSPECTION_URL`) | Verify callers' bearer tokens. Required to bind a non-loopback address, and for tools that read claims. |
| `TOOLGATE_TASK_STORE=sqlite`, `TOOLGATE_STATE_DB` | Keep tasks and business-key reservations across restarts. |

Standalone mode is a plain MCP server: there is no enterprise audit, and the startup banner says so. When an organisation needs control over who calls what, run it under ToolGate instead.

## 12. Package

```sh
toolgate pack                         # build/toolgate/<name>-<version>.tgpkg (Maven: target/toolgate/)
toolgate validate build/toolgate/orders-0.1.0.tgpkg
toolgate verify   build/toolgate/orders-0.1.0.tgpkg   # prints packageDigest
```

`pack` runs your build, then writes a deterministic archive: the manifest, a canonical descriptor per tool, your executable and `CHECKSUMS.sha256`.

For Java the executable is `artifacts/tool.jar`, a fat jar of your jar and its whole runtime class path, with `Main-Class` set, so a device needs only a JRE. Merging follows a few rules: your jar comes first, then dependencies in class-path order, and the first copy of a resource wins; `META-INF/services` files are merged; signatures, `module-info` and the dependencies' manifests are dropped. Two *different* copies of one class stop the build, naming both jars, because either choice could break the tool at run time; exclude one of the dependencies. ToolGate refuses a Java tool that needs anything besides its one jar. It validates its own output. The `packageDigest` is the SHA-256 of the checksums file, so it identifies the exact package. Administrators allow packages by that digest.

Optional files under `marketplace/` (`listing.json`, `README.md`, `CHANGELOG.md`, `icon.png`), `tests/self-tests.json` and `sbom/cyclonedx.json` in the staging directory are included as well.

Bump `version` in your build for every release you hand to an administrator. The same version with different contents is a different digest and will be treated as a new package.

## 13. Run it under ToolGate

Under ToolGate your tool runs in a **Tool Host**, a device enrolled with Control that holds deployed packages and runs one process per call. Here is the path from package to a governed MCP call, as an administrator walks it in the console.

1. **Upload.** *Server packages → Upload a package.* Control checks the archive rules, every checksum and every descriptor digest, then stores the exact bytes. Only the installation's Super Admin can upload. (API: `POST /api/control/v1/packages/uploads` with the `.tgpkg` as `application/vnd.ololabs.toolgate.package+zip`.)
2. **Deploy.** *Server packages → Deploy a version.* Pick the version and give a value for each global and each destination host setting (the console prefills them from the descriptors). Each new tool is added to the directory **disabled**, in the default Tool Group. Tool Hosts fetch the package and report the tools they can run. (API: `POST /api/control/v1/packages/deployments`.)
3. **Enable.** *Tools → `acme.orders/greet` → Enable.* A reviewed change, approved by a second administrator.
4. **Allow the package.** In the binding the tool runs under, add the `packageDigest` to the allowed packages and allow the `invoke` action. This is what lets *this exact build* run; a new build needs a new approval.
5. **Grant.** Grant an agent or user group the `invoke` action on resource `tool://acme.orders/greet`.
6. **Call.** Granted agents now see `acme.orders/greet` in `tools/list` at the Gateway's `/mcp` endpoint, and `tools/call` runs it.

What happens on each call:

1. The Gateway asks Control whether this caller may run this tool now. Control places the call on a Tool Host that holds the exact package and signs a single-use **permit** for that host. The permit pins the package, the descriptor, the globals and the argument digest.
2. The Tool Host verifies the permit, claims it with Control, and starts your runtime with one input on stdin (`TOOLGATE_PROTOCOL=2`). Your handler runs exactly as in standalone mode.
3. Every route call goes to the host's **broker** as a `BROKER_REQUEST` frame on stdout and comes back as a `BROKER_RESPONSE` line on stdin. The broker builds the request from the approved route and destination and adds the credentials. The tool process has no other way out. A client tool on a device speaks the same protocol; there the broker serves only the device files area.
4. The result goes back to the Gateway and to Control's audit record. Revoking a grant hides the tool and blocks further calls immediately.

**Where it runs: local or remote.** A Tool Host can sit next to the Gateway or on any other machine, a laptop behind NAT included: an enrolled host also fetches its calls from Control, so it needs no inbound port. When deploying, name the hosts in *Tool Hosts* (blank means every Tool Host). To move your tool to another machine, deploy the same version again naming that host; calls go there as soon as it holds the package. (API: `hostIds` on the deployment request.) Your code does not change.

What changes for your code between modes: nothing. Globals and claims come from the platform instead of environment variables, secrets are redeemed through handles, route credentials are added by the broker, and business keys are reserved by Control. TCP destinations are not available under a Tool Host yet.

To try the whole path on one machine, the quickstart image (`apps/quickstart`) runs Control, a Gateway and two Tool Hosts together, one of them pull-only; `tools/quickstart/check.py` uploads, deploys and calls the starter exactly as above, then moves it to the other host and calls it again.

## 14. Rules that keep a tool compatible

- Declare everything the tool touches: values, routes, destinations, auth, errors. Anything undeclared is refused at compile time or at the call.
- Keep handlers free of global state between calls. Under a Tool Host each call is a fresh process.
- Never read credentials from files or the environment yourself; declare a secret or, better, route auth.
- Make code before `requestInput()` safe to repeat.
- State the effect honestly, and use a business key for anything that must not happen twice.
- Never build URLs by hand or use another HTTP client: only `context.http()` and `context.tcp()` reach the network under ToolGate.
- Keep `descriptorGeneration` pinned, and change it deliberately.
- Keep `toolgate.dev.json` out of git.

## 15. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Compile error naming `descriptorGeneration` | Set `toolgate { descriptorGeneration.set(1) }` (Gradle) or `<descriptorGeneration>1</descriptorGeneration>` (Maven, with `<extensions>true</extensions>`). |
| Compile error at a `get(...)` call | The convenience call matches no route, or two. Use string literals and list the route in `routes`. |
| `ROUTE_REFUSED` at runtime | The tool called a route it doesn't list, or in a test no recorded exchange answered it. |
| `CONFIGURATION` | A global, secret or destination host has no value. Set it in `toolgate.dev.json`, the environment, or the deployment. |
| `CLAIMS_UNAVAILABLE` | The tool reads a claim but the call has no verified identity (stdio has none). Use HTTP with `TOOLGATE_AUTH_*`, or the dev file's `claims` in development. |
| `UNDECLARED_ERROR` | A `ToolBusinessError` code isn't in the tool's `errors`. |
| Tool missing from `tools/list` under ToolGate | Check, in order: deployed, enabled, package digest allowed in the binding, `invoke` granted, and a Tool Host reporting the tool. |
| `pack` fails with "two different copies of …class" | Two dependencies carry different versions of one class. Exclude one in your build (`exclude(...)` in Gradle, `<exclusions>` in Maven) so the fat jar holds one version. |
| `toolgate check` fails on `toolgate.dev.json` | It's tracked by git. `git rm --cached toolgate.dev.json`. |

## 16. Reference

| Topic | Where |
|---|---|
| Java SDK modules, runtime variables and behaviour | [tool-sdk/java-sdk/README.md](tool-sdk/java-sdk/README.md) |
| The `toolgate` CLI and package format | [tool-sdk/cli/README.md](tool-sdk/cli/README.md) |
| Authoring contract, all languages | [tool-sdk/spec/authoring/](tool-sdk/spec/authoring/README.md) |
| Java annotations and `ToolContext`, exactly | [tool-sdk/spec/authoring/java.md](tool-sdk/spec/authoring/java.md) |
| Errors | [tool-sdk/spec/authoring/errors.md](tool-sdk/spec/authoring/errors.md) |
| HTTP routes | [tool-sdk/spec/authoring/http.md](tool-sdk/spec/authoring/http.md) |
| Standalone and governed modes | [tool-sdk/spec/runtime-modes.md](tool-sdk/spec/runtime-modes.md) |
| Tool Host | [apps/tool-host/README.md](apps/tool-host/README.md) |
| Conformance cases (for writing an SDK in another language) | [tool-sdk/conformance/](tool-sdk/conformance/README.md) |

Questions and proposals: [SUPPORT.md](SUPPORT.md) and [CONTRIBUTING.md](CONTRIBUTING.md). Security reports: [SECURITY.md](SECURITY.md).
