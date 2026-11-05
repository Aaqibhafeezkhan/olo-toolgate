# Fresh schema design specifications (design gate D2)

Current invocation/task storage lives directly in the single PostgreSQL and
SQLite V1 initial schemas under apps/control-plane/src/main/resources/db/.
Only empty databases or matching current-schema stores are supported.

The PostgreSQL and SQLite kill-switch.sql fragments describe the unimplemented
[kill switch](../kill-switch.md). They are not database migrations, upgrade
steps or runtime-loaded schemas. When that feature is implemented, fold its
reviewed objects into the fresh initial schemas and update their current
identity/checksum. Never append an upgrade chain or convert an older store.

The current-schema specification tests assemble an isolated empty test database
from the current initial schema and the appropriate unshipped design fragment.
They exercise the same accept/refuse scenarios on both dialects. Applying a
fragment in this fixture does not support upgrading any deployed database.
