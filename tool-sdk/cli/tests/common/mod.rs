// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
#![allow(dead_code)]
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};

pub fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn json(path: impl AsRef<Path>) -> Value {
    serde_json::from_slice(&std::fs::read(root().join(path)).unwrap()).unwrap()
}

/// A fresh, empty directory under the system temporary directory.
pub fn scratch(label: &str) -> PathBuf {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    let dir = std::env::temp_dir().join(format!(
        "toolgate-cli-{label}-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// The v2 fixture manifest as packager input: computed members removed.
pub fn source_manifest() -> Value {
    let mut manifest = json("tests/fixtures/contracts/v2/valid.json")["PackageManifest"].clone();
    let object = manifest.as_object_mut().unwrap();
    object.remove("descriptorSetDigest");
    for tool in object["tools"].as_array_mut().unwrap() {
        tool.as_object_mut().unwrap().remove("toolDescriptorDigest");
    }
    for artifact in object["artifacts"].as_array_mut().unwrap() {
        let artifact = artifact.as_object_mut().unwrap();
        artifact.remove("artifactDigest");
        artifact.remove("sizeBytes");
    }
    manifest
}

pub const ARTIFACT: &[u8] = b"PK fixture jar bytes\n";

/// Writes a staging directory for `manifest` with the fixture artifact.
pub fn stage(label: &str, manifest: &Value, artifact: &[u8]) -> PathBuf {
    let dir = scratch(label);
    std::fs::write(
        dir.join("toolgate-package.json"),
        serde_json::to_vec_pretty(manifest).unwrap(),
    )
    .unwrap();
    std::fs::create_dir_all(dir.join("artifacts")).unwrap();
    std::fs::write(dir.join("artifacts/app.jar"), artifact).unwrap();
    dir
}
