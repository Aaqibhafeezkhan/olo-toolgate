# Protected tenant operations

Tenant administration is a local operator command, separate from tenant administrator HTTP authority. It opens no listener, runs no schema migration, reads no ambient environment configuration and never borrows the runtime database identity. Run it from a restricted operator job/container with the matching packaged Control application and explicitly mounted platform inputs. Customer processes and Gateway containers must not receive those inputs or execution access to the operator job.

Create a private `operator.json` with `properties` containing explicit configuration values:

```json
{
  "properties": {
    "toolgate.tenancy.mode": "subdomain",
    "quarkus.datasource.jdbc.url": "jdbc:postgresql://postgres.internal:5432/toolgate?sslmode=verify-full&sslrootcert=/etc/operator/database-ca.pem",
    "quarkus.datasource.username": "control_app",
    "quarkus.flyway.username": "control_migrator",
    "toolgate.control.platform.username": "control_provisioner",
    "toolgate.control.platform.password-path": "/etc/operator/platform-password",
    "toolgate.control.vault.wrapping-key-ring-path": "/etc/operator/vault-key-ring.json",
    "toolgate.control.identity.custody-root": "/etc/operator/tenant-identity",
    "toolgate.control.identity.publication-path": "/etc/operator/publication.json",
    "mp.jwt.verify.publickey.location": "/etc/operator/jwt-public.pem",
    "quarkus.http.ssl.certificate.files": "/etc/operator/control-tls.crt"
  }
}
```

Include the deployment's policy, Fleet and package private-key paths when publishing identity keys so existing purpose-separation checks cover them too. Custody checks enforce protected ancestry and bounded regular files. The PostgreSQL identity must be a member of `toolgate_control_platform`, distinct from runtime/migration identities, with no superuser, RLS bypass or schema CREATE privilege. Verified database TLS is required. Development-only SQLite requires an existing initialized database and `developmentSqlite: true`; loopback PostgreSQL without TLS additionally requires `developmentLoopback: true`.

```sh
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json preview /etc/operator/new-tenants/manifest.json
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json provision /etc/operator/new-tenants/manifest.json
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json inspect tenant-one
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json suspend tenant-one --expected-uuid <registered-UUID>
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json resume tenant-one --expected-uuid <registered-UUID>
python tools/tenancy/admin.py --app /app --connection /etc/operator/operator.json publish-identity /etc/operator/publication.json
```

Without Python, invoke `io.ololabs.toolgate.control.adapter.TenantOperator` with Java's classpath containing `/app/app/*`, `/app/lib/main/*` and `/app/lib/boot/*`, separated by the platform classpath separator. Arguments are command, protected operator JSON, target, and the expected UUID for lifecycle changes. No password, key value or DSN belongs in argv.

Preview validates the complete manifest/review/layer set and reports only UUID, alias, action and digest, without registering tenants. Provision uses the exact startup coordinator, validates all newly requested inputs before registration, and commits independently per tenant. Existing initialized tenants return `ALREADY_INITIALIZED`; changing files cannot re-import settings. Failed tenants remain unavailable in PROVISIONING with a bounded diagnostic; a nonzero operator exit requires inspection and an explicit retry. The initial schema must already exist under Flyway. Initializer inputs and trust remain protected and independently reviewed.

Roll public TLS client trust roots out before activating new device CAs. Publish each tenant's independent identity keys through the existing generation/preannouncement service. Provisioned tenants without published usable identity keys cannot enroll devices or serve usable signed discovery. Verify canonical HTTPS discovery and approve dedicated Tool Hosts before enabling workloads. This workflow creates no public tenant-registration endpoint.

Suspend/resume requires the registered UUID as well as its alias. It serializes through the registry/authority transaction, advances authorization epochs, and emits audit/outbox events. New authentication-dependent actions and dispatch are denied while suspended; public unknown/suspended hosts use the same 404 envelope. Existing invocations lose authority at their next protected boundary. Immediate operating-system process termination is not claimed. Resuming cannot revive old permits. Deletion, retention and key destruction remain separate reviewed operations.

See the [shared deployment example](../../deploy/compose/Multitenant/README.md), [identity custody](tenant-identity-custody.md), and [initialization rules](tenant-isolation.md).
