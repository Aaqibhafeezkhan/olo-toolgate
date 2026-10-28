# Contract set v2: binding generation design

## Purpose

How milestone M1 generates Rust, Java, TypeScript and PHP bindings for the frozen v2 schemas in [packages/contracts/schemas/v2](../../packages/contracts/schemas/v2/README.md), alongside the existing v1 bindings, and how the contract-set version moves to 1.0. This is a design note (gate D1). M1 part 1 implemented decisions 1 to 6 and 8 in `tools/contracts/generate.py`. Decision 7, the move to `1.0.0`, shipped with the last M1 part, when the Gateway and Control began to consume v2 (decision 10).

## Current state (v1)

- `tools/contracts/generate.py` reads only `schemas/v1/*.json` and emits one flat namespace per language: Rust crate `olo-toolgate-contracts`, Java package `io.ololabs.toolgate.contracts`, TypeScript `@olo-labs/toolgate-contracts`, PHP `OloLabs\ToolGate\Contracts`.
- It refuses `oneOf`, `anyOf`, `allOf`, `if`/`then`/`else`, `not`, `patternProperties`, `dependentSchemas` and `unevaluatedProperties`, inline enums and inline object models.
- It copies each schema into `packages/contracts/rust/schemas/v1/` for `include_str!`. `rust/schemas/v1/approval.schema.json` has no canonical source in `schemas/v1/` (it is a stale copy), and nothing detects that today.
- `tools/contracts/fixtures.py` builds the v1 round-trip corpus; `generated-manifest.json` records source digests.

## Decisions

1. **Namespaced, side by side.** v2 models are generated into a separate namespace in every language, so v1 and v2 types with the same name (`RequestContext`, `PackageManifest`, `ToolDefinition`) coexist during migration:

   | Language | v1 (unchanged) | v2 |
   |---|---|---|
   | Rust | crate root | module `olo_toolgate_contracts::v2` |
   | Java | `io.ololabs.toolgate.contracts` | `io.ololabs.toolgate.contracts.v2` |
   | TypeScript | package root | subpath export `@olo-labs/toolgate-contracts/v2` |
   | PHP | `OloLabs\ToolGate\Contracts` | `OloLabs\ToolGate\Contracts\V2` |

   Same package identities and one shared SemVer; no new artifacts. Closed records may reference an explicit definition in the other bundled version (`../v1/...` or `../v2/...`). Generation qualifies the target namespace, and offline validators register both canonical versions while exposing only their selected version's model names. References outside the bundled set remain invalid; composition keywords remain unsupported. The v2 scoped console response reuses frozen v1 endpoint health and enrollment prompts through this rule.

2. **Same structural subset, two additions.** v2 schemas stay inside the v1 generator's subset (enforced now by `test_definitions_stay_in_the_generator_subset`). The generator gains exactly:
   - `type: number` → `f64` / `Double` / `number` / `float`;
   - a property schema with no `type` (only a description, used for typed defaults and effective values) → `serde_json::Value` / `JsonNode` / `unknown` / `mixed`.
   Everything else that the v1 generator refuses stays refused.

3. **`const` and integer-const fields** (`schemaVersion: 2`, `descriptorGeneration: 1`, `formatVersion: 2`) are emitted as the literal type where the language has one (TypeScript) and validated on construction elsewhere.

4. **Stale-copy check.** The generator computes the full output set for both versions and fails (in `--check`) when `packages/contracts/rust/schemas/` contains any file without a canonical source. M1 removes `rust/schemas/v1/approval.schema.json`, or restores its canonical source if something still needs it, in the same PR that adds the check.

5. **Manifest.** `generated-manifest.json` gains `schemaSha256` entries for `schemas/v2/*.json`; the generator digest covers both versions.

6. **Fixtures.** `tests/fixtures/contracts/v2/valid.json` is the v2 round-trip corpus. The per-language round-trip tests (`rust/tests/roundtrip.rs`, `ContractRoundTripTest.java`, `php/tests/roundtrip.php`, `typescript/test/roundtrip.mjs`) gain a v2 section that decodes and re-encodes every v2 fixture byte-equivalently (after JSON normalisation). `invalid.json` stays a schema-validation corpus; bindings are structural and are not expected to reject every invalid case.

7. **Version.** Product and SDK release versions follow [version.json](../../version.json). The v2 wire-schema change removes device identity from `RequestContext` and uses Control-issued Tool Host permits; the wire-schema version is independent of the release channel. v1 bindings stay published in the same artifacts for at least the legacy window in ADR 018.

8. **Descriptor digests are not computed by the bindings.** Canonicalisation and digests live in the SDK packagers and Control, implemented against [tool-sdk/spec/descriptor](../../tool-sdk/spec/descriptor/README.md) and its vectors, not in generated code.

9. **Implementation notes (M1 part 1).**
   - Constant enforcement applies to v2 only, so the published v1 bindings stay byte-identical.
   - Enum values that are not identifiers (`KillIssuer` `control-breakglass`) are renamed per language, and the wire value is kept.
   - Property names that are Java keywords (`default`, `enum`) get a trailing underscore on the Java record component, and the `@JsonProperty` name is kept.
   - A definition that is only a `$ref` (`ServiceRoute`) is a Rust and TypeScript type alias. Java and PHP use the target type.
   - The stale-copy rule covers every generated directory, and `rust/schemas/v1/approval.schema.json` is removed.

10. **v1 consumes v2 (last M1 part).** The request context exists once, in v2. `RequestContext`, `CredentialKind`, `RequestMode` and `ActorHop` are removed from the v1 schemas (the last two were `EnterpriseRequestMode` and `EnterpriseActorHop`, wire-identical), and v1 models reference them as `../v2/common.schema.json#/$defs/...`. The generator names a cross-version reference in the v2 namespace (`v2::RequestContext`, `io.ololabs.toolgate.contracts.v2.RequestContext`, `V2.RequestContext`, `\OloLabs\ToolGate\Contracts\V2\RequestContext`). v2 stays self-contained. The Rust `CANONICAL_SCHEMAS` inventory embeds v1 and v2, and Control's `ContractCodec` adopts every v2 definition a v1 schema references, so offline validators resolve the references. The contracts crate carries the contract-set version; the Rust applications keep the product version.

## Acceptance for M1

- `python tools/contracts/generate.py --check` covers v1 and v2 and the stale-copy rule.
- Round-trip tests pass for every v2 fixture in all four languages.
- No v2 schema file changes in the M1 PRs.
