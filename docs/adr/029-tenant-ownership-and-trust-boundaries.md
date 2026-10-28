# ADR-029: UUID tenant ownership with shared management and isolated execution

**Status:** Accepted.

Quickstart and registered subdomains need the same authorization model. A host
name, a user-supplied tenant header or a deployment-wide endpoint setting cannot
establish ownership of a device, database record or secret.

Use a shared management plane and PostgreSQL tables with dedicated tenant
execution pools. The default organization is an ordinary tenant with one
server-generated, immutable UUID. Store its canonical UUID string in existing
`tenant_id` columns. `quickstart`, `default` and organization names are aliases.
SQLite supports one default tenant; shared hosting requires PostgreSQL.

Provision explicitly through a protected platform boundary. Registry and host
ownership are platform metadata; tenant revision, authorization and cache clocks
live in a separate RLS-protected authority row. Runtime writes cannot create a
tenant or missing authority implicitly. `PostgresStore.transaction(...)` sets
transaction-local scope before tenant SQL. Forced RLS, non-null ownership,
ownership keys and foreign keys supplement application authorization. Runtime
database roles cannot enumerate the registry or bypass RLS.

Copy common and tenant-specific initialization layers into independent tenant
records once. Persist the receipt and activation atomically. Configuration import
does not overwrite settings on every start. This is an empty-database first
release with one Flyway V1, without backfill or identity compatibility adapters.

Resolve a registered HTTP authority to one tenant and require authenticated JWT
and device identity to agree with it. Host routing itself grants no authority.
Unknown and suspended tenants have the same anonymous response. A device
certificate identifies its tenant on protected routes; the optional UUID header
is only a consistency assertion. Gateway credentials and endpoint journals bind
one UUID. Changing a saved tenant requires explicit purge and new enrollment.

Discovery uses a v2 signed envelope and a per-tenant discovery key ring, separate
from that tenant's device CA and all deployment/server signing authorities.
Clients verify the expected origin and UUID before credentials or installation
writes. Signer rotation requires preannouncement; issuer overlap and retirement
are authoritative even when an old CA remains in listener trust. Native hosts,
installers and Chrome propagate the verified tenant context.

Tenant vault versions have independent encryption and retry keys. Encryption
context binds tenant, secret and version. The protected local KEK provider wraps
those keys outside the shared database; it is not an external KMS/HSM service.
Secret delivery checks the current consumed permit, invocation, device and live
authority before decryption. Shared Control and its platform custodian remain
trusted across the tenants they serve; this boundary does not contain compromise
of that application identity.

Execute customer code in dedicated pools with separate mounts, networks and
resource limits. Shared Control must not execute that code or expose its custody
to workers. Tenant admission, accounting and paginated background work bound
shared resources. Replica-local limits and physical disk enforcement retain the
deployment boundaries documented in the operations guide.

Restore only into a different empty offline destination using the matching
schema, independently retained live authority/key ledgers and two pinned
reviewers per tenant. Both SQLite and PostgreSQL quarantine restored runtime
authority and leave UUIDs SUSPENDED. Old discovery signers and device CAs become
retired tombstones. Fresh identity publication while suspended requires a
retained restore floor and quarantine audit, a fully retired prior inventory,
new key IDs and the next generation. It cannot revive old IDs. Resume requires
an active discovery signer and device CA; clients require fresh trust and device
approval. Normal rotation still requires preannouncement. Traffic cutover and
fencing the old source are explicit operator responsibilities.

The [deployment guide](../deployment/multitenant.md) and
[qualification record](../deployment/multitenant-qualification.json) record the
implemented boundaries and tests. Native feature qualification does not publish
a signed release or replace RC admission. A customer cluster, DNS and custody
rollout requires its own validation; accepted design and test gates do not claim
that the implementation has already operated as a production SaaS service.
