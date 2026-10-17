<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 020: Deployment binding and configuration precedence

**Status:** Accepted (design gate D1), on merge of the PR that adds this file

## Context

A package version says what a tool is (ADR [014](014-tool-package-format-v2.md)). Where and how one tenant runs it (target pools or Device Groups, global values, secret bindings, service profile, limits, grants, rollout) must change without a new package, and every effective value must be explainable. Values today come from env and Helm, the settings record, policy and device configuration, and the startup configuration import overwrites settings (platform-requirements.md §2.1). Limits must also respect hard platform ceilings (platform-requirements.md §13.5).

[platform-requirements.md](../contracts/platform-requirements.md) §8.5 (register row W) calls the contract `deployment-binding.v1.json`. This ADR places it in the v2 contract set with its own format version.

## Decision

1. **Separate contract.** `DeploymentBinding` is defined in `packages/contracts/schemas/v2/deployment-binding.schema.json`, part of the v2 contract set, with its own `bindingFormatVersion: 1` field and its own monotonic `revision` per binding. Changing a pool, a value or a grant creates a new binding revision, never a new package version. The file name `deployment-binding.v1.json` in platform-requirements.md §8.5 refers to this schema.
2. **Fields:** `bindingId`, `revision`, `tenantId`, package id and `packageDigest`; `configRevision` (`ToolConfigurationRevision`: global values, secret and connection bindings); the `ServiceProfile` digest (`service-profile.schema.json`); `target` (Tool Host pool ids or Device Groups, with allowed runtime tiers); grants and catalog scopes; limit overrides; `rolloutPlan`; `effectSafety`, `businessKeyNamespace` and their review approvals.
3. **Pinned at admission.** The binding digest is part of the `InvocationPermit` (`permit.schema.json`). In-flight work keeps the binding revision it was admitted with.
4. **Precedence, highest first; a higher layer caps a lower one, never "last set wins":**
   1. platform ceilings, compiled in, operators may only lower;
   2. deployment environment (env, Helm, Kubernetes): bootstrap values and lower ceilings; env-owned values cannot be changed in the console;
   3. tenant policy: tighten limits, restrict tiers, require ASK;
   4. pool or Device Group configuration: capacity, allowed tiers, egress posture;
   5. deployment binding: values for this package within layers 1 to 4; inside the binding a pool-specific override beats a tool-group override, which beats a tenant-wide override;
   6. package defaults: default values only.
5. **Limits with hard ceilings** (platform-requirements.md §13.5). Tool request, pool or device configuration and platform ceiling each must be at most the one below it. Starting ceilings for review: sync deadline 60 s, task deadline 15 min, memory 4 GiB, input 1 MiB, output 4 MiB, 256 argument properties, signed descriptor 1 MiB, 64 tools per package. Negotiation never exceeds a ceiling. Clients keep v1 limits until a client release.
6. **Conflict rules.** Two values at the same layer and specificity are rejected when saved, never resolved by timestamp. A lower layer exceeding a higher one is rejected with the limiting layer named. When a higher layer is later lowered, affected bindings show "capped by tenant policy" and the effective value changes in a new binding revision.
7. **Env versus database.** Env and Helm supply bootstrap defaults and ceilings; database values are runtime settings inside them. At startup the database is never overwritten by env, except env-owned fields, which are always read from env and shown read-only. Env-owned fields are excluded from the startup configuration import.
8. **Effective values.** `GET /api/control/v1/deployments/{bindingId}/effective` returns every field with its value and source layer, using the effective-value shape in `deployment-binding.schema.json`. The console shows the same.
9. **Backup and restore** exports bindings and tenant settings, never env-owned values or secrets (references only).

Contract surface: binding create, update and effective-value endpoints are specified in `packages/contracts/openapi/proposed/control-v1.proposed.yaml` (and `quickstart-v1.proposed.yaml` where Quickstart serves them). The served `packages/contracts/openapi/*-v1.yaml` files change only when M1 implements each endpoint, so the served spec never advertises an unimplemented endpoint.

## Alternatives Considered

- **Settings scattered across package, tool and device records.** Rejected (row W): values cannot be explained and changes need new packages.
- **A separate `v1` schema tree only for bindings.** Rejected: the binding references v2 identifiers, permits and service profiles; keeping it in the v2 set with its own `bindingFormatVersion` lets it evolve independently without a parallel tree.
- **Last writer wins or timestamp resolution.** Rejected: a later lower-priority change could silently raise a limit.
- **Env overwrites the database at every start.** Rejected: it erases runtime administration; only env-owned fields come from env.

## Security Impact

- No layer can raise a value above a higher layer; platform ceilings bound every negotiation.
- The binding digest in the permit stops a configuration change from altering work already admitted.
- Review approvals for effect safety and business-key namespaces are part of the binding, so they cannot change without a new revision.
- Exports never contain secrets or env-owned values.

## Operational Impact

Admins edit bindings instead of re-uploading packages. Every value has a visible source layer, which simplifies support. Operators set ceilings and env-owned values through env or Helm only. Lowering a ceiling produces new binding revisions that admins can review.

## Compatibility Impact

New contract; nothing existing changes shape. The startup import stops overwriting env-owned fields. Future binding changes bump `bindingFormatVersion` without forcing a contract-set MAJOR bump.

## Consequences

- Positive: deployment changes are cheap, audited and explainable.
- Positive: precedence is deterministic and enforced at save time.
- Negative: save-time validation must evaluate all six layers, and lowering a higher layer fans out new revisions.

## Validation

- `deployment-binding.schema.json` with valid and invalid fixtures in `tests/fixtures/contracts/v2/` passes the contract checks, including same-layer conflicts and ceiling violations.
- Precedence unit cases for every layer pair and the in-binding specificity order.
- Effective-value API cases showing the source layer for each field, and a permit test that in-flight work keeps its binding revision.
- Review of `control-v1.proposed.yaml` against this ADR before M1.
