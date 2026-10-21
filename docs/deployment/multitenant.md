# Multitenant deployment and qualification

Use a persisted default tenant for a local evaluation, and explicit tenant provisioning for shared subdomains. Every stored tenant identity is a canonical UUID string. `quickstart`, `default` and customer names are aliases; hostnames resolve to the registered UUID. Adding a DNS record never creates a tenant.

| Deployment | Database | Execution boundary |
| --- | --- | --- |
| Local Quickstart | SQLite, or explicitly configured PostgreSQL | One evaluation composition; no HA claim |
| Shared Control | PostgreSQL with forced tenant RLS | Dedicated Gateway and Tool Host pool, custody and data volume per tenant |

The supported database baseline is an empty database initialized from the matching release's single Flyway V1; Quickstart uses its matching SQLite V1. Unsupported development histories fail without baselining, backfill or checksum repair. Initialize each UUID once. Common, tenant-specific and layered imports use explicit reviewed inputs and one transactional receipt. Removed overwrite/default-ID settings are rejected. Later changes use reviewed administration, rather than startup reseeding.

## Provision and route

1. Prepare separate migration, runtime and platform database principals. Runtime inherits only `toolgate_control_runtime`; the protected operator inherits `toolgate_control_platform`. Use verified PostgreSQL TLS and separately mounted platform-password custody. Keep the operator job inaccessible to customer executors.
2. Prepare exact hostnames and canonical HTTPS Control/Gateway origins for each tenant, for example `tenant1.xyz.com` and `tenant2.xyz.com`. Allocate a separate device CA, discovery signer and executor credentials per UUID. TLS server certificates and tenant device/discovery authorities have separate purposes.
3. Preview and provision the protected manifest using [tenant operations](../control-plane/tenant-operations.md). The coordinator registers PROVISIONING, validates imports/custody, writes initial authority and runtime profiles, then activates atomically. Ordinary tenant writes cannot create or activate a registry row. Existing receipts are not replayed.
4. Deploy the [shared Compose topology](../../deploy/compose/Multitenant/README.md), or the [Helm inputs](../control-plane/tenant-routing.md). Preserve direct device TLS/mTLS through the Control listener; the example TCP stream forwards the unchanged handshake. Do not translate forwarded certificate headers into device authentication. Keep management listeners private and route each Gateway only to its configured UUID.
5. Give each Tool Host its own protected store, work directory, enrollment and volume. Remote hosts pull dispatches through their authenticated Control connection. Set CPU, memory, process and execution-concurrency limits; provision a real filesystem quota for each tenant volume. Docker named volumes do not impose disk quotas. Customer builds remain outside shared Control. See [capacity boundaries](../control-plane/tenant-capacity.md).
6. Approve a host's displayed enrollment code and key fingerprint, then independently review runtime grants. Device approval and management roles create no runtime permission. Use independently managed bindings for independently deployed packages; a deployment profile reconciles the package allowlist of the bindings it explicitly owns.

The selected origin is part of the client trust boundary. Install the matched v2 client/extension artifacts. Discovery verifies UUID, exact origins, lifetime, signer continuity and the tenant's device authorities before enrollment. A configured UUID is an assertion, not authentication. Protected device requests derive ownership from the certificate; the UUID header remains optional and must agree when present. Wrong-tenant origins, credentials, certificates and native contexts are refused without automatic rebinding. Unknown and suspended public hosts have the same unavailable response.

## Secret and key custody

Secret ciphertext, independently random encryption/retry keys, key versions and delivery authorization are tenant-scoped. Associated data binds the ciphertext to its tenant and record. Secret delivery also requires the current invocation fencing token. Database ownership and RLS apply to both values and wrapped key metadata.

The current wrapping provider is an operator-managed protected local KEK ring; it is a deployment trust boundary, not an implemented external KMS/HSM service. Control custody can unwrap the tenants it serves. Separate signing/provider access, restrict operator custody and keep wrapping material outside shared database backups. Follow [vault custody](../control-plane/tenant-vault.md) and [discovery/device key rotation](../control-plane/tenant-identity-custody.md) for versioning, rewrapping, preannouncement, overlap and retirement. Do not retire a vault key while retained ciphertext still requires it.

## Operate, recover and cut over

Suspend through the protected UUID-confirmed operator, fence its executors and reconcile in-flight effects. Suspension denies new authenticated work; immediate termination of already running arbitrary effects is not promised. Other tenants remain active. Keep authority and retirement ledgers independently of backups.

For PostgreSQL, use the [recovery runbook](../control-plane/tenant-recovery.md) for full, single-tenant or selected-tenant recovery. Every path requires the current layout-3 manifest, matching V1 hash, complete UUID/key inventory and two externally pinned reviewers per archived UUID. Restore into a different empty database with runtime CONNECT revoked. There is no inventory-free single-tenant recovery path or live database merge.

For SQLite Quickstart, stop the composition and take the supervisor's offline backup under its exclusive volume lock. The layout-1 manifest contains `files` and `tenantRecovery`: UUID, full identity/vault key inventories, cache-generation floor and approval-clock floor. Refresh that metadata from the independently retained final ledger after fencing; the old snapshot cannot establish current retirements. Two independent reviewers sign the whole manifest and external authority/session/credential/snapshot floors. Mount their review/trust independently and restore into an empty writable data volume.

Both recoveries suspend the target, retire restored device/discovery signers above the reviewed generation floor, preserve post-backup tombstones and encrypted records, advance authority floors and quarantine outstanding work. Ciphertext tied to a retired key is rejected. Finished results and packages remain stored; access still requires fresh authorized credentials and reviewed grants. Publish fresh higher-generation identity custody, reapprove devices, re-establish reviewed management access and review intended runtime grants before activating or cutting over DNS. Keep the old source fenced; switching traffic back to an old database is not a supported rollback.

SQLite stages on the destination volume, so recovery works with a read-only container root filesystem. An interrupted file publication leaves `restore.pending`, which prevents startup and another backup. Retain that volume for restricted diagnosis and recover again into a new empty volume. Never delete the fence to force startup.

## Qualification evidence

Run from a clean checkout with matching genuine six-target client assets. The commands create only disposable test containers/databases/volumes; service installation runs only on disposable native CI runners.

```sh
python tools/quickstart/check.py --build --client-assets deploy/client-assets/release
python tools/quickstart/check.py --postgresql --no-browser
python tools/tenancy/check.py --image IMAGE --output build/quickstart/tenant-runtime-evidence.json
python tools/tenancy/check.py --postgresql --image IMAGE --output build/quickstart/tenant-postgresql-runtime-evidence.json
python tools/tenancy/recovery_check.py --output build/control/tenant-recovery.json
python tools/control/helm.py
python tools/tenancy/deployment_check.py --image IMAGE
```

The full SQLite gate includes real image backup, independent signed restore, invalid-review rejection and retained package/ciphertext checks. The PostgreSQL full flow covers real packages, reviewed HUMAN/SERVICE/ASK effects, a pull-mode host, revocation and restart. The shared TLS fixtures cover colliding IDs, optional UUID assertions, foreign certificates, signer/CA rotation, protected provisioning/lifecycle/capacity updates and an unaffected second tenant. Replay each exported public evidence file with the production verifier:

```sh
TOOLGATE_TENANT_RUNTIME_EVIDENCE=build/quickstart/tenant-runtime-evidence.json cargo test --locked -p olo-toolgate-tenant-identity --test runtime_cutover -- --ignored
```

Repeat for PostgreSQL evidence. Replay uses the captured packet's issue time; production verification uses the current clock.

The feature [native qualification workflow](https://github.com/olo-labs/olo-toolgate/actions/workflows/tenant-qualification.yml) runs macOS, Linux and Windows on both native architectures. It verifies target/source, strict lint, client tests, actual service lifetime, packaged installation and cleanup. Windows additionally checks the scoped Chrome host, foreign-tenant rejection, repair and explicit purge. Windows ARM uses x64 Python for fixture dependencies; Rust, installed services and verified payloads remain native ARM64. The evidence collector requires all six targets, one source SHA, successful cleanup and exact installer hashes from the genuine public bundle. Missing, mixed-source or tampered evidence cannot pass. This workflow publishes no release and leaves main/tag RC admission in place.

Retain the workflow run URL, native evidence, immutable image IDs and SHA-256 checksums of runtime/recovery reports with the reviewed PR. Template/schema validation is not a live Kubernetes rollout; verify the target cluster's actual DNS, certificates, custody permissions, database TLS and storage quotas before admitting customer traffic. The [plan](../../plan.md) records accepted gates and remaining work.
