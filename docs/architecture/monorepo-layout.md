# Repository layout

| Directory | Contents |
|---|---|
| `apps/` | Gateway, Control, console, Quickstart, endpoint client, Tool Host and Marketplace scaffolds |
| `crates/` | Shared Rust libraries |
| `backend-libs/` | Shared Java libraries |
| `packages/contracts/` | Canonical schemas/OpenAPI and generated Rust, Java, TypeScript and PHP bindings |
| `tool-sdk/` | CLI, Java SDK, language-neutral specifications and conformance fixtures |
| `examples/` | Runnable tool and agent examples |
| `deploy/` | Compose helpers, Helm chart, Kubernetes assets and container build support |
| `config/initial/` | Initial configuration presets |
| `debug/` | Persistent local debugging workflow |
| `tools/` | Build, validation, release and developer automation |
| `tests/` | Contract, CI and other cross-component checks/fixtures |
| `docs/` | User, contributor and operator guides, security model and ADRs |
| `docs/planning/` | Consolidated pending and planned features |

Generated build output and local credentials belong in ignored directories.
Canonical cross-language schemas live in `packages/contracts/schemas/`; do not
create competing model definitions. See [schema workflow](../development/schema-workflow.md).
