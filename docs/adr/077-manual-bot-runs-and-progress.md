<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# 077: Explicit console bot runs and recorded execution progress

Status: accepted for bounded local console execution; full roadmap acceptance remains in workflow-v1-status.

A console Run action submits text to a published workflow through backend run
admission. Current tenant Super Admin authority, exact publication digest and
generation, an entry-eligible configured agent and current service grants are
required. The backend derives its agent group. A unique configured dispatch mode
for that entry is reused; absent a dispatch mode, PREPARE keeps mutations denied.
Conflicting configured modes require selecting an existing dispatch binding; the backend validates that it belongs to the selected workflow/entry. No arbitrary mode is supplied by the browser. Input is encrypted through existing
run custody. Idempotency binds caller/key/exact request; unknown submissions must
reuse that key rather than create another run. Manual admission never forges a
trigger event or changes device files. Drafts cannot run.

Progress is recorded through the existing tenant audit transaction and read only
for the selected run by a live Super Admin. Control records queue/claim/finish
and step authorization. Lease-authenticated workers report bounded structural
node/model/tool progress. Reports contain IDs and phase only, not prompts,
arguments, file contents, provider bodies, credentials or chain of thought.
Runtime reports are observations, not independent tool-effect receipts. Terminal
result/finality remain backend-owned. The UI polls ordered cursor pages, retains
its accepted run on transient polling errors, and never silently resubmits.
Closing the panel stops polling; it does not cancel execution.

LangGraph remains stateless; progress and outcomes survive its restart in backend
storage. No new schema migration, retry of an external effect or checkpoint/resume
is introduced. Production/HA and full roadmap qualification remain separate.

Optional cloud observations reuse structural progress, never raw runnable
inputs/outputs or automatic callback capture. A bounded asynchronous worker
exporter sends OTLP/HTTP JSON to an installation-owned collector. Vendor keys
stay in collector-only private files; profile import cannot configure endpoints
or tracing credentials. Langfuse/LangSmith/Phoenix mapping carries common run/
OTEL trace IDs. Delivery failure drops telemetry and never changes execution
or retries effects. Full distributed correlation and content/token/cost export
remain outside this bounded slice.
