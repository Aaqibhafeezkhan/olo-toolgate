# Optional shared directory cache

Control defaults to its bounded process-local directory cache. Set cache mode to
`redis` to add an external Redis or Valkey cache shared by Control replicas. The
deployment does not provision a cache server. Use a standalone endpoint or a managed
proxy supporting RESP2 `AUTH`, `GET` and `SET ... EX` on database 0. Redis Cluster
redirects, Sentinel discovery and client-certificate authentication are not supported.

The database remains authoritative: every directory lookup checks its current tenant
revision and generation before consulting either cache. Changed generations use new
keys; old keys expire. Only successful, read-only directory snapshots are published
after transaction commit. Rollbacks and uncommitted writes cannot populate the shared
cache. Cache errors, expired/corrupt values and timeouts fall back to database reads;
database errors still fail closed. Gateway authorization always requires live Control.

Shared values are encrypted and authenticated with AES-256-GCM. A dedicated key shared
by all Control replicas protects directory data even from cache-side modification.
Never reuse a signing key, Vault key or another deployment's cache key. Malformed
configuration or unreadable custody files prevents startup; an unreachable server
does not. Runtime failures produce a warning at most once a minute per process.

## Environment settings

These settings belong to the deployment and cannot be changed by configuration import
or the settings API. Existing Docker images must be rebuilt with this implementation.

| Variable prefix `TOOLGATE_CONTROL_CACHE_` | Default | Meaning |
|---|---|---|
| `MODE` | `local` | `local` or `redis` (also for Valkey) |
| `HOST` | empty | Required server hostname/IP in Redis mode; no URL or credentials |
| `PORT` | `6379` | Server port, 1–65535 |
| `TLS` | `true` | Verified server certificate and hostname; disabling requires Control development mode |
| `USERNAME` | `default` | Redis ACL username |
| `NAMESPACE` | empty | Required unique namespace per database/deployment, up to 128 letters, digits, colons, underscores or hyphens |
| `PASSWORD_PATH` | empty | Absolute path to protected password file, up to 4096 bytes; trailing whitespace is removed |
| `ENCRYPTION_KEY_PATH` | empty | Absolute path to protected file containing base64 text for 32 random bytes |
| `CA_PATH` | empty | Optional PEM CA bundle; otherwise system trust is used |
| `TIMEOUT_MS` | `500` | Total deadline for one cache operation, 50–2000 ms, including DNS/TLS/authentication |
| `TTL_SECONDS` | `3600` | Shared entry lifetime, 1–3600 seconds; hits do not refresh it |

Use a dedicated ACL user restricted to the namespace's `:directory:v1:*` keys and
`GET`/`SET`. Control uses `AUTH` on each connection. Values are capped at 2 MiB and
512 records; larger snapshots use the database/local cache. Each process allows at
most eight simultaneous external operations; failures bypass the endpoint for five
seconds. No cache connection or secret is needed in local mode.

Keep replica clocks synchronized. Change the namespace after restoring an earlier
database or reusing a database for a different installation, since revision counters
may repeat. Rotate the encryption key by updating every replica; mixed keys cause
safe misses until rollout completes. Namespace changes also isolate cache format or
TTL changes during a rollout. Cache contents need no backup and may be evicted freely.

## Docker Compose

For GatewayControl, copy
[`env/.env.cache.example`](../../deploy/compose/GatewayControl/env/.env.cache.example)
to `env/.env.cache`, replace the host and namespace, and place the password,
encryption-key text and optional private CA under `custody/control`. The existing
read-only `/custody` mount exposes them to Control. Omit `CA_PATH` when system trust
is sufficient. Keep secret files readable only by the container's UID/group 65532 or
root, with no untrusted write access to files or parent directories. The example
file contains settings and paths only; never put passwords or keys in it.

For either QuickStart stack, set the variables in the stack's `.env`. Put protected
files under `env/` and use container paths such as `/external/cache-password` and
`/external/cache-encryption-key`. For example:

```dotenv
TOOLGATE_CONTROL_CACHE_MODE=redis
TOOLGATE_CONTROL_CACHE_HOST=cache.example.com
TOOLGATE_CONTROL_CACHE_TLS=true
TOOLGATE_CONTROL_CACHE_NAMESPACE=toolgate-evaluation-unique
TOOLGATE_CONTROL_CACHE_PASSWORD_PATH=/external/cache-password
TOOLGATE_CONTROL_CACHE_ENCRYPTION_KEY_PATH=/external/cache-encryption-key
```

QuickStart remains single-node/non-HA; separate SQLite databases must never share a
namespace. For production replicas, use the same external PostgreSQL database.

## Helm

Create an existing Kubernetes Secret with keys `password` and `encryption-key`. The
latter is the base64 *text* of 32 random bytes (Kubernetes Secret `data` adds its own
encoding; `stringData` accepts this text directly). Reference a separate Secret with
`ca.pem` only when using a private CA. The chart mounts these read-only and never
creates or embeds credentials in a ConfigMap.

```yaml
control:
  cache:
    mode: redis
    host: cache.example.com
    port: 6379
    tls: true
    username: toolgate
    namespace: toolgate-production
    credentialsSecret: control-cache
    caSecret: cache-ca # omit for system trust
    timeoutMs: 500
    ttlSeconds: 3600
  networkPolicy:
    cacheTo:
      - ipBlock:
          cidr: 192.0.2.10/32 # replace with actual cache endpoint peers
    dnsTo:
      - namespaceSelector:
          matchLabels:
            kubernetes.io/metadata.name: kube-system
        podSelector:
          matchLabels:
            k8s-app: kube-dns
```

Merge these settings with the required identity/database/signing configuration.
When NetworkPolicy is enabled, explicit cache and DNS peers are required. The cache
egress rule opens only the configured TCP port to the specified peers. Helm always
requires TLS. Render the actual values before applying:

```sh
helm lint --strict deploy/helm/olo-toolgate -f production-values.yaml
helm template toolgate deploy/helm/olo-toolgate -f production-values.yaml
```
