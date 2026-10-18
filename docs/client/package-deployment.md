# Managed package deployment: use, operate and debug

Module 09 adds fleet reconciliation to the protected endpoint service. It runs
without a desktop login when the OS service and system-accessible OCI engine run
at boot. The Module 08 Windows/macOS engine lifetime and ARM execution limits
still apply; unsupported packages fail instead of invoking host interpreters.
See [runtime configuration](local-runtimes.md), [service installation](install-and-enrollment.md)
and [ADR 010](../adr/010-signed-fleet-reconciliation.md).

The current [device registry](../control-plane/device-registry.md) independently
gates owner/device enablement and installed-client connection approval. Assignment,
activation and READY status never authorize tool execution; current filters and
permits still apply. Approval revision is separate from report revision, and
temporary suspension preserves the protected identity for later resumption.

## Trust and package format

Use the additive [fleet schema](../../packages/contracts/schemas/v1/fleet.schema.json)
and [Control API](../../packages/contracts/openapi/control-v1.yaml). Foundation
client/deployment wire contracts remain frozen. A descriptor is UTF-8 JSON, at
most 32 KiB, with packageId, immutable SemVer version, target platforms/architectures,
minimumClientVersion, digest-pinned OCI runtimes, tool registrations and a confined
JSON self-test for every tool. No ZIP/TAR extraction, install scripts or host hooks
exist. Native/Python/Node/PowerShell/Shell/Java/.NET follow Module 08 confinement;
Only `JAVA_SDK` runtimes activate.

An approved external release authority signs **exact descriptor bytes** using
RFC 7515 RS256 compact JWS. Header alg=RS256, typ=toolgate-package-release+jws,
kid names an approved release key. Canonical unpadded base64url is required;
unknown headers/algorithms and duplicate JSON keys fail. The release envelope
contains packageId/version/manifestDigest (lowercase SHA-256 of exact bytes),
sizeBytes and release.jws. Upload the raw descriptor to the immutable external
HTTPS mirror at `/DIGEST.json`, with Content-Type application/json. Uploading a
new descriptor under an old package/version is prohibited. Marketplace signing
is outside this module; configure valid independently approved inputs.

Organization assignment signs typ=toolgate-fleet-desired+jws and binds tenant,
server, enrolled device, monotonic generation, issue/expiry and the complete
assignment snapshot. Five-minute expiry blocks assigned tools; rollback is a
higher generation assigning a previously approved immutable version. Organization
deployment trust never grants runtime permission. The mTLS artifact grant has
its own typ=toolgate-fleet-artifact+jws, exact digest/size/device/generation and
60-second maximum lifetime. Every download rechecks current assignment/revocation.

Public key entries are `{kid,n,e}` with unpadded base64url RSA modulus, e=AQAB,
2048/3072/4096-bit RSA and at most four keys per domain. Release and organization
keys must be distinct; Control rejects reuse with IdP, policy signer or device CA.
Key rotation: deploy old+new public keys to Control and client first, switch kid
and signer second, wait for all clients and desired/grant expiry, then remove the
old key. Retain release keys needed to verify deployed/rollback versions.

## Production configuration

Existing Control PostgreSQL, direct TLS/mTLS and authenticated administrative APIs
are required. Initialize Flyway version 1 with the migration role
before serving; runtime role
cannot create tables or alter immutable releases. Fleet writes, generations,
idempotency and audit share one tenant-serialized transaction. Rollouts survive
replica restart. No new mandatory server or embedded artifact store is introduced.

Set TOOLGATE_CONTROL_FLEET_ENABLED=true and supply external mounted files:

| Setting suffix (prefix TOOLGATE_CONTROL_FLEET_) | Value |
|---|---|
| KEY_ID | Active organization signer kid |
| PRIVATE_KEY_PATH | Dedicated organization PKCS8 RSA key file |
| RELEASE_KEYS_PATH | JSON array of approved public release keys |
| ORGANIZATION_KEYS_PATH | JSON array including signer and overlapping rotation keys |
| ARTIFACT_ORIGIN | Fixed HTTPS origin without path/query/userinfo/fragment |
| ARTIFACT_CA_PATH | Optional mirror CA PEM bundle, at most four certificates |
| ARTIFACT_TOKEN_PATH | Optional private mirror bearer-token file |

No secret belongs in values, browser storage, package metadata, logs or a URL.
Mirror redirects and inherited proxies are disabled. The server allows two
concurrent transfers, at most 32 KiB, with a five-second total transfer deadline.
Configure immutable retention/backups and HTTPS authentication at the external
mirror. Private OCI registry images must be administrator-preloaded; this module
does not export registry credentials to tools. Existing allowFirstUsePull controls
approved public-image provisioning; unavailable images fail closed.

Helm control.fleet enables the feature. signingSecret has private.pem; trustSecret
has release.json and organization.json. artifactSecret, when present, has token
and ca.pem. Configure artifactOrigin, artifactPort and a narrow
control.networkPolicy.artifactTo peer/IP range; endpoint.enabled is mandatory.
The chart creates references and intentional egress, never signing keys or stores.

In the root-owned client configuration add deployment.releaseKeys and
deployment.organizationKeys arrays; supply existing execution engine settings and
tools Gateway/device identity settings. Empty execution.runtimes/tools is valid
for fleet-only registration. Local administrator registrations remain separate;
package tool/runtime IDs cannot collide with local or other assigned IDs. Reload
by restarting the OS service; IPC callers cannot upload trust keys or descriptors.

## Deploy, update, rollback and uninstall

1. Mirror the signed release descriptor and publish its release envelope using
   POST /api/control/v1/fleet/releases with a fresh admin token and Idempotency-Key.
2. Open **Packages** in the console, choose the release, enter enrolled device IDs,
   and create a canary rollout. The initial percentage selects at least one device
   using a stable hash order. Increasing it retains the original canary cohort.
3. Observe READY/FAILED/offline/waiting/pending/superseded from Control; advance
   with the current rollout revision. Offline and stale observations never count
   as READY. Revoked devices fail. A superseded rollout cannot silently advance.
4. Update by creating another rollout for a newer approved version. Roll back by
   creating a new rollout for an earlier approved version, never decreasing a
   generation. Uninstall by clearing desired presence; it removes registrations.
   Shared images remain cached, and no shared-engine image/container prune occurs.

Desired state is persisted as owner-only fleet-intent.json before downloads.
The client verifies descriptor hash/signature, platform/architecture/minimum
client version, closed registration graph and runtime constraints; it prepares
all candidate images, runs confined self-tests and atomically replaces
fleet-active.json before publishing READY. This record includes signed activation
metadata and observed status. Restart re-verifies both journals and repeats health
preparation; a saved READY flag alone cannot authorize tools. Interrupted downloads
are discarded and retried from the beginning. Failed install preserves the last
activation record while blocking tools from the superseded release. Recovery or
rollback requires a fresh verified desired assignment and online Gateway permission.

Bounds: 128 immutable releases and 256 rollouts per tenant, 256 explicit devices
per rollout, 16 assignments/device, desired snapshot at most 60,000 UTF-8 bytes,
16 runtimes/32 tools across package+local configuration. Pagination is 32 entries.
These are explicit foundation caps; plan retention/tenant partitions before the
cap. Do not delete records or reset generations in SQL to reclaim capacity.

## Debug environment and production troubleshooting

Run `python tools/enterprise/check.py` for durable Fleet/Builder, tenant isolation,
certificate-bound dispatch and current creator group authority, and
`python tools/quickstart/check.py --build` for actual Gateway/native effects.
Use nonce-owned disposable fixtures and dedicated ephemeral test keys.

Deployments and artifact grants do not authorize ordinary tool execution.
Assignments require current group-derived deploy authority; downloads recheck that
creator and exact target/generation/digest before returning bytes. Ordinary effects
require the current complete group path, installed profile, independent ASK approval
where applicable and a short-lived consumed Control permit.

Use [online authority and recovery operations](../enterprise-access-control/operations.md)
and the current client confinement guide. Do not use archived individual ACL or
Gateway policy/bundle settings to restore old permissions.
