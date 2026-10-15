# Authoring contract (language-neutral)

## Purpose

Defines every declaration and runtime accessor a tool author can use, independent of language. The per-language files map these names onto idiomatic APIs; they **must not** add, remove or change meaning.

## 1. Identifiers

| Identifier | Grammar | Example |
|---|---|---|
| Package id | `^[a-z][a-z0-9-]{1,30}(\.[a-z][a-z0-9-]{1,30}){1,3}$` | `acme.orders` |
| Tool local name | `^[a-z][a-z0-9_-]{0,47}(\.[a-z][a-z0-9_-]{0,47}){0,2}$` | `orders.lookup` |
| `toolId` | `<package id>/<tool local name>`, at most 128 characters, and matching the existing id rule `[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}` | `acme.orders/orders.lookup` |
| `mcpName` (default) | `<package alias>.<tool local name>`, where the package alias is the last segment of the package id; 1–128 characters of `A–Z a–z 0–9 _ - .`; uniqueness enforced at enable (platform-requirements.md §10.4) | `orders.orders.lookup` |
| Variable name (param, global, secret, claim) | `^[A-Za-z][A-Za-z0-9_]{0,63}$`, unique per tool across all kinds, case-insensitively | `OrderId`, `ApiKey` |
| Route, destination, auth id | `^[a-z][a-z0-9_.-]{0,63}$`, unique per package | `orders.get` |
| Error code | `^[A-Z][A-Z0-9_]{2,63}$` | `ORDER_NOT_FOUND` |

`toolgate.*`, `hotfolder.*` and `builtin.*` prefixes are reserved and **must** be rejected by the packager.

## 2. Package declaration

`ToolPackage(id, name, publisher, description?)`. Exactly one per package. The version comes from the build (Gradle, Maven, `pyproject.toml`, `package.json`, `.csproj`) and **must** be SemVer 2.0.0.

## 3. Tool declarations

`ServerTool(...)` declares a tool that runs on a Tool Host (or in standalone mode). `ClientTool(...)` declares a tool that runs on an endpoint device. Both accept:

| Attribute | Type | Default | Meaning |
|---|---|---|---|
| `name` | tool local name | required | Local name; `toolId` is derived |
| `title` | string, 1–100 characters | required | Human title. `text` is accepted as an alias; giving both is an error. |
| `description` | string, 1–2048 characters | required | Shown to the model |
| `useWhen` | string, at most 512 characters | none | Appended to the description as guidance |
| `effect` | `READ_ONLY` \| `IDEMPOTENT` \| `NON_IDEMPOTENT` \| `UNKNOWN` | `UNKNOWN` | Effect safety ([execution.md](execution.md)) |
| `destructive` | boolean | false | Policy default becomes ASK |
| `openWorld` | boolean | true for server tools with routes, false otherwise | MCP `openWorldHint` |
| `async` | boolean | false | Runs as a task |
| `maxDurationMs` | integer | 30 000 sync, 900 000 async | Must not exceed the platform ceiling |
| `stateless` | boolean | false | Declares no reliance on process state; enables opt-in sandbox reuse |
| `maxConcurrency` | integer ≥ 1 | unlimited | Honoured by both runtimes |
| `routes` | list of route ids | empty | Routes this tool may call ([http.md](http.md)) |
| `tcp` | list of TCP destination ids | empty | TCP destinations this tool may open |
| `requiresBusinessKey` | boolean | true when `NON_IDEMPOTENT` and `destructive`, else false | [execution.md](execution.md) §4 |
| `businessKeyNamespace` | `AGENT` \| `DELEGATED_USER` \| `TENANT` | `AGENT` | [execution.md](execution.md) §4 |
| `businessKeyArgument` | parameter name | none | Argument carrying the business key when not sent as `Idempotency-Key` |
| `cancelIfAbandoned` | boolean | false | Tasks only |
| `errors` | list of `ErrorDecl(code, description, retryable)` | empty | Declared business error codes ([errors.md](errors.md)) |
| `toolsets` | list of strings | empty | Visibility grouping only |

`ClientTool` additionally accepts `tiers` (list of `OCI_SANDBOX`, `WASI`, `HOST_NATIVE`; default `[OCI_SANDBOX]`). In release 1 a `ClientTool` **must not** declare `routes`, `tcp`, secrets or `HttpAuth` (no network or secrets on clients); the packager rejects them.

**Declaration scope.** Globals, secrets, claims, destinations, routes and auth may be declared on the package or class; they then apply to every tool in that scope. A tool-level declaration with the same name as a scope-level one is an error, never an override.

## 4. Inputs (AI-supplied arguments)

Two equivalent forms, which **must** produce byte-identical descriptors:

1. **Typed parameters** (default): each handler parameter except `ToolContext` is an input, annotated with `Param`.
2. **Grouped form:** `ToolVariables(inputs = [Variable(...)], globals, jwt, secrets)` on a handler that takes only `ToolContext`.

`Param` / `Variable` attributes:

| Attribute | Type | Default |
|---|---|---|
| `name` | variable name | the language parameter name, converted to the exact source spelling |
| `description` | string ≤ 1024 | required for every input |
| `type` | value type (§7) | inferred from the language type |
| `required` | boolean | true unless the language type is optional/nullable |
| `defaultValue` | string, parsed as `type` | none; a default makes the input optional |
| `min`, `max`, `minLength`, `maxLength`, `pattern` (ECMA-262), `enumValues`, `format` | per JSON Schema 2020-12 | none |
| `sensitive` | boolean | false; redacted from logs, errors and audit |

Object, array and record types are mapped structurally to JSON Schema 2020-12 with `additionalProperties: false` unless the type is an explicit map.

## 5. Configuration and identity declarations

| Declaration | Attributes | Supplied by (governed) | Supplied by (standalone) |
|---|---|---|---|
| `Global(name, type, defaultValue?, required?, description?, constraints…)` | §4 constraints | Approved configuration revision | Env `TOOLGATE_GLOBAL_<NAME>` or `toolgate.dev.json` |
| `Secret(name, type = STRING, scope = GROUP \| USER, description?)` | | Control secret delivery or credential broker | Env `TOOLGATE_SECRET_<NAME>`, OS keychain, or `toolgate.dev.json` |
| `JwtClaim(name, claim, type = STRING, required = true)` | `claim` is a JSON pointer or a top-level claim name | Gateway-verified identity | Verified bearer token, or fixtures in development mode only ([runtime-modes.md](../runtime-modes.md) §4) |

Globals **must not** hold network destinations; a global whose value is used to build a URL or host fails packaging ([http.md](http.md)).

## 6. Other declarations

| Declaration | Purpose | Defined in |
|---|---|---|
| `HttpAuth`, `Destination`, `HttpRoute`, `TcpDestination` | Network access | [http.md](http.md) |
| `Idempotency(header, scope)` | Forwarding the business key upstream | [execution.md](execution.md) §4 |
| `Reconcile` | Marks a handler that answers `APPLIED` / `NOT_APPLIED` / `UNKNOWN` | [execution.md](execution.md) §5 |
| `Example(arguments, expectedOutput?, expectedError?)` | Up to 8 per tool; become self-tests (the existing fleet self-test fields) | [execution.md](execution.md) §6 |

## 7. Value types

| Value type | JSON Schema | Java | Python | TypeScript | .NET |
|---|---|---|---|---|---|
| `STRING` | `string` | `String` | `str` | `string` | `string` |
| `INT` | `integer`, 32-bit range | `int` / `Integer` | `int` (range-checked) | `number` (integer-checked) | `int` |
| `LONG` | `integer`, 53-bit safe range | `long` / `Long` | `int` | `number` (safe-integer-checked) | `long` |
| `NUMBER` | `number` | `double` / `BigDecimal` | `float` / `Decimal` | `number` | `double` / `decimal` |
| `BOOLEAN` | `boolean` | `boolean` | `bool` | `boolean` | `bool` |
| `DATE` | `string`, `format: date` | `LocalDate` | `datetime.date` | `string` (ISO date) | `DateOnly` |
| `DATE_TIME` | `string`, `format: date-time` | `OffsetDateTime` | `datetime` (aware) | `string` (RFC 3339) | `DateTimeOffset` |
| `DURATION` | `string`, `format: duration` | `Duration` | `timedelta` | `string` (ISO 8601) | `TimeSpan` |
| `ENUM` | `string` + `enum` | `enum` | `enum.Enum` | string literal union | `enum` |
| `URI` | `string`, `format: uri` | `URI` | `str` | `string` | `Uri` |
| `OBJECT` | `object` | record / POJO | `TypedDict` / dataclass / Pydantic model | interface / Zod object | record / class |
| `ARRAY` | `array` | `List<T>` | `list[T]` | `T[]` | `IReadOnlyList<T>` |
| `BINARY` | `string`, `contentEncoding: base64` | `byte[]` | `bytes` | `Uint8Array` | `byte[]` |

`LONG` values outside ±(2^53 − 1) **must** be rejected at input validation, so every language reads the same value.

## 8. `ToolContext`

Neutral names; each language file gives the exact signatures.

| Member | Returns | Notes |
|---|---|---|
| `getValue(name)` and typed getters (`getString`, `getInt`, `getLong`, `getNumber`, `getBoolean`, `getDate`, `getDateTime`, `getDuration`, `getObject`, `getList`, `getBinary`) | AI input | Grouped form only; typed parameters are passed directly |
| `global()` | Typed accessor over declared globals | Same getters |
| `secret()` | Typed accessor over declared secrets | Values exist only where the transport allows it ([http.md](http.md) §5) |
| `jwt()` | Typed accessor over declared claims, plus `getUserName()`, `getSubject()`, `getIssuer()` | Only declared claims are readable |
| `http()` | Route client | [http.md](http.md) |
| `tcp()` | TCP connector | [http.md](http.md) §6 |
| `invocation()` | `{invocationId, toolId, agentId?, userId?, catalogRevision?, deadline, businessKey?, attempt, mode}` | `mode` is `STANDALONE` or `GOVERNED` and **must not** change behaviour; it exists for diagnostics |
| `progress(fraction, message?)` | void | Fraction 0..1, monotonic; ignored for sync calls without a progress token |
| `isCancelled()` and the language cancellation token | boolean / token | [execution.md](execution.md) §3 |
| `requestInput(request)` | the answer | MRTR in sync calls, `input_required` in tasks |
| `log()` | Logger with `debug`, `info`, `warn`, `error` | Redacted and size-capped |

Reading an undeclared name, or reading with the wrong type, is a compile-time error where the language can detect it (Java processor, .NET generator, TypeScript types) and a `ToolConfigurationError` ([errors.md](errors.md)) otherwise.

## Related Docs

- [http.md](http.md), [errors.md](errors.md), [execution.md](execution.md)
- [Runtime modes](../runtime-modes.md)
