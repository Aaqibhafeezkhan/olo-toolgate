<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 078: OLO bot publication diagnostics

A selected imported bot must explain all known publication blockers without a
failed publish attempt. A tenant-scoped, no-store diagnostics endpoint requires
current Super Admin authority before reading/decrypting the exact revision. Its
canonical response contains bounded human-readable blockers, never secret values,
remote exception bodies or trigger events. These messages are display text, not
new permission or execution contracts.

Production readiness derives from the same collected checks used for diagnostics:
compiled graph/generation/worker presence, package/device application, trigger
readiness, model secret scope and referenced configuration/owner clearance.
Publication and run admission continue to check current readiness and authority;
the UI report never grants access. Diagnostic reads do not provision, approve,
publish, alter directories or contact model providers.

The console displays a persistent red list, polls the exact revision every five
seconds, aborts stale requests on navigation and retains the last known blockers
when checking fails. Draft/import/action errors remain visible. Cleared blockers
are removed only after a successful fresh check. The generic unknown-check message
is shown separately from confirmed dependency problems.

No schema migration, legacy alias or compatibility store is added. Diagnosis does
not prove provider connectivity, filesystem access, effect finality or full
roadmap completion. Local implementation/acceptance is recorded in workflow-v1-status.
