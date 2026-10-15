<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Conformance cases

## Purpose

This directory holds the acceptance cases for the SDK and governed platform.
[suites.json](suites.json) defines each suite, runner and required evidence.
[Platform requirement IDs](../../docs/contracts/platform-requirements.md) connect
the cases to maintained design sources. Pending implementation is tracked in
the [backlog](../../docs/planning/README.md).

The cases say **what** must hold, in the words of the design they come from. Each language and component writes its own runner, which reads these files.

## Layout

| Path | Contents |
|---|---|
| [`case.schema.json`](case.schema.json) | JSON Schema for a case file |
| [`suites.json`](suites.json) | The nine conformance suites: what each contains, when it runs, what it blocks, its runner and the milestone where it first passes |
| `<suite>/<area>.json` | The cases for one area of one suite |
| [`fixtures/packages/`](fixtures/packages/README.md) | Package archive fixtures for `contract-descriptor/packages.json`, plus their generator |

Other fixtures are used where they already exist, rather than copied:
- the v2 schema fixtures under `tests/fixtures/contracts/v2/`;
- the descriptor vectors under `tool-sdk/spec/descriptor/vectors/`;
- the composition vectors in `tool-sdk/spec/composition/cases.json`;
- the official SDK pins and scenario lists under `tool-sdk/spec/sdk-compat/`.

## Case format

Each case has:
- `id`: an `area/name` slug, unique across all suites. The Tool Host threat model names cases by these ids.
- `title`.
- `requirements`: up to 8 sources, written as a plan section, a spec section, an ADR decision or a threat id.
- `level`: `unit`, `component`, `integration` or `system`.
- `targets`: the SDK languages or components the case runs against. `perTarget: true` means the case runs once for each target, for example once per SDK language.
- `given` (optional), `when` and `then`: the setup, the action, and the expected results. `then` may list up to 12 results.
- `fixtures` (optional): files the case uses, as paths relative to the repository root.
- `status`: `authored` or `passing`.

## Status rules

- A case without passing runner evidence stays `authored`.
- A case becomes `passing` only in the PR where its runner passes it in CI. That PR sets `status` and fills `evidence` with the runner and test name.
- A passing case that later fails blocks the same scope its suite blocks (`suites.json`).
- A case is changed or removed only together with the design text it cites. If that text is a frozen design artifact, the change needs the ADR amendment that re-opens its gate.

## Runner contract

A runner for a suite:
1. reads every case in its suite whose `targets` include the runner's language or component;
2. runs each case, once per target when `perTarget` is set, and reports results keyed by case id;
3. fails if any case it owns is missing a test. A case nobody runs counts as failing, not as skipped.

`tests/contracts/test_conformance_cases.py` checks the files themselves:
- every file validates against the schema and sits in its suite's folder;
- case ids are unique, and every suite has cases;
- every Tool Host threat names cases that exist;
- every invocation transition T1 to T14 and every persistence row 1 to 11 of platform-requirements.md §12.7 has a case;
- every composition error code has a case;
- every referenced fixture exists;
- the package fixtures match their generator.

## Suites

| Suite | Folder | First passing |
|---|---|---|
| Contract and descriptor | [`contract-descriptor/`](contract-descriptor/) | M0, M1 |
| SDK unit | [`sdk-unit/`](sdk-unit/) | M0 |
| Protocol compatibility | [`protocol/`](protocol/) | M1, M2 |
| Security and authorisation | [`security/`](security/) | M1, M2 |
| Integration and failure injection | [`integration/`](integration/) | M2, M3, M4 |
| Mode equivalence | [`mode-equivalence/`](mode-equivalence/) | M0, M2, M5 |
| Official MCP SDK compatibility | [`official-sdk/`](official-sdk/) | M0 |
| Multi-host chaos and load | [`chaos-load/`](chaos-load/) | M4 |
| Full language matrix | [`language-matrix/`](language-matrix/) | M0, M5, M6 |

## Known gaps

- The specifications do not yet name the exact error codes for package archive rejections, the stale-catalog error or the launcher's launch failures. Those cases expect a rejection. M1 names the codes and updates the cases.
- The compatibility matrix of Tool Host, client and OS versions (platform-requirements.md §5.6) is not enumerated yet. `language-matrix/matrix.json` will take its rows from the matrix when M6 publishes it.
- Recorded IdP sample tokens (`security/credentials.json`) are captured in M1 for each shipped template.
