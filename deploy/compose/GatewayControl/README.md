# Separate production Gateway and Control

Optional external Redis/Valkey directory caching is configured through
`env/.env.cache`; see [shared cache configuration](../../../docs/deployment/shared-cache.md).
The default uses only the bounded process-local cache.

This stack consumes externally provisioned PostgreSQL, identity, TLS, purpose-specific signing keys and group authority. It does not generate private credentials, bootstrap a Super Admin from an IdP role claim, or install a local ALLOW policy. For a self-contained local evaluation use QuickStart instead.

1. Pin matching Control/Gateway images in `.env`, preferably by digest.
2. Create protected `custody/control`, `custody/gateway` and `custody/proxy` directories. Set files readable by UID 65532 with no untrusted write permission. Never commit these files.
3. Supply `env/control.env` using [external production settings](../../../docs/operations/execution-guide.md). Use PostgreSQL `sslmode=verify-full`, separate migration/runtime roles and a mounted database CA. Mount the verified IdP public key, dedicated effect signer, optional snapshot/Fleet signers, device CA, Vault encryption key and separately pinned review trust in `/custody`. Never reuse signing keys across purposes.
4. Initialize an empty tenant using an externally signed installation packet and `TOOLGATE_CONTROL_RECOVERY_PACKET_PATH` / `TOOLGATE_CONTROL_RECOVERY_TRUST_PATH`; existing tenant recovery requires two independent reviewers. Remove the one-time packet setting after adoption. [Recovery and migration runbook](../../../docs/enterprise-access-control/operations.md).
5. Supply `custody/gateway/gateway.json` from the [online configuration example](../../../docs/examples/gateway-online.json), listening on `0.0.0.0:8081`, management `0.0.0.0:9091`, `trustedTlsProxy=true`. Use a system-trusted HTTPS Control URL reachable from this network. Supply mounted current-format `credentials.json` and an independent relay token file. Their exact hashes/epochs/expiry must match reviewed workload bindings; device approval cannot manufacture Agent credentials.
6. Mount trusted HTTPS `server.crt` / `server.key` in `custody/proxy`. Configure DNS and certificates consistently. The default exposed port is loopback only; enterprise ingress and network isolation are operator responsibilities. Devices use Core's own authenticated mTLS listener, which must be routed separately; the public console proxy cannot impersonate devices.
7. Run `manage.ps1 deploy` or `sh manage.sh deploy`. Inspect both readiness listeners through the restricted backend network. Create Team/Agent Group grants and bindings through independent configuration review before discovery/execution can succeed.

`undeploy` preserves externally held database/custody. Updates never convert old credentials or revive old policies. Back up the database and separately protected custody, then use the [signed quarantine restore](../../../docs/enterprise-access-control/operations.md) before reconnecting an isolated restored tenant.
