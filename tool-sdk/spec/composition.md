# Library composition

## Purpose

Defines how a tool package includes tool libraries: the fragment a library ships, the one merge algorithm every packager runs, the mapping keys, and the failures. Fixtures in [composition/cases.json](composition/cases.json) are normative.

## 1. Fragment

A tool library is an ordinary Maven, PyPI, npm or NuGet package that contains `toolgate-fragment.json` (in the jar root, the wheel's `toolgate/` data directory, the npm package root, or the NuGet `content/toolgate/` folder):

| Member | Content |
|---|---|
| `fragmentVersion` | `1` |
| `libraryId` | A package id ([authoring/README.md](authoring/README.md) §1) |
| `version` | SemVer of the library |
| `descriptorGeneration` | Generation of every descriptor in the fragment |
| `namespacePrefix` | `^[a-z][a-z0-9]{1,30}$`, unique among the libraries in one build |
| `tools` | Semantic descriptors ([descriptor/README.md](descriptor/README.md)) with **local** names; `toolId` and `mcpName` are assigned at merge |
| `routes`, `destinations`, `httpAuth`, `tcp`, `globals`, `secrets`, `claims` | Declarations with library-local names |

The packager reads the fragment from the resolved library artifact and records its content digest (`sha256` of the fragment's canonical bytes).

## 2. Merge algorithm

Inputs: the host's own declarations, the fragments of every library in the build's lockfile, and the build's mapping block. Discovery order never matters: libraries are processed sorted by `libraryId`.

1. **Versions.** Exactly one version per `libraryId`; otherwise `COMPOSITION_DUPLICATE_LIBRARY`.
2. **Generation.** Each fragment's generation must be supported by the host's packager. A different but supported generation is normalised only through the rewrite list ([descriptor/README.md](descriptor/README.md) §4); if the result differs, `COMPOSITION_GENERATION_UNSUPPORTED`.
3. **Namespacing.** Every library name is prefixed: tool local names become `<prefix>.<name>`; route, destination, auth and TCP ids become `<prefix>.<id>`; globals, secrets and claims become `<prefix>.<Name>` (the variable-name grammar is extended by exactly one prefix segment for library declarations). References inside the fragment are rewritten the same way.
4. **Mappings.** Applied as written in the build (§3). Each mapping is checked for compatibility; an incompatible one fails with `COMPOSITION_INCOMPATIBLE_MAPPING`, and a mapping that names something that doesn't exist fails with `COMPOSITION_UNKNOWN_MAPPING_TARGET`.
5. **Exclusions and narrowing.** Excluded tools are removed. Narrowing (a shorter route list, a stronger effect class, a lower `maxConcurrency` or `maxDurationMs`) is applied. Any widening fails with `COMPOSITION_WIDENING`; any weakening of effect safety or business-key settings fails with `COMPOSITION_EFFECT_WEAKENED`.
6. **Identity checks.** Assign `toolId = <host package id>/<local name>` and `mcpName` per the authoring rules. A duplicate `toolId` fails with `COMPOSITION_TOOL_ID_CONFLICT`; a duplicate `mcpName`, including after client-specific shortening, fails with `COMPOSITION_MCP_NAME_CONFLICT`. A duplicate declaration id after prefixing fails with `COMPOSITION_DECLARATION_CONFLICT`.
7. **Record.** Emit `composition[]`, sorted by `libraryId`: `{libraryId, version, fragmentDigest, namespacePrefix, mappings[], excluded[], narrowed[]}`. The SBOM lists the same libraries.

Permissions stay per tool and are never unioned. A library tool keeps exactly the routes its fragment lists (after narrowing).

## 3. Mapping keys

| Key | Effect | Compatible when |
|---|---|---|
| `secret(name, to)` | The library secret is replaced by the host's (or another library's, by full name) | Same `type` and `scope` |
| `global(name, to)` | Shares a global | Same `type`, constraints and `required`; defaults may differ only if the host's is used |
| `claim(name, to)` | Shares a claim | Same `claim` pointer and `type` |
| `destination(id, to)` | Shares a destination | Same `port`, `spkiPins`, `caBundle` and transport |
| `exclude(toolName)` | Removes a library tool | Always |
| `narrow(toolName) { routes, effect, maxConcurrency, maxDurationMs }` | Restricts a library tool | Only restrictions |

There is no key to add a route, widen a path pattern or weaken an effect class.

## 4. Failure codes

`COMPOSITION_DUPLICATE_LIBRARY`, `COMPOSITION_GENERATION_UNSUPPORTED`, `COMPOSITION_INCOMPATIBLE_MAPPING`, `COMPOSITION_UNKNOWN_MAPPING_TARGET`, `COMPOSITION_WIDENING`, `COMPOSITION_EFFECT_WEAKENED`, `COMPOSITION_TOOL_ID_CONFLICT`, `COMPOSITION_MCP_NAME_CONFLICT`, `COMPOSITION_DECLARATION_CONFLICT`. Each failure reports the library, the item and the build location of any mapping involved.

## Related Docs

- [Descriptor](descriptor/README.md)
- platform-requirements.md §7.7
