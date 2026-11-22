<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Multitenant Docker development installation

One shared PostgreSQL 17 and Control serve two separately authenticated tenants.
Each tenant has a dedicated Gateway, Tool Host volume/network and workflow worker.
A private mTLS graph preparer uses explicit per-tenant Control origins and reader
tokens. It prepares reviewed graphs; it does not execute tenant workflows.

| Tenant | Console / Gateway | Device / direct Control TLS |
| --- | --- | --- |
| A | `https://tenant-a.olo.test:18444` | `https://tenant-a.olo.test:18443` |
| B | `https://tenant-b.olo.test:19444` | `https://tenant-b.olo.test:19443` |

Published ports bind loopback. Add `127.0.0.1 tenant-a.olo.test tenant-b.olo.test`
to the local machine's hosts file and trust the reviewed local TLS CA.
Distinct hostnames remain required: Control's registry owns DNS/IP names, not
ports. Visiting `localhost` on two different ports does not select two tenants.
For a hosted deployment, change the exact hostnames consistently in ingress,
Compose aliases, profiles and custody. Provision the real subdomains before
traffic; do not rewrite Host or trust forwarded certificate headers.

## Prepare the destination

This wrapper starts an operator-prepared shared deployment. It does **not**
generate tenant identities, signed administrator reviews, JWT/password accounts,
model services or credentials. It is not a one-click multitenant Quickstart.
Control has its existing configured JWT issuer; Quickstart's single-installation
password endpoint is not hosted here. Use the destination issuer's independently
provisioned human/service identities and current tenant authority.

1. Copy `.env.example` to `.env`; select matching reviewed image versions/digests.
   Run `build-runtime.bat` (or `sh build-runtime.sh`) for the local runtime carrying
   per-tenant reader origins. Startup checks that capability before starting any
   services. Local build provenance records the HEAD baseline and includes your
   working-tree sources; it is not a signed/released source snapshot.
   Defaults name development artifacts and are not a qualification or availability
   guarantee. The Tool Host runs the genuine binary embedded in the Quickstart
   image with its supervisor entrypoint replaced; no second Control starts.
2. Prepare the protected files below using [tenant operations](../../../../docs/control-plane/tenant-operations.md),
   [identity custody](../../../../docs/control-plane/tenant-identity-custody.md),
   [vault custody](../../../../docs/control-plane/tenant-vault.md) and
   [shared topology setup](../../../compose/Multitenant/README.md).
3. Use fresh matching-schema storage. The PostgreSQL entrypoint initializes four
   independent login secrets and the two NOLOGIN capability roles on an empty
   volume only. Control's migration identity owns the application database;
   runtime and platform identities cannot bypass RLS. Existing stores are never
   deleted, converted or assigned new credentials by this wrapper.

All private inputs live under **`deploy/docker/multitenant/dev/credentials/`**:

```text
credentials/
  database/ admin-password, migrator-password, runtime-password,
            platform-password, server.crt, server.key
  control/  control.env, database-ca.pem, platform-password,
            manifest.json, publication.json, tenant-identity/,
            <administratorProvisioningRef>/packet.json and trust.json,
            server.crt, server.key, device-trust.p12,
            jwt-public.pem, vault-wrapping.json,
            graph-client.p12, graph-client-password, graph-ca.crt,
            remaining configured signing/package/permit custody
  ingress/  tls.crt, tls.key, control-ca.pem
  graph-builder/ graph.json, server.crt, server.key, ca.crt,
                 control-ca.crt, reader-a.token, reader-b.token
  tenant-a/ gateway/config.json, credentials.json, model.env,
                    service JWT/public TLS roots referenced by config
            host/config.json and its public trust/permit files
            worker/worker.token, ca.crt
  tenant-b/ same layout with independent tenant credentials
```

Restrict private files to their intended identities. Application services run as
UID/GID 65532. PostgreSQL's private TLS key must be readable by its postgres
identity with server-accepted private-key permissions. Use container-compatible
protected storage/ACLs; a Windows bind mount is not proof those checks pass.
No tenant worker, Gateway or Tool Host mounts Control/platform custody.

Use [control.env.example](examples/control.env.example) as a starting point,
not complete signing/identity provisioning. Its database passwords must match the
corresponding protected database files. The server certificate must cover
`postgres` for verified database TLS. The Control/ingress certificates must cover
both tenant hostnames. Provide current signed initialization packets/trust and
independent device CA/discovery keys for both actual tenant UUIDs.

Each manifest entry uses its table's exact Control/Gateway origins, explicit
`serverId`, registered host and local `administratorProvisioningRef`. Do not add
a `tenantId` field to the manifest; the current initializer obtains the UUID
from reviewed administrator provisioning. Ordinary bot import cannot register a
tenant. Retain the existing reviewed configuration and initialization contract.

Configure each Gateway with its own UUID and Control HTTPS URL,
`publicDiscovery`, `listen: "0.0.0.0:8081"`,
`managementListen: "0.0.0.0:9091"`, `trustedTlsProxy: true`, tenant authorities
and `toolHosts: []`. Only the exact read-only `/v1/health/ready` route is proxied
from management. Never publish or proxy the remaining management API.

The per-tenant `gateway/model.env` requires an explicit
`TOOLGATE_WORKFLOW_MODEL_ORIGINS` allowlist and, for private roots, the existing
`TOOLGATE_WORKFLOW_MODEL_CA_FILE` path under `/etc/gateway`. Gateways have separate
model egress networks; providers must be reachable there. Tool Hosts and workers
remain on their tenant's restricted networks. A local model service is a
destination prerequisite, not provisioned by these files. An exported source
model declaration can need an explicitly reviewed destination-local endpoint
binding; importing does not transfer source secrets or infrastructure.

Give each Tool Host its own enrolled identity directory, store/work paths under
`/data`, canonical Control origin and permit public keys. Approve it independently.
Configure per-tenant filesystem quotas separately; named volumes impose no disk
quota. There is no Docker socket in this composition.

Use [graph.json](examples/graph.json) with your two registered UUIDs. Supply a
graph server certificate for `graph-builder`, its dedicated service CA and the
matching Control client identity. Copy each tenant's independently issued current
`toolgate-workflow-runtime` workload token into its worker file and the matching
graph reader file; do not reuse a tenant token across entries. Replace both files
on explicit credential renewal. Existing authorization/expiry checks apply.

Use rebuilt matching runtime and Quickstart artifacts for the per-reader
`origin`/`tokenFile` format. The obsolete single-origin reader configuration is
refused; there is no old-format conversion.

## Operate

From any directory on Windows:

```bat
D:\git\olo-toolgate\deploy\docker\multitenant\dev\validate.bat
D:\git\olo-toolgate\deploy\docker\multitenant\dev\start.bat
D:\git\olo-toolgate\deploy\docker\multitenant\dev\status.bat
D:\git\olo-toolgate\deploy\docker\multitenant\dev\stop.bat
```

On Linux/macOS use `sh <same-directory>/validate.sh`, `start.sh`, `status.sh`
and `stop.sh`. Validation checks protected layout/configuration and renders Compose
without printing private settings. It does not validate signatures, TLS custody,
live tenant authentication or execution; Control and the services enforce those
at runtime. Start does not claim readiness. Stop retains data and credentials.

No operation automatically resets a database, recreates keys or replays effects.
Review actual service status and bounded operator logs before explicitly retrying
a failed startup. Never paste private `docker compose config` or env output into
recording artifacts.

## Startup use-case options

Interactive `start.bat` offers infrastructure only, importing the incident-triage
bot, device-file processing, authority enforcement, publication A/B, portable
summary, scoped log triage, or the existing MCP proxy setup guide.
For import, choose Tenant A, B or both and provide the actual exported `.olo`.
Put a current destination tenant operator token in
`credentials/tenant-a/operator.token` and/or `tenant-b/operator.token`. Tokens
are read from private files and never passed in command-line values. The importer
observes tenant API startup with GETs only, then uses existing reviewed
import/provision/prepare/publish operations; missing custody/devices/grants remain
readiness blockers. The log bot needs independently enrolled client devices and
file scopes; importing it does not create those resources. MCP registration needs
your real upstream URL and scoped credentials in the tenant console and is not
performed automatically by the menu.

Unattended equivalent:

```powershell
python deploy/docker/multitenant/dev/manage.py start --use-case incident --tenant both --bot-file C:/Users/Anupriya/Downloads/my-export.olo
```

## Import the actual Quickstart export

Use [tenant incident triage](../../../../bot/configurations/tenant-incident-triage/README.md).
Customize and publish it in Quickstart; export and inspect those exact `.olo`
bytes. Sign into each destination tenant using its configured issuer, import
independently, resolve local model custody/authority, prepare and publish. Run
the same fixture and verify the saved customization, independent tenant results,
independent updates and denied foreign reads. No source account/identity copying
is part of this workflow.

The [owning deployment guide](../../../../docs/deployment/multitenant.md) defines
runtime/recovery gates. Passing Compose or component tests does not establish
the end-to-end transfer. Record source/image/bot identities and executed outcomes;
this wrapper adds no historical qualification claim.
