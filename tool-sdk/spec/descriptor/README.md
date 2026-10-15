# Semantic tool descriptor, generation 1

## Purpose

Defines what each SDK's packager emits for a tool (the **semantic descriptor**), its canonical bytes, the three digests, and when two descriptors count as equivalent. Test vectors in [vectors/](vectors/) are normative: every SDK **must** reproduce their canonical bytes and digests exactly.

## 1. Scope

The semantic descriptor is the part of a tool that decides what it does and what it may touch. It is computed per tool and is **identical in standalone and governed mode** ([runtime-modes.md](../runtime-modes.md) §3). D1's `ToolDefinition` v2 schema **must** carry every member below with the same name and meaning; D1 may add deployment and packaging members, which are outside the semantic descriptor.

Excluded from the semantic descriptor: SDK and runtime versions, artifact digests, entry points, build metadata, `Example`s, marketplace data, and every deployment value (destination hosts and `defaultHost`, global values other than declared defaults, secret values and sources, identity configuration).

## 2. Members (generation 1)

All members are always present unless marked optional. Optional members are **omitted** when absent; `null` is never emitted. Arrays are always present, possibly empty. Defaults are materialised, so declaring a default explicitly and leaving it implicit produce the same bytes.

| Member | Content |
|---|---|
| `descriptorGeneration` | `1` |
| `toolId`, `mcpName` | [authoring/README.md](../authoring/README.md) §1 |
| `kind` | `SERVER` or `CLIENT` |
| `title`, `description` | As declared; `text` is emitted as `title` |
| `useWhen` | Optional |
| `inputSchema` | §5 |
| `outputSchema` | Optional; §5 rules, from the declared or inferred return type. Absent for `ToolResult` returns without an output schema. |
| `errors` | `[{code, description, retryable}]`, sorted by `code` |
| `effect` | `{safety, destructive, openWorld}` with defaults resolved |
| `businessKey` | `{required, namespace, argument?, idempotencyHeader?, idempotencyScope?}` |
| `execution` | `{async, maxDurationMs, stateless, maxConcurrency?, cancelIfAbandoned}` with defaults resolved |
| `reconcileTool` | Optional tool local name of the `Reconcile` handler |
| `tiers` | `CLIENT` only, sorted |
| `routes` | The tool's routes, full declaration minus host values, sorted by `id`; list members inside a route keep declaration order except `query`, `headers`, `requestContentTypes`, which are sorted |
| `destinations`, `httpAuth`, `tcp` | Only those reachable from the tool's routes and `tcp` list, sorted by `id`; destinations without `defaultHost` |
| `variables` | `{globals, secrets, claims}`, each sorted by `name`. Globals carry `{name, type, required, default?, sensitive, …constraints}` with `default` as a typed JSON value. |
| `permissions` | Derived, sorted: `HTTP_ROUTE`, `TCP`, `CREDENTIALS_IN_SANDBOX`, `USER_SECRET`, `TOKEN_EXCHANGE`, `CONNECTION` |
| `toolsets` | Sorted |

"Sorted" always means by UTF-16 code units, the RFC 8785 order, so every language sorts the same way.

## 3. Canonical form and digests

1. Normalise every string (keys and values) to Unicode NFC.
2. Serialise with RFC 8785 (JCS): keys sorted by UTF-16 code units, no insignificant whitespace, ECMAScript number and string serialisation.
3. Encode as UTF-8.

| Digest | Definition |
|---|---|
| `toolDescriptorDigest` | `"sha256:" + lowercase hex SHA-256 of the canonical bytes of one semantic descriptor` |
| `descriptorSetDigest` | Same, over the array of `{toolId, toolDescriptorDigest}` for every tool in the package, sorted by `toolId` |
| `artifactDigest`, `packageDigest` | Over exact bytes; defined by the package format (platform-requirements.md §7.2) and frozen at D1. They are never used to decide semantic equality. |

Generation 1 emits integers only for numeric values it derives (bounds of `INT` and `LONG`, sizes, durations). Author-declared `NUMBER` bounds and defaults are serialised by JCS's number rules; the packager rejects values that are not finite doubles.

## 4. Equivalence

Two descriptors are **equivalent** if their canonical bytes are equal after applying this closed rewrite list (version 1) to `inputSchema` and `outputSchema` of both:

1. inline a local `$ref` to `#/$defs/<name>` that is not recursive, then drop `$defs` entries no longer referenced;
2. replace `"type": ["x"]` with `"type": "x"`;
3. sort `required` arrays;
4. remove a keyword whose value equals the JSON Schema default, from this list only: `additionalProperties: true`, `uniqueItems: false`, `minItems: 0`, `minLength: 0`, `deprecated: false`, `readOnly: false`, `writeOnly: false`.

Anything else that differs is a **change**, including descriptions, enum order and recursive references, and goes to the API-compatibility and security-delta classifiers. Equal digests imply equivalence; equivalence does not imply equal digests. Mode equivalence requires equal **digests**, not merely equivalence.

## 5. Schema generation rules (generation 1)

- Inputs form one closed object: `{"type": "object", "properties": {…}, "required": [sorted], "additionalProperties": false}`. No `$schema`, `$id` or `title` keywords.
- Each property carries `type`, `description` (required for inputs), and only the constraint keywords that were declared: `minimum`, `maximum`, `minLength`, `maxLength`, `pattern`, `enum` (declaration order), `format`, `default`, `contentEncoding`.
- `INT` always gets `minimum: -2147483648`, `maximum: 2147483647` unless narrower bounds are declared; `LONG` gets ±9007199254740991.
- Optional and nullable language types make the input not required; `null` is never added to `type`.
- `sensitive: true` is emitted as `"x-toolgate-sensitive": true` on the property.
- Records, classes and Zod objects become closed objects; explicit maps become `{"type": "object", "additionalProperties": <value schema>}`.
- `$ref` and `$defs` are emitted only for recursive types, keyed by the type's simple name.
- Value types map as in [authoring/README.md](../authoring/README.md) §7.

**TypeScript (Zod) constructs allowed in generation 1.** Anything not listed fails packaging.

| Zod | JSON Schema |
|---|---|
| `z.string()` with `.min`, `.max`, `.length`, `.regex`, `.email()`, `.url()`, `.uuid()`, `.date()`, `.datetime({ offset: true })`, `.duration()` | `string` with `minLength`, `maxLength`, `pattern`, `format` |
| `z.number()` with `.int()`, `.min`, `.max`, `.safe()` | `number`, or `integer` with `.int()` (`INT` bounds) or `.int().safe()` (`LONG` bounds) |
| `z.boolean()` | `boolean` |
| `z.enum([...])` | `string` with `enum` |
| `z.object({...})` | closed object |
| `z.record(z.string(), T)` | map |
| `z.array(T)` with `.min`, `.max` | `array` with `minItems`, `maxItems` |
| `.optional()`, `.default(v)`, `.describe(s)` | not required, `default`, `description` |
| `binary()` (SDK helper) | `BINARY` |

Not allowed: unions, intersections, `nullable`, `transform`, `refine`, `preprocess`, `lazy`, `any`, `unknown`, `bigint`, `z.date()`.

## 6. Test vectors

| File | Exercises |
|---|---|
| `tool-01-checksum.json` | Minimal client tool, defaults materialised |
| `tool-02-orders-lookup.json` | Server tool with route, destination, auth, globals, secrets, claims, errors |
| `tool-03-refund.json` | Non-idempotent destructive task tool, business key, non-ASCII strings, UTF-16 key ordering (`Ａlpha` sorts after `😀note`) |
| `set-01-orders.json` | `descriptorSetDigest` over two tools listed out of order |
| `equiv-01` to `equiv-04` | Each rewrite rule, and three non-equivalent changes |
| `*.jcs` | Expected canonical bytes |
| `expected.json` | Expected digests and equivalence results |

`python3 vectors/verify.py` recomputes everything with the reference implementation; the vectors were also cross-checked with an independent Node implementation. Each SDK's packager test suite **must** load these files and produce the same bytes and results.
