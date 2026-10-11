// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Conformance: contract-descriptor/descriptors.json, the packager's share of the descriptor
//! vectors (tool-sdk/spec/descriptor/vectors).
mod common;
use common::{json, root};
use toolgate_cli::{canonical, descriptor};

const VECTORS: &str = "tool-sdk/spec/descriptor/vectors";

/// descriptors/canonical-bytes
#[test]
fn canonical_bytes_and_digests_match_the_vectors() {
    let expected = json(format!("{VECTORS}/expected.json"));
    let digests = expected["digests"].as_object().unwrap();
    assert_eq!(3, digests.len());
    for (file, want) in digests {
        let value = json(format!("{VECTORS}/{file}"));
        let jcs = std::fs::read(root().join(VECTORS).join(file.replace(".json", ".jcs"))).unwrap();
        assert_eq!(canonical::canonical_bytes(&value).unwrap(), jcs, "{file}");
        assert_eq!(
            descriptor::tool_descriptor_digest(&value).unwrap(),
            want.as_str().unwrap(),
            "{file}"
        );
    }
}

/// descriptors/set-digest
#[test]
fn set_digest_matches_the_vector() {
    let expected = json(format!("{VECTORS}/expected.json"));
    for (file, want) in expected["sets"].as_object().unwrap() {
        let set = json(format!("{VECTORS}/{file}"));
        let tools: Vec<_> = set["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|name| json(format!("{VECTORS}/{}", name.as_str().unwrap())))
            .collect();
        assert_eq!(
            descriptor::descriptor_set_digest(&tools).unwrap(),
            want.as_str().unwrap()
        );
        let mut reversed = tools.clone();
        reversed.reverse();
        assert_eq!(
            descriptor::descriptor_set_digest(&reversed).unwrap(),
            want.as_str().unwrap()
        );
    }
}

/// descriptors/equivalence
#[test]
fn equivalence_matches_the_vectors() {
    let expected = json(format!("{VECTORS}/expected.json"));
    let cases = expected["equivalence"].as_object().unwrap();
    assert_eq!(4, cases.len());
    for (file, want) in cases {
        let case = json(format!("{VECTORS}/{file}"));
        assert_eq!(
            descriptor::equivalent(&case["a"], &case["b"]).unwrap(),
            want.as_bool().unwrap(),
            "{file}"
        );
    }
}

/// descriptors/includes-semantics: every semantic member changes the digest.
#[test]
fn every_semantic_member_changes_the_digest() {
    let base = json(format!("{VECTORS}/tool-02-orders-lookup.json"));
    let digest = descriptor::tool_descriptor_digest(&base).unwrap();
    let changes: [(&str, serde_json::Value); 12] = [
        ("/title", "Another title".into()),
        ("/description", "Another description".into()),
        (
            "/inputSchema/properties/orderId/description",
            "Changed".into(),
        ),
        ("/errors/0/retryable", true.into()),
        ("/effect/safety", "WRITE".into()),
        ("/businessKey/required", true.into()),
        ("/routes/0/path", "/v2/orders/{orderId}".into()),
        ("/variables/globals/0/required", false.into()),
        ("/variables/secrets/0/name", "otherSecret".into()),
        ("/variables/claims/0/claim", "other".into()),
        ("/execution/maxDurationMs", 1234.into()),
        ("/permissions", serde_json::json!([])),
    ];
    for (pointer, value) in changes {
        let mut changed = base.clone();
        *changed
            .pointer_mut(pointer)
            .unwrap_or_else(|| panic!("{pointer} is not in the vector")) = value;
        assert_ne!(
            descriptor::tool_descriptor_digest(&changed).unwrap(),
            digest,
            "{pointer}"
        );
    }
}
