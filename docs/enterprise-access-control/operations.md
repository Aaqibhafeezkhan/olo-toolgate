# Enterprise operation and recovery

## Authority and incident response

Control is the current authorization authority. Inspect `GET /api/control/v1/access/status` for directory revision, epoch, published graph sequence and actual device acknowledgements; inspect `/access/operations` for unpublished outbox events, unknown effects and expired execution records. These endpoints require current management scope. An acknowledgement describes metadata adoption and supplies no permit.

Alert on unavailable current authority, repeated watchdog failures, growing unpublished snapshot events and nonzero unknown outcomes. Core metrics include `toolgate_control_unknown_outcomes`, `toolgate_control_unpublished_snapshot_events`, `toolgate_control_expired_effects_total` and `toolgate_control_watchdog_sweeps_total`. Keep management endpoints on restricted networks; metrics contain no resource, token or individual identity labels. Configure the protected audit/telemetry collector for retention.

Disable or revoke the relevant group, grant, identity or binding through reviewed configuration. Online dispatch, checkpoint, secret use and response checks observe current authority. Default groups remain enabled and carry no implicit grants. Immediate revocation blocks new effects; already completed external effects are preserved as evidence. A long-running operation must stop at the next protected checkpoint where safe and report the completed subset.

Effect permits last at most ten seconds with zero skew/grace. Gateway readiness observes Core within a five-second poll interval; every effect still requires current Core authority. Outages deny new effects. Synchronize clocks. No offline ALLOW or exactly-once external-effect guarantee is provided.

## Unknown outcomes

A durable reservation is single-use. A consumed effect that expires without reliable completion becomes `OUTCOME_UNKNOWN`. Preserve the runtime journal and downstream evidence; do not blindly resend destructive work.

Use Effect outcomes to propose an exact terminal state with the expected invocation revision, every confirmed resource, evidence digest and result digest. A different currently authorized checker reviews the exact proposal revision. Reconciliation changes durable outcome metadata, never issues a new effect permit or redispatches work. The certificate-bound native client may recover its local fence only from the exact authoritative terminal outcome. Current grants remain necessary for any subsequent new operation.

## Reviewed configuration

Directory writes create drafts with a digest, graph revision/epoch, changed records, affected groups and individuals. Submit, obtain the required independent current reviewers, then apply. Self-review, stale reviews, revoked reviewers and conflicting retries reject. Import and high-risk expansion require two reviewers. Reviews expire within thirty minutes.

Initialize an empty PostgreSQL or SQLite database from the release V1 schema. Pre-release databases and configuration formats are unsupported. Use current configuration export/import and simulation to review changes. Shadow evaluation has no effects, quota charge, approval consumption or secret disclosure. Rollback creates a new monotonic reviewed configuration.

## Backup and recovery

Use consistent database and custody backups with protected external storage. Keep public trust pins and observed live revision/epoch/sequence/session/credential floors outside the backup. Keep post-backup retired identifiers and credential digests in the external retirement ledger. An archive cannot select its own recovery authority.

For SQLite, `tools/enterprise/restore.py prepare`, separate `sign` invocations and `assemble` create a fifteen-minute two-reviewer authorization bound to the exact manifest and external floors/retirements. Mount the review and public trust separately and run the stopped supervisor's reviewed restore command into an empty independent destination. The restore verifies inventory/hash/path safety, advances authority, disables runtime grants/definitions/devices/workloads/custom groups/roles, clears management assignments, invalidates sessions and credentials, retains consumed nonces/audit/history and records uncertain executions. Post-backup retired identities and their dependent facts cannot be resurrected. The source backup is unchanged.

For every PostgreSQL recovery, including a single tenant, follow [the tenant recovery runbook](../control-plane/tenant-recovery.md). The current layout-3 manifest requires complete UUID reviews and live key-retirement inventory. Inventory-free layout-2 archives are rejected.

Run `tools/enterprise/postgresql_restore.py --archive BACKUP --manifest MANIFEST --review REVIEW_BUNDLE --trust TRUST --connection PRIVATE_TARGET_JSON` with psycopg 3 and matching PostgreSQL client tools. Single-tenant archives use the same one-entry review bundle and key inventory as shared recovery. The target must be a different empty database owned by a non-superuser restore principal in the platform role. Connection configuration requires `verify-full` TLS; trust/credentials must be mounted independently of the backup. Runtime CONNECT remains revoked on success and failure, and recovered tenants remain suspended. Re-establish reviewed management access, fresh signing/device custody and intended runtime grants before reconnecting customer traffic. The test-only loopback flag does not permit remote unverified database transport.

`tools/enterprise/recovery.py` prepares a separate management-only recovery packet. Each independent reviewer signs with their own protected RSA key; Control uses separately mounted trust. Existing-tenant recovery requires two distinct key holders, an exact live revision and a time window of at most one hour. It cannot restore runtime grants, rebind stable identity facts or enable a disabled account. Fresh empty-tenant installation is a separately pinned one-time initialization. Reapplying an accepted packet cannot reseed old authority.

## Supported execution boundaries

Device files-area operations use containment, restricted portable paths, bounded files, exclusive/atomic writes and symlink/hardlink protection. Web search checks public addresses, resolves once, pins sockets and TLS host names, and forbids proxies/redirects and private destinations. Group-scoped Vault delivery requires a consumed certificate-bound effect, current complete secret-use grants and the declared exact secret reference; values are never exposed in management lists or logs.

Managed OCI tools use pinned image/source/manifest digests, fixed argv, bounded JSON protocols, unprivileged read-only sandboxes, no host mount and no network. Arbitrary host shell, raw SQL, host filesystem mounts, unreviewed egress and generic secret-environment injection are not supported. Such requests fail closed. Introduce a structured provider/extractor and independently tested confinement before supporting a new protected effect kind. Nested/dynamic groups ([#240](https://github.com/olo-labs/olo-toolgate/issues/240)) and offline execution ([#239](https://github.com/olo-labs/olo-toolgate/issues/239)) are likewise unsupported; no inferred membership or offline lease is manufactured.

## Sequential implementation acceptance

Run `python tools/enterprise/finish.py` from the repository root with pinned Python
tooling, Java 21, Node, Docker and the repository's Rust/Helm tooling available.
Windows can use `TOOLGATE_DOCKER_TOOLS=1`. The runner executes source/contract checks,
Java conformance, actual Linux custody, fresh SQLite/browser/native effects,
production Gateway, fresh PostgreSQL runtime, production Control replicas,
reviewed PostgreSQL restore, deployment rendering, local SDK publication, source
scans, exact-image vulnerability scans/SBOMs and acceptance reporting in that order.
It stops on the first failure. Use `--only NAME` to repair and rerun a single gate;
partial success is explicitly recorded as a partial run.

Logs and pass/fail reports live under `build/enterprise/finish`. Fixtures create
their own uniquely named containers and volumes; they do not reset the debug
installation. `acceptance.py --write` maps all 96 scenarios to exact named JUnit
cases and required runtime evidence. Missing, failed or skipped tests remain open.
An automated case count is distinct from a passing integrated run, image review,
operator configuration and enterprise capacity qualification. The latency report
documents the actual small fixture and its CPU/memory/sample limits.

Trusted identity synchronization uses stable tenant/issuer/subject facts and the
reviewed canonical configuration import. It cannot merge identities by email,
silently activate a new User, invent nested membership or assign individual ACLs.
An identity-provider-specific SCIM connector is not supplied by this implementation.
