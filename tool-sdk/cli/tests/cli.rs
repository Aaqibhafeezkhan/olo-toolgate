// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The `toolgate` binary's commands and exit codes.
mod common;
use common::{root, source_manifest, stage, ARTIFACT};
use std::process::Command;

fn toolgate(args: &[&str]) -> (i32, String) {
    let output = Command::new(env!("CARGO_BIN_EXE_toolgate"))
        .args(args)
        .output()
        .unwrap();
    let text = String::from_utf8_lossy(&output.stdout).into_owned()
        + &String::from_utf8_lossy(&output.stderr);
    (output.status.code().unwrap(), text)
}

fn fixture(name: &str) -> String {
    root()
        .join("tool-sdk/conformance/fixtures/packages")
        .join(name)
        .display()
        .to_string()
}

#[test]
fn verify_exit_codes() {
    let (code, out) = toolgate(&["verify", &fixture("valid.tgpkg")]);
    assert_eq!(0, code, "{out}");
    assert!(out.starts_with("packageDigest sha256:"));
    let (code, out) = toolgate(&["verify", &fixture("zip-slip.tgpkg")]);
    assert_eq!(1, code);
    assert!(out.contains("rejected: unsafe entry path"), "{out}");
    assert_eq!(2, toolgate(&["verify"]).0);
}

#[test]
fn pack_then_validate() {
    let dir = stage("cli", &source_manifest(), ARTIFACT);
    let output = dir.join("out.tgpkg").display().to_string();
    let (code, packed) = toolgate(&["pack", &dir.display().to_string(), "-o", &output]);
    assert_eq!(0, code, "{packed}");
    let (code, validated) = toolgate(&["validate", &output]);
    assert_eq!(0, code, "{validated}");
    assert!(
        packed.ends_with(&validated),
        "pack prints the same report as validate"
    );
}

#[test]
fn digest_and_equivalent() {
    let vectors = root().join("tool-sdk/spec/descriptor/vectors");
    let v = |f: &str| vectors.join(f).display().to_string();
    let (code, out) = toolgate(&["digest", &v("tool-01-checksum.json")]);
    assert_eq!(0, code);
    let expected: serde_json::Value =
        serde_json::from_slice(&std::fs::read(vectors.join("expected.json")).unwrap()).unwrap();
    assert!(out.contains(
        expected["digests"]["tool-01-checksum.json"]
            .as_str()
            .unwrap()
    ));
    let (code, out) = toolgate(&["canonical", &v("tool-01-checksum.json")]);
    assert_eq!(0, code);
    assert_eq!(
        out.as_bytes(),
        std::fs::read(vectors.join("tool-01-checksum.jcs")).unwrap()
    );
    let (code, _) = toolgate(&[
        "equivalent",
        &v("tool-01-checksum.json"),
        &v("tool-02-orders-lookup.json"),
    ]);
    assert_eq!(1, code);
}
