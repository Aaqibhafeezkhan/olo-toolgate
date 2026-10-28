<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 014: Tool package format v2, conversion to fleet releases and version lifecycle

**Status:** Accepted (design gate D1), on merge of the PR that adds this file

## Context

The Tool SDK (ADR [019](019-tool-sdk-authoring-contract.md)) produces packages that tenants upload, review and enable. Today the repository has `PackageManifest` v1 and a `ToolDefinition` with only `id`, `name`, `description`, `actions[]` and schemas, and devices trust exactly one format: an RS256 compact JWS over the exact `FleetPackageDocument` bytes, with tool code baked into digest-pinned OCI images and no ZIP or TAR extraction on the device (ADR [010](010-signed-fleet-reconciliation.md), platform-requirements.md §2.1). Control already has fleet releases and rollouts, the Builder seal, publication, release and deploy flow (ADR [011](011-designated-client-tool-authoring.md)), and maker/checker configuration changes.

The plan ([platform-requirements.md](../contracts/platform-requirements.md) §7, §8.1 to §8.4, §16, decision register rows 1, 3, 8, AC, AE, AH, AI, R7, R12) needs one package format that can be signed without circularity, verified at every hop, compared semantically across runtime modes, and rolled out and retired without inventing a second governance path or a new trust format on devices.

## Decision

1. **One authoritative schema.** `packages/contracts/schemas/v2/package.schema.json` defines `PackageManifest` v2 (`schemaVersion: 2`) and `ToolDefinition` v2, which embeds the semantic descriptor from `descriptor.schema.json` (generation 1). Shared ids come from `identifiers.schema.json`. Every documentation example is generated from fixtures under `tests/fixtures/contracts/v2/` and validated in CI. v1 manifests stay readable and are upgraded on read; the contract set takes a MAJOR bump.
2. **Package layout.** A `.tgpkg` is a deterministic ZIP (sorted entries, fixed timestamps) holding `toolgate-package.json`, `tools/`, `marketplace/`, `tests/self-tests.json`, `artifacts/`, `sbom/`, a canonical `CHECKSUMS.sha256` and an optional `signatures/` directory (platform-requirements.md §7.2).
3. **Three identities, never conflated** (platform-requirements.md §7.2):
   - `toolDescriptorDigest` per tool and `descriptorSetDigest` per package: SHA-256 of the RFC 8785 canonical semantic descriptor. Must be equal in standalone and governed mode. Used for mode equivalence, the security delta, catalog entries and the permit.
   - `artifactDigest`: SHA-256 of one executable artifact's exact bytes. Used by the content-addressed store, the launcher and the permit.
   - `packageDigest`: SHA-256 of the canonical `CHECKSUMS.sha256` bytes, not of the ZIP. Used for upload, signing, enable, rollout and pinning of invocations and tasks.
4. **Signing without circularity.** Signatures are DSSE envelopes over an in-toto statement whose subject is the `packageDigest`. Publisher signatures are optional; organization release and Marketplace signatures are always detached and stored by Control or the Marketplace (platform-requirements.md §7.3).
5. **Verification at every hop.** CLI pack, Control upload, artifact mirror, Tool Host and client all apply the ZIP rules, exact file set, canonical checksum format and manifest consistency of platform-requirements.md §7.6. Any failure rejects; nothing is repaired. Verified artifacts are copied into a read-only content-addressed store and mounted only from it.
6. **Artifacts and launcher.** Server tools run on approved, signed, digest-pinned base runtime images that hold only a language runtime and `toolgate-launch`; the SDK runtime ships inside the tool artifact (platform-requirements.md §7.4).
7. **Conversion to fleet releases.** Devices never receive a `.tgpkg`. On enable, Control converts the upload: client tools ship as OCI images built from an approved base and pinned by digest, and Control issues the RS256 release JWS over a `FleetPackageDocument`, exactly as today. DSSE over the `packageDigest` is upload-side provenance only. Client-tool packages are limited to the existing 32 tools per client until a client release raises it.
8. **Library composition** follows platform-requirements.md §7.7: conflicts fail, only explicit narrowing mappings are allowed, and composition is recorded in the descriptor.
9. **Upload and one-click enable** (platform-requirements.md §8.1). `POST /api/control/v1/packages/uploads` stores a sealed, immutable version and drafts the review. Enable submits a maker/checker configuration change (auto-approved in Quickstart and dev), signs the organization release, and creates fleet and Tool Host assignments and grants. Every call still goes through policy, ASK, permits and audit.
10. **API compatibility and security delta are separate checks** (platform-requirements.md §8.3). The security delta, including route-by-route `ServiceProfile` widening, always needs re-approval whatever the version number. An unclassifiable change counts as widening.
11. **Version lifecycle** (platform-requirements.md §8.4). States `UPLOADED, VERIFIED, IN_REVIEW, APPROVED, ROLLING_OUT, ACTIVE, DEPRECATED, DRAINING, RETIRED`, plus `REJECTED`, `PAUSED`, `ROLLED_BACK` and `SUSPENDED` (kill switch). Client-tool rollout reuses existing fleet desired generations, canary percentage and rollback; server-tool rollout uses sticky agent cohorts. At most two routable versions per package by default. Each invocation and task is pinned to its `packageDigest` at admission. `RETIRED` requires that no task or invocation references the digest. P0 delivers states up to `ACTIVE`, all-at-once rollout with self-tests and manual rollback; cohorts, health gates and auto-update channels are P1.
12. **Catalog revisions** (platform-requirements.md §16). Each catalog scope has a revision digest over its entries and pinned package digests; a call against a stale revision whose schema changed is rejected with a stale-catalog error and `toolsListChanged`.

Contract surface: the upload, review, enable and lifecycle endpoints are specified in `packages/contracts/openapi/proposed/control-v1.proposed.yaml`. The served `packages/contracts/openapi/control-v1.yaml` changes only when M1 implements each endpoint, so the served spec never advertises an unimplemented endpoint.

## Alternatives Considered

- **Ship `.tgpkg` to devices and extract it there.** Rejected (row R12): reverses the "no ZIP or TAR on clients" invariant and adds a second trust format.
- **One package digest for everything.** Rejected (row AE): standalone and governed builds differ in bytes, so mode equivalence would be either false or impossible.
- **Digest of the ZIP file, or signatures inside the checksummed set.** Rejected: adding a signature would change the identity it signs.
- **Extend v1 compatibly.** Rejected (row 8): v1 cannot carry descriptors, effect safety or routes without ambiguity.
- **A parallel package lifecycle beside fleet releases and configuration changes.** Rejected (row AI): two governance paths would diverge.
- **Last-wins or union merge of library fragments.** Rejected (row R7).

## Security Impact

- Upload never grants execution by itself: enable is a reviewed configuration change, and every security delta needs explicit re-approval.
- Device trust is unchanged: only RS256 release JWS over `FleetPackageDocument` and digest-pinned OCI images.
- Path traversal, symlinks, zip bombs, unlisted files and checksum drift are rejected at every hop; check-then-use races are removed by the content-addressed store.
- Base images must be signed by a tenant-trusted signer and are verified when prepared, not only when approved.

## Operational Impact

None for existing deployments until M1. Operators later gain package review, rollout and retirement screens; artifact storage grows with retention; Quickstart defaults to immediate 100% rollout with self-tests.

## Compatibility Impact

MAJOR change to the contract set: `PackageManifest` v2 and `ToolDefinition` v2 under `$id` base `https://schemas.ololabs.io/toolgate/v2/`. v1 manifests are upgraded on read. `FleetPackageDocument` and the device release JWS are unchanged, so existing clients need no update.

## Consequences

- Positive: one reviewable format with honest identities; devices keep their proven trust path; lifecycle reuses tested Control flows.
- Negative: Control must build or select OCI images for client tools, which adds a conversion step to enable.
- Negative: deterministic packaging and canonical checksums constrain build tooling in all four SDK languages.

## Validation

- `package.schema.json` and `descriptor.schema.json` with valid and invalid fixtures in `tests/fixtures/contracts/v2/` pass the existing contract checks (`preflight.yml`).
- Packager conformance (platform-requirements.md §21): equal `toolDescriptorDigest` across modes, deterministic `packageDigest`, every §7.6 rejection case.
- Conversion test: an enabled client-tool package yields a `FleetPackageDocument` and release JWS that an unmodified endpoint client accepts.
- Lifecycle tests for every state transition, pinning across rollback, and the `RETIRED` reference check.
- Review of `control-v1.proposed.yaml` against this ADR before M1.
