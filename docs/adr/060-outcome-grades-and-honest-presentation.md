<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 060: Outcome grades and honest presentation

**Status:** In implementation for S23; no stage or phase acceptance. Started at
the user's explicit request while S22 remains pending its Linux evidence.

This implements the presentation part of the preserved proposal's phase 4
(§4.6 "Business outcome and its grade"). Phase 4 changes presentation only. It
does not change how Control derives operation state, which observations
resolve an effect, settlement guards or recovery. Fresh current contracts and
schemas only; no compatibility grade aliases or conversion of stored outcomes.

## Grades

`EnterpriseOutcomeGrade` is replaced with the plan's grades plus Control's
record of its own authority:

| Grade | Meaning |
|---|---|
| `TARGET_CONFIRMED` | A target outcome report or reconciler lookup stands behind the outcome. |
| `BROKER_OBSERVED` | Effect only. A broker response on a route whose review declares its synchronous response authoritative. Depends on an intact broker. |
| `HUMAN_RECONCILED` | An independent reviewer's reconciliation decision with an evidence digest. |
| `CONTROL_PROVEN` | Control's own records: no execution authority was consumed, or an effect's authority was released unsent and closed. |
| `EXECUTOR_REPORTED` | The executor's report alone. |
| `UNKNOWN` | Nothing outcome-bearing; the outcome is unresolved. |

The former `REPORTED` conflated "executor said so" with "unresolved"; the former
`REVIEWER_CONFIRMED` becomes `HUMAN_RECONCILED`. `TARGET_DEDUPLICATION_ASSUMED`
was a reliance, not an evidence source. The existing `deduplicationAssumed` flag
carries it beside the underlying grade.

## Operation grade

An operation that is not finalized (`RUNNING`, `OUTCOME_UNKNOWN`,
`RECONCILIATION_REQUIRED`) is `UNKNOWN`. A finalized operation is graded by the
weakest source its outcome depends on, never the strongest single observation:

1. A current human attempt review: `HUMAN_RECONCILED`.
2. `FAILED` with no consumed attempt: `CONTROL_PROVEN`.
3. Every consumed attempt has the qualified broker-only scope and at least one
   effect exists. Finalization already resolves every effect only by a target
   witness or a closed unsent release, so the outcome is `TARGET_CONFIRMED` when
   any effect is target-witnessed, and `CONTROL_PROVEN` when all were released
   unsent.
4. Otherwise `EXECUTOR_REPORTED`. This includes `FAILED` after authority was
   consumed: Control cannot rule out unmediated effects.

The previous derivation graded any finalized operation `TARGET_CONFIRMED` if one
generation was witnessed, and any `FAILED` operation `CONTROL_PROVEN`. Both
overclaimed and are replaced.

## Fenced, unfenced and unmediated evidence

The first authorization of an effect freezes its reviewed fencing class
(fenced target class, or unfenced) and the authoritative-response declaration
in the immutable effect intent. Re-review cannot rewrite the classification of
an effect that was already authorized.

Each operation outcome reports `coverage`:

- `NO_AUTHORITY_CONSUMED`: no attempt consumed execution authority.
- `MEDIATED_ONLY`: every consumed attempt was confined to brokered effects.
- `UNMEDIATED_POSSIBLE`: some consumed attempt could act outside the broker,
  so effects outside the journal are unknown.

It also counts fenced and unfenced effects. Effect history lists each
generation's fencing and its observation sources (`AUTHORIZATION_CONSUMED`,
`BROKER_RESPONSE`, `TARGET_OUTCOME`, `RECONCILER_LOOKUP`). Authorization
consumption and dispatch are never outcome evidence on their own.

## Surfaces

- Console: operation history shows the grade with its meaning, coverage, and
  per-effect fencing and observations.
- Export: a bounded, cursor-paginated tenant export of current operation
  outcomes with grade, coverage and fencing counts.
- Analytics: Control-derived grade and coverage distribution for the window,
  presented separately from Gateway-recorded call states.

## Current limitation

Control stores broker observations as `BROKER` effect evidence, but no
production path records them yet: `EffectStore.observe` has no caller. Until a
broker reporting path exists, `BROKER_OBSERVED` and the `BROKER_RESPONSE`
observation are defined and tested but do not appear in production records.

## Deliberately deferred

Treating an authoritative broker response as resolving an effect for
finalization changes settlement semantics and its SQL guards. It is not part of
this presentation stage. Until a later stage decides it, such effects show
`BROKER_OBSERVED` while the operation stays unresolved.
