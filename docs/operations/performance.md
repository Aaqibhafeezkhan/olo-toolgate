# Container performance

Quickstart now defaults to `TOOLGATE_CONTROL_CLIENT_CHECKIN_MS=2000` with no
jitter. Enterprise retains its 2000 ms default with 20% jitter. Explicit environment
values still override these defaults. Idle job pickup can take one check-in interval;
the existing execution deadline and offline threshold checks still apply.

## Directory cache and tenant isolation

Each Control process populates its directory cache on first access. Entries expire
60 minutes after loading, even when frequently accessed. The cache retains at most
128 tenants and an estimated 16 MiB of directory data, evicting least recently used
entries when either bound is reached. Directory records and reference collections
are immutable. The device adoption digest is computed once for each cached version.

Every load reads the tenant's current configuration revision and cache generation
from the database before reusing an entry. The generation is maintained by database
triggers on directory records and server settings, covering insertion, updates and
deletion by administrators, clients and other Control replicas. This includes identity
observations that intentionally do not advance the configuration revision. The TTL
is an eviction policy, not a window in which stale authority is accepted. Endpoint
state, credentials, settings, authorization epochs and execution decisions continue
to use their existing current database checks.

Writes clear the transaction's memoized data, so subsequent reads see their own
changes. Shared entries are published only after a successful commit. A rollback
cannot publish its data, and a delayed reader cannot overwrite a newer cached
generation. Failed database checks fail closed. PostgreSQL uses migration V22;
Quickstart SQLite uses migration V15. Apply migrations before running the new binary.

## SQLite connection reuse

The Quickstart service owns an Agroal pool with a minimum of one and maximum of
four SQLite connections and a three-second acquisition timeout. Keeping one
connection open avoids checkpoint cleanup caused by repeatedly closing the last
connection. WAL and `synchronous=FULL` remain enabled.

Each transaction explicitly sets SQLite's transaction mode and `query_only` state.
Before returning a connection, outstanding work is rolled back, auto-commit is
restored, `query_only` is disabled and the transaction mode is restored to IMMEDIATE.
The service closes its own pool on shutdown. Offline migration/recovery callers of
`SqliteState.open` retain the unpooled adapter; managed services use `openPooled`.

Run `SqliteReuseBenchmarkTest` to compare 200 durable transactions in each mode.
This microbenchmark measures connection/commit overhead, not application capacity.
On the development Windows host on 2026-10-15, the final run measured 12.83 ms per
unpooled commit and 1.69 ms per pooled commit (7.6 times faster). An earlier run
measured 13.73 ms and 1.63 ms respectively. Both modes verified `synchronous=FULL`.

## Diagnostic telemetry and logging

Protocol diagnostics share a buffer per protected client state directory. The newest
100 entries, up to 64 KiB, remain available immediately to the read-log tool. The
buffer is persisted every five seconds, after 32 new entries, and on close. A sudden
process failure can lose buffered diagnostic packets. Execution journals, replay
protection and database audit records retain their existing durability guarantees.

Production Compose definitions use Docker's `local` log driver with 10 MiB files and
three rotated files per container. Rotation applies to service logs; database audit
retention is unchanged. Quickstart and device health checks run every 30 seconds.
The Quickstart probe imports only the HTTP client instead of the full supervisor.
Five probes against the existing demo measured median process times of 241 ms for
the supervisor probe and 135 ms for the HTTP-only probe on the same host.
The Marketplace helper's shell probe checks the HTTP status without starting a JVM.

Rebuild the affected images and recreate containers to activate source, environment,
logging and health-check changes. Existing running containers keep their image and
creation-time logging configuration until recreated.
