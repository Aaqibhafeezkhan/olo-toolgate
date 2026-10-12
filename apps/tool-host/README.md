# Tool Host

**Status:** Admission, the content store, the broker and the Java launcher are built (M0 part 13). Gateway dispatch, the Control endpoints for Tool Hosts and package upload come next; sandbox isolation (network namespace, container) comes after.
**Primary stack:** Rust

## Responsibility

The Tool Host runs server tools (plan.md §11, §13.1). It admits a dispatch only with a permit Control signed for this host, claims the permit with Control, and runs the tool from the exact package, service profile and globals the permit pins. The tool reaches the network only through the host's broker, which builds every upstream request from the approved service profile and adds credentials itself.

```text
Gateway ── POST /v1/dispatch {permit, arguments} ──▶ Tool Host
                                                      │ verify permit (signature, aud, window, jti, arguments digest)
                                                      │ find package, profile, globals by digest
                                                      │ Control: permits/consume ──▶ fencing token
          ◀── ADMITTED {invocationId, fencingToken} ──┤
                                                      │ start the runtime: ToolInput on stdin
                                                      │   route calls ──▶ broker (Unix socket) ──▶ upstream
                                                      │ ResultFrame on stdout
                                                      │ Control: effects/report
          ◀── RESULT {invocationId, outcome, result} ─┘
```

## Run and verify

```sh
export TOOLGATE_TOOL_HOST_CONFIG=/etc/toolgate/tool-host.json
cargo run -p olo-toolgate-tool-host --locked
```

`cargo test -p olo-toolgate-tool-host` covers permits, the store, the broker's checks and dispatch through a real host with a fake Control and upstream (the dispatch tests need `python3`, which stands in for a runtime). `apps/tool-host/e2e/run.sh` builds the Java starter with `toolgate init` and `toolgate pack` and runs both of its tools through the host binary; it needs a JDK, `python3`, `openssl` and cargo.

## Configuration

One JSON file, refused at startup if anything is wrong.

| Field | Meaning |
|---|---|
| `hostId` | This host. A permit's `aud` must equal it. |
| `listen` | The dispatch listener. Loopback only until the mTLS listener for remote Gateways arrives. |
| `permitKeys[]` | `{kid, publicKeyPath}`: Control's effect-signing keys, as a PEM `PUBLIC KEY` or certificate. |
| `permitIssuer` | The `iss` a permit must carry. |
| `storeDir` | The content store (below). |
| `workDir` | Unpacked packages and one directory per running invocation. Keep the path short: it holds the broker's Unix sockets. |
| `control` | `{url, clientCertificatePath?, clientKeyPath?, caCertificatePath?, developmentLoopbackHttp?}`. `url` is https, or http to loopback with `developmentLoopbackHttp`. The certificate and key are the host's workload identity. |
| `runtimes.javaPath` | The `java` the JVM launcher runs (default `java`, Java 17 or later). |
| `limits` | `maxConcurrent`, `maxInputBytes`, `maxOutputBytes`, `syncDeadlineMs`, `memoryBytes`, `maxPermitWindowMs`. Each may only be lowered below the platform ceiling (plan.md §13.5). |
| `developmentLoopbackHttp` | Lets the broker send plain http to loopback destinations. For development and tests only. |

## Dispatch

`POST /v1/dispatch` takes a `Dispatch` and answers `application/x-ndjson`:

1. One `AdmissionResponse` line, definitive. `REFUSED` carries the reason:
   - `PERMIT_INVALID`: bad signature, unknown key, another issuer or host, a window over `maxPermitWindowMs`, a replayed `jti`, arguments that do not match `argumentsDigest`, arguments over the input limit, or Control refusing the claim;
   - `PERMIT_EXPIRED`: outside `nbf`/`exp` (2 s skew) or past the deadline;
   - `DIGEST_NOT_PRESENT`: something the permit pins is not in the store or does not match, or the permit grants a route the tool does not declare or the profile does not approve;
   - `UNSUPPORTED_TIER`: a runtime this host has no launcher for;
   - `BUSY`: every slot is in use;
   - `STORE_UNAVAILABLE`: the store or Control could not be read.
   Nothing that refuses consumes the permit at Control, except Control's own refusal.
2. After `ADMITTED`, one `ResultMessage` line. A frame with `isError` is `FAILED`. A process that dies or passes its deadline is `OUTCOME_UNKNOWN` when the permit says `requiresExecutionLedger`, and `FAILED` otherwise.

`GET /v1/health` answers `{ready, hostId, available}`.

## Content store

Everything is found by the digest the permit pins, never by name or version.

| File | Digest |
|---|---|
| `packages/<hex>.tgpkg` | `packageDigest`: `sha256` of the package's `CHECKSUMS.sha256`. The package passes the CLI's archive rules and packager checks before first use. |
| `profiles/<hex>.json` | `serviceProfileDigest`: the canonical digest (NFC, then RFC 8785) of the `ServiceProfile`. Its `revision` must equal `serviceProfileRevision` and its `packageId` the package's id. |
| `globals/<hex>.json` | `globalsDigest`: the canonical digest of the tool's resolved globals, one JSON object of name to value. |

The permit's other pins are checked against the package: `toolDescriptorDigest` against the tool's definition, `runtimeDigest` against the canonical digest of the tool's `RuntimeDeclaration`, and `artifactDigest` against the tool's first artifact. `argumentsDigest` is `sha256` over the RFC 8785 form of the arguments, as Control's `Jcs.digest` computes it.

A verified package is unpacked once under `workDir/packages/<hex>/` (`app.jar`, and the jars of `libs.zip` under `lib/`), through a temporary directory and a rename.

## Runtime protocol

The host starts the tool's runtime with a clean environment: `HOME` and the working directory are the invocation's own directory, plus `LANG`, `TOOLGATE_PROTOCOL=2`, `TOOLGATE_BROKER_SOCKET` and `TOOLGATE_BROKER_CAPABILITY`. It writes one `ToolInput` line to stdin and reads one `ResultFrame` line from stdout, killing the process at the deadline. The JVM launcher runs `java -Xmx<memory> -XX:+ExitOnOutOfMemoryError -cp app.jar:lib/* <entryPoint>`; the Java SDK's `Main` switches to governed mode on `TOOLGATE_PROTOCOL=2`. Another language needs a launcher in `runner.rs` and its `runtimeId` in `store::RUNTIMES`; admission, the store and the broker stay as they are.

`secretHandles` are given only to tools with the `CREDENTIALS_IN_SANDBOX` permission. Other tools never see a secret: the broker adds credentials to their route calls.

## Broker

Each invocation gets its own Unix socket and a random capability. A request without that capability, or after the invocation ends, is refused.

`POST /v1/call` takes the route call in the recorded-exchange shape of the SDKs' test mode (`tool-sdk/spec/authoring/testing.md`): `route`, `method`, `destination`, `variables`, `query`, `headers`, `contentType`, and the body as `body` (JSON), `bodyText` or `bodyBase64`. The broker:

- accepts only a route the permit grants, as the service profile approves it, with its method and destination;
- requires every path variable, checks it against its pattern, rejects `/`, `\`, `.`, `..` and encoded separators or dots, and percent-encodes it;
- allows only the route's query names and headers, and never `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `Forwarded`, `X-Forwarded-*`, hop-by-hop headers or the auth's own header;
- checks the body's size and content type against the route;
- sends only to a `BROKERED` https destination. Every resolved address is checked at connect time: link-local and metadata, loopback, unspecified, multicast and reserved addresses are refused, and private ranges need an approved `(host, CIDR, port)` entry. TLS is verified against the system roots, the profile's `caBundle` (PEM), or its `sha256/<base64>` SPKI pins, which take the place of the CA and host-name checks;
- adds the route's `API_KEY` or `BASIC` credential last, fetched from Control for this invocation; other auth types are refused for now;
- follows no redirects, and removes `Set-Cookie` and authentication challenges from the response.

It answers `{status, headers, bodyBase64}`, `{failure}` (`CONNECTION_REFUSED`, `TIMEOUT_AFTER_SEND`, `RESET_AFTER_SEND`) when the call failed in flight, or `{refused}` with the reason when it would not send. A response is cut one byte past `maxResponseBytes`, so the SDK reports it as too large.

`POST /v1/secrets` takes `{handle}` and answers `{value}` or `{refused}`.

Every decision is logged on the `toolgate::audit` target with the invocation, route, destination, method, status, bytes, duration, upstream request id and refusal reason. Bodies and credentials are never logged.

## Control

The host calls three endpoints under `/api/control/v1/access`, presenting its workload certificate:

| Call | Body | Answer |
|---|---|---|
| `POST /permits/consume` | `PermitConsumption` | `PermitConsumed` with the fencing token |
| `POST /secrets/deliver` | `{invocationId, name}`, with the fencing token in `Toolgate-Fencing-Token` | `{value}` |
| `POST /effects/report` | `InvocationResultReport`, with the fencing token in `Toolgate-Fencing-Token` | any 2xx |

Control's support for Tool Host certificates on these endpoints arrives with Gateway dispatch.

## Not yet

- Gateway dispatch, Tool Host registration, kill pushes and the mTLS dispatch listener.
- A result outbox: a report Control does not take is logged, not retried.
- Sandbox isolation beyond a clean environment and its own directory: the network namespace, firewall and container tiers of plan.md §11.5 and §11.6.
- Tunnel destinations, TCP, OAuth 2.0, token exchange and connection auth, streaming routes.
