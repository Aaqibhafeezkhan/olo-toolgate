# Live tenant identity qualification

After building a matching Quickstart image, run:

```sh
python tools/tenancy/check.py --image olo-toolgate-quickstart:module11 --output build/quickstart/tenant-runtime-evidence.json
TOOLGATE_TENANT_RUNTIME_EVIDENCE="$PWD/build/quickstart/tenant-runtime-evidence.json" cargo test --locked -p olo-toolgate-tenant-identity --test runtime_cutover -- --ignored
```

The first command creates and removes its own Docker container and fresh volume. It uses an existing local image, publishes no ports, and keeps networking on loopback. Optional `--control-app` and `--gateway-binary` paths mount matching local build artifacts for focused development checks. The fixture deliberately disables optional package/profile initialization and embedded builds; the full Quickstart release check qualifies those separately.

The fixture provisions two tenant UUIDs with separate signing/CA custody and canonical hostnames. TLS hostname and certificate validation stay enabled. It verifies signed discovery, repeated device identifiers, administrator approval, authenticated check-in without a tenant header, foreign JWT/certificate refusal, and two Gateway processes bound to separate tenant credentials. Anonymous Gateway metadata works with no service-JWT file present.

Four Control launches exercise initial keys, preannouncement, activation with CA overlap, and retirement. Gateway processes retain their verification floors across those launches. An old tenant CA remains in the public listener trust store deliberately: application authorization must still reject its retired certificate. The second tenant continues using its original certificate. A test-only platform lifecycle mutation then checks uniform public unknown/suspended errors and an unaffected second Gateway. This mutation is not an operator administration interface.

The exported JSON contains synthetic public signed documents and certificates, with no private keys, enrollment device codes or tokens. The Rust test consumes that evidence using the production shared verifier, including an offline missed-announcement HTTPS refresh, serialized trust floors, rollback denial, CA overlap/retirement and cross-tenant identity denial. It replays each short-lived packet at its captured issue time; production verification uses the current clock.

This is protocol/runtime qualification. Dedicated execution-pool mounts/networks, operator lifecycle administration, quotas, tenant recovery and the complete native OS/release matrix retain their separate deployment/release gates.
