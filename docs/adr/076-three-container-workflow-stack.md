<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 076: Three-container Quickstart workflow deployment

Status: accepted for the bounded local three-container deployment; A/B, cold-cache rebuild and credential-preservation acceptance pass. Production/HA and full roadmap acceptance remain outstanding; see workflow-v1-status.

Quickstart composes backend/Gateway, Admin UI and a loopback local OCI registry.
LangGraph composes a private mTLS graph API and execution worker. Device remains
separate. Production separates Admin UI from Gateway/backend and supplies its own
registry/storage. Model services and monitoring remain operator-owned.

Backend trigger routing uses existing tenant-scoped subscription sessions,
owner/source reauthorization, durable admission and acknowledged cursors. No
subscription listener, Admin credential or signing key runs in LangGraph.
Duplicate admission and run leases remain backend authority. Cache is bounded,
in-memory and disposable; it never authorizes a run. External cache is deferred.
No new checkpoint/resume or effect replay guarantee is introduced.

External operator scripts provision short-lived runtime credentials through a
Quickstart Admin-authenticated API. No identity-renewal sidecar or automatic
rotation runs inside the containers. Admin password is never changed by this
operation. Expired service credentials fail closed; explicit script invocation
replaces files. Only the runtime-specific credential subtree mounts in LangGraph.
Quickstart initializes certificates and local storage before dropping privileges;
certificate expiry/rotation requires explicit operator intervention.

Supervisors forward termination, stop siblings on child failure and provide
readiness. Fresh composition only: refuse obsolete multi-container definitions;
do not migrate/delete existing stores. Bot import/switch never edits device files.

Later external cache configuration must preserve tenant/revision/compiler binding
and cache-miss correctness. Persist serializable configuration/metadata through an
explicit backend; do not pickle compiled Python graphs or make cache contents an
authority source. Checkpoint/resume is a separate contract and is not added here.
