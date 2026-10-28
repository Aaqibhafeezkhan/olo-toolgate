# Tenant identity verification

`rust/` is the private shared verifier used by the native client, Gateway and Tool Host. It validates closed discovery v2 records against bundled schemas, strict JSON, RSA signatures, tenant UUID, canonical HTTPS origins, purpose-separated public keys, device CA constraints and validity windows. Public interoperability vectors live in `tests/fixtures/tenant-discovery/v2.json`.

`verify` and `verify_bytes` require a previously announced signer once a trust floor exists. `verify_https_refresh` additionally permits an unknown signer at a strictly newer generation only for bytes fetched from the installation's fixed Control origin through independently authenticated HTTPS. Callers must never use that refresh entry point for browser, IPC or arbitrary relay input. Known pinned signers cannot bypass signature checks through the refresh path. TLS roots remain a separate operator-controlled trust domain.

Native and Tool Host installations persist the tenant, Control origin, manifest and monotonic discovery trust floor in private local state before accepting certificates. Reset, expiration and renewed enrollment retain that binding. Gateway public discovery keeps only public process-local continuity; device clients enforce their own durable floor.

The leaf verifier requires a tenant-owned published CA, the exact server/tenant/device subject, matching key fingerprint, current bounded lifetime and client-only extended usage. Native/enrolled identities also bind the exact user/device URI. Preprovisioned workload identities require the canonical device URI; Control still matches it against the enrolled user record when authenticating requests.

Run the verifier tests with `cargo test --locked -p olo-toolgate-tenant-identity`. These cryptographic checks complement dedicated tenant credentials, mounts and execution pools; they do not replace operating-system isolation or release qualification.
