# Tenant recovery

Quickstart SQLite recovery and PostgreSQL recovery both require two independent signed reviews, a matching release schema, and externally retained live authority and key-retirement records. Recovery leaves each restored UUID SUSPENDED. Private custody and archives require separate access controls; a successful restore does not authorize runtime traffic.

## Default-tenant SQLite

Use Quickstart's packaged `--backup` and `--restore` commands described in [the deployment guide](../deployment/quickstart.md). Stop and fence the source before the final backup and review. Restore into a different, empty data volume with the same image release. The layout-1 manifest must include `tenantRecovery`: the canonical `tenantId`, complete `identityKeys` and `vaultKeys`, `cacheGenerationFloor` and `approvalClockFloor`. Retain the live ledger independently and review its current values, including retirements after the backup. Inventory-free development archives are rejected.

The packaged restore validates the archive and both reviewers before publishing data. It stages quarantine inside the writable destination volume so the container root can remain read-only. A `restore.pending` marker fences a partially published destination: normal startup and backup refuse it. Discard a failed destination and restore into another empty volume after resolving the failure. Do not delete the marker to start a partial restore.

The same authority, key-retirement, uncertain-work and activation rules below apply. `tools/tenancy/sqlite_recovery_check.py` qualifies the packaged command against a stopped disposable image volume; the full Quickstart image check invokes it automatically.

## PostgreSQL

Shared PostgreSQL recovery uses a consistent custom-format archive, the matching release schema, externally pinned reviewers, and live authority/key-retirement records. Restore into a **different, empty, offline database**. Individual recovery restores selected UUIDs into that separate deployment; it never merges a snapshot into the running shared database. Other source tenants continue using their existing database and executors.

The restore operator is a privileged platform custodian with access to the entire archive. Protect backups with encryption at rest, restricted access and retention controls. A tenant selection is not permission to give a customer the shared archive. Customer self-service recovery and automatic DNS cutover are outside this interface.

## Prepare and review

1. Capture a consistent `pg_dump -Fc` using the platform backup principal authorized to read every tenant through forced RLS. Do not grant BYPASSRLS or backup privileges to runtime credentials. Use matching PostgreSQL major client/server versions; qualification exercises PostgreSQL 17.
2. Retain source authority floors and retirement ledgers independently of the archive. Before an actual tenant cutover, suspend its source UUID through the protected operator and drain or reconcile in-flight work. Fence its old Gateway and Tool Host pool. Capture final floors after fencing so no later source write can outrun the review. Other source tenants remain active. Disaster recovery requires the independently retained ledger if the source is unavailable.
3. Construct a layout-3 manifest with exactly `layoutVersion: 3`, `databaseSha256`, `schemaSha256`, `tenants` and `keyInventory`. Hash the custom-format archive and the exact release's `apps/control-plane/src/main/resources/db/migration/V1__initial_schema.sql` file bytes. Include every archived canonical UUID in `tenants`, even for an individual recovery. All PostgreSQL recovery, including a database containing one UUID, uses this format. Inventory-free layout-2 archives, old development schemas, compatibility adapters and backfills are rejected. Only ACTIVE/SUSPENDED tenants can be selected; exclude unfinished PROVISIONING tenants and explicitly provision them later.

`keyInventory` maps each UUID to exactly `identityKeys`, `vaultKeys`, `cacheGenerationFloor` and `approvalClockFloor`. Export the complete current key rows, including post-backup keys and retired tombstones, through the live platform boundary under its transaction-local tenant context:

| Source | Exact inventory row fields |
| --- | --- |
| `control_tenant_identity_keys` | `purpose`, `key_id`, `state`, `provider_reference`, `public_material`, `key_fingerprint`, `generation` |
| `control_tenant_vault_keys` | `version`, `state`, `provider_reference`, `wrapped_encryption_key`, `wrapped_retry_key` |
| `control_tenant_authority` | `directory_cache_generation` becomes `cacheGenerationFloor` |
| `control_approval_clocks` | `observed_at` becomes `approvalClockFloor` |

Inventories contain public identity material and encrypted key wraps; they must contain no private signing key, unwrapped DEK or plaintext secret. Keep the manifest protected because wrapped custody and tenant metadata are sensitive. An inventory that omits a backup key, changes immutable identity material, or revives a retired key is rejected. Do not derive these live assertions solely from the archived snapshot.

4. For every archived UUID, use `tools/enterprise/restore.py prepare` with independently observed `directoryRevisionFloor`, `authorizationEpochFloor`, `snapshotSequenceFloor`, `sessionEpochFloor`, `credentialEpochFloor`, `retiredCredentialDigests` and `retiredRecordIds`. Two independent RSA reviewers sign each authorization using the existing `sign` and `assemble` commands. Each authorization covers the hash of the entire manifest, including every tenant's key inventory. Reviews expire after at most fifteen minutes and are verified again after archive import; sign close to the restore operation.
5. Bundle the assembled tenant reviews:

```sh
python tools/enterprise/restore.py bundle --reviews /review/tenant-a.json /review/tenant-b.json --output /review/fleet.json
```

The review set must exactly match all archived UUIDs. Omitting tenant B's review is rejected even when only A is selected. Pinned public reviewer trust comes from a separately mounted source, never an archived key chosen by the backup. Inputs are bounded to 2 MiB per review/manifest file; divide oversized backup sets into reviewed recovery units before using this interface.

## Restore offline

Create an empty database owned by a dedicated restore login. This owner must hold `toolgate_control_platform`, be subject to forced RLS, and have neither SUPERUSER nor BYPASSRLS. Keep its custody separate from the running deployment. It must be able to restore ownership and revoke database CONNECT. Matching role definitions must already exist in the recovery cluster.

Supply a protected target JSON containing explicit `host`, `port`, `dbname`, `user`, `password`, `sslmode: "verify-full"` and the pinned `sslrootcert` path. No password or DSN appears on the command line. Mount target credentials and pinned reviewer trust independently of the archive directory. Use the matching release's Python requirements and PostgreSQL `pg_restore` executable.

```sh
# All declared tenants in the separate destination.
python tools/enterprise/postgresql_restore.py \
  --archive /backup/database.dump --manifest /review/manifest.json \
  --review /review/fleet.json --trust /trust/reviewers.json \
  --connection /run/recovery/target.json

# Only A in a separate destination; source B is untouched.
python tools/enterprise/postgresql_restore.py \
  --archive /backup/database.dump --manifest /review/manifest.json \
  --review /review/fleet.json --trust /trust/reviewers.json \
  --connection /run/recovery/target.json --tenant TENANT_A_UUID
```

Repeat `--tenant` to select several UUIDs; omission selects all. CONNECT is revoked from PUBLIC and other nonsuperuser roles before importing. Concurrent sessions and a nonempty target are rejected. Runtime roles are never reconnected. Failure after import leaves the destination inaccessible to runtime credentials; discard that isolated failed target and use another empty database after resolving the review or custody issue.

Inside one exclusive transaction, the fleet path checks the complete table inventory and forced RLS, removes unselected archived customers in foreign-key order, and quarantines every selected tenant. Deliberate removal occurs only in the new offline database: owner retention triggers are disabled transactionally while deleting unselected customers, then enabled again. Foreign keys and RLS remain enabled. Failure rolls back pruning and trigger changes. Customer counters come from the consistent archive and normal accounting triggers adjust them as queued work closes; they are never loaded twice.

Recovery advances directory/authorization, session/credential, snapshot, cache-generation and approval-clock floors. It preserves audit, consumed nonces, identifier/credential tombstones and encrypted secrets; removes post-backup retired identities; disables runtime grants/workloads and device approvals; and clears old execution leases. Queued work expires. Dispatched or executing work becomes `OUTCOME_UNKNOWN`, including its task projection, and requires reconciliation before retry. Pending authoring/MCP work cannot restart from the snapshot.

Every restored discovery signer and device CA becomes RETIRED above the externally reviewed generation. Post-backup key tombstones are retained. Provision fresh tenant identity keys and device approvals before activation. Vault inventory follows the reviewed lifecycle without decrypting customer secrets. If archived ciphertext still references a key retired in the live ledger, recovery fails closed: obtain a reviewed backup whose secrets were re-encrypted while that key remained available. Restoring ciphertext never authorizes reviving its retired DEK.

## Review and cut over

Keep recovered UUIDs SUSPENDED and CONNECT revoked while validating configuration, custody, authority floors and recovery audit. Supply independently protected matching KEK/provider custody; rotate service credentials, tenant signers and device CAs; re-enroll or reapprove devices. Use a fresh dedicated Tool Host pool and reconcile uncertain external effects. Do not copy executor leases or authorize automatic retries.

Use the protected operator to publish a higher-generation identity set while the recovered UUID remains SUSPENDED. This recovery publication requires the retained restore floor and quarantine audit, a completely retired old identity inventory, fresh key IDs and the next generation. Retired IDs cannot be revived. Supply a fresh protected publication packet/custody and matching TLS listener trust instead of replaying the archived publication packet. Repeated publication of that exact set is idempotent; subsequent rotation follows normal preannouncement rules. Resume rejects an existing identity inventory without both an active discovery signer and device CA.

Resume only the intended recovered UUID after review. Grant runtime CONNECT only when the destination is ready to start its separately configured Control. Route that tenant's hostname/Gateway through the reviewed deployment change. Never resume the former source copy of that UUID: this interface does not provide a distributed cross-database activation lock or automatic DNS cutover. Source tenant B's data, authority and custody remain in its original deployment.

`python tools/tenancy/recovery_check.py --output build/control/tenant-recovery.json` qualifies real PostgreSQL 17 shared and selected-tenant archives. It checks complete independent reviews, source preservation across every owned table, key retirements, ciphertext retention, monotonic authority, revoked runtime connectivity on successful/failed imports, old sessions/credentials, task/lease quarantine, and rejection of tampered/schema-mismatched/nonempty targets. Control CI runs this gate. Full image/OS and operational cutover release qualification remains phase 8 in `plan.md`.
