# Default tenant and subdomain multitenancy implementation plan

Status: implementation started; multitenant support is not complete. Revised against the official architecture guidance linked in section 13. The recommendations below apply established patterns to this repository; they are not a claim that this implementation is already production-proven.

### Implementation checkpoint

Working branch: `feature/multitenant-support-gap`. Commit each verified milestone on this branch and report its commit, overall completed percentage, pending percentage and next milestone. Stage only files belonging to this work; unrelated workspace content is excluded.

Overall progress: **63% complete, 37% pending**. This is a weighted planning estimate of accepted implementation gates, not a measure of production readiness. The fixed weights below total 100; credit a row only when its stated implementation and verification gate passes. Phase 1 is split so each foundation gate is credited independently. Record any future scope/weight change explicitly rather than silently changing the denominator.

Milestone allocation update: the former 19% phase 1b allocation is now split into 5% for the ownership inventory/customer-data RLS gate and 14% for the remaining registry, UUID, lifecycle and ownership-constraint gates. The original phase 1b total and the overall 100% denominator are unchanged. The registry/authority split is now complete: registry and hostname metadata use platform-only privileges; authority and customer data use tenant RLS.

| Milestone | Weight | Status | Earned progress |
| --- | --- | --- | --- |
| 0: Secret-delivery fencing | 5% | Implemented and verified | 5% |
| 1a: Remove unused lock table; advisory-lock behavior | 1% | Implemented and verified | 1% |
| 1b.1: Ownership inventory, customer-data RLS and transaction-local context | 5% | Implemented and verified | 5% |
| 1b.2: Registry/authority split, UUID ownership, lifecycle/transaction gates and remaining ownership constraints | 14% | Implemented and verified | 14% |
| 2: Default tenant and layered initialization | 12% | Implemented and verified | 12% |
| 2a: Tenant vault keys and secret custody | 10% | Implemented and verified | 10% |
| 3: Tenant resolution, service/deployment refactor and CI guards | 16% | Implemented and verified | 16% |
| 4: Device/Gateway binding and discovery v2 cutover | 18% | Trust, native-context and Gateway/worker binding foundations verified; coordinated cutover pending | 0% |
| 5: Deployment and tenant administration | 5% | Pending | 0% |
| 6: Capacity and background isolation | 5% | Pending | 0% |
| 7: Recovery qualification | 5% | Pending | 0% |
| 8: Release qualification and documentation | 4% | Pending | 0% |
| Total | 100% | In progress | 63% |

- Phase 0 is implemented: secret delivery requires the current consumed permit nonce, matching invocation/device/epoch and live authority before secret lookup/decryption. The HTTP adapter, endpoint transport and OpenAPI header contract carry the fence. Regression coverage includes missing/invalid/foreign fences, a replaced attempt, wrong device/tenant, revoked grants and rejection before ciphertext decryption.
- Phase 1 has begun with removal of the unused `quickstart_vault` table/grants from both V1 schemas. Quickstart's PostgreSQL adapter now takes a tenant-namespaced transaction advisory lock instead; SQLite keeps `BEGIN IMMEDIATE`.
- Phase 1b.1 is implemented: the checked `db/schema-ownership.json` inventory covers all 39 application tables in both storage modes, including tenant column types, primary/foreign keys and runtime privileges. PostgreSQL forces restrictive RLS on 38 customer-data tables, with transaction-local context centralized in `PostgresStore.transaction(...)`. The Quickstart helper and offline recovery boundary also set local scope. SQLite now explicitly marks every tenant column non-null, including its single-column text primary keys. CI checks inventory drift and blocks new Java context-setting boundaries; real PostgreSQL catalog checks verify deployed policies, ownership keys and runtime privileges.
- Phase 1b.2 is implemented: canonical UUID validation and registry ownership foreign keys cover all tenant data. Registry/lifecycle and hostname metadata are platform-only; runtime counters live in `control_tenant_authority` under RLS. Ordinary transactions admit only active tenants with existing authority, updates are optimistic, cache triggers never insert authority, and suspension serializes against admitted writers. Dedicated platform credentials handle allocation and paginated enumeration; watchdog work remains tenant-scoped. The inventory now covers 42 tables: 40 under forced tenant RLS and two platform-only metadata tables.
- Startup support for the foundation is implemented: protected Quickstart UUID state, explicit allocation, atomic configuration/receipt/audit/activation, layered initialization and removal of settings overwrite. Startup readers, Helm and supervisor use the default alias selector and persisted UUID; CI blocks new legacy setting reads and unapproved connection/context boundaries. Phase 2 is implemented and verified: the manifest and all includes have a 16 MiB aggregate bound, failed apply attempts retain a non-sensitive platform diagnostic after rollback, Quickstart uses complete-record/array replacement, and concurrent replicas initialize once. SQLite helpers enforce ownership foreign keys. The manifest schema, ordered source/release-bundle provenance and operational semantics are documented.
- Verification: the full Control suites reported 282 tests, zero failures/errors and five optional skips (277 passed). Two additional focused unsupported-database tests passed, preserving PostgreSQL/SQLite data and refusing baselining/checksum repair. All 26 Quickstart tests, three ownership/privileged-boundary checks, five Control asset checks and nine endpoint contract checks passed. Lifecycle tests cover explicit registration, missing authority, optimistic updates, atomic rollback, no bootstrap replay and suspension/writer ordering; layered tests cover two independent tenants and rejected common/foreign inputs. Focused live SQLite and PostgreSQL Quickstart proofs verified an active UUID tenant, authenticated administrator edit and restart preserving that edit despite changed initialization inputs, with exactly one receipt and initialization audit. The cached image's obsolete optional sealed package inventory was excluded from this focused proof; the complete release-image gate remains phase 8. The real PostgreSQL privileged backup/owner restore proof also passed.
- Phase 2 qualification: the full Control suites passed 288 tests with zero failures/errors and seven optional/platform skips (281 passed). Four Linux manifest/custody tests passed separately, including both manifest tests skipped on Windows. All 28 Quickstart tests and all three schema/boundary checks passed. Fresh SQLite and PostgreSQL bring-up/restart proofs preserved administrator settings and one initialization receipt/audit per tenant; PostgreSQL used distinct runtime/platform roles. Full image qualification remains phase 8.
- Phase 2a is implemented: independent random tenant encryption/retry keys, wrapped-key metadata, tenant/key ownership foreign keys, forced RLS, versioned secret envelopes and contextual authentication. Protected platform provisioning seeds keys atomically; runtime cannot insert or mutate key metadata. Rotation, KEK rewrap and retirement serialize against admitted writes, audit and advance tenant authority. Runtime never creates missing keys or converts unsupported ciphertext. The supported backend is an operator-managed protected local KEK ring; the provider interface does not claim external KMS implementations. Discovery stays on the development CA-signed protocol until phase 4.
- Phase 2a verification: full Control regression suites passed (291 tests, zero failures/errors, seven optional/platform skips). Five final vault tests passed on the current code, including both storage modes, wrapping-key rollback and missing-key rejection. All 28 Quickstart tests and three schema/boundary checks passed. Live SQLite/PostgreSQL bring-up, metadata-only vault API and restart proofs passed; Helm lint and protected key-ring render passed. The inventory covers 43 tables, 41 under tenant RLS. See `docs/control-plane/tenant-vault.md` for provider custody and rotation rules.
- Phase 3 foundation is implemented: strict authority parsing and active-host resolution, optional expected-tenant UUID comparison, SNI/Host misdirection and verified-principal matching helpers. Canonical per-tenant Control/Gateway profiles are validated against registered hostname ownership and committed with initialization, provenance and activation. Runtime has SELECT only on profile metadata. The inventory covers 44 tables, 42 under tenant RLS. This foundation earns no additional weighted progress until the complete phase 3 HTTP/service gate passes.
- Phase 3 foundation verification: the full Control suites passed 295 tests with zero failures/errors and seven optional/platform skips (288 passed). Four Linux manifest/custody tests and three schema/privileged-boundary checks passed. All five final focused tests and the packaged SQLite/PostgreSQL bring-up/restart checks passed, verifying canonical profiles, vault access and saved settings across restart.
- Phase 3 is implemented: Control binds registered hostname/SNI/optional UUID context before assets, API operations and socket upgrades; JWTs must match before identity observation. Request-scoped Endpoint/MCP/Fleet/Builder services use persisted tenant profiles; background work receives explicit tenant IDs, Builder files use UUID namespaces, and readiness no longer depends on the default tenant. Code-attempt limits survive service recreation. The HTTP response filter preserves typed tenant errors and actionable application details. Helm projects protected manifests and the CI guard blocks renewed default-tenant/runtime legacy setting reads, including helper key literals. Direct Host-preserving routing is supported; forwarded authority rewriting is explicitly rejected. Deployment-wide device/Fleet key inputs remain part of the phase 4 trust cutover, not a claim of independent tenant signer custody.
- Phase 3 verification: complete Control regression suites passed (301 tests, zero failures/errors, seven optional/platform skips); focused routing/principal/response-filter regressions and the packaged build passed. All 28 Quickstart tests, three schema/boundary checks, nine endpoint contract checks, deterministic generation of 932 bindings, offline Rust client/Gateway compilation, Helm lint and subdomain-manifest rendering passed. Packaged SQLite and PostgreSQL bring-up/restart proofs preserved administrator edits. The PostgreSQL proof initialized a second tenant through a protected signed manifest, verified both host/token pairs, rejected crossed JWTs with `TENANT_MISMATCH`, used tenant B's canonical configuration/discovery identity and wrote isolated vault entries for both tenants. Builder toolchain validation remains a startup gate, with its own focused regression. The cached image's obsolete optional sealed package/profile inputs remain excluded from this focused proof; full release-image qualification remains phase 8. See `docs/control-plane/tenant-routing.md`.
- Phase 4 foundation is implemented: a verified device certificate must bind its signed UUID, server, device and identity URI to the resolved tenant and enrolled record, in addition to its key fingerprint. A certificate for another tenant is rejected with `TENANT_MISMATCH` even if the same public key was enrolled in both tenants. Client HTTP/socket error handling distinguishes tenant/authority denials from actual revocation, bounds and validates error bodies, preserves identity/key/pending reports, and carries the mismatch through authenticated IPC. This earns no phase 4 weight until its full cutover gate passes.
- Phase 4 foundation verification: complete Control suites passed (303 tests, zero failures/errors, seven optional/platform skips) and the packaged build passed. The cross-tenant certificate test also rejects wrong device, user and server claims without advancing the report sequence; the correct certificate continues to work. The full client suite passed 61 tests, with one existing real-runtime fixture test skipped, including HTTP/socket error decoding and identity retention/resumption. Separate discovery keys/v2, immutable installation UUID binding, native/extension context checks, Gateway credential selection and execution-pool cutover remain pending.
- Discovery v2 protocol foundation is implemented: a closed compact JWS schema, generated Java/Rust/TypeScript/PHP bindings, tenant/profile-bound Java signing and Java/Rust verification share public-only interoperability vectors. Verification enforces the UUID issuer, registered HTTPS audiences (including different Control/Gateway origins), algorithm and message purpose, bounded public key rings, preannounced rotation, rollback protection, independent tenant device-CA metadata and expiry. Unknown headers, shared CA/discovery keys, unannounced signers, v1 documents and tenant aliases fail. The new helpers are deliberately separate from live serving; tenant private-key custody/provisioning and the coordinated producer/client cutover are still required. See `docs/control-plane/discovery-v2.md`.
- Discovery foundation verification: complete Control suites passed (306 tests, zero failures/errors, seven skips), including three Java interoperability/signing tests; the PostgreSQL privileged backup/owner restore proof and packaged build passed. Rust client tests passed 64 tests with one existing real-runtime fixture skip; four contract round-trip tests passed. All seven v2 structural contract checks, the public-vector authenticity/custody check and deterministic generation of 947 bindings passed. Fresh packaged SQLite Quickstart provisioning, metadata-only vault access and restart preserved administrator edits and exactly one initialization receipt/audit. The focused cached-image proof continues to exclude obsolete optional sealed package/profile inputs; full image qualification remains phase 8. This foundation earns no phase 4 weight.
- Additional release-gate findings from the broad Python contracts run: 113 tests ran, with 23 failing migration subcases, nine errors and one skip. The unchanged migration specification scenarios still insert the non-UUID `t1` without the tenant registry prerequisite; the frozen v1 error-enum gate rejects the previously added tenant error values. The Linux test environment also lacks `license_expression`, and Windows-checkout Compose shell fixtures fail before creating their fake-Docker call log. These are recorded release blockers, not a passing broad contract gate. The generator check must run after the foundation drift test, which intentionally changes and then restores a generated file.
- Tenant identity custody foundation is implemented: public metadata and immutable protected PKCS#8 references are tenant-owned under forced RLS, runtime has SELECT only, and a global fingerprint constraint prevents key reuse across tenants/purposes. Protected operator publication validates key possession, tenant/profile/CA binding, independent key domains, exact generations and preannounced signer activation before an atomic per-tenant commit. Publication advances revision/epoch, audit and outbox. Retired keys remain immutable tombstones and need no private-file fallback. Tenant-specific issuers prevent foreign tenant/server issuance, verify read-only CA overlap and reject retired CAs; they refuse CA-signed discovery. Suspend/resume now advances authority and emits audit/outbox so old permits cannot revive. See `docs/control-plane/tenant-identity-custody.md`.
- Identity custody verification: complete Control suites passed (308 tests, zero failures/errors, seven skips), including full SQLite/PostgreSQL key publication, rotation, rollback, duplicate-key isolation and CA overlap/retirement scenarios; final focused tests also reject extra material in a public certificate. All three ownership/boundary checks passed. The inventory now covers 45 application tables, 43 with forced tenant RLS. Packaged SQLite/PostgreSQL Quickstart checks published protected key metadata once, preserved administrator edits and initialization receipts/audits, and retained exactly one key-publication audit through another restart. The focused cached-image proofs retain the documented optional-package/profile exclusions. The key provider is not yet selected by live discovery/device routes; matching client and Quickstart cutover remains pending, and no phase 4 weight is earned yet.
- Native context foundation is implemented: every Chrome health/enrollment request carries its console URL through a closed shared v2 IPC model. The service checks protected origin and an optional expected UUID under its own state lock before exposing health or starting enrollment; denial has no health/code/connection/activity/power data and preserves the journal. Existing installation configuration is checked before browser Connect fetches setup material or invokes elevated changes. Native success replies bind the console origin; errors never append another installation's server URL. The extension discards canceled/replaced results, drops cached codes on mismatch and stops automatic setup/enrollment/repair. The console stops polling and hides setup links on mismatch. See `docs/control-plane/native-tenant-context.md`.
- Native context verification: 66 client/native Rust tests and four contract round-trip tests passed, with one existing real-runtime fixture test skipped. All 17 extension tests passed, including wrong-tenant Connect/status and cancellation privacy checks; all 13 focused Connect tests passed (the isolated run does not satisfy the unrelated API coverage threshold, so the full UI suite remains a commit gate). Type checks, all seven v2 schema tests, public-fixture guard and deterministic generation of 951 bindings passed. Complete Control suites passed again (308 tests, zero failures/errors, seven skips), with privileged backup/owner restore, packaged build and final SQLite protected-publication/restart proof. Live v2 verification, signed audience expansion, immutable installation UUID persistence, removal of parked multi-gateway profiles and Gateway credentials remain coordinated-cutover work; no phase 4 weight is earned yet.
- Gateway/Tool Host binding foundation is implemented: each Gateway process has one canonical `control.tenantId`, explicit bounded authorities, a tenant-bound credential mount/reload and a fixed Control service credential. Host/HTTP authority agreement and optional UUID assertions are checked before authority calls; forwarded headers do not select a tenant. The relay rejects foreign contexts and sends the expected UUID to Control. Control/Gateway now emit the specified `INVALID_TENANT_ID` error. Tool Hosts require their own UUID, reject correctly signed foreign-tenant permits even with repeated host IDs, assert the UUID to Control, and persist a versioned immutable enrollment binding before key generation/network traffic. Deletion/expiry retain that binding; unsupported journals/orphaned old keys are rejected unchanged. Quickstart, Helm values/examples and render gates supply explicit ownership; child processes do not inherit Control identity-custody settings.
- Gateway/worker verification: all 25 Gateway tests passed, including host ambiguity, unknown-host-before-assertion behavior, optional headers, foreign credential/reload rejection and Control health assertions. All 17 Tool Host tests passed, including real Python SDK effects and rejection before nonce consumption; three final identity-state tests passed, including one additional orphaned-key case (18 distinct tests verified in total). Client/contracts suites passed again (66 client/native tests, one existing runtime fixture skip, four contract tests). All 28 Quickstart tests, four Gateway asset/schema tests, focused Control resolver tests, strict Helm lint and tenant ingress rendering passed. Matching built Control/Gateway/Tool Host binaries passed fresh SQLite and PostgreSQL protected-publication/restart proofs and live Gateway success/403/400/404 checks. The focused image proof retains its optional-package/profile exclusions and is not full release-image qualification.
- Live discovery v2/device-issuer selection, immutable native-client UUID persistence, qualified isolated execution/build workers, operator/deployment administration, capacity/background isolation and recovery/release qualification remain pending. Directory prefixes alone do not isolate untrusted build/tool code from platform credentials; shared Control must not execute customer code or mount tenant executor access. The current application must not be described as supporting shared production tenants yet.

## 1. Outcome and scope

Support the same tenant model in three deployment forms:

| Deployment | Example public address | Tenant selection |
| --- | --- | --- |
| Quickstart | `http://localhost:8080` and `https://localhost:8443` | Automatically provisioned default tenant |
| Single organization | `https://tools.xyz.com` | Configured default tenant on registered hosts |
| Shared deployment | `https://tenant1.xyz.com`, `https://tenant2.xyz.com` | Registered hostname mapped to a tenant |

The default tenant is an ordinary tenant selected automatically during bring-up. It has no cross-tenant authority. Every tenant, including the default, has exactly one immutable, server-generated UUID as its stored and authenticated identity. `quickstart`, `default` and `tenant1` are human-readable aliases only, never values written to `tenant_id` or issued as identity claims. This plan targets one first-release V1 on an empty database, with no existing-installation conversion or compatibility adapters.

Allow both common and per-tenant initial configuration. Copy common templates into each tenant's own records during initialization. Saved tenant configuration remains authoritative after initialization.

The first shared deployment uses PostgreSQL with shared tables and existing tenant-scoped keys. SQLite remains the supported single-tenant Quickstart storage option; qualifying shared SQLite hosting is outside this plan.

### 1.1. Selected architecture: shared management, isolated execution

Use a hybrid/bridge isolation model: share tenant management, routing and PostgreSQL infrastructure while dedicating execution pools and device identities to tenants. AWS describes this combination of pooled and siloed components as the [bridge model](https://docs.aws.amazon.com/whitepapers/latest/saas-tenant-isolation-strategies/the-bridge-model.html). This is a design recommendation for ToolGate, not a requirement to deploy on AWS.

| Option | Assessment for this repository | Decision |
| --- | --- | --- |
| Entire deployment and database per tenant | Strong boundary and simple tenant recovery, with more provisioning/operational cost | Supported future deployment option for stricter isolation |
| Shared management, shared tenant-scoped database, dedicated execution pools | Reuses existing directory/storage code and separates tenant-controlled execution | Initial shared deployment target |
| Fully shared management and untrusted execution hosts | Depends on confinement that this repository has not completed | Excluded from the initial shared release |

```text
tenant1.xyz.com / tenant2.xyz.com
  -> trusted routing and discovery
  -> authenticated tenant context
  -> shared tenant services + PostgreSQL row isolation
      -> tenant1 execution pool / tenant1-bound devices
      -> tenant2 execution pool / tenant2-bound devices
```

Keep platform tenant/domain provisioning separate from tenant application operations. Platform credentials must not be usable as ordinary tenant credentials, and normal runtime identities cannot enumerate or administer every tenant. This follows the separation discussed in [Azure multitenant control-plane guidance](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/control-planes); ToolGate's existing `Control` service may host these modules initially, without creating another service prematurely.

Introduce a tenant placement abstraction with one default deployment initially. Store placement independently of the public hostname so a tenant can later move to a dedicated deployment without changing its identity or public URL. Multi-region placement, automated tenant relocation and additional stamps are subsequent work. [Azure deployment stamps](https://learn.microsoft.com/en-us/azure/architecture/patterns/deployment-stamp) provide the scaling pattern; the first release needs one placement, not a distributed routing platform.

### 1.2. Enforce isolation below authentication

Enforce the same verified tenant context at HTTP, application, repository, database, queue and execution boundaries. Authentication alone does not establish tenant isolation, as explained in [AWS tenant-isolation guidance](https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html).

Required shared-release boundaries are tenant-scoped application interfaces, PostgreSQL row-level security, composite tenant ownership constraints, tenant-scoped messaging/storage and dedicated execution pools. Admission quotas and recovery qualification are also release gates, rather than optional post-launch hardening.

## 2. First-release schema: one V1 with Flyway

Implement the complete design directly in `apps/control-plane/src/main/resources/db/migration/V1__initial_schema.sql` and the corresponding `db/quickstart/V1.sql`. Flyway owns PostgreSQL initialization; the checksummed SQL path owns SQLite initialization. V1 creates the final registry/authority split, ownership constraints, RLS policies, key metadata and initialization records for an empty database.

Rules:

- There is one unreleased V1 per storage mode, not an additive migration chain for earlier development snapshots. Update these V1 definitions and their schema fixtures together before release.
- No backfill, alias-to-old-storage-ID mapping, existing-device conversion, old wire-format adapter, legacy ciphertext reader or re-encryption process is included.
- Keep PostgreSQL and SQLite definitions aligned for shared concepts, with PostgreSQL-only RLS explicitly identified.
- Preserve separate schema-owner, platform-provisioning and tenant-runtime database privileges. Establish one initialization owner per storage mode.
- Tenant creation is application provisioning after schema creation, not a V1 seed or a per-customer migration.
- Reject unsupported non-empty development databases and incompatible V1 histories without silently baselining, repairing, resetting or deleting them. Operators choose a new empty database/data directory; the application never clears the old one.
- Once this V1 has been released, future schema evolution must append reviewed migrations without modifying the released V1. That future policy does not introduce upgrade or backfill work into this first-release scope.

Restarts of this release reuse its unchanged schema and initialized UUID tenants. A future released-format upgrade, if needed, requires its own reviewed design. Fresh-install qualification and same-release restart/recovery replace all former existing-installation acceptance criteria.

## 3. Tenant registry and deployment configuration

Add registry records conceptually equivalent to:

| Record | Required information |
| --- | --- |
| `control_tenants` platform registry | Canonical UUID string as `tenant_id`, unique human-readable alias, display name, lifecycle status and timestamps; no mutable authority counters |
| Hostname mapping | Normalized exact hostname, tenant ID, canonical/alias designation, enabled status |
| Tenant service configuration | Canonical console, Control, Gateway, discovery and enrollment URLs; authentication and trust references; deployment placement reference |
| Initialization record | Tenant ID, initialization version, pinned bundle identity, effective digest, completion time |
| `control_tenant_authority` runtime authority | `tenant_id` referencing the registry, revision, `authorization_epoch` and `directory_cache_generation`; tenant-scoped RLS |

Use `PROVISIONING`, `ACTIVE`, and `SUSPENDED` lifecycle states initially. A failed initialization remains unavailable in `PROVISIONING`, with a recorded error suitable for operator diagnosis. Add deletion/offboarding state only with the corresponding retention and credential-retirement workflow.

Enforce unique hostname ownership across tenants. Keep hostname mappings as normalized registry-owned rows with a tenant foreign key, separate from the immutable UUID. Resolve the previous open question now: `control_tenants` is the platform registry, and `control_tenant_authority` holds mutable runtime counters. Only the narrowly privileged provisioning service may insert a registry row and its initial authority row. Lifecycle state exists only in the registry; do not duplicate an `ACTIVE` flag in the authority table.

Generate and persist the canonical UUID once during explicit provisioning; never derive it from the hostname or regenerate it on restart. Store its lowercase, hyphenated 36-character UUID string in every `tenant_id` column. Retain the V1 `varchar(128)` PostgreSQL column type and SQLite text type; do not introduce a second UUID column or convert only new columns to native PostgreSQL UUID. Validate canonical UUID syntax at the typed application boundary and registry schema, and enforce matching non-null foreign keys on tenant-data columns. All runtime claims, device configuration, encryption context and contracts use this same UUID string. Manifest `alias` values and default-alias configuration are provisioning selectors only and never cross into tenant authorization or storage keys.

Proposed environment configuration, to be finalized with existing naming conventions:

```properties
# Quickstart / single organization
TOOLGATE_TENANCY_MODE=single
TOOLGATE_DEFAULT_TENANT_ALIAS=quickstart
TOOLGATE_SINGLE_TENANT_HOSTS=localhost,127.0.0.1
```

```properties
# Shared production deployment
TOOLGATE_TENANCY_MODE=subdomain
TOOLGATE_TENANT_BASE_DOMAIN=xyz.com
TOOLGATE_DEFAULT_TENANT_ALIAS=default
TOOLGATE_INITIALIZE_MANIFEST=/run/toolgate/initialization/tenants.yaml
```

`TOOLGATE_DEFAULT_TENANT_ALIAS` is the sole environment default-alias selector; the name deliberately does not call an alias an ID. `TenancyConfiguration` reconciles it with the manifest's `defaultTenantAlias`; conflicting aliases fail startup. With neither, default-only Quickstart selects alias `quickstart` and fresh non-Quickstart selects alias `default`. The provisioning service creates the UUID on first bring-up and resolves the alias to that same persisted UUID on subsequent starts.

Remove `toolgate.control.endpoint.tenant-id` / `TOOLGATE_CONTROL_ENDPOINT_TENANT_ID` and the earlier proposed `TOOLGATE_DEFAULT_TENANT_ID` setting entirely. Do not implement fallback aliases or precedence rules for them. Configuration validation rejects these unsupported settings with directions to the default-alias/manifest input. Helm and `supervisor.py` emit the new alias selector for provisioning, then use the resolved UUID for every generated runtime tenant field. No service may independently read a deployment-wide endpoint tenant ID.

Provision the default tenant in both modes. In subdomain mode it is accessible only through its own registered hostname. A missing or unknown host must never fall back to it. Explicitly support local ports and configured loopback hosts in single mode; production hostname mappings use normalized DNS names with port validation handled separately.

## 4. Common and per-tenant initialization

Introduce an operator-owned initialization manifest. This is a new manifest format, not an existing accepted configuration file:

```yaml
formatVersion: 1
defaultTenantAlias: default
commonImports:
  - common/groups.json
  - common/roles.json
  - common/policies.json
tenants:
  - alias: default
    name: Default organization
    hosts: [tools.xyz.com]
    administratorProvisioningRef: bootstrap/default
    imports:
      - tenants/default.json
  - alias: tenant1
    name: Organization One
    hosts: [tenant1.xyz.com]
    administratorProvisioningRef: bootstrap/tenant1
    imports:
      - tenants/tenant1.json
  - alias: tenant2
    name: Organization Two
    hosts: [tenant2.xyz.com]
    administratorProvisioningRef: bootstrap/tenant2
    imports:
      - tenants/tenant2.json
```

The bootstrap references identify separately protected, tenant-bound administrator provisioning material. They do not embed passwords, private keys, or an authentication bypass in the manifest. Preserve existing reviewed installation/provisioning authority requirements.

With no manifest, Quickstart constructs the equivalent default-only manifest internally. An explicitly configured manifest or import that is missing or invalid is an error, not an instruction to silently use empty defaults.

Composition order for each tenant:

1. Load the release-pinned initial configuration bundle.
2. Apply common imports in manifest order.
3. Apply tenant-specific imports in manifest order.
4. Integrate tenant-specific installation identities through the authorized bootstrap workflow.
5. Validate the complete configuration and render a preview/diff.
6. Commit tenant configuration, initialization marker, audit and authority/revision changes atomically; activate the tenant only when ready.

Merge semantics:

- Identify directory records by entity kind and ID. Reject duplicates within a file. Later layers replace matching complete records; omitted records remain.
- Treat record arrays and membership lists as explicit replacements when the matching record is replaced. Do not concatenate arrays or silently union permissions.
- Resolve settings with a documented schema-aware merge: omitted fields inherit; scalar values and arrays replace; null is accepted only where the schema permits it. An empty array explicitly clears an allowed list.
- Common templates omit tenant identity. Tenant-specific snapshots must name the target tenant; mismatches fail validation.
- Exclude tenant hostname ownership, administrative identities, credential material, private keys and tenant-specific secret bindings from common templates. Permit reusable policy/group definitions and non-secret settings.
- Enforce protected defaults, reference integrity, permission ceilings, package digest requirements and record/size limits after composition. Later layers cannot override those constraints.
- Do not introduce implicit deletion through initialization overlays. Removal of established configuration uses the reviewed runtime import workflow.

Use common imports as one-time templates, not live inheritance. Updating a common file must not change an active tenant or overwrite its administrative edits.

## 5. Initialization consistency and lifecycle

Unify the release bundle, installation packet, optional initial import and environment-owned settings paths behind a tenant-aware provisioning coordinator. Keep their trust boundaries explicit; setting a file path must not bypass required installation identity verification.

The existing initializer applies configuration and records its completion marker in separate transactions. Replace that separation with a transaction boundary covering all authoritative tenant initialization writes.

Remove `toolgate.config.import-overwrite` and its independent settings-reimport API. `ConfigurationImportBootstrap.start()` currently reads that flag, and `ServerSettingsService.importAtStartup(...)` can overwrite saved settings on every restart. The new configuration contract rejects the removed setting if supplied, including `false`; it does not provide a deprecated compatibility path. Apply `TOOLGATE_SETTINGS_*` initialization inputs through the coordinator on first creation only. Later changes require the reviewed tenant import workflow.

Implementation requirements:

- Prevalidate the complete manifest and referenced files before initializing any new tenant. Reject duplicate IDs/hosts, invalid references and unsupported fields early.
- Use bounded reads and resolve imports within the configured initialization directory; do not accept remote includes or paths escaping that directory.
- Model provisioning as an idempotent operation with a durable operation ID and steps for registry allocation, configuration application, external resource creation and activation. Where external resources are involved, use an outbox/retry workflow with explicit cleanup, not one long database transaction or an assumed distributed rollback.
- Lock initialization per tenant using database-backed serialization. Replicas must not create duplicate administrators, memberships, audits or initialization markers.
- Compute a canonical effective configuration digest and retain ordered source/bundle provenance. Do not log bootstrap secrets or store unredacted secret-bearing source files in the registry.
- Make successful initialization a one-time event. Changing a source digest on restart must not authorize another bootstrap import. Report that the tenant is already initialized and route later changes through reviewed configuration management.
- If a database transaction fails, leave no partially activated tenant. A retry must safely resume provisioning without resetting epochs or replaying external side effects.
- Apply any external credential creation through an explicit idempotent provisioning step; do not assume a database rollback reverses external effects.
- Commit independently per tenant after global manifest validation. If one tenant fails, already active tenants keep serving; expose the failed tenant's provisioning status and fail only that tenant's traffic. Do not allow one failed tenant to make the entire shared deployment unready.
- Keep schema failure and invalid deployment-wide configuration fatal to startup. Define readiness separately from individual tenant readiness.

Add operator commands/APIs for provision, preview, inspect and suspend/resume. Provisioning tenants later must call the same service used at initial bring-up. Ordinary tenant administrators cannot register another tenant, claim hostnames or reopen bootstrap mode.

## 6. Public request routing and authentication

Implement equivalent tenant resolvers in Control and Gateway with shared contract fixtures.

```text
Public hostname
  -> exact registered host mapping
  -> active tenant context
  -> verified token or device certificate
  -> require authenticated tenant == resolved tenant
  -> tenant-scoped application operation
```

The host selects a workspace; it never proves membership. Resolve the tenant before login, public discovery and enrollment as well as authenticated routes.

- Reject unknown hosts, malformed/ambiguous authorities, disabled mappings and suspended tenants. Do not create tenants from DNS names or a first login.
- Accept forwarded host information only on explicitly trusted proxy connections. The proxy must strip client-supplied forwarding values before setting its own.
- Validate origin/redirect URLs against the resolved tenant. Do not construct privileged links or redirects from arbitrary Host headers.
- Require verified token `tenant_id` to match public tenant context before identity observation or data access. Missing tenant claims are not defaulted.
- Treat internal machine routes separately: derive their tenant from verified service credentials, not the internal service hostname or a client-supplied tenant header.
- Return bounded errors without revealing another tenant's configuration.

Domain-to-tenant routing and authenticated access remain separate checks, consistent with [Azure request-mapping guidance](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/map-requests). Do not use a browser-supplied tenant UUID or a wildcard DNS match as proof of membership.

### 6.1. Database and asynchronous isolation

Require PostgreSQL row-level security (RLS) on tenant-owned tables in V1 alongside application filters and composite tenant keys. PostgreSQL documents both default-deny behavior and the [owner/superuser bypass rules](https://www.postgresql.org/docs/current/ddl-rowsecurity.html); role configuration is part of the implementation, not an assumption.

- Make `PostgresStore.transaction(TenantId, boolean, Function<Session,T>)` the single runtime entry point that sets PostgreSQL tenant context. Validate the canonical UUID, then after starting the transaction and before any tenant SQL, advisory-lock-dependent work, `JdbcSession` creation or callback invocation, execute parameterized `SELECT set_config('toolgate.tenant_id', ?, true)` on that same connection. Apply it to read and write transactions. Policy expressions use that exact setting and reject missing/empty context. Commit/rollback clears the transaction-local value; never use a connection-wide tenant setting that can leak through the pool. Do not scatter context-setting code across adapters or individual repositories. Skip the PostgreSQL statement on SQLite.
- Define `USING` and `WITH CHECK` policies for reads and writes. Deny a cross-tenant insert/update as well as hiding rows. Enable and force RLS where appropriate; the runtime role is neither an owner, a superuser nor a `BYPASSRLS` role.
- Review policy composition so another permissive policy cannot widen tenant access. Deny runtime schema/policy changes, `TRUNCATE` and other table-wide operations that RLS does not constrain. Validate view/function privileges and direct partition access under the supported PostgreSQL version.
- Use `(tenant_id, resource_id)` ownership keys and matching foreign keys for references. Review global unique constraints and error responses so one tenant cannot inspect another tenant's records through conflicts.
- Make tenant context a required repository/transaction argument. Keep raw database connections and platform-wide queries behind separate, narrowly privileged operator paths. Registry resolution exposes only the metadata needed to select an active tenant; it does not grant cross-tenant data access.
- Test missing context, deliberately omitted SQL filters, cross-tenant writes and reuse of the same pooled connection by different tenants. RLS limits query mistakes; it does not contain a fully compromised application that can deliberately choose another authorized database context.
- Every durable job, outbox entry, dispatch and result carries tenant identity, operation ID and the required authority/fencing information. Consumers derive context from authenticated producers and stored ownership and recheck current authority before effects.
- Cache keys, object-store authorization, secret access and result URLs must include tenant scope. A tenant-prefixed object name alone is not an access-control policy.
- Global maintenance uses a separately authorized enumeration step followed by bounded tenant-scoped transactions. It must not silently disable RLS for normal runtime work.

`PostgresStore.tenants()` currently borrows a connection and reads all `control_tenants` outside `transaction(...)`, with a fixed 10,000-row limit. Classify it explicitly as a platform-only path. Move it behind a paginated platform registry/enumeration interface with narrowly scoped privileges and a separate authorization/connection boundary; ordinary tenant repositories and request handlers cannot call it. Watchdogs and platform workers enumerate there, then call `PostgresStore.transaction(...)` per tenant for actual work. Do not give the general tenant runtime role unrestricted registry access or add a default context to make enumeration work under RLS. CI must identify raw connection borrowing outside these approved boundaries.

Registry and authority access are separate and settled in V1:

- `control_tenants` owns registration and lifecycle; normalized hostname tables reference it. Tenant runtime has no direct registry `INSERT`, `UPDATE`, `DELETE` or cross-tenant `SELECT` privilege. Only explicit platform provisioning inserts a UUID tenant in `PROVISIONING` and its one authority row.
- `control_tenant_authority` owns revision, authorization epoch and cache generation, with a non-null UUID-string primary/foreign key. Tenant runtime may `SELECT` and constrained `UPDATE` its own authority row under RLS; it may not insert/delete that row or change its tenant ID. Keep monotonic counter guards here.
- Replace `PostgresStore.JdbcSession.save(...)`'s `INSERT INTO control_tenants ... ON CONFLICT DO UPDATE` with an optimistic `UPDATE control_tenant_authority ... WHERE tenant_id=? AND revision=?` guarded by active status. Require exactly one affected row, otherwise roll back and fail closed. No upsert, implicit registration or default revision/epoch when an authority row is missing.
- Move `PostgresStore.directoryVersion()`, directory/revision reads, `PostgresEnterpriseStore.authorizationEpoch()`, authorization outbox queries and all other authority-counter SQL to `control_tenant_authority`. Missing rows are errors, not revision/epoch zero. Rewrite both PostgreSQL and SQLite cache-generation triggers to update an already provisioned authority row and fail if it is absent; no trigger may insert registry or authority rows implicitly.
- Supply active-status checks through a narrow, audited database helper that reveals only whether the transaction's current UUID is active. Its fixed-search-path, least-privilege implementation may read the registry; runtime receives only execute permission, not broad registry access. Authority RLS/write checks include this guard. For write transactions the helper must hold a shared lock on the registry row until commit so platform suspension/lifecycle updates serialize against admitted writes; do not attempt row-locking reads in PostgreSQL read-only transactions.
- Ordinary `PostgresStore.transaction(...)` work rejects unregistered, `PROVISIONING` and `SUSPENDED` tenants before callback execution. Read paths also require an active registry result and existing authority row. Long-running external effects still require their current-authority checkpoints; database gating alone does not promise instantaneous termination.
- A dedicated platform provisioning transaction, inaccessible through tenant runtime APIs/credentials, may seed the authority row and initial tenant records while status is `PROVISIONING`; it validates initialization before changing the registry to `ACTIVE`. Reuse the scoped transaction-context implementation without adding a caller-controlled runtime bypass flag. Tenant creation tests/fixtures must provision registry and authority explicitly rather than depending on `save(...)` to create them.

SQLite uses the same separate tables, update-only authority rules and lifecycle checks under its transaction model, without claiming PostgreSQL RLS or row-lock syntax.

SQLite Quickstart has one provisioned tenant and retains scoped repository checks and negative tests; PostgreSQL RLS is not emulated or claimed for SQLite.

### 6.2. Mandatory tenant ownership on every tenant-data table

Make `tenant_id NOT NULL` mandatory on every application table containing tenant-owned data, including join/child/history tables. Do not infer ownership only through a parent table, JSON document, filename or application convention. Apply this rule to the default tenant as well. Store the canonical lowercase UUID string in every such column, retaining V1 `varchar(128)`/text types consistently. Aliases never appear in ownership columns; there are no old-key mappings or staged data conversions.

Maintain the checked `apps/control-plane/src/main/resources/db/schema-ownership.json` inventory with table name, classification, tenant column/type, ownership constraints, RLS policies and permitted runtime roles. The completed registry/authority split is recorded there; the inventory must track every new table and constraint as implementation proceeds. This is a release artifact, not an assumption based on selected examples. The design combines the layered checks in [OWASP multitenant guidance](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html) with the database enforcement illustrated by [AWS SaaS Factory's PostgreSQL RLS example](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/).

| Table category | Required ownership |
| --- | --- |
| Users, memberships, roles, grants, policies, bindings and settings | Non-null tenant ID on every row |
| Devices, identity bindings, certificates, enrollments and console sessions | Non-null tenant ID, including pending and retired records |
| Invocations, attempts, tasks, approvals, leases, permits, results and reconciliation | Non-null tenant ID on primary and child/history rows |
| Packages, documents, deployments, host registrations, Fleet and Builder records | Non-null tenant ID even when content digests or resource IDs repeat |
| Audit, outbox, jobs, replay/idempotency, quotas and recovery floors | Non-null tenant ID; no shared runtime table with implicit ownership |
| Secrets, secret versions/bindings, tenant keys and rotations | Non-null tenant ID plus tenant-scoped permissions and encryption context |
| `control_tenant_authority` | Non-null canonical tenant ID; own-row runtime SELECT/UPDATE under RLS; only provisioning inserts |
| `quickstart_vault` placeholder | Remove from both V1 definitions and grants; replace its lock-only use with a transaction advisory lock |
| Tenant registry, hostname mappings, tenant provisioning and placement assignments | Explicit tenant ID; platform-only registration/enumeration privileges |
| Truly global framework/platform metadata | Explicit reviewed exception; no customer payloads or tenant credentials |

Global exceptions are narrow: Flyway schema history, SQLite migration checksums and deployment-level metadata that contains no tenant-owned content. Do not attach these records to the default tenant or give ordinary tenant identities access to them. A table containing customer information cannot be classified as global to avoid the tenant-ID rule. Pre-tenant platform security events belong in a separate restricted platform event stream, not a nullable tenant audit table. Shared/public catalog data needs an explicit data-sharing design before it can become an exception; tenant uploads remain tenant-owned.

The reviewed baseline's `quickstart_vault` stored no vault data: its only non-DDL reference was the lock target in `apps/quickstart/supervisor.py:Database.execute(...)`. The vault API proxies to Control, whose actual encrypted secret records are in `control_secrets`. The first implementation checkpoint removes this unused table and its grants from both V1 schemas and replaces the PostgreSQL translation of `BEGIN IMMEDIATE` with parameterized `pg_advisory_xact_lock`, using a reserved Quickstart-operation namespace and the configured tenant value. The canonical UUID provisioning work will supply that value in the final design; there is no assertion that the current hard-coded tenant identity is already replaced. SQLite keeps its real `BEGIN IMMEDIATE`. All callers protecting the same critical section must use that lock key and transaction lifetime; lock acquisition failure aborts the operation. Record the placeholder as removed in the ownership inventory; the advisory lock creates no global data-table exception.

V1 enforcement and qualification:

- Fill ownership from verified server context, never directly from a request body. Reject missing context and mismatched payload IDs; do not add a database default that silently assigns rows to the default tenant.
- Tenant-scope primary/unique keys and foreign-key relationships. A child row with tenant1 must not reference a tenant2 parent even if the parent ID is globally unique. Retain permanent tenant tombstones where necessary for immutable audit/retirement history; do not cascade-delete evidence during tenant offboarding.
- Require explicit ownership for bulk imports, batch APIs, upserts, background jobs and administrative scripts. Review views, triggers, stored functions, security-definer code, partitions and materialized data for bypass paths.
- Define non-null ownership, constraints and RLS directly in V1 for empty databases. No unscoped-table backfill exists. Schema qualification rejects missing constraints and fixtures with orphaned ownership instead of assigning records to a default tenant.
- Add CI catalog checks that fail if a new application table is absent from the ownership manifest, a tenant-data table lacks non-null ownership/RLS, or a runtime role can bypass it. Validate deployed role permissions as well as schema definitions.
- Run negative database tests using the actual runtime role: omitted filters, null tenant, cross-tenant joins and foreign keys, bulk writes, pooled-connection reuse and indirect access through views/functions. Check tenant-specific exports and backups as well as HTTP responses.

Tenant IDs are necessary ownership metadata, not a guarantee against a breach. RLS, authorization, credential custody, execution isolation and negative tests enforce the boundary together.

## 7. Service, identity and execution refactoring

Replace singleton tenant fields in Endpoint, discovery, MCP relay, Fleet, Builder and Tool Host adapters with explicit resolved tenant context and tenant-scoped configuration. Audit all affected entry points, including WebSocket upgrades, enrollment polling, package downloads, permit consumption, result reporting and secret delivery.

### 7.0. Concrete refactor inventory and CI boundary

The source inventory currently has nine Control classes reading the old endpoint tenant setting (the original eight plus `SdkBuilderBootstrap`). Not all reads are startup observers: request-time helpers are equally in scope.

| Class in `apps/control-plane/src/main/java/io/ololabs/toolgate/control/adapter` | Replacement responsibility |
| --- | --- |
| `AuthorizationHealth` | Deployment readiness plus separately scoped tenant status; no single configured tenant determines shared readiness |
| `ConfigurationImportBootstrap` | One-time coordinator per target tenant; remove repeated settings overwrite |
| `CorePackageBootstrap` | Idempotent package/bootstrap work per provisioned tenant |
| `EndpointBootstrap` | Tenant-aware service/configuration/issuer resolution instead of one fixed tenant |
| `EnterpriseOperationResource` | Verified request/device tenant for permits, effects, secret delivery and events |
| `FleetBootstrap` | Tenant-specific Fleet/Builder configuration and trust |
| `PackageProvisioningBootstrap` | Tenant-owned provisioning jobs and device-following work |
| `PackageResource` | Certificate/request-bound tenant for host registration, content and dispatch |
| `SdkBuilderBootstrap` | Tenant-owned build queue, worker inputs and artifacts; bounded platform scheduling |

Refactor their application-service constructors and retained tenant fields as part of the same phase, including `EndpointService`, `McpService`, `FleetService`, `BuilderService` and `SdkBuilderService`. Include `deploy/helm/olo-toolgate/templates/control.yaml`, chart values/schema, deployment env examples and `apps/quickstart/supervisor.py` configuration generation. `TenancyConfiguration` consumes only the new alias/manifest inputs and resolves them to the canonical UUID; it does not adapt an old endpoint tenant setting.

Add a CI check over source and deployment generation that rejects any runtime use of `toolgate.control.endpoint.tenant-id` / `TOOLGATE_CONTROL_ENDPOINT_TENANT_ID`, including `@ConfigProperty`, config lookups and composed prefix/helper reads such as `EndpointBootstrap.setting("tenant-id")`. Explicit unsupported-setting validation, documentation and negative fixtures are the only textual exceptions, not a runtime reader allowlist. Prove the guard detects direct and helper-based reads; Helm/supervisor output must never emit the removed setting or alias strings in runtime tenant identity fields.

Use a request/operation-bound immutable tenant context. Background jobs receive an explicit tenant ID; do not rely on a thread-local tenant surviving asynchronous work.

Gateway and authentication changes:

- Select Gateway-to-Control credentials by tenant, bind them to that tenant and preserve rotation/expiry checks. Prefer short-lived, audience-restricted credentials with automated renewal. Do not create one unrestricted gateway credential that accepts any caller-supplied tenant. Protected, atomically replaced files may deliver these credentials; this is a transport choice, not an old-identity compatibility mechanism.
- Include tenant identity together with the resource ID in Tool Host routes, invocation/result lookups, caches, dispatch state, deduplication and replay records wherever identifiers can repeat.
- Start with a shared IdP issuing tenant-bound tokens. Model authentication references per tenant so separate issuer/key configurations can be added without redesigning routing. Independent customer IdPs are a later milestone.
- Keep browser cookies host-only, with Secure on HTTPS, HttpOnly and the required SameSite/CSRF protections. Never set a shared `.xyz.com` authentication cookie. Validate tenant binding even if a cookie is replayed manually.
- Display the selected tenant at login and in the console; do not require users to type a tenant ID. Reauthentication on a different registered host selects another workspace.
- Scope first-administrator setup and all management permissions to the target tenant. Platform-operator authority is separate and auditable.

Assign Tool Host pools to a single tenant for the initial shared release. Maintain separate identities and protected state/work directories. Do not schedule unrelated tenants' untrusted code together until the unfinished process, filesystem, resource and network isolation is implemented and qualified.

A dedicated pool must be an enforced execution boundary, not only a database label: run different tenants on separate hosts/VMs or another independently qualified isolation boundary, with distinct workload identities and no shared readable credential/work directories. Separate network/egress policy and pool registration prevent one pool from accepting another tenant's dispatch. Two ordinary child processes under one OS identity are not this boundary.

### 7.1. Bind each device installation to one tenant UUID

Each managed endpoint and Tool Host installation has exactly one persisted tenant binding. Add device configuration equivalent to:

```json
{
  "tenantId": "4a130f27-89bb-4eb9-bb12-3c28473d59e1",
  "gatewayUrl": "https://tenant1.xyz.com",
  "controlUrl": "https://tenant1.xyz.com:8443"
}
```

These are proposed device configuration fields. Use the existing configuration's nesting where appropriate when implementing them. The UUID is a tenant selector, not a credential or proof of permission.

- Before first enrollment, require agreement between configured tenant UUID, verified signed discovery, the registry mapping and the tenant receiving enrollment. First enrollment pins that binding in protected local state and the issued device identity.
- Generate a distinct private key locally for each installation and issue an individual, renewable device certificate whose tenant identity is the canonical UUID. Validate trusted issuer, tenant/device identity, certificate validity and current server-side enrollment/revocation status on use. Short certificate lifetime does not replace current-authority checks. Specify one first-release identity encoding and matching verification, without an old-certificate adapter.
- Tenant UUID and discovery alone never enroll a device. Reuse the existing approved enrollment challenge. If a console-to-device bootstrap handoff is needed, issue a short-lived single-use grant bound to the authenticated tenant, target audience, origin, nonce and device public-key digest; consume it atomically. Do not introduce a shared fleet password or shared tenant private key. This adopts the unique-device-identity principle from [AWS device provisioning](https://docs.aws.amazon.com/iot/latest/developerguide/iot-provision.html), without requiring AWS or importing its claim-certificate workflow.
- For Quickstart, obtain the default tenant UUID from verified local discovery automatically. No manual UUID entry is required.
- Support fresh enrollment and same-release certificate renewal only. Do not convert existing pre-release device identities or pins; reject unsupported versions with instructions for an explicit fresh enrollment. Never automatically clear a device's stored binding or install an identity adapter.
- Check the binding on presence pings, enrollment polls, check-ins, WebSocket connections, configuration adoption, catalog requests, dispatch, approvals, permits, results and secret delivery. A request from another tenant must not produce a device proof, disclose local enrollment details or execute work.
- Scope persisted configuration, enrollment challenges, certificate/key use, replay state and pending work to the binding. Validate tenant provenance on incoming work even if it arrives over an established connection.
- Changing a hostname within the same tenant requires verified tenant configuration. Moving the installation to another tenant requires an explicit authorized disconnect/re-enrollment flow, retirement of old local authority and fresh approval. Browser navigation, a ping response or a new URL alone cannot rebind it.
- A separately installed client instance may have its own protected identity/state directory. Supporting multiple active tenants in one installation is outside the initial scope.

### 7.2. Request tenant details through the Chrome extension

Extend the existing console-to-extension-to-native-host connection flow:

1. From an approved console origin, request tenant discovery for that origin. Return the advertised tenant UUID and public service URLs to the connection UI; the extension must not accept an arbitrary UUID asserted by page JavaScript as verified identity.
2. Have the native host independently validate HTTPS, signed discovery, advertised origins and the local tenant binding. Bind the native connection request to the expected UUID and console origin.
3. For an unbound installation, show the selected organization and bind it through the existing explicit Connect/enrollment action. For an already bound installation, require the UUID to match before requesting enrollment, device proof or tenant-scoped health.
4. Key extension connection state by validated origin and tenant UUID. Pass expected tenant context on native health, enrollment and proof requests; enforce it in the native host and device service, not only in extension JavaScript.
5. On a different tenant's page, report `TENANT_MISMATCH` with a clear message such as "This device is connected to a different organization." Do not expose the bound tenant's UUID, device ID, fingerprint, approval code, health details or proof to that page.
6. Cancel outstanding connection work when origin/tenant context changes. Do not misclassify tenant mismatch as a missing client or trigger automatic installation, repair or enrollment.

Discovery exposes the public identity of the tenant serving that origin. Requests for local device details still require the existing trusted extension/native messaging boundary and origin checks. Tenant mismatch does not authorize the extension to switch the client.

Bind device proof signatures to the canonical tenant UUID, exact authorized audience/origin, device identity, server-issued nonce and expiry. Verify nonce single-use and current session/enrollment authority server-side. A proof valid for tenant1 or one browser origin cannot be replayed in tenant2 or another origin.

### 7.3. Detect the wrong Gateway through existing discovery

Reuse the `GET /.well-known/olo-toolgate-client` URL as the tenant discovery and connection-check surface, served by Control and exposed at the tenant's public Gateway origin. Do not add a second `POST /access/tenant/ping` protocol with a separate source of truth. The final first-release design uses a **v2 discovery envelope**, containing the canonical tenant UUID, public URLs, signing identity/key-ring metadata and validity window; it does not silently change the v1 signature interpretation. Apply `Cache-Control: no-store` initially and bounded admission.

A native client already bound to a tenant may send an optional `Toolgate-Tenant-ID` header with its expected UUID. This is only a compatibility assertion, never authentication. The server compares it to the host-resolved tenant without looking up or exposing the asserted tenant's resources. If omitted, discovery returns only the public metadata for the active host tenant. On protected device routes the verified certificate identifies the tenant; this header is never required and its absence is not an error. If supplied, it must agree with both the verified identity and host context. Browser discovery can omit the header and compare the verified document; do not broaden CORS to allow arbitrary pages to query local device state.

The native client validates HTTPS server identity and the signed discovery trust chain, then compares the advertised tenant UUID against its protected binding before sending enrollment material or credentials. The document's own embedded CA is not sufficient independent proof of trust: initial trust comes from verified HTTPS or an explicitly managed trust anchor; local Quickstart uses its existing protected bootstrap. A matching discovery response reports reachability and tenant compatibility, not device approval or execution authority.

Discovery signing is **per tenant**. Provision one purpose-specific asymmetric discovery key/key version for each tenant, including Quickstart's default tenant; never select a deployment-wide fallback signer. The key is separate from TLS, device-CA, JWT-verification, policy/permit-signing and vault-encryption keys. Persist only tenant-owned key IDs and protected provider/custody references in registry configuration, not private key bytes in common templates or exports. `TenantDiscoverySigner.sign(resolvedTenant, payload)` selects that tenant's key server-side and verifies payload UUID, issuer/audience, canonical origins and validity window against registry configuration before signing.

The versioned signed envelope authenticates its key ID and algorithm as well as the payload; tenant identity, URLs and expiry cannot be substituted. Bootstrap trust for the signing public key comes from authenticated HTTPS discovery/protected operator provisioning, not from accepting a self-asserted tenant UUID/key pair. Store accepted tenant/key trust with the local binding; rotate with an overlapping key ring and an authenticated update path, never by unverified URL changes. An active tenant without a usable signing key remains unavailable for discovery under the same public unavailable-response policy. A shared KMS/provider may protect multiple tenant keys, but document that provider's shared compromise boundary and never reuse the same discovery private key across tenants. Refactor the current `X509DeviceIssuer.discovery(...)` path, which uses the device-CA private key, into the tenant discovery signer rather than leaving implicit key reuse.

Ship the producer and consumer protocol changes as one phase-4 cutover:

- Phase 2a handles vault keys only. It neither provisions nor selects the new discovery signer; the development build keeps its current device-CA-signed v1 discovery until phase 4 is complete. This is sequencing of unreleased implementation, not a supported old-installation upgrade path.
- Phase 4 adds the per-tenant discovery key provisioning hook and v2 schema/envelope, authenticated algorithm/key ID, domain-separated signature input, key-ring trust/rotation and independent device-CA metadata. Only then does the server select v2 signing for the qualified release.
- Update `apps/endpoint-client/src/identity.rs:verify_discovery(...)` to verify the v2 discovery key ring rather than using the device-CA certificate as its signature key. Validate the requested discovery audience/public origin against the configured origins; a separate canonical Control URL must not be mistaken for the browser/Gateway origin. Retain independent device-CA validation for enrollment certificates.
- Update endpoint configuration/state, native host request/response protocol, browser-extension connection flow and their fixtures to carry the v2 version, UUID and key-ring metadata. The native verifier remains the trust decision point; the extension must not substitute its own unverified public key.
- Update Quickstart signing/provisioning and distribution artifacts in the same change. Gate activation/publication of v2 on end-to-end verification by the matching endpoint/native/extension build. Until that passes, do not label the intermediate build as supporting the new multitenant discovery protocol.
- In the final first release, reject unsupported v1 envelopes/clients explicitly. There is no dual verifier, downgrade fallback, old pin conversion or v1 compatibility mode. Test unknown/revoked key IDs, cross-tenant ring substitution, algorithm/version tampering and rotation using v2 only.

| Condition | Response | Device/extension behavior |
| --- | --- | --- |
| Active host; no expected UUID, or the expected UUID matches | `200`, versioned signed discovery | Validate and compare UUID locally, then continue authentication |
| Registered host belongs to another tenant than a supplied expected UUID | `403`, `code: TENANT_MISMATCH` | Stop this connection attempt, retain binding and display a wrong-organization URL message |
| Malformed supplied UUID or duplicate tenant header | `400`, `code: INVALID_TENANT_ID` | Correct configuration; do not guess or fall back |
| Unknown/unregistered host, or host tenant is provisioning/suspended, before authentication | Identical `404`, `code: TENANT_UNAVAILABLE` | Retain binding and report unavailable; never select the default tenant |
| Missing/invalid authentication on a protected route | `401` | Report authentication failure; do not infer a trusted tenant from unverified claims |
| Valid authenticated identity belongs to another tenant on a protected route | `403`, `code: TENANT_MISMATCH` | Deny before tenant data access or any device state change |
| Server cannot serve the requested HTTP authority/connection context | `421` where emitted by the origin according to HTTP semantics | Report routing/authority failure, without changing tenant binding |
| TLS hostname/certificate or handshake validation fails | Transport error; an HTTP response may be unavailable | Report the connection/TLS failure; never bypass verification to obtain a mismatch response |

Before authentication, evaluate unknown/non-active host state before expected-UUID comparisons. Use the same bounded error structure, headers and retry policy for unknown, provisioning and suspended tenants, with no state-specific redirect or `Retry-After`. Do not deliberately vary timing or include status details. Operator tooling and an authorized tenant-status interface can expose operational reasons after authentication; public discovery does not. An active tenant's successful discovery is intentionally public, so the design hides inactive-state distinctions, not the existence of every active public domain. Protected status handling may distinguish failure reasons only after authorization to that tenant and must not depend on the optional header.

Mismatch errors contain a request ID and stable code, not another tenant's canonical URL, registry details or device identity. Do not redirect discovery or forward credentials to a purported correct URL. The client uses its saved verified configuration or explicit reconfiguration to correct the destination. Successful discovery does not replace continuous binding checks on actual requests.

This corrects the earlier design's use of `421 TENANT_MISMATCH`. [RFC 9110 section 15.5.20](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.20) defines 421 for misdirected authority/connection context and permits clients to retry over another connection. Ordinary tenant authorization mismatch uses 403; it must not invite a connection retry as if routing alone could grant access.

Define these response details in versioned contracts and extension/native protocol fixtures. Preserve existing health/liveness endpoints for infrastructure; do not use global Gateway readiness as proof of tenant compatibility.

### 7.4. Tenant-owned secrets, keys and delivery

Secrets are tenant data throughout storage, encryption, authorization, caching, execution and recovery. A UUID prefix is an addressing convention, not a security boundary. [Azure's vault isolation guidance](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/service/key-vault) distinguishes shared and dedicated vaults and warns that an application identity with access to every tenant's secrets remains a shared compromise boundary. Choose the following concrete model for ToolGate.

Existing implementation evidence to retain and extend:

- `VaultService` already uses tenant-scoped store transactions and tenant/name/group AES-GCM authenticated data.
- `PostgresEnterpriseStore` selects and upserts secrets by tenant plus secret name. Identical names in different tenants must remain independent.
- `VaultResource` currently obtains one deployment-level raw AES key from `toolgate.control.vault.key-path`. Replace direct use of that one key for all customer ciphertext with a tenant key provider.
- The reviewed baseline's Tool Host sent a fencing token that the resource/service did not pass into the secret-authority check. Phase 0 now fixes this path and verifies the consumed, current-attempt fence before delivery; the remaining tenant key work builds on that enforcement.

Storage and encryption requirements:

1. Every secret row, version, reference, binding, key-wrap record, audit event and rotation job has a non-null tenant ID. Apply RLS and tenant-aware ownership constraints to all customer secret tables.
2. Use a separate randomly generated data-encryption key (DEK) per tenant, with explicit key/version metadata. Envelope-encrypt it under a protected key-encryption key (KEK); keep unwrapped keys out of database backups and configuration exports. Use a vetted cryptographic library/provider rather than inventing a cipher or deriving keys directly from a public UUID.
3. Quickstart uses one tenant DEK wrapped by a protected local KEK. Shared deployments use a restricted external KMS/vault wrapping provider or an operator-managed protected wrapping service. Define the provider interface now; qualify at least one supported backend before shared release rather than listing every vendor as implemented.
4. Include an unambiguous, versioned encoding of tenant identity, immutable secret ID, secret version and authorized binding context in AES-GCM AAD. Include non-secret tenant/key identity as encryption context when the wrapping provider supports it. [AWS KMS documents encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html) as authenticated contextual data; it is not a place for plaintext secrets.
5. Store key IDs/versions with ciphertext and prohibit silent fallback to a default tenant/key. A wrong tenant, modified context or unknown/retired key version fails closed. Derive separate purpose-specific keys for encryption and retry-digest authentication; do not reuse the raw AES key for unrelated cryptographic operations.
6. A shared KEK/provider identity is an explicitly documented platform trust boundary. For customers requiring stronger isolation, allow tenant-dedicated KEKs/vaults together with dedicated application credentials/deployments. A DEK per tenant alone does not contain compromise of a service that can unwrap all DEKs.

Secret authorization and delivery requirements:

- Resolve tenant from verified principal/device identity, then check active tenant state, tenant-owned secret, group/binding permissions and current invocation authority. Reject a caller-supplied reference to another tenant even when the secret names, device IDs and invocation IDs are the same.
- A change to binding/group fields included in authenticated encryption requires authorized re-encryption and a new version. Do not mutate ownership metadata while keeping ciphertext tied to the old context.
- Require the device/Tool Host certificate, executing invocation, current fencing token/attempt, secret declaration and delivery audience to agree before decryption. Bind broker handles to tenant, invocation, attempt and intended use; reject stale/replayed handles.
- Management read permissions expose authorized metadata only. They do not automatically reveal values. The Chrome extension, browser connection flow and Gateway discovery never receive secret values or decryption keys.
- Prefer broker-side credential injection for destination-bound credentials. If a declared tool must receive plaintext, deliver only its authorized secret for that active invocation; never mount a tenant's entire vault or use a host-wide plaintext environment file.
- Prefer no reusable plaintext secret cache initially. If key/secret caching is necessary, key it by tenant, secret/version and purpose, keep it bounded and short-lived, and invalidate on revocation/rotation/suspension. Cached material never bypasses fresh authorization. Avoid logging values, credentials, secret-bearing payloads or sensitive names in shared diagnostics.
- Common initialization contains no secret values or cross-tenant secret bindings. Resolve tenant-specific secret references through protected provisioning inputs; never inherit tenant1 credentials into tenant2 when applying common templates.
- Separate deployment credentials (database migration, platform TLS, wrapping-provider credentials) from customer vault contents. Tenant administrators and tools cannot retrieve platform secrets.

Rotation and first-release format requirements:

- Define tenant key and ciphertext metadata directly in both V1 schemas. Provision secrets directly with the tenant envelope format. Pre-release ciphertext is unsupported: no data conversion, raw-key reader or re-encryption path is included. An unsupported ciphertext format fails closed without changing stored data or falling back to a default key.
- Distinguish upstream credential rotation from encryption-key rotation and KEK rewrapping. Invalidate handles/caches and apply current authority on each operation. Retain decryption access needed for authorized recovery while preventing retired credential use.
- Back up ciphertext and wrapped DEKs with tenant/version ownership; keep KEK custody and recovery authorization separate. Test a single-tenant restore without decrypting or changing another tenant's secrets. Offboarding must respect retention/legal recovery policy before key destruction.

## 8. DNS, TLS and deployment

Add Compose/Helm examples for default-only and shared subdomain operation. Configure exact tenant host routes or wildcard DNS/TLS for `*.xyz.com`, with application registry checks in either case. The apex domain needs its own explicit mapping and certificate coverage if used.

The selected first-release topology pools Control/PostgreSQL and uses a separate Gateway process/release for each tenant, with dedicated workload/service-token mounts and tenant execution pools. Replicas of one Gateway keep that same UUID; incoming hostnames or asserted IDs never choose another tenant's credentials. This supports `tenant1.xyz.com` and `tenant2.xyz.com` with separate ingress routes while Control maintains registered host/UUID mappings. Embedded SDK builds and customer tool processes cannot share Control's platform/key custody; isolated workers or externally built verified packages are required for shared release qualification. See `docs/gateway/configuration.md` and `docs/gateway/deployment.md` for the implemented Gateway boundary.

Browser/API/MCP traffic can use a trusted HTTPS reverse proxy with path routing. Device identity routes must preserve direct peer-certificate verification. Specify a separate published direct-mTLS Control listener, for example `https://tenant1.xyz.com:8443`, with TCP passthrough where necessary. Keep its URL in tenant discovery/configuration; do not trust forwarded certificate headers.

Each tenant's signed discovery document contains its own tenant ID, organization name and canonical URLs. DNS routing, SNI/certificate validation, HTTP host resolution and authenticated tenant identity must agree. Keep device mTLS routes off a listener that terminates TLS without an approved certificate-authentication design.

Use the dedicated per-tenant discovery signing keys and key-selection/rotation rules in section 7.3. A deployment TLS certificate may cover several registered hosts; that does not make the tenant discovery signer or tenant device identity shared.

Quickstart retains its local HTTP console and HTTPS device path. Its loopback-only proxy and local certificate rules remain explicit; subdomain support must not turn the Quickstart authentication proxy into a general public proxy.

## 9. Operational isolation and recovery

Before declaring shared deployments supported:

- Add tenant request/concurrency limits and queue/storage accounting alongside deployment-wide safety limits. Existing policy execution budgets do not replace infrastructure admission limits.
- Prevent one tenant from monopolizing database connections or background workers. Paginate tenant enumeration, bound work per sweep and isolate per-tenant failures.
- Make operational status available per tenant while retaining bounded metric cardinality. Keep platform-wide diagnostics restricted to operators.
- Extend backup review/quarantine to validate all declared tenants in a shared database. Preserve monotonic authority, session and credential floors and retirement evidence per tenant.
- Define single-tenant recovery from a shared database: restore to an isolated environment, extract the reviewed tenant state and apply it through an explicitly fenced recovery procedure. Do not overwrite a live shared database or recover only directory JSON while leaving inconsistent runtime authority.
- Make suspension deny new authentication-dependent actions and new dispatch, including use of existing credentials. Define how active invocations drain or terminate; do not claim immediate emergency termination until kill delivery is implemented.
- Keep deletion/offboarding as a separate reviewed operation covering retention, secret retirement, devices, domains and external artifacts. Do not automatically purge a tenant because it disappears from the initialization manifest.

## 10. Delivery phases and dependencies

| Phase | Deliverable | Completion gate |
| --- | --- | --- |
| 0 | Independent secret-delivery fencing bug fix: pass `Toolgate-Fencing-Token` through `EnterpriseOperationResource.secret(...)` and `VaultService.deliver(...)` into `EnterpriseOperations.secretAuthority(...)`; verify current executing attempt/nonce and device before decryption; update all callers | Missing, stale, mismatched and cross-invocation fences deny delivery; correct current fence succeeds; no registry/RLS/key-provider prerequisite |
| 1 | Finalize one complete V1 per storage mode; registry/authority split, canonical UUID-string identity, initialization tracking, RLS and transaction gating; remove `quickstart_vault` and use a Quickstart advisory lock | Fresh-schema ownership audit, no implicit tenant/authority creation, active-state/update-only tests, registry/runtime privilege separation and unsupported-database rejection |
| 2 | Default tenant and common/per-tenant provisioning coordinator; remove `import-overwrite` restart behavior | Default-only bring-up; deterministic, atomic/idempotent initialization; saved settings survive restart |
| 2a | Tenant vault key provider/new-format secrets, current-authority delivery and rotation; no discovery protocol change | Depends on phase 1 context and phase 0 fencing; fresh-secret isolation tests; current CA-signed development discovery unchanged |
| 3 | Host/context resolution and section 7.0's nine Control readers, their constructors, Helm and `supervisor.py`; tenant-only repositories; new alias config with removed-setting validation and CI guards | Two tenants with repeated identifiers share Control safely; no removed-setting runtime reads or compatibility resolver; generated tenant identity fields contain UUIDs only |
| 4 | Gateway credentials, console/device binding, per-tenant discovery key provisioning, v2 signing and endpoint-client/native/extension v2 key-ring verification together | Matched server/client artifact cutover; no v1 downgrade fallback; two-tenant flows, optional header, uniform public errors and v2 key rotation all verified |
| 5 | DNS/TLS/ingress examples and operator tenant administration | Single/shared deployment smoke tests, direct device mTLS, provision/suspend/resume, no public inactive-state enumeration |
| 6 | Admission quotas, paginated maintenance and background failure isolation | Saturation/fairness tests and platform-only enumeration authorization checks |
| 7 | Shared and individual-tenant backup/recovery qualification | Isolated restore preserves other tenants and authority/key retirement rules |
| 8 | Release qualification and documentation | Fresh Quickstart and PostgreSQL matrix, same-release restart/recovery, CI guards and reviewed deployment guide |

Phase 0 is independently shippable and should not wait for multitenancy. Phases 1 and 2 deliver the default-tenant initialization foundation. Phase 2a secrets/key work can proceed once phase 1's tenant-scoped interfaces exist, independently of the full request refactor; its provisioning hook integrates with phase 2 before tenant activation. Shared-subdomain support is complete only after the remaining applicable gates through phase 8; DNS aliases alone do not satisfy it.

Primary code areas include `apps/control-plane`, `apps/gateway`, `apps/quickstart`, `apps/endpoint-client` and its native messaging/install helpers, `apps/browser-extension`, `apps/tool-host`, `apps/admin-ui`, `packages/contracts`, `config/initial`, deployment templates and enterprise recovery tooling. Add new manifest schemas and fixtures without treating tenant-agnostic templates as ordinary tenant-bound runtime imports.

## 11. Acceptance criteria

- Empty-database Quickstart starts with one UUID tenant, alias `quickstart`, and no manifest requirement. Same-release restarts preserve UUID, configuration, identities and authority history. Earlier development databases are rejected unchanged; no upgrade/backfill/adapter acceptance path exists.
- A single-tenant custom domain works through the same application services and cannot be selected through unregistered hosts.
- Common-only, tenant-only and layered initialization produce the documented results; previews show the effective tenant configuration before apply.
- Invalid/missing explicit files, duplicate records/hosts, cross-tenant snapshots, path escapes, broken references and invalid overrides fail without partial activation.
- Supplying removed `toolgate.config.import-overwrite` configuration fails validation regardless of value. `TOOLGATE_SETTINGS_*` inputs cannot mutate an initialized tenant outside reviewed import. Test multiple restarts and changed settings inputs.
- `TOOLGATE_DEFAULT_TENANT_ALIAS` and manifest `defaultTenantAlias` agree, with one persisted UUID per alias. Removed endpoint/default-ID settings are rejected, never adapted. CI covers all nine current Control readers, helper-based property access, Helm and supervisor output; generated runtime identity fields contain UUIDs only.
- Repeated starts, changed initialization files, concurrent replicas and crashes before/during commit cannot reseed or partially overwrite an initialized tenant.
- Same-release restart never restores removed grants, re-enables identities or lowers session/credential epochs. Tenant creation after initial bring-up uses the same explicit provisioning path.
- `tenant1.xyz.com` and `tenant2.xyz.com` can use identical user/device/tool/host/invocation IDs and independently complete login, enrollment, approval, upload, deployment, catalog, invocation and result retrieval.
- Cross-tenant tokens, certificates, workload credentials, results, package downloads, secrets, WebSocket connections and manually replayed cookies are rejected.
- Every tenant-data table is recorded in the ownership manifest and enforces non-null ownership, tenant-aware keys and foreign keys, and PostgreSQL RLS under actual runtime credentials. CI rejects newly unclassified tables. Test reads with omitted filters, cross-tenant writes, connection reuse and view/function paths; verify explicit global exceptions contain no tenant payloads.
- Neither V1 schema/grants nor runtime SQL references `quickstart_vault`; tests cover transaction advisory-lock contention/rollback in PostgreSQL and real `BEGIN IMMEDIATE` in SQLite. Actual vault isolation tests use `control_secrets`.
- `PostgresStore.transaction(...)` is the only tenant-runtime `set_config` entry for both read and write work, before tenant SQL. `PostgresStore.tenants()` is behind the approved paginated platform path; tenant callers cannot enumerate it or borrow an unrestricted connection.
- Every tenant ID in database ownership columns, identity claims, device state, encryption context and runtime contracts is the same canonical UUID string. `quickstart`/`default` occur only as aliases. UUIDs survive same-release restart, restore and hostname changes; there is no second storage-key mapping.
- Ordinary writes for an unregistered/provisioning/suspended tenant, missing authority row or stale revision fail without creating any row. Registry and initial authority INSERT privileges belong only to provisioning. Revision/epoch/cache reads and outbox/triggers use `control_tenant_authority`; runtime cannot mutate registry lifecycle or enumerate tenants. Suspension serializes against an already admitted write, and counters never default to zero on missing rows.
- An endpoint and a Tool Host configured for tenant1 ignore/reject tenant2 work and never answer tenant2 with a device proof, enrollment code or protected local details, even when resource IDs are identical.
- Chrome extension discovery obtains the selected origin's UUID; native verification rejects a forged page UUID, another tenant's origin and stale/in-flight context changes. A mismatch never starts automatic installation, enrollment or tenant switching.
- A tenant1 device checking tenant2's Gateway discovery with its expected UUID receives `403 TENANT_MISMATCH`; without the optional header, the client rejects the differently bound signed discovery locally before sending credentials. Its saved binding/authority remains unchanged. Distinguish true HTTP authority/connection misdirection (`421`) from tenant denial and test all response-matrix cases.
- A protected device request with a valid tenant-bound certificate works without `Toolgate-Tenant-ID`; an explicitly supplied mismatched UUID is denied. Public unknown, provisioning and suspended hosts return the same 404/code/body structure/headers with no reason-specific retry hint; UUID comparisons do not reveal their state.
- Phase 2a leaves development CA-signed discovery unchanged. Phase 4 provisions the per-tenant discovery key and ships v2 production and verification together across endpoint-client/native/extension/Quickstart. Different tenants and device-CA/TLS/policy purposes use different signing keys. Wrong-tenant key rings, unknown/revoked key IDs, origin/version/algorithm substitutions and v1 downgrades fail. Rotation works within v2 with no shared fallback signer or old pin conversion.
- Identical secret names, group IDs, device IDs and invocation IDs in two tenants never return the other tenant's values or metadata. Wrong tenant/secret/version AAD, swapped ciphertext/wrapped DEKs, unknown key versions and cross-tenant provider references fail closed.
- Secret delivery rejects a valid device on the wrong tenant, a stale fencing token/attempt, expired authority and a revoked handle before decrypting. Extension, logs, exports, process environments and shared diagnostics do not expose unauthorized values.
- Tenant key/credential rotation, cache invalidation, suspension and isolated restore preserve other tenants' keys and credentials. Unsupported pre-release ciphertext is rejected unchanged with no conversion path or default-key fallback.
- Quickstart binds devices to its default tenant UUID without manual entry. Explicit cross-tenant re-enrollment retires the previous local binding and requires fresh tenant approval.
- Unknown subdomains, forged forwarding headers, invalid origins, internal service-host requests and suspended tenants cannot bypass tenant resolution or authentication checks.
- Tenant-specific rate saturation or worker failure does not stop another tenant's ordinary work. Tenant enumeration is paginated and every tenant eventually receives background maintenance.
- Shared-database backup/restore and the defined individual-tenant recovery procedure preserve other tenants and all required authority floors.
- Fresh V1 Quickstart SQLite and single/shared PostgreSQL deployments pass their respective checks. Unsupported database history is rejected unchanged with instructions to select a new empty database/data directory.

## 12. Review decisions recorded by this plan

1. Keep a default tenant for automatic bring-up; no fallback for unknown hosts.
2. Use one-time common templates plus per-tenant overrides, with no live configuration inheritance.
3. Define one first-release V1 using Flyway for PostgreSQL and checksummed V1 SQL for SQLite; use application services for tenant initialization. No prior-installation migration or compatibility adapters are included.
4. Keep tenant IDs immutable and hostname ownership explicit.
5. Preserve identity, review, permit and resource boundaries while making their configuration tenant-aware.
6. Use PostgreSQL and tenant-dedicated Tool Host pools for the first supported shared deployment.
7. Treat independent customer IdPs, arbitrary custom-domain self-service, billing and a full platform-management UI as subsequent work. A protected operator provisioning interface is included in the initial scope.
8. Bind every device installation to one tenant UUID, expose tenant discovery through the Chrome extension, and reject wrong-tenant Gateway connections explicitly without automatic redirection or rebinding.
9. Require non-null tenant ownership on every tenant-data table, PostgreSQL RLS, tenant-aware references and an automated schema-ownership audit. Keep global metadata exceptions explicit and free of customer payloads.
10. Scope secrets and their lifecycle to tenants, with per-tenant DEKs, protected wrapping keys and current invocation/fencing checks before delivery.
11. Reuse signed discovery for Gateway compatibility checks; return 403 for tenant mismatch and reserve 421 for its defined HTTP authority/connection semantics.
12. Store one canonical UUID string in all `tenant_id` columns. Keep aliases separate, split platform registration from tenant authority, and prohibit implicit creation by writes or triggers.
13. Remove the unused `quickstart_vault` lock table. Qualify discovery v2 signer and all client verifiers in the same delivery phase.

## 13. Architecture references and their application

These are official vendor, standards and security-community sources. They establish patterns and constraints, not proof that ToolGate has already met them. Provider-specific examples inform a self-hosted, vendor-neutral implementation; this plan does not require adoption of those cloud products.

| Source | Applied design decision |
| --- | --- |
| [AWS SaaS bridge isolation model](https://docs.aws.amazon.com/whitepapers/latest/saas-tenant-isolation-strategies/the-bridge-model.html) | Combine shared management/storage with dedicated execution boundaries |
| [AWS tenant isolation fundamentals](https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html) | Treat isolation as an explicit resource boundary beyond login |
| [Azure tenant request mapping](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/map-requests) | Separate hostname routing from verified tenant authorization |
| [Azure multitenant control planes](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/considerations/control-planes) | Separate platform provisioning from tenant operations |
| [Azure deployment stamps](https://learn.microsoft.com/en-us/azure/architecture/patterns/deployment-stamp) | Keep tenant identity independent of future deployment placement |
| [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) | Enforce row ownership and account for privileged-role bypasses |
| [AWS SaaS Factory PostgreSQL RLS example](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/) | Centralize pooled database enforcement rather than relying only on query filters |
| [OWASP multitenant security guidance](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html) | Verify tenant context across requests, repositories, caches and asynchronous work |
| [AWS device provisioning](https://docs.aws.amazon.com/iot/latest/developerguide/iot-provision.html) | Separate bootstrap from unique device operational identity |
| [Azure vault tenancy guidance](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/service/key-vault) | Scope secret access and expose the limits of a shared provider identity |
| [AWS KMS encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html) | Bind ciphertext/key use to non-secret tenant context |
| [RFC 9110, 421 semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.20) | Avoid treating a tenant authorization denial as a transport-routing retry |

Release evidence must include the complete V1 table/privilege audit, fresh-schema and unsupported-database tests, adversarial two-tenant integration tests, explicit provisioning/authority-update tests, discovery-v2 client cutover, device enrollment/rotation, secret custody, saturation and same-release recovery results. Until those pass, describe the implementation as under development rather than production-proven.
