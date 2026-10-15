<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# ADR 019: Tool SDK authoring contract, runtime modes and MCP SDK compatibility

**Status:** Accepted (design gate D0), on merge of the PR that adds this file

## Context

ToolGate will ship a Tool SDK in Java, Python, TypeScript and .NET so that anyone can write MCP tools that run either as a plain MCP server (standalone) or under ToolGate governance (Tool Host and managed endpoints). Publishing an SDK freezes an API that outside authors depend on, and the governed platform will enforce what the SDK declares. Reviews of the plan ([docs/contracts/platform-requirements.md](../contracts/platform-requirements.md), revision 10) required that, before any SDK code:

- the entire authoring contract is frozen, including the parts only governed mode exercises;
- standalone and governed mode are defined so that a tool cannot silently gain permissions or change its external effects when moved between them;
- JWT claims are verified whenever they influence permissions, downstream identity or execution, with fixture claims only in an explicit development or test mode;
- the official MCP SDK versions are pinned, tested, and every missing feature has an adapter design;
- library composition fails on conflicts unless an explicit, reviewed mapping resolves them.

## Decision

Adopt the normative specification in [`tool-sdk/spec/`](../../tool-sdk/spec/README.md) as the Tool SDK authoring contract:

1. **One contract, four languages.** [`authoring/`](../../tool-sdk/spec/authoring/README.md) defines every declaration, `ToolContext` member, value type, error and execution rule; the language files map them without adding or changing meaning.
2. **Semantic descriptor and digests.** [`descriptor/`](../../tool-sdk/spec/descriptor/README.md) defines descriptor generation 1, its RFC 8785 canonical form, `toolDescriptorDigest` and `descriptorSetDigest`, and a closed equivalence rewrite list, with normative test vectors. Artifact and package digests are exact-byte identities and never decide semantic equality.
3. **Runtime modes.** [`runtime-modes.md`](../../tool-sdk/spec/runtime-modes.md) fixes the capability matrix and the invariant: equal descriptor digests in both modes, governed mode adds only reviewed restrictions, no mode-only API, failures are loud and coded.
4. **Identity.** Standalone `production` mode accepts claims only from a verified credential (adapter A4) and refuses to start without a verifier when any tool depends on identity; fixture claims exist only in explicit `development` (loopback or stdio, marked results) and `test` modes.
5. **Composition.** [`composition.md`](../../tool-sdk/spec/composition.md) namespaces every library declaration, fails every conflict, allows only narrowing mappings, and records composition in the descriptor.
6. **Official MCP SDKs and adapters.** The pinned versions and test results in [`sdk-compat/`](../../tool-sdk/spec/sdk-compat/README.md) are the dependency baseline. [`protocol-layer.md`](../../tool-sdk/spec/protocol-layer.md) defines adapters A1 (protocol core, Java), A2 (Tasks, all), A3 (`requestState`, all), A4 (identity, all) and A5 (method routing, .NET and Java).

Any change to these files is an amendment to this ADR and reopens gate D0.

## Alternatives Considered

- **Freeze only the standalone subset for SDK 0.1 and add governed features later.** Rejected: governed features would then change the meaning of published APIs.
- **Use each official MCP SDK's own Tasks and bearer support.** Rejected: only .NET has Tasks, and no suite tests bearer verification, so behaviour would differ by language.
- **Byte-identical descriptors across SDK upgrades.** Rejected as too strict; versioned descriptor generations plus a closed rewrite list keep equality decidable.
- **Last-writer-wins or union merge for libraries.** Rejected: both can silently broaden access.

## Security Impact

- Moving a tool between modes cannot add routes, secrets, claims, destinations or weaker effect classes; enable compares descriptor digests.
- Unverified claims are impossible in production mode; `alg: none` and HMAC are always rejected; JWKS and introspection rules are shared across languages.
- Free-form URLs and host values from runtime data do not exist in the API; the broker remains the runtime boundary in governed mode.
- `requestState` is signed, bound to tool, arguments and identity, and single use.

## Operational Impact

None for existing deployments: this ADR adds specification files and no product code. Later milestones (platform-requirements.md §22.2) implement it.

## Compatibility Impact

Defines the public SDK API that SDK 0.1 will publish, and descriptor generation 1. Existing v1 contracts and tool protocol v1 are unchanged. Official SDK upgrades require a new compatibility record.

## Consequences

- Positive: SDK authors and reviewers have one testable definition; mode equivalence and composition are mechanically checkable.
- Negative: ToolGate owns five adapters, including a full 2026-07-28 core for Java until the official Java SDK catches up.
- Negative: changes to the contract need an ADR amendment, which slows small API additions by design.

## Validation

- Descriptor vectors: `python3 tool-sdk/spec/descriptor/vectors/verify.py` (also cross-checked with an independent Node implementation).
- Composition fixtures in `tool-sdk/spec/composition/cases.json` become packager tests in every SDK.
- Adapter acceptance cases in `protocol-layer.md` and the official conformance suite version in the compatibility record.
- The mode-equivalence suite (platform-requirements.md §21) runs every example in both modes.
