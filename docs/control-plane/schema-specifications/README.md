# Schema verification fixtures

Runtime storage is defined directly by the current PostgreSQL and SQLite V1
initial schemas under `apps/control-plane/src/main/resources/db/`. Only empty
databases and matching current-schema stores are supported.

The SQL fragments in this directory are isolated schema-test inputs. They are
not shipped migrations or an upgrade procedure. Their tests assemble disposable
empty databases and exercise the declared acceptance/refusal rules on each
dialect. This does not qualify an upgrade of any deployed store.

Fold reviewed storage changes into the fresh initial schemas and update their
current identity/checksum and tests. Reject incompatible stores without conversion
or deletion; preserve current-schema recovery and immutable evidence.
