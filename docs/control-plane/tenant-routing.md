# Tenant request routing

Control resolves the HTTP authority through the platform hostname registry before serving console assets, discovery, enrollment, APIs or a device WebSocket upgrade. Hostnames select an active workspace; they do not authenticate a caller. Unknown, provisioning and suspended hosts receive the same non-cacheable `404 TENANT_UNAVAILABLE` envelope. An optional `Toolgate-Tenant-ID` assertion must be a canonical UUID matching that workspace. Its absence is accepted, including on certificate-authenticated routes.

Direct HTTP/HTTPS authority is the supported input. Forwarded and X-Forwarded-Host values are ignored. Control refuses startup with `quarkus.http.proxy.proxy-address-forwarding=true`; trusting arbitrary proxy-rewritten authority is unsupported. A reverse proxy must preserve the original registered Host, and device routes require direct TLS or TCP passthrough. A supplied TLS SNI name must match the normalized HTTP host; disagreement returns `421 MISDIRECTED_REQUEST`. Duplicate/malformed authorities are rejected. HTTP/2 Host and authority must agree.

The immutable resolved context lives in the Vert.x request, without a thread-local or a default-tenant fallback. The verified JWT tenant must match it **before** identity observation, role resolution or directory changes. A valid token for another workspace receives `403 TENANT_MISMATCH`. The Endpoint, MCP, Fleet, Builder and Tool Host adapters use that same context. WebSocket channels capture an explicit tenant service before upgrading and retain it for periodic certificate checks and every message.

Device authentication checks the signed certificate subject (device ID, canonical tenant UUID and server ID) and its identity URI against the enrolled tenant/user/device record, as well as verifying its issuer, lifetime, client usage and public key. A public key enrolled in two tenants does not make a certificate interchangeable between them. HTTP and socket clients preserve their existing identity and pending reports on `TENANT_MISMATCH`, tenant unavailability and authority misdirection; only a validated ordinary authentication/revocation denial marks the local identity revoked. Error bodies have a separate 4 KiB bound, and malformed/untyped denials do not retire the device key.

Endpoint profiles are persisted during protected initialization, with registered-host ownership validation. Per-request services read the profile under the tenant transaction boundary; public client configuration and discovery use its canonical URLs and identity. Enrollment code-attempt budgets are shared across service instances and namespaced by tenant/user. Builder queues use explicit tenant transactions and filesystem directories `<data-directory>/<tenant UUID>/{work,artifacts}`. Startup package/profile work enumerates active tenants explicitly; one tenant's failure does not skip the rest. Deployment readiness checks platform connectivity and effect readiness independently of any customer's status.

Browser Origin values must exactly match a canonical Control/Gateway origin, including scheme and port. A console-only tenant without an endpoint profile accepts its registered HTTPS request origin. The Quickstart-only HTTP exception permits the same loopback authority. Missing Origin remains valid for native clients and server-to-server calls. Redirects remain relative or use stored canonical URLs. The console displays its hostname before login and the verified tenant UUID after login; it never asks the user to supply an arbitrary tenant ID.

## Helm inputs

`control.defaultTenantAlias` is a provisioning selector, not a stored identity. For default-only initialization, `control.tenancy.hosts` supplies exact registered hostnames; when omitted, initialization derives local hosts plus configured endpoint hosts. Once initialized, configuration is not replayed at restart.

For a PostgreSQL manifest deployment, provide an existing protected Secret and explicit file projections. The Secret contains `manifest.json`, signed per-tenant administrator packets, their trust files and referenced imports. No private packet or key content belongs in chart values.

```yaml
control:
  defaultTenantAlias: tenant-one
  vaultKeyRingSecret: tenant-vault-wrapping
  tenancy:
    mode: subdomain
    hosts: []
    initializationSecret: tenant-initialization
    initializationItems:
      - {key: manifest.json, path: manifest.json}
      - {key: tenant-one-packet, path: tenant-one/packet.json}
      - {key: tenant-one-trust, path: tenant-one/trust.json}
      - {key: tenant-two-packet, path: tenant-two/packet.json}
      - {key: tenant-two-trust, path: tenant-two/trust.json}
```

The chart projects files read-only with mode `0440`. Paths are relative to the protected manifest directory. Endpoint identity and canonical URLs come from each manifest entry; deployment-wide TLS/device trust inputs remain explicit. Manifest subdomain mode requires a manifest Secret and protected vault wrapping ring. See [tenant initialization](tenant-isolation.md) for the packet, UUID and one-time initialization rules.

## Qualification boundary

Control, native clients, Tool Hosts and dedicated Gateways now use tenant-bound discovery v2 and independent tenant identity keys. The functional two-tenant rotation and lifecycle checks are in `tools/tenancy/check.py`. See [tenant operations](tenant-operations.md) for protected provisioning and suspend/resume, and [the shared deployment](../../deploy/compose/Multitenant/README.md) for TLS passthrough and dedicated execution pools. Use the [deployment qualification guide](../deployment/multitenant.md) for capacity, recovery and release-image checks.
