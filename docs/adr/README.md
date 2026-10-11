# Architecture Decision Records

Create an ADR when changing:

- component responsibility.
- major language/runtime.
- policy engine.
- cryptographic format.
- package format.
- signing/trust model.
- persistent-store choice.
- endpoint enforcement approach.
- public API compatibility.

Use `000-template.md`.

- [001: Shared contracts and foundation](001-foundation-contract-generation.md)
- [002: Gateway static foundation](002-gateway-static-foundation.md)
- [003: Control Plane transactions and identity](003-control-plane-transactions.md)
- [004: Embedded administration console](004-embedded-admin-console.md)
- [005: Signed policy publication and verified snapshots](005-signed-policy-bundles.md)

- [006: Durable exact-operation ASK approvals and single-use permits](006-ask-approvals.md)
- [007: Protected endpoint identity and browser enrollment](007-endpoint-enrollment.md)
- [008: Capability-scoped built-ins and anonymous client distribution](008-hotfolder-builtins.md)
- [009: Managed runtimes execute in a disposable OCI sandbox](009-managed-local-runtime-sandbox.md)
- [010: Signed fleet generations and immutable OCI package descriptors](010-signed-fleet-reconciliation.md)
- [011: Signed source authoring and designated-client sandbox tests](011-designated-client-tool-authoring.md)
- [012: Client MCP requests delivered by polling](012-client-mcp-polling-relay.md)
- [012a: Single-node Quickstart composition](012a-single-node-quickstart.md)
- [013: Tool Host, permits, egress broker, secrets and transports](013-tool-host-egress-broker-secrets.md)
- [014: Tool package format v2, conversion to fleet releases and version lifecycle](014-tool-package-format-v2.md)
- [015: Credential classification, catalog scope and MCP routes](015-credentials-catalog-scope-routes.md)
- [016: Durable tasks, effect safety, business keys and persistence](016-tasks-effect-safety-business-keys.md)
- [017: Emergency kill switch](017-kill-switch.md)
- [018: MCP protocol versions and the legacy compatibility adapter](018-mcp-versions-legacy-adapter.md)
- [019: Tool SDK authoring contract, runtime modes and MCP SDK compatibility](019-tool-sdk-authoring-contract.md)
- [020: Deployment binding and configuration precedence](020-deployment-binding.md)
- [021: Client check-in settings and the Configuration menu](021-client-checkin-configuration-menu.md)
