# Tool Host

**Status:** ToolGate is in Beta Release. Admission, the content store, the broker, the Java launcher, enrollment as a device and Gateway dispatch are built (M0 parts 13 and 14). The quickstart image runs a Tool Host, and `tools/quickstart/check.py` calls a Java tool through `/mcp`. Sandbox isolation (network namespace, container) and durable result delivery are tracked in [#230](https://github.com/olo-labs/olo-toolgate/issues/230).
**Primary stack:** Rust

## Responsibility

The Tool Host runs server tools (platform-requirements.md §11, §13.1). It admits a dispatch only with a permit Control signed for this host, claims the permit with Control, and runs the tool from the exact package, service profile and globals the permit pins. The tool reaches the network only through the host's broker, which builds every upstream request from the approved service profile and adds credentials itself.

```text
Gateway ── POST /v1/dispatch {permit, arguments} ──▶ Tool Host
                                                      │ verify permit (signature, aud, window, jti, arguments digest)
                                                      │ find package, profile, globals by digest
                                                      │ Control: permits/consume ──▶ fencing token
          ◀── ADMITTED {invocationId, fencingToken} ──┤
                                                      │ start the runtime: ToolInput on stdin
                                                      │   BROKER_REQUEST (stdout) ──▶ broker ──▶ upstream
                                                      │   BROKER_RESPONSE (stdin) ◀── broker
                                                      │ RESULT frame on stdout
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
| `workDir` | Unpacked packages and one directory per running invocation. |
| `control` | `{tenantId, url, clientCertificatePath?, clientKeyPath?, caCertificatePath?, identityTrustDirectory?, developmentLoopbackHttp?}`. `tenantId` is the canonical UUID this host belongs to. `url` is a canonical HTTPS origin. Plain HTTP is limited to explicit loopback fixtures with no device or workload credentials. The certificate and key are the host's workload identity; leave them out with `enrollment`. Preprovisioned identity requires an absolute private `identityTrustDirectory` for durable discovery authority. |
| `enrollment` | `{identityDir, poolId?}`: enroll with Control as a device and fetch assignments (below). `identityDir` holds the device key, its certificate and the check-in sequence. |
| `runtimes.javaPath` | The `java` the JVM launcher runs (default `java`, Java 17 or later). A bare name is looked up on the host's `PATH` at startup, because tool processes start with an empty environment. |
| `runtimes.pythonPath` | Python 3.10+ for SDK `tool.pyz` artifacts (default `python3`, or `python` on Windows). Resolved at startup and launched with `-I` in the same clean environment and v2 broker protocol as Java. |
| `limits` | `maxConcurrent`, `maxInputBytes`, `maxOutputBytes`, `syncDeadlineMs`, `memoryBytes`, `maxPermitWindowMs`. Each may only be lowered below the platform ceiling (platform-requirements.md §13.5). |
| `developmentLoopbackHttp` | Lets the broker send plain http to loopback destinations. For development and tests only. |

Every accepted permit must name `control.tenantId`, even when another tenant uses the same host ID and signing authority. The check occurs before nonce consumption, secret delivery or sandbox execution. Requests to Control carry the same UUID assertion. Use dedicated tenant credential files, identity/store/work directories and execution pools; a shared content digest is not permission to access another tenant's artifacts.

Enrolled hosts persist a versioned UUID binding before creating a device key or contacting Control. Changing the configured tenant fails without rewriting that state or key. Expiry, device deletion and renewed enrollment retain the UUID. Unsupported development journals or orphaned older keys are rejected unchanged; use an explicitly fresh identity directory rather than converting or deleting them automatically. Enrolled hosts persist the verified v2 manifest and signing-key floor alongside the UUID and canonical Control origin. An anonymous HTTPS client fetches discovery without presenting a device certificate. Certificates are checked against that tenant's published device-CA ring, exact identity claims, key fingerprint, client-only EKU and lifetime before use. Unknown CA IDs trigger a fixed-origin HTTPS refresh; ordinary untrusted envelopes cannot introduce an unknown signer. Deletion and re-enrollment retain the trust floor and origin.

Preprovisioned hosts use the same v2 verification and tenant certificate rules. Mount only that tenant's certificate/key and public TLS roots; `identityTrustDirectory/authority.json` persists the owner, origin and trust floor. Startup verifies the host ID and refreshes discovery before readiness; subsequent requests refresh at least every four minutes. A changed origin, foreign certificate or retired CA fails before authenticated traffic. Protect this directory and isolate tenant processes, mounts and networks; these checks do not provide an operating-system sandbox.

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

A verified package is unpacked once under `workDir/packages/<hex>/` (each artifact under its file name; a Java tool is one fat jar, `tool.jar`), through a temporary directory and a rename.

## Runtime protocol

The host starts the tool's runtime with a clean environment: `HOME` and the working directory are the invocation's own directory, plus `LANG`, `TOOLGATE_PROTOCOL=2` and `TOOLGATE_EXECUTION_TARGET=SERVER`. It speaks tool protocol v2 over stdio, the one protocol every executor uses ([ADR 027](../../docs/adr/027-one-tool-protocol-device-files.md)): it writes one `ToolInput` line to stdin, answers each `BROKER_REQUEST` frame on stdout with one `BROKER_RESPONSE` line on stdin, and takes the final `RESULT` frame, killing the process at the deadline. A tool runs from exactly one self-contained artifact, the one its permit pins, and a JVM tool's must be a jar, so the device needs only a JRE. The JVM launcher runs `java -Xmx<memory> -XX:+ExitOnOutOfMemoryError -cp tool.jar <entryPoint>`; the Java SDK's `Main` switches to governed mode on `TOOLGATE_PROTOCOL=2`. Another language needs a launcher in `runner.rs` and its `runtimeId` in `store::RUNTIMES`; admission, the store and the broker stay as they are.

`secretHandles` are given only to tools with the `CREDENTIALS_IN_SANDBOX` permission. Other tools never see a secret: the broker adds credentials to their route calls.

## Broker

The broker answers `BROKER_REQUEST` frames for the running invocation only; there is no socket or capability to leak. A Tool Host provides `ROUTE_CALL` and `SECRET_REDEEM` and refuses every other operation (the device `FILES_*` operations included) with `BROKER_REFUSED`.

`ROUTE_CALL` takes the route call in the recorded-exchange shape of the SDKs' test mode (`tool-sdk/spec/authoring/testing.md`): `route`, `method`, `destination`, `variables`, `query`, `headers`, `contentType`, and the body as `body` (JSON), `bodyText` or `bodyBase64`. The broker:

- accepts only a route the permit grants, as the service profile approves it, with its method and destination;
- requires every path variable, checks it against its pattern, rejects `/`, `\`, `.`, `..` and encoded separators or dots, and percent-encodes it;
- allows only the route's query names and headers, and never `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `Forwarded`, `X-Forwarded-*`, hop-by-hop headers or the auth's own header;
- checks the body's size and content type against the route;
- sends only to a `BROKERED` https destination. Every resolved address is checked at connect time: link-local and metadata, loopback, unspecified, multicast and reserved addresses are refused, and private ranges need an approved `(host, CIDR, port)` entry. TLS is verified against the system roots, the profile's `caBundle` (PEM), or its `sha256/<base64>` SPKI pins, which take the place of the CA and host-name checks;
- adds the route's `API_KEY` or `BASIC` credential last, fetched from Control for this invocation; other auth types are refused for now;
- follows no redirects, and removes `Set-Cookie` and authentication challenges from the response.

It answers `{status, headers, bodyBase64}`, `{failure}` (`CONNECTION_REFUSED`, `TIMEOUT_AFTER_SEND`, `RESET_AFTER_SEND`) when the call failed in flight, or `{refused}` with the reason when it would not send. A response is cut one byte past `maxResponseBytes`, so the SDK reports it as too large.

`SECRET_REDEEM` takes `{handle}` and answers `{value}` or `{refused}`.

Every decision is logged on the `toolgate::audit` target with the invocation, route, destination, method, status, bytes, duration, upstream request id and refusal reason. Bodies and credentials are never logged.

## Enrollment

With `enrollment` set, the host is a device of Control, so it sits in Control's device groups and placement sends it calls like any other device. With device id `hostId` it:

1. Creates a P-256 key in `identityDir` and starts an enrollment (`POST /api/control/v1/endpoint/enrollments`) with a CSR, then polls until an administrator approves it in the console's enrollment list. Its log shows the user code.
2. Presents the issued certificate on every later call. Control renews it on check-in; an expired one is recovered by enrolling the same key again, which needs no new approval.
3. Registers as a Tool Host (`POST /api/control/v1/tool-hosts/registrations`).
4. Fetches its assignments (`GET /api/control/v1/tool-hosts/assignments`) and puts each deployment's package (`GET .../tool-hosts/packages/{digest}`), service profile and globals (`GET .../tool-hosts/documents/{digest}`) into the content store, checking each against its digest.
5. Checks in (`POST /api/control/v1/endpoint/check-in`) reporting the assigned tools whose content it holds, so Control places a call only on a host that can run it.

Steps 3 to 5 repeat at the interval Control's check-in answers. If Control has deleted the device, the host starts a new enrollment.

## Local or remote: pull mode

An enrolled host also fetches its calls from Control, so it runs tools wherever it is: a server next to the Gateway, or a laptop behind NAT that the Gateway cannot reach. Every 250 ms it calls, presenting its device certificate:

| Call | Body | Answer |
|---|---|---|
| `POST /api/control/v1/tool-hosts/dispatches/next` | none | the oldest queued `Dispatch` for this host, now claimed, or 204 |
| `POST /api/control/v1/tool-hosts/dispatches/{id}/admission` | `AdmissionResponse` | `ToolHostDispatchStatus` |
| `POST /api/control/v1/tool-hosts/dispatches/{id}/result` | `ResultMessage` | `ToolHostDispatchStatus` |

Admission and execution are the same as for `POST /v1/dispatch`: the permit is verified here, and consumed at Control, exactly once. The Gateway uses its direct route to a host when it has one, and otherwise queues the signed `Dispatch` at Control (`POST /api/control/v1/access/dispatches`) and reads its status until the result arrives.

Which host runs a tool is the deployment's choice. A deployment with `hostIds` runs only on those hosts; without them it runs on every Tool Host. To move a tool from one machine to another, deploy the same version again naming the other host. Placement stops sending calls to the old host at once and sends them to the new one as soon as it holds the content; the old host drops the deployment from its assignments at its next check-in.

## Control

For each invocation the host calls three endpoints under `/api/control/v1/access`, presenting its workload certificate:

| Call | Body | Answer |
|---|---|---|
| `POST /permits/consume` | `PermitConsumption` | `PermitConsumed` with the fencing token |
| `POST /secrets/deliver` | `{invocationId, name}`, with the fencing token in `Toolgate-Fencing-Token` | `{value}` |
| `POST /effects/report` | `InvocationResultReport`, with the fencing token in `Toolgate-Fencing-Token` | any 2xx |

Control takes these only from a registered Tool Host whose device id is the permit's audience.

## Not yet

- Kill pushes, and the mTLS dispatch listener for Gateways on other machines (pull mode covers hosts the Gateway cannot reach).
- A result outbox: a report Control does not take is logged, not retried.
- Sandbox isolation beyond a clean environment and its own directory: the network namespace, firewall and container tiers of platform-requirements.md §11.5 and §11.6.
- Tunnel destinations, TCP, OAuth 2.0, token exchange and connection auth, streaming routes.
