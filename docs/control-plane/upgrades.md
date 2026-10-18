# Database initialization and upgrades

The first release initializes an empty PostgreSQL database with Flyway
`V1__initial_schema.sql` and an empty Quickstart SQLite database with `V1.sql`.
Both files define the complete current schema directly, including invocation
tasks, package provisioning, directory caching and console device sessions.
Application initialization supplies the current tenant configuration separately.

Pre-release migration histories and database files are unsupported. For a
pre-release development installation, start with a new empty database or a new
Quickstart data directory. The application does not reset existing databases.
Do not repair or baseline an old database to make it appear to match V1.

Deploy matching Control, Gateway, managed client, console and contract artifacts.
Future released schema changes start at V2 and append migrations without changing
the released V1 files. SQLite records the V1 checksum and refuses changed or newer
schema history; Flyway validates its migration history on initialization.

Runtime credentials cannot change schema or update/delete audit, immutable release
or credential-history records. Keep separate migration and runtime database roles.
Apply PostgreSQL migrations through the database owner; never use Flyway clean as
recovery. Active writes, audit and revision/outbox changes commit atomically.

Helm rollback changes application manifests, not database history. Restore only
into an isolated empty destination using external review trust, live monotonic
floors and post-backup retirement evidence; quarantine prior runtime/management
authority before reconnecting Core.
