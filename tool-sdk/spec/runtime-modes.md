# Runtime modes

## Purpose

Defines the two runtimes behind the one authoring contract, the invariant that binds them, and how identity works when no Gateway is present.

## 1. Modes

| Mode | Runs as | Selected by |
|---|---|---|
| **Standalone** | A plain MCP server (stdio or Streamable HTTP) built from the tool project: `toolgate dev`, or the standalone runtime embedded in an application | Starting the standalone runtime |
| **Governed** | One invocation per sandbox on a Tool Host (server tools) or a managed endpoint (client tools), driven by tool protocol v2 | Running the packaged artifact under `toolgate-launch` |

`invocation().mode` reports the mode for diagnostics only. Tool code **must not** branch on it, and the analysers warn when it does.

## 2. Capability matrix

| Capability | Standalone | Governed |
|---|---|---|
| Input and output schemas | Identical | Identical |
| Authoring API | Identical | Identical |
| Route definitions | Same descriptor; the SDK HTTP client refuses undeclared routes (developer feedback, not a security boundary) | Same descriptor; enforced by the broker |
| Destination hosts | Local setting `TOOLGATE_DESTINATION_<HOSTSETTING>` or `toolgate.dev.json`, validated against the declared destination (port, TLS, pins) | Reviewed service-profile setting |
| Globals | Env or `toolgate.dev.json`, validated against the same declarations | Approved configuration revision |
| Secrets | Env, OS keychain or `toolgate.dev.json` | Vault and the credential broker; brokered secrets never enter the sandbox |
| JWT claims | Verified identity, or a development fixture (§4) | Gateway-verified identity |
| Effect safety, retries, no-redirect | Same rules in the SDK HTTP client; at most one execution per invocation id within the process | Ledger, fencing and broker |
| Business keys | Local reservation store (SQLite) with the same namespaces and outcomes | Control's reservation |
| Tasks | Local task store (memory or SQLite) implementing the Tasks extension through adapter A2 | Durable tasks in Control |
| Audit | Local diagnostics only; the startup banner says there is no enterprise audit | Governed audit |
| Client tools | Refused at startup unless endpoint device services are present, with code `CLIENT_TOOLS_UNAVAILABLE` | Managed endpoint execution |
| Network isolation | None; the process has the host's network | Per-sandbox namespace, broker and the transport matrix |

## 3. The invariant

**Moving a tool between modes never silently gains a permission or changes an external effect.** Concretely:

1. **Same descriptor digest.** Each tool's `toolDescriptorDigest` ([descriptor/README.md](descriptor/README.md)) **must** be equal in both modes. The standalone runtime reports it in `server/discover` `_meta["io.ololabs.toolgate/descriptorDigests"]` and `toolgate dev --digests`; governed enable shows it beside the package's value.
2. **Governed adds only reviewed restrictions.** The governed runtime **must not** add a route, secret, claim, destination or weaker effect class that the descriptor does not declare.
3. **No mode-only API.** No method, annotation or attribute works in only one mode. A capability a runtime cannot honour fails loudly with a fixed code (`CLIENT_TOOLS_UNAVAILABLE`, `CLAIMS_UNAVAILABLE`, `CONFIGURATION`), never by returning empty or default data.
4. **Visible differences only.** The values that legitimately differ (destination host, globals, secret source, identity source) are deployment values, excluded from the descriptor digest and shown side by side at review.
5. **Proved by tests.** The mode-equivalence suite runs every `Example` and shared fixture in both modes and compares outputs, error codes, route calls (route id and variables) and effect behaviour under injected failures (timeout after send, reset, 5xx).

## 4. Identity in standalone mode

`TOOLGATE_MODE` selects how claims are obtained. There is no unverified-claims path outside `development` and `test`.

| `TOOLGATE_MODE` | Claims come from | Rules |
|---|---|---|
| `production` (default) | A verified credential only, through adapter A4: a JWT checked against a configured issuer (JWKS URL or pinned keys, issuer, audience, algorithm allowlist, clock skew ≤ 60 s, `exp` and `nbf` required), or RFC 7662 introspection | If any tool declares a claim, a `USER`-scoped secret, `TOKEN_EXCHANGE` auth or the `DELEGATED_USER` namespace, the runtime **refuses to start** without a verifier (`CONFIGURATION`). A missing or invalid token on HTTP gets 401 at the transport. Over stdio there is no bearer token, so those tools return `CLAIMS_UNAVAILABLE`. |
| `development` | Fixture claims from `toolgate.dev.json` | Must be set explicitly. Binds only to stdio or loopback, prints a warning banner, and marks every result `_meta["io.ololabs.toolgate/identity"] = "fixture"`. Refuses to start when the configuration names a production issuer (any issuer configured for A4) or a non-loopback bind address. |
| `test` | Fixture claims set by `ToolHarness` | Only inside the testing library; the runtime entry points reject it. |

Claims never come from tool arguments, headers other than `Authorization`, or environment variables, in any mode.

Verifier configuration (standalone): `TOOLGATE_AUTH_ISSUER`, `TOOLGATE_AUTH_AUDIENCE`, `TOOLGATE_AUTH_JWKS_URL` or `TOOLGATE_AUTH_KEYS_FILE`, `TOOLGATE_AUTH_ALGORITHMS` (default `RS256,ES256`; `none` and HMAC algorithms are always rejected), or `TOOLGATE_AUTH_INTROSPECTION_URL` with client credentials as secrets (`TOOLGATE_AUTH_CLIENT_ID`, `TOOLGATE_AUTH_CLIENT_SECRET`). Exactly one key source or introspection URL is set. With a verifier, production may bind HTTP to a non-loopback address; without one it binds only loopback, as development always does.

## 5. `toolgate.dev.json`

```json
{
  "mode": "development",
  "destinations": { "AcmeApiHost": "localhost:8443" },
  "globals": { "TimeoutSeconds": 5 },
  "secrets": { "ApiKey": "dev-only-key" },
  "claims": { "preferred_username": "dev.user", "sub": "dev-1" }
}
```

- The file is read only in standalone mode and **must** be git-ignored; `toolgate check` fails if it is tracked.
- `claims` is read only when `TOOLGATE_MODE=development`; otherwise its presence is a startup warning and it is ignored.
- Values are validated against the declarations exactly as governed configuration is.

## Related Docs

- [Authoring contract](authoring/README.md)
- [Protocol layer and adapters](protocol-layer.md)
