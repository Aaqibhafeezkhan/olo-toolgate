<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# MCP proxy definition digest

## Purpose

Control and the Tool Host compare upstream MCP tool definitions by digest: Control when it accepts a scan and approves
tools, the Tool Host when it checks freshness before a call. Both must compute the same value from the same definition,
so this page defines it once and [definition-digest.json](definition-digest.json) holds the shared vectors. Java
(`McpDefinitions.digest` in Control) and Rust (`mcp::definition_digest` in the Tool Host) are tested against them.

## Definition

1. Take the tool object exactly as the upstream returned it in `tools/list`.
2. Keep only these members, those that are present: `name`, `title`, `description`, `inputSchema`, `outputSchema`,
   `annotations`. Every other member (for example `_meta` or `icons`) is dropped and does not affect the digest.
3. Serialize the result as RFC 8785 JSON: object keys sorted by UTF-16 code units, ECMAScript number formatting, the
   RFC 8785 string escapes, UTF-8. Strings are **not** Unicode-normalized: the definition is compared as returned, so
   any change, including a change of normalization form, is a change of definition.
4. The digest is `sha256:` followed by the lowercase hex SHA-256 of those bytes.

A member that is present with a value of `null` is kept as `null`. Scan validation (`McpUpstreamTool`) refuses such a
definition before any digest is stored, so the case does not reach an approval.

## Vectors

Each case gives the upstream `tool` object, its `canonical` serialization and its `digest`. An implementation passes
when it produces both for every case.
