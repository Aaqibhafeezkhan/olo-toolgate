# Network access: routes, destinations and TCP

## Purpose

Defines the only ways tool code reaches a network, identically in every SDK: named HTTP routes (normative), a statically proven URL-style convenience, and TCP to approved destinations. The broker enforces the same declarations at runtime in governed mode (plan.md §11.8, §11.9).

## 1. Declarations

```text
Destination(id, hostSetting, defaultHost?, port = 443, scheme = https, tls = {caBundle? | spkiPins[]?})
HttpAuth(id, type, header?, secret?, connection?, scopes?, audience?)
HttpRoute(id, destination, method, path, query = [], headers = [], requestContentTypes = ["application/json"],
          maxRequestBytes = 1 MiB, maxResponseBytes = 4 MiB, streaming = NONE, timeoutMs = 10 000, auth?)
TcpDestination(id, hostSetting, defaultHost?, port)
```

| Rule | Detail |
|---|---|
| Host values | `hostSetting` names a **service-profile setting**, confirmed by an admin at enable and part of the security delta. It is not a global and is not readable through `global()`. `defaultHost` is only the proposed value. |
| `path` | RFC 6570 level-1 template. Every variable carries a pattern: `{name:regex}`. A variable without a pattern is an error. The patterns `.*` and `.+` are rejected; a variable never matches `/`. |
| `tls` | `spkiPins` are `sha256/<base64>` of a certificate's SubjectPublicKeyInfo; with pins, a server is trusted only when a certificate in its chain matches one. Without pins or `caBundle`, the system CA store applies. |
| `method` | One of `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`. Fixed per route. |
| `query`, `headers` | Allowlists of names. `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `X-Forwarded-*` and any header used by the route's auth are never allowed. |
| `streaming` | `NONE` (default), `RESPONSE`, `BIDIRECTIONAL` |
| `HttpAuth.type` | `API_KEY`, `BASIC`, `OAUTH2_CLIENT_CREDENTIALS`, `TOKEN_EXCHANGE`, `CONNECTION`, `CUSTOM` |
| Ownership | Ids are unique per package. A tool lists the routes and TCP destinations it may use (`routes`, `tcp`); calling anything else fails packaging and is refused at runtime. |

## 2. The normative route API

```text
http().route(routeId)
      .pathVar(name, value)        // required for every template variable
      .query(name, value)          // null/None/undefined values are omitted
      .header(name, value)         // must be in the route's allowlist
      .body(value)                 // serialised per requestContentTypes
      .timeout(duration)           // may only shorten the route timeout
      .send(ResponseType)          // or sendRaw() for bytes and headers
```

Responses: status 2xx returns the parsed body. Any other status raises `UpstreamError` ([errors.md](errors.md)). A route with `streaming = RESPONSE` returns a bounded stream.

## 3. The URL-style convenience form

```text
http().get(destinationId, pathTemplate)     // also post, put, patch, delete, head
      .pathVar(...) ...                      // then exactly as §2
```

A convenience call is accepted **only if the build-time analyser proves** all three:

1. `destinationId` and `pathTemplate` are **string literals** at the call site (no concatenation, formatting, interpolation, constants from other compilation units, variables, globals, arguments or claims);
2. method + destination + template match **exactly one** declared route, with identical variable names (patterns come from the route);
3. that route is in the calling tool's `routes`.

The analyser rewrites the call to its route id in the descriptor. Anything it can't prove **fails packaging** with the source location and the candidate routes. There is no runtime URL parsing and no best-match fallback.

| Language | Analyser |
|---|---|
| Java / Kotlin | Annotation processor (javac Tree API / KSP) |
| Python | AST analyser in the packager |
| TypeScript | TypeScript compiler API analyser in the packager |
| .NET | Roslyn analyzer shipped with the generator package |

A call reached through reflection, dynamic dispatch, `getattr`, computed property access or `dynamic` that the analyser can't follow is unprovable and fails packaging.

## 4. What doesn't exist

No SDK provides a free-form URL or host string, `.url(...)`, a base URL, or a way to set a destination from a runtime value. These are absent from the API, so the rule doesn't depend on the analyser.

## 5. Credentials

| `HttpAuth.type` | Transport | Secret enters the tool process? |
|---|---|---|
| `API_KEY`, `BASIC`, `OAUTH2_CLIENT_CREDENTIALS`, `TOKEN_EXCHANGE`, `CONNECTION` | Brokered route (governed); the SDK client adds it (standalone) | Governed: **no**, the broker injects it. Standalone: the SDK client reads it from the local credential provider. |
| `CUSTOM`, or the tool's own client over the tunnel | CONNECT tunnel | Yes; requires permission `CREDENTIALS_IN_SANDBOX`, always a security delta |

In governed mode `secret().get…` for a secret bound only to brokered auth throws `ToolConfigurationError`; tool code can't read it.

## 6. TCP and the tool's own HTTP client

Release 1 transports (plan.md §11.9):

| Transport | Status |
|---|---|
| Brokered HTTP(S) via §2 / §3 | Supported (default) |
| The tool's own HTTP(S) client through CONNECT, using proxy settings the runtime provides (`HTTPS_PROXY`, JVM `https.proxyHost`, .NET `DefaultProxy`, Node `ProxyAgent`) | Supported; credentials need `CREDENTIALS_IN_SANDBOX` |
| `tcp().connect(destinationId)` returning a connected stream; Java `ToolGateSocketFactory` for drivers with a socket-factory option | Supported for declared `TcpDestination`s |
| Drivers without proxy or socket-factory support, Unix sockets, UDP, QUIC, ICMP, inbound listeners, native binaries ignoring proxy settings | **Unavailable**; the packager fails when a tool declares one and warns on known driver dependencies without a `TcpDestination` |

In standalone mode the SDK applies the same declarations as developer feedback; it is not a network boundary ([runtime-modes.md](../runtime-modes.md)).

## Related Docs

- [errors.md](errors.md) for `UpstreamError`
- [execution.md](execution.md) for retry and redirect rules
