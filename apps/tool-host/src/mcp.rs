// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The MCP proxy runtime. `definition_digest` is the definition digest of tool-sdk/spec/mcp-proxy: `sha256:` over
//! the RFC 8785 form, without NFC, of an upstream tool's six definition members exactly as returned. Control's
//! `McpDefinitions.digest` computes the same value.

use serde_json::{Map, Value};

/// The members a definition digest covers; every other member is dropped.
pub const DEFINITION_MEMBERS: [&str; 6] = [
    "name",
    "title",
    "description",
    "inputSchema",
    "outputSchema",
    "annotations",
];

pub fn definition_digest(tool: &Value) -> Result<String, String> {
    let tool = tool
        .as_object()
        .ok_or("an MCP tool definition is a JSON object")?;
    let kept: Map<String, Value> = DEFINITION_MEMBERS
        .iter()
        .filter_map(|member| tool.get(*member).map(|v| (member.to_string(), v.clone())))
        .collect();
    crate::digest::jcs(&Value::Object(kept))
}
