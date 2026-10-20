# Tenant capacity

Control enforces admission after resolving a registered tenant. `toolgate.capacity.requests-per-second` defaults to 1000 and `toolgate.capacity.http-concurrency` to 64. These are per tenant, per replica; deployments must account for replica count when selecting limits. Exhaustion returns 429 with `Retry-After: 1`. The bounded 4096-owner admission map retains active leases and fails closed when full. A response ending or disconnecting releases its lease. Health endpoints use the separate management path.

`toolgate.capacity.database-concurrency` defaults to 8 with the default 16-connection runtime pool. Admission happens before borrowing a connection and releases on every transaction outcome. Configuration must leave connections for other tenants. Platform operations use separate credentials and a separate pool. This bounds a single tenant's concurrency; it does not promise weighted scheduling across arbitrarily many simultaneous tenants or a deployment-wide rate counter.

The RLS-protected `control_tenant_capacity` row accounts transactionally for package bytes, server documents, encrypted secrets, invocation events, authoring jobs, MCP requests, enterprise invocations and Tool Host dispatches. Defaults are 1 GiB of payload bytes, 100,000 stored rows and 256 queued rows. Each queued row reserves 16 MiB from the same byte budget for completion output. Directory configuration has its existing 512-record/1 MiB bounds. Lifecycle audit, outbox and security tombstones remain available when customer capacity is full. Payload accounting is a logical budget, not a measurement of PostgreSQL pages, indexes, WAL or executor files; provision disk, retention and database monitoring separately.

Owner triggers update usage in the same transaction as the payload. Tenant runtime credentials can read their capacity row under RLS but cannot write counters or limits. Failed reservations roll back payload and usage together. Durable capacity exhaustion fails the transaction with the existing bounded persistence error; callers must apply backoff rather than immediately retrying. Reducing a limit below current usage is rejected.

Inspect through the local operator:

```sh
python tools/tenancy/admin.py --app /opt/control/quarkus-app --connection /run/operator/config.json capacity tenant-a
python tools/tenancy/admin.py --app /opt/control/quarkus-app --connection /run/operator/config.json capacity tenant-a --expected-uuid TENANT_UUID --limits-file /run/operator/limits.json
```

The protected limits file has exactly `maxStoredBytes`, `maxStoredRows` and `maxQueuedRows` integer fields. Updates require the expected UUID and append a tenant audit event. There is no customer-facing limit editor.

The shared Compose example enforces CPU, memory and process limits for each dedicated executor pool. Set Tool Host execution concurrency and per-process memory below those limits (for example, two 512 MiB processes in a 2 GiB pool). Provision a separately quota-limited backing filesystem for each tenant's data volume; Docker local named volumes alone impose no disk quota. Customer builds remain outside shared Control. The deployment proof exhausts one pool's memory and checks that the other still serves requests, in addition to checking mount and network isolation.

The watchdog and package maintenance loops page through at most 32 tenants per tick, retain cursors, and isolate per-tenant failures. Registry enumeration is platform-only. Use bounded aggregate metrics; tenant UUIDs must not become unbounded metric labels.
