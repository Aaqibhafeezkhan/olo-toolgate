// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The canonical form matches the shared descriptor vectors (tool-sdk/spec/descriptor/vectors) byte for byte, the
//! same bytes `toolgate pack` and the SDKs hash for descriptor digests.

use olo_toolgate_trigger_supervisor::canonical::canonical_bytes;
use std::path::PathBuf;

#[test]
fn canonical_bytes_match_the_shared_vectors() {
    let vectors =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../tool-sdk/spec/descriptor/vectors");
    let mut checked = 0;
    for entry in std::fs::read_dir(&vectors).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|e| e == "jcs") {
            let value: serde_json::Value =
                serde_json::from_slice(&std::fs::read(path.with_extension("json")).unwrap())
                    .unwrap();
            assert_eq!(
                canonical_bytes(&value).unwrap(),
                std::fs::read(&path).unwrap(),
                "{}",
                path.display()
            );
            checked += 1;
        }
    }
    assert!(checked >= 2, "the vectors are present");
}
