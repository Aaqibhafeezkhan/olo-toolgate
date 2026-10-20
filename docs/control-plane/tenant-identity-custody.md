# Tenant identity key custody

Control now serves discovery v2 using the resolved tenant's protected discovery signer and issues device certificates using that tenant's independent CA. Enabled enrollment refuses startup without protected custody and a public client truststore. The endpoint has no live CA-signed v1 fallback. Shared production qualification remains gated by the complete plan.

`control_tenant_identity_keys` stores a canonical tenant UUID, purpose, immutable key ID, public material, public-key fingerprint, generation, state and protected provider reference. Private keys never enter the database, configuration exports or audit documents. PostgreSQL forces tenant RLS, grants runtime SELECT only and reserves mutations for the platform role. A global unique public-key fingerprint prevents reuse across tenants and identity purposes. The custody provider additionally rejects keys used for the configured JWT, policy, Fleet, package release, PEM TLS chain or development device CA. The local mounted provider is implemented; external KMS backends are not claimed.

Set `toolgate.control.identity.custody-root` to an absolute protected directory. Every PKCS#8 PEM file has the fixed reference `<tenant UUID>/<DISCOVERY or DEVICE_CA>/<key ID>.pk8.pem`. References cannot name arbitrary files or another tenant. Ownership, writable ancestors, private-key read permissions, file size and symlink containment are checked. Projected Secret files may resolve within their protected mount. Active private keys must match the registered public fingerprint. Missing or replaced material fails; runtime never creates keys or uses a default signer.

Set `toolgate.control.identity.publication-path` to a protected JSON file. It is processed after tenant registration, before ordinary startup observers. The equivalent environment variables are `TOOLGATE_CONTROL_IDENTITY_CUSTODY_ROOT` and `TOOLGATE_CONTROL_IDENTITY_PUBLICATION_PATH`. The packet has exactly `formatVersion: 1` and `publications`, with at most 256 entries. Each publication contains exactly `tenantId`, `generation` and `keys`. Each key contains `purpose`, `keyId`, `state` and, for `DEVICE_CA` only, `certificatePem`.

```json
{
  "formatVersion": 1,
  "publications": [{
    "tenantId": "10000000-0000-4000-8000-000000000001",
    "generation": 1,
    "keys": [
      {"purpose": "DISCOVERY", "keyId": "discovery-a", "state": "ACTIVE"},
      {"purpose": "DEVICE_CA", "keyId": "<certificate DER SHA-256>", "state": "ACTIVE", "certificatePem": "<public PEM certificate>"}
    ]
  }]
}
```

The example contains placeholders. Supply a real self-signed SHA-256/RSA device CA with path length zero, key-cert-sign usage and exactly one single-valued OU containing that tenant UUID. Its key ID is the lowercase certificate-DER SHA-256. Both discovery and device CA keys use distinct RSA 3072/4096-bit keys with exponent 65537. A publication requires exactly one active key of each purpose, at most four discovery public keys and at most two non-retired device authorities. The provider verifies a locally signed discovery document during preflight, including the registered profile, issuer, audiences, certificate purposes and private-key possession.

Each tenant publication commits independently. Registry locking serializes it against admitted writes. The first generation is 1; changed publications must advance by exactly one. An identical restart is a no-op. Reusing an ID with different public material/reference, skipping generations, reviving a retired key or activating an unannounced discovery signer fails and rolls back that tenant's publication. A successful publication advances directory revision and authorization epoch, emits the authorization outbox entry and records a metadata-only audit. A later tenant's failure does not undo earlier committed tenant publications; correct the failing packet and restart.

For discovery rotation, publish A active plus B read-only, then select the preannounced B as active in the next generation. Omit A in a later generation to retire it. Retired rows remain as ownership and rollback tombstones; their private files can be removed. They cannot be reactivated or deleted through the platform runtime role. See [discovery v2](discovery-v2.md) for client continuity requirements, including the authenticated refresh path still needed for offline clients.

For device CA rotation, retain the old CA as read-only while selecting the new CA for issuance. The tenant issuer verifies existing device certificates against either retained CA, always binds issuance to the tenant/server, and rejects retired CAs. Provision the aggregate public mTLS truststore and roll all servers before selecting a replacement issuer. Application CA selection does not update an already-running TLS listener. Quickstart provisions separate discovery and device-CA keys before starting Control, publishes their metadata once, and builds its public client truststore from retained tenant CAs. Its original development root remains TLS-only. The native client verifies v2 before enrolling and checks the issued certificate against the signed tenant CA ring. Listener roots can identify an authenticated foreign tenant for `TENANT_MISMATCH`, but cannot revive an own-tenant CA retired in authoritative metadata. Production rotation, offline continuity and the complete platform matrix remain qualification gates.

The supported mounted provider shares the application's ability to read tenant key files. Per-tenant keys prevent accidental fallback and cross-tenant key reuse; they do not contain compromise of that application identity or the platform credential. Use separate deployments and dedicated provider identities where that compromise boundary is insufficient.

Tenant suspend/resume now advances directory revision, authorization epoch and the outbox in the same registry transaction as the status change, with a lifecycle audit. Old permits cannot become valid again after resume.

For Helm, set `control.endpoint.identityCustodySecret` and `identityKeyFiles`. The Secret includes `publication.json` plus one key per private file. Each projection maps a Secret `key` to `path: <UUID>/<DISCOVERY|DEVICE_CA>/<key ID>.pk8.pem`. The chart mounts the protected root at `/etc/toolgate/tenant-identity`. Supply the independent server TLS Secret and aggregate public PKCS#12 client truststore separately; never put a device private key in the TLS truststore. The removed deployment-wide `deviceCaSecret` setting cannot supply the tenant issuer.
