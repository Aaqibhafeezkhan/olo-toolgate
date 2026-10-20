# Shared Control, dedicated tenant execution

Use the default Quickstart for a single tenant. This composition provides the shared PostgreSQL topology: one Control, one Gateway per tenant, and separate Tool Host containers/volumes/networks. Set the required image variables to qualified immutable digests. Create the restricted `PLATFORM_NETWORK` for the external PostgreSQL service; it must not contain customer executors. The complete release matrix remains a separate gate in `plan.md`.

Create the two tenants with the protected initialization manifest described in [tenant routing](../../../docs/control-plane/tenant-routing.md). Use these canonical origins:

| Tenant | Control/device origin | Gateway/console origin |
| --- | --- | --- |
| tenant-one | `https://tenant1.xyz.com:8443` | `https://tenant1.xyz.com` |
| tenant-two | `https://tenant2.xyz.com:8443` | `https://tenant2.xyz.com` |

Configure exact DNS records to the ingress host and TLS certificates covering both names. Port 443 terminates browser/MCP TLS. Port 8443 passes the original TLS handshake to Control, preserving device certificate authentication. Control verifies SNI, Host, registered tenant ownership and the JWT/certificate independently. Forwarded certificate headers are never authentication. Unknown names receive no default tenant. Edit the example names consistently in nginx, network aliases, manifest and signed profiles before provisioning.

`CONTROL_ENV` is a private environment file containing verified PostgreSQL TLS, separate migration/runtime/platform credentials, JWT public identity, protected initialization/vault/identity publication paths and the existing Control TLS/trust settings. `CONTROL_CUSTODY` contains only Control's protected inputs. The composition forces subdomain mode and disables embedded customer builds. Publish externally built verified packages. Keep backup, wrapping-key and signing-key custody separate as documented in the operator runbook.

Each `TENANT_*_GATEWAY_CUSTODY` contains its own `config.json`, `credentials.json`, service-JWT file and public Control TLS roots. Configure `control.tenantId`, canonical Control URL, `publicDiscovery`, `tenantHosts`, and `trustedTlsProxy=true`; listen internally on `0.0.0.0:8081`. Never mount another tenant's credentials or Control platform custody. Use `toolHosts: []` for this topology: Tool Hosts pull relayed dispatches through their own authenticated Control connections.

Each `TENANT_*_HOST_CUSTODY` contains its own Tool Host configuration, permit public keys and public TLS CA. Configure the owner's UUID, canonical HTTPS Control origin, `enrollment.identityDir=/data/identity`, `storeDir=/data/store`, `workDir=/data/work`, and a loopback listener. The current host listener supports local direct dispatch only; remote containers use pull mode. Approve each host separately. A Tool Host mounts only its own `/data` volume. Default networks deny Internet egress; any required destination access needs a reviewed tenant-specific egress arrangement.

`INGRESS_CUSTODY` contains `tls.crt`, `tls.key`, and the public `control-ca.pem`. It contains no device-CA private keys, vault KEKs or database credentials. The ingress image must include nginx stream/TLS support. All services run nonroot with dropped capabilities and read-only root filesystems; make mounted private files readable only to the configured service identity. No container receives the Docker socket. Keep management listeners unpublished.

Validate with `docker compose -f deploy/compose/Multitenant/compose.yaml config`, then deploy with the same file and protected environment. Check nginx with `docker compose -f deploy/compose/Multitenant/compose.yaml exec ingress nginx -t`. Run `tools/tenancy/deployment_check.py --image <local-Python-image>` for the ownership render and disposable mount/network isolation proof. The runtime protocol check is in [tools/tenancy](../../../tools/tenancy/README.md).
