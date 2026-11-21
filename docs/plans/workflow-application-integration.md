<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# External workflow integration: remaining qualification

Current contracts, setup and limits belong in the
[application guide](../api/workflow-applications.md). This page tracks only
unfinished end-to-end acceptance. Target integration PRs at
`feature/workflow-enhancement`; keep umbrella PR #297 draft until acceptance.

## Public ingress and real execution

- Execute a published text bot as an ordinary user through reviewed HTTPS ingress
  and obtain the real final response. Exercise a tool/device flow with all resource
  grants. Deny each missing workflow/agent/model/tool/device grant independently.
- Qualify password/session revocation between login, submit, claim, protected
  effects and result/event delivery, including concurrent membership changes.
- Exercise missing/duplicate/wrong login tenants, conflicting optional headers,
  forged token claims, expired tokens and guessed cross-user/cross-tenant run IDs
  through actual authenticated HTTP, proving no private disclosure or effects.

Acceptance: one authorized real completion with recorded outcomes, all negative
cases denied before protected operations, and no uncertain-effect replay.

## Observation and delivery

- Qualify SSE through both Quickstart and reviewed external ingress: reconnect,
  cursor replay, terminal result, token expiry, revocation, slow consumers and
  disconnect cleanup under concurrency.
- Qualify trigger assignment/instance status with real devices and scoped users;
  cover forbidden selectors, replacement UIDs and absence of provider/private data.
- Exercise a publicly hosted signed callback receiver: ownership proof, duplicate
  ACKs, retry exhaustion, DNS changes/redirects, expiry, renewal, process restarts
  and multiple delivery workers. Prove no duplicate workflow execution.

Acceptance: authorized observers receive retained structural progress and result
announcements; revoked/foreign observers receive no further private data; callback
deduplication and reconciliation recover lost notifications without effect replay.

## Distribution and clients

- Publish matching tested images after applicable component and repository gates.
- Execute the supplied Postman collection and signed receiver against those images
  on a fresh installation; add and execute a small Rust caller/SSE client example.
- Validate a third-party chat adapter's provider signature, caller-to-OLO mapping
  and persistent event-to-run idempotency using a real provider integration.

Acceptance: consumers can reproduce the documented flow from published artifacts
without source-only dependencies. Record runtime evidence in PRs/CI artifacts;
do not infer production or HA qualification from local fixtures.
