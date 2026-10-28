// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The Tool Host's definition digest agrees with the shared vectors in tool-sdk/spec/mcp-proxy.

use serde_json::{json, Value};
use std::path::Path;
use tool_host::mcp::definition_digest;

#[test]
fn digests_match_the_shared_vectors() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../tool-sdk/spec/mcp-proxy/definition-digest.json");
    let vectors: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let cases = vectors["cases"].as_array().unwrap();
    assert!(cases.len() >= 8);
    for case in cases {
        assert_eq!(
            definition_digest(&case["tool"]).unwrap(),
            case["digest"].as_str().unwrap(),
            "{}",
            case["name"]
        );
    }
}

#[test]
fn refuses_anything_but_an_object() {
    assert!(definition_digest(&json!([])).is_err());
}
