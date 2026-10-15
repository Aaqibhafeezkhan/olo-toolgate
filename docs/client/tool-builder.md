# Custom local tool builder: production and debug guide

Module 10 adds authoring to the existing Admin UI at `/console/#builder`.
Tool authors must authenticate with a current organization admin token over TLS.
Anonymous native client downloads remain on the console home page; authoring is
an administrative action. See [ADR 011](../adr/011-designated-client-tool-authoring.md),
[runtime installation](local-runtimes.md) and [fleet configuration](package-deployment.md).

Use the current [device registry controls](../control-plane/device-registry.md)
for designated-client tests and deployed execution. Authoring/test success does
not approve or reenable the client. Current owner/device/connection gates apply
alongside signed leases, resource filters and runtime isolation.

## Production configuration

Use matching Control/UI/contracts and supported clients. Apply the complete
Flyway migration series, including V11 for current device controls, with the
migration identity before serving traffic. The runtime database identity has
bounded tenant-scoped access; sealed drafts and versions cannot be edited/deleted.
Back up the database, retained release descriptors and external trust settings.

There are no new mandatory services, Helm values or authoring secrets. Enable the
existing endpoint/fleet configuration: direct Control TLS with device-client
certificate validation, external organization signing key, separate release trust
keys, and the HTTPS artifact mirror. Keep release private keys outside Control.
The device CA, organization signer and release authority must be separate keys.
See the exact environment names and Helm Secret references in the fleet guide.
Do not forward client certificates through an unverified proxy header.

Enroll a designated test client. Configure its protected service with `tools`,
`execution` and `deployment` as documented in the runtime/fleet guides. The service
polls signed test jobs through its existing mTLS check-in channel. Review and
preload digest-pinned runtime images or enable bounded first-use image pulling.
Installing Python on the host is unnecessary: the managed image contains it.
An offline or incorrectly configured client cannot produce a successful test.

The service runs without an interactive login when its OS service and system OCI
engine run at boot. Linux service execution is tested. Windows/macOS system engine
lifetime across logout/boot and ARM execution remain uncertified; cross-built
binaries do not establish that guarantee. Follow [runtime support](local-runtimes.md).

## Create, test, version and deploy

1. Open **Tool builder**. Choose a unique package ID, tool ID and immutable SemVer
   version. Select a runtime, its reviewed digest-pinned image and exact version.
2. Python, Node and PowerShell source defines `tool(arguments)` returning a JSON
   object. Source is limited to 8 KiB UTF-8. Native, Java JAR and .NET tools use
   reviewed image-contained artifacts with the editor empty. Batch/CMD and WASM
   authoring are unsupported and fail closed.
3. Provide closed object input/output schemas and up to eight examples containing
   `arguments` and `expectedOutput`. Describe the tool's purpose, use conditions
   and exclusions for AI consumers. These descriptions grant no authority.
4. Choose `COMPUTE` and `CUSTOM` resource `runtime/<toolId>`. This module exposes
   isolated compute only. File, network and credential permissions are rejected.
   Named `secret://...` credential requirements may be recorded in a draft, but
   prevent testing and sealing until a supported credential-binding mechanism
   exists. Never paste credential values. The scanner rejects known secret
   patterns; human review remains necessary for unusual or encoded secrets.
5. **Save and scan draft**. Enter enrolled client IDs. **Test on designated client**
   queues each example on the first specified client; the same list supplies
   deployment targets. Refresh results. Test jobs expire after ten minutes;
   leased execution expires within two minutes. Failed/expired tests do not seal.
6. **Create organization package** only after every example of this exact draft
   passes. This seals the version and downloads its canonical candidate descriptor.
   A test never registers the tool for ordinary invocation. A sealed version stays
   immutable even before assignment. **Clone to new version** starts a new draft.
7. Review the code/image and use the independent release authority to sign the
   exact downloaded bytes outside Control:

   ```sh
   python tools/builder/sign.py --descriptor custom-echo-1.0.0.json \
     --key /secure/release-private.pem --kid release-2026 \
     --output custom-echo-1.0.0.release.json
   ```

   The command exclusively creates the output and never executes tool code.
   Install `tools/requirements.txt` in the operator's isolated Python environment.
   Mirror the original descriptor bytes as `<manifestDigest>.json`; reformatting
   after signing changes the hash. The example key path is an external operator
   input, never a repository key or Control JVM setting.
8. Paste the release envelope and **Publish organization release**. Control verifies
   the independent signature and exact equality with the sealed package. Choose
   targets/canary percentage and **Deploy package**. Observe READY/failure state
   under **Packages**. Every ordinary invocation still requires a fresh Gateway
   decision for the exact tool, runtime image, arguments and resource.
   Inline tools also bind permits to a digest of the complete immutable runtime
   and tool registration, so changing source cannot reuse an old approval.
   Register a reviewed, versioned extractor in Control and place the Tool in its
   primary Tool Group. Create an explicit Tool Group execution binding and complete
   group grants. Package assignment and successful tests create no runtime rights.

   ```sh
   olo-toolgate-client run custom.echo '{"text":"hello"}'
   ```

9. **Prepare publication** downloads author metadata, source, schemas and examples
   without tenant/device/job records. Review the contents before sharing. This
   action prepares an export; it does not publish to a marketplace or Module 11.

Updates use new versions. Deploy retained versions at a higher desired generation
for rollback. Use the existing package uninstall action to remove assignment.
Do not edit signed descriptors, active runtime state or database rows manually.

## Execution boundary

Control treats source as bounded data. No interpreter, compiler or sandbox runs
inside the Control JVM. The endpoint verifies the organization signature, direct
mTLS device identity, tenant/server/client binding, lease, supported platform,
permissions, source digest and example before preparing its existing OCI engine.
Fixed runners receive hex-framed source and JSON over stdin. Source and arguments
cannot change host commands. Shell source defines a Bash `tool` function taking
the entire JSON invocation and writing the complete output protocol, including
the same requestId; only builtins are available because child processes are denied.

Execution has no network, host mounts, injected secrets or child processes. It is
non-root with a read-only root, bounded scratch space, timeout/memory/output limits,
cleared environment and existing seccomp confinement. Shell cannot launch `jq`.
Compiler-dependent tools must arrive as reviewed image artifacts. Test results
contain status/error codes, never raw stdout/stderr. Existing runtime output
validation rejects malformed, oversized or incorrectly bound JSON.

## Debug configuration and verification

Use disposable PostgreSQL, fresh test-only keys and a test endpoint. Keep actual
author source inside the same confined engine; debug mode does not enable host
execution, unrestricted shells or secret logging. Run the real integration gate:

```sh
python tools/enterprise/check.py
python tools/quickstart/check.py --build
```

EndpointTest covers certificate-bound Builder/Fleet dispatch and revocation of the
creator's group authority before queued dispatch, result submission and artifact
retrieval. The native OCI runtime tests cover confinement separately. Historical
module reports describe earlier implementations; they are not current gate results.

## Diagnose failures

| Symptom | Action |
|---|---|
| 401/403 | Renew the admin token; check role/tenant and direct TLS. For tests check active enrolled device and lease binding. |
| Save rejected | Inspect schema, image/version, source hash/UTF-8 budget and resource mapping. Remove credential values; never log the submitted source. |
| QUEUED/EXPIRED | Check client service health, heartbeat, Control mTLS and `deployment` configuration. Restarting a job requires a new test request. |
| FAILED UNSUPPORTED | Check OS/architecture/runtime availability and permissions. Remove unbound credential requirements. |
| FAILED VALIDATION/TIMEOUT | Reproduce the exact example in the confined test engine; check return object/schema/output/time limits. Only redacted status is sent to Control. |
| Seal conflict | Refresh revision; ensure every current example passed and choose a new version if already reserved. Editing a draft invalidates its previous tests. |
| Release rejected | Check independent release key ID/trust, exact candidate equality, original bytes/hash and mirror path. |
| Deployment not READY | Use Packages and the fleet guide for assignment, image availability, health and desired/reported reconciliation. |
| Invocation BLOCK | Check current assignment and fresh Gateway policy. Passing a builder test cannot override Gateway, ASK or revocation. |

Inspect `toolgate_control_builder_operations_total`, existing health/readiness and
bounded correlation IDs. Audit operations record save/test/lease/result/seal/export
atomically with state. Do not collect source, tokens, private keys, raw test output
or uploaded release bodies into production logs. Two Control replicas use the
shared database tenant transaction for revisions/leases; process-local state is
not authoritative. Retained test jobs prune after seven days on new test creation;
draft capacity is 128 and retained job capacity is 256 per organization.
