# ADR-024: One CI workflow with shared check ownership

**Status:** Accepted

## Context

Five main workflows repeat metadata, workspace tests, platform builds and image
checks. Development publication then waits for those separate runs. Repetition
makes failures expensive to diagnose and can publish from mismatched attempts.

## Decision

`ci.yml` owns admission and preflight, then runs shared foundation checks and six
native platform jobs in parallel. One image stage consumes their artifacts and
tests each production image. Development publication consumes only artifacts
from that same run and SHA after every required gate succeeds.
Foundation also builds and validates the UI once. Gradle and the CI Docker builds
consume that verified UI artifact; ordinary Docker builds keep building from source.

Each identical check has one owner. OS/architecture, database, published-contract
and production-image contexts remain distinct checks. Legacy callable workflows
remain available for protected release callers; main does not invoke them.

## Security Impact

RC admission, all vulnerability/license/secret gates, native isolation tests and
protected publication remain mandatory. Publication verifies the run's SHA,
branch, workflow, event and successful prerequisite jobs, including all six
native targets. It does not wait for its own unfinished publishing job.

## Operational and Compatibility Impact

Main has one automatic workflow. Platform jobs still use their native runners.
Manual build-only publication and protected tagged publication retain their
existing contracts. Existing clients and artifact formats remain compatible.

## Alternatives Considered

Keeping five independent main workflows repeats shared work. One runner cannot
test the required native Windows, macOS and Linux service managers.

## Validation

Focused workflow ownership tests protect the main call graph, indirect helper
calls and release rejection of failed, missing or mismatched evidence. Pinned
actionlint validates the changed workflows. Full hosted validation runs after
an admitted merge; local validation uses the user's requested focused scope.
