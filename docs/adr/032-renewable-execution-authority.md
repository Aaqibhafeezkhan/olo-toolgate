<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 032: Renewable execution authority and durable evidence

**Status:** Accepted for the execution-lease implementation of issue #230.

## Decision

Admission remains a single-use nonce transition in Control's tenant transaction.
A running Tool Host must also hold a renewable 30 second lease, capped by the
invocation deadline. Control records the fence, admitting device and lease expiry
durably in that same transaction. A heartbeat re-evaluates current authorization;
an expired lease cannot be resurrected. The healthy supervisor checks at most every
10 seconds and stops on denial or at expiry during a partition. The invocation
deadline is an independent upper bound.

Execution authority and evidence are separate. Results retain their original
fence and device identity after cancellation or expiry, and never grant execution
authority. Late reports are evidence for reconciliation, not permission to re-run.
The host persists result reports before delivery and retries the identical report
after restart. A failed acknowledgement never causes tool execution to repeat.

## Security and compatibility

New storage is tenant-scoped on SQLite and PostgreSQL. Existing clients without
the heartbeat cannot extend an invocation's initial lease. Result delivery is
at least once; Control must deduplicate reports. A compromised host can disable
its watchdog: the bound applies only to healthy enforcement hosts. No containment,
target-side fencing, sender constraint or externally anchored audit claim follows
from a lease. Those remain separate milestones in the pinned issue plan.

## Validation

Exercise stale fences, wrong devices, expiry, revocation, partition, restart and
result acknowledgement loss. Record executed checks and remaining roadmap scope
in the operator guide and release scorecard.
