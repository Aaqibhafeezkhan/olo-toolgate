# Deployment profiles

A deployment profile declares the packages a deployment needs, pinned exactly, and what to activate. Control applies it
at startup through one application service; scripts and charts only choose the file. Building an image never uploads
a package, enables a tool or creates a grant.

Contract: `DeploymentProfile` and `ProvisioningStatus` in `packages/contracts/schemas/v2/profile.schema.json`.
The Quickstart image ships `deploy/profiles/fresh.json` and selects it with `TOOLGATE_CONTROL_PROFILE_PATH`; mount
another file and set that variable to use a different profile with the same image.

```json
{
  "profileVersion": 2,
  "profileId": "fresh",
  "packages": [
    {
      "packageId": "ololabs.hello-world",
      "source": "PREBUILT_CORE",
      "version": "1.0.0",
      "required": true,
      "hostIds": ["tool-host-local"],
      "tools": [{"toolId": "ololabs.hello-world/server_greet", "toolGroupId": "default-tools"}]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `source` | `PREBUILT_CORE` resolves only to the sealed artifact in the image's core lock (`TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH`); its digest comes from that lock and a declared `packageDigest` must equal it. `MARKETPLACE` downloads the exact signed release from the configured Marketplace and requires `packageDigest`; a release with another digest is refused before download. |
| `hostIds` | Tool Hosts that receive the package's eligible ServerTool exports. ClientTool exports are never deployed to a Tool Host. |
| `deviceGroupIds` | Device groups whose active, approved devices receive the package's device-runnable exports (every ClientTool, and a ServerTool that needs no routes, secrets, globals or claims) as a signed client release. Devices that join a group later receive it too. An entry that activates exports needs `hostIds` or `deviceGroupIds`. |
| `globals` | Package global values for the deployment. |
| `tools` | Installed exports to enable, each in a reviewed Tool Group that must already exist. |
| `bindingIds` | Existing execution bindings whose allowed package digests become exactly the profile packages that name them. |
| `required` | A required package that fails makes the profile `FAILED`; an optional one is reported without blocking readiness. |
| `readiness` | Calls a deployment script makes through the Gateway, as the named agent, before it declares the profile ready. |
| `workflows` | Optional workflow catalog, explicit model deployments/groups, prompts/context, agent definitions, workflow groups and immutable default dispatch bindings. This uses the profile lifecycle below; package provisioning alone cannot publish it. |

## Reviewed workflow profile revisions

Only the current `profileVersion: 2` format is accepted. Version 1 inputs and
existing stores with an older fresh-schema checksum are rejected and retained;
there is no conversion, deletion, migration or compatibility mode. Use fresh
stores and matching current Control, SDK and profile artifacts for this branch.
Package-only deployment profiles still use the same current version-2 contract.

Workflow profiles use these authenticated Control APIs:

| Request | Result |
|---|---|
| `POST /api/control/v1/workflow-profiles` with a DeploymentProfile containing `workflows` | Stage a canonical immutable DRAFT and return its digest; identical content is idempotent. No publication or grants. |
| `GET /api/control/v1/workflow-profiles/{id}/revisions/{digest}` | Current lifecycle status, publication generation and live dependency readiness. |
| `GET /api/control/v1/workflow-profiles/{id}/revisions/{digest}/document` | Private canonical desired document for review; contains prompts and secret references, never vault values. |
| `POST /api/control/v1/workflow-profiles/{id}/review` with `{"profileDigest":"sha256:…"}` | Record explicit review of those exact immutable bytes. |
| `POST /api/control/v1/workflow-profiles/{id}/provision` with the same digest request | Provision the reviewed revision's packages through the existing package service; return its ordinary provisioning status. |
| `POST /api/control/v1/workflow-profiles/{id}/publish` with `{"profileDigest":"sha256:…","expectedGeneration":0}` | Atomically activate a reviewed, dependency-ready revision. Use the latest generation for a replacement. Stale generations or unready dependencies return 409. |

Workflow staging rejects invalid package declarations such as duplicate target
groups and trigger assignments without a target group. The sealed package
inventory and installation dependencies are checked when provisioning.

This initial management boundary requires a **current Super Admin** for every
operation, including document/status reads. Cached actor flags and token roles
cannot grant access. Scoped workflow management roles and user-filtered runtime
resolution are still pending; do not treat this endpoint as their implementation.
Self-review is allowed at this explicit Super Admin boundary; no independent
review quorum is claimed. These routes do not grant execution or service delegation.

Workflow/model groups, workflow definitions, model deployments, agent prompt
definitions and dispatch bindings are owned by one profile. Another profile
cannot overwrite them. A workflow ID/version cannot be reused with different
definition content; publish a new version. A subscription/source pair has one
profile owner. Omission never deletes resources, releases ownership or revokes
shared grants. Explicit reviewed cross-profile sharing is not implemented yet.

Model deployments declare `id`, a fixed HTTPS `origin` with no path/query/userinfo,
`modelId`, `secretName`, `toolGroupId` and `deviceGroupId`. Model-group references
must close over these local declarations. Supervisor and agents each name their
own model group. Vault values stay in the existing vault; readiness checks that
the tenant's secret reference exists under the declared enabled groups. This is
a dependency/reference check, not a provider credential validity test or live
inference. No provider request is made during upload, review or publication.

Publication requires current enabled agents/groups/tools, matching group
membership, scoped vault references and the **same profile digest's** READY
package provisioning result. Trigger bindings also require current immutable
subscription/assignment UIDs, active unexpired subscriptions, Running assignments,
source membership, current listener subscription grants/full source clearance,
and service-agent group membership. Missing/unavailable authority never activates
a draft. Nonempty tool readiness cases currently block workflow publication
because their execution evidence is not integrated; they are not silently skipped.

The pointer, ownership, review and audit share tenant transactions. Private
prompts/configuration are stored in tenant-vault-encrypted documents;
the canonical digest and lifecycle metadata remain visible within
the tenant. Envelopes bind every chunk to tenant, profile, digest and key version.
Normal key rotation retains READ_ONLY custody for existing revisions; retired
or unavailable keys block reads. There is no plaintext fallback.

Replacing a draft or failed dependency cannot expose partial runnable configuration; current
dependency checks run again when the internal admission boundary resolves a
published profile. A PUBLISHED status is historical activation, and may report
`dependenciesReady: false` after membership/dependency changes. It is never cached
runtime authorization. No runnable API, grants, model proxy, lease or trigger
admission is created by this lifecycle alone. Complete one-upload directory and
grant provisioning, readiness-case execution, runtime authorization and the
real two-profile demo remain tracked in the [workflow status](../architecture/workflow-v1-status.md).

## How Control applies a profile

1. The whole profile and the core lock are validated before anything changes. An invalid profile stops startup.
2. Each package is imported through the ordinary package verifier, as the installation's Super Admin resolved through
   the central role resolver. Importing never grants execution.
3. Packages with `hostIds` are deployed once every named Tool Host has registered; until then the package and the
   profile are `PENDING` and Control retries every 15 seconds.
4. Packages with `deviceGroupIds` get one client release (below), which needs client images and the package-release
   key; without them the package is `FAILED` with that reason. The release is rolled out to every active, approved
   device of those groups that does not hold it yet, and the status lists those devices. With no such device the
   package is `PENDING`. A profile that names device groups is reapplied every minute even when `READY`, so a device
   approved later receives the release; the reapplication changes nothing else.
5. Declared exports are enabled in their Tool Groups and declared bindings are updated in reviewed directory changes,
   each audited. A device export's binding digest is its managed digest from the release. An export that is not
   installed from the pinned package blocks the profile; nothing is created to satisfy a reference.
6. The outcome is recorded with the profile digest and a generation. An identical repeat changes nothing. A changed
   profile records the digest it replaced. Removing an entry never uninstalls a package; uninstalling is an explicit
   operation.

`GET /api/control/v1/packages/provisioning/{profileId}` (requires `package.read`) returns the latest status: `READY`,
`PENDING` with the target it waits for, or `FAILED` with the reason. A failed profile keeps Control running for repair and
is retried every minute; nothing falls back to another version. `GET /api/control/v1/packages/provisioning` returns
every applied profile's status, and the console shows them under **Server packages → Deployment profiles**: each
package's state, step, targets and reason, and the ADDED/CHANGED/REMOVED changes from the profile applied before. Grants, policies and approvals stay
separate reviewed records: a READY profile has installed tools, not given anyone access to run them.

## Client-tool images

Packages whose exports run on client devices are released as OCI images (ADR 026). Configure the registry Control
pushes to and the approved JRE base mirrored there:

| Variable | Meaning |
|---|---|
| `TOOLGATE_CONTROL_CLIENT_IMAGES_ENABLED` | `true` to allow client releases. |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_REGISTRY_URL` | `https://registry.example.com` (plain `http` only for a loopback development registry). |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_REPOSITORY` | Repository for client-tool images, for example `toolgate/client-tools`. |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_BASE` | The mirrored approved base, `mirror/eclipse-temurin@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c`. |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_CREDENTIALS_PATH` | Optional protected file holding `user:secret`. |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_CA_PATH` | Optional PEM bundle for a private registry CA. |
| `TOOLGATE_CONTROL_CLIENT_IMAGES_PLATFORMS` | Client platforms the release declares (default `LINUX`). |
| `TOOLGATE_CONTROL_PACKAGE_RELEASE_PRIVATE_KEY_PATH`, `TOOLGATE_CONTROL_PACKAGE_RELEASE_KEY_ID` | Optional package-release key; it must be a configured release trust key and differ from the organization key. Quickstart supplies its own. |

`POST /api/control/v1/packages/client-releases` with `{"packageDigest": "sha256:..."}` (permission `package.publish`)
builds the image deterministically, pushes it by digest, writes disabled tool records and returns the
`FleetPackageDocument` with each export marked supported or not. With the release key, Control signs and records the
release and mirrors its bytes; otherwise sign `documentBase64` with `tools/builder/sign.py` and publish it through the
fleet release API. Devices still need a rollout, enrollment, an enabled tool, grants and permits.

## Helm

The chart sets the same variables from `control.profile.configMap` (a ConfigMap holding `profile.json`),
`control.clientImages.*` (registry URL, repository, approved base by digest, platforms, and optional
`credentialsSecret` with `credentials` and `caSecret` with `ca.pem`), and `control.packageRelease.*` (a Secret with
`private.pem` and its key id; the key must also be in the fleet release trust keys). `control.networkPolicy.registryTo`
opens egress to the registry on the port its URL names.


## Workflow configuration foundation (#295)

The workflow feature branch defines `WorkflowConfiguration` in
`packages/contracts/schemas/v2/workflow.schema.json`, with generated Java, Rust,
TypeScript and PHP models. It declares workflow groups, supervisor prompts and
models, agent prompts/models/context, bounded execution, and default-workflow
subscription bindings. Model deployment, identity, tool and trigger references
must be checked in the current tenant. Configuration membership grants no access.

Control's `WorkflowConfigurations` service validates local reference closure,
duplicate identifiers, explicit models, entry agents, execution limits and
unambiguous dispatch bindings before asking its tenant-scoped dependency port
about external references. An unavailable dependency returns a generic error.
It is a read-only validation component; no resource is provisioned or published.

This foundation is not yet accepted by deployment profile upload. The existing
DeploymentProfile contract remains unchanged until version-2 orchestration,
management authorization, catalog persistence and readiness are delivered. Do
not add these fields to a deployed version-1 profile or interpret successful
structural validation as READY or execution permission. See the
[live workflow status](../architecture/workflow-v1-status.md) for remaining work.

## Published workflows in the console

The Overview, Workflows and Agents configuration pages show published workflow
and agent properties to users with tenant-wide agent and agent-group read access.
Control returns this decision as `canReadPublishedWorkflows` in the admin
session and checks it again for every published configuration request.
Group-scoped readers keep their existing directory access. Super Admins manage
drafts and publication. Use **Devices / Triggers** for device trigger management
and **Workflows / Trigger packages** to configure packages in a workflow profile.
