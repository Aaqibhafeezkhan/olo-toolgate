<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Conformance fixture server

## Purpose

Runs the official MCP conformance suite (`@modelcontextprotocol/conformance@0.2.0-alpha.12`) against the Java protocol layer: adapter A1 (the 2026-07-28 core), adapter A2 (Tasks), adapter A3 (MRTR `requestState`) and the Streamable HTTP transport. It is a test program and is not published.

The suite calls fixture tools by bare names such as `test_simple_text`. SDK tools are always named `<package alias>.<local name>`, so the fixtures are a `ToolCatalog` served by `ProtocolCore` directly, which is how the official SDKs run the suite too. The runtime behaviour behind the catalog (validation, the error model, limits) is covered by the runtime's own tests.

## Running it

```bash
tool-sdk/java-sdk/conformance-server/run.sh
```

It needs Node.js and npm. It builds the server, starts it on `localhost:18765` (`TOOLGATE_CONFORMANCE_PORT` changes the port), installs the pinned suite under `build/conformance/npm`, and runs `--requirements 2026-07-28` with [expected-failures.yml](expected-failures.yml). Per-scenario `checks.json` files land in `build/conformance/results`. It exits non-zero on a failure that is not in the baseline, or on a baseline entry that now passes.

## Result

On 2026-10-11, every required 2026-07-28 server scenario that applies to tools passes. That covers `server-stateless` (29 checks), `tools-list`, the seven `tools-call-*` scenarios, the thirteen tool `input-required-result-*` (MRTR) scenarios, `server-sse-multiple-streams`, `dns-rebinding-protection`, the tools half of `caching`, and `http-header-validation`. All ten `tasks-*` extension scenarios pass, including `tasks-mrtr-composition`; `tasks-status-notifications` is a placeholder the suite always skips.

The baseline holds prompts, resources and completions: `prompts-*`, `resources-*`, `completion-complete`, the prompt and resource checks in `caching`, `sep-2164-resource-not-found` and `input-required-result-non-tool-request`. ToolGate serves tools only, and A1's acceptance in [protocol-layer.md](../../spec/protocol-layer.md) counts the scenarios that apply to tools. The baseline counts warnings as failures, so SHOULD-level checks on missing features are listed too.

Two scenarios outside the required set still fail and are not scored: `json-schema-2020-12` and `http-custom-header-server-validation` (`x-mcp-header`).
