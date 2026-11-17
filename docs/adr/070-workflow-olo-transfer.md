<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 070: Workflow-level .olo configuration transfer

Status: accepted. Supersedes ADR 069's whole-deployment archive behavior.

The archive format and separate-tool-artifact limitation below are superseded by
[ADR 071](071-profile-builds-and-embedded-packages.md); this decision records the
earlier configuration-only delivery.

One ZIP-compressed `.olo` transfers one workflow, its local definition closure,
named trigger declarations/connections and referenced directory configuration.
It contains no README, Docker images, model weights, databases, human accounts,
private keys or credential stores. Provider secrets, approved devices, signed
packages and trusted graph factories must be provisioned on the destination.
Import verifies bounded archive inventory and content hashes. It never installs
Python code or calls Docker. The existing tenant-authenticated profile stage,
review and publication operations remain authoritative. Referenced directory
changes use the existing configuration-policy MERGE path (including its existing
Super Admin exception); trigger creation uses existing
authenticated trigger APIs. Missing resources remain explicit readiness blockers.

Profiles may contain multiple workflows. Replacing or removing one prunes only
its unshared definition closure; other workflows and shared definitions remain.
Conflicting shared definitions require explicit separate identifiers. Historical
revisions/audit and globally provisioned identities/assets are not deleted.

Both the normal console and local port 48190 serve the same workflow UI. The local
operator proxies only to its selected loopback Control origin; Control still
authenticates each API request. Browser mutations require the local origin. The
deployment operator remains separately authenticated at `/operator`. Whole-stack
archive import/export is removed. No compatibility reader for it is introduced.

Folder delivery follows the configured profile ID and permitted workflow IDs.
Before delivery it independently confirms the run's digest and exact workflow
version against Control's current publication over authenticated TLS. A stale
publication at lookup, another profile, unbound workflow or mismatched version is
refused. This lookup is not atomic with the later file write or revocation;
trusted projection remains outside #230's native-write guarantees.
Successful publication can therefore update a
workflow without rotating credentials or restarting the delivery container.

An explicit factory reset uses fresh storage and preserves only the actual local
admin password verifier and its existing private login files. It does not copy
the prior database, sessions, signing keys or workflow state. A root initializer
places the verifier in fresh service-owned custody. Ordinary workflow import,
replace and delete do not invoke this lifecycle or change login credentials.
