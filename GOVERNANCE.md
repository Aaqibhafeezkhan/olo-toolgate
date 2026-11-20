# Governance

OLO ToolGate is maintained under the `olo-labs` GitHub organization.

## Principles

- technical decisions are documented
- security boundaries require explicit review
- public APIs and schemas are treated as contracts
- maintainers should prefer reversible decisions
- project direction should be visible through issues, discussions and current architecture guides

## Maintainer Responsibilities

Maintainers:

- review code
- protect release/signing processes
- keep contributor setup working
- enforce security invariants
- keep public contracts documented
- triage reports
- label good first issues
- guide contributors toward appropriately scoped changes

## Architecture Decisions

Review major decisions in an issue. Keep the adopted behavior in the owning
[architecture guide](docs/architecture/README.md). Only unfinished work belongs
in [plans](docs/plans/README.md); Git history retains superseded designs.

## Becoming a Maintainer

Long-term contributors may be invited based on:

- sustained quality contributions
- review participation
- reliability
- security awareness
- constructive community involvement

## Releases

A release must satisfy documented release gates before being marked stable.
