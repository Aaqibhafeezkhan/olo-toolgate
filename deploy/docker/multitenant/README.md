<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Shared-Control multitenant deployment

Use [dev](dev/README.md) for the Docker deployment entrypoints. Its private custody
lives at `deploy/docker/multitenant/dev/credentials`, separate from Quickstart.
The topology has one PostgreSQL and Control, dedicated tenant Gateways, Tool Hosts
and workflow workers, and a private shared graph-preparation service.

Export the actual customized `.olo` from Quickstart and import those same bytes
independently in each tenant. Destination identities, runtime credentials, model
secrets, grants and publication are local administration; they are not copied
from the source installation. Start with [tenant incident triage](../../../bot/configurations/tenant-incident-triage/README.md).

This is a deployment wrapper for existing administration and runtime contracts,
not a tenant password/identity provisioning service. See the
[qualification boundary](../../../docs/deployment/multitenant.md) before admitting
customer traffic. In particular, a rendered composition is not an executed
two-tenant export/import qualification.
