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

#[test]
fn guide_prints_the_bundled_community_guide() {
    let (code, out) = toolgate(&["guide"]);
    assert_eq!(0, code, "{out}");
    assert_eq!(
        std::fs::read_to_string(root().join("TOOL-SDK-GUIDE.md")).unwrap(),
        out
    );
    assert_eq!(2, toolgate(&["guide", "extra"]).0);
}

#[test]
fn pack_writes_the_tool_schema_beside_the_package() {
    let dir = stage("schema", &source_manifest(), ARTIFACT);
    std::fs::create_dir_all(dir.join("training")).unwrap();
    std::fs::write(
        dir.join("training/annotations.json"),
        r#"{"tools":{"orders.lookup":{"phrasings":["where is order {orderId}"]}}}"#,
    )
    .unwrap();
    let output = dir.join("acme.orders-1.4.0.tgpkg");
    let (code, packed) = toolgate(&[
        "pack",
        &dir.display().to_string(),
        "-o",
        &output.display().to_string(),
    ]);
    assert_eq!(0, code, "{packed}");
    let path = dir.join("acme.orders-1.4.0.tool-schema.json");
    assert!(
        packed.contains(&format!("wrote {}", path.display())),
        "{packed}"
    );
    let schema: serde_json::Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    assert_eq!("toolgate.tool-schema", schema["format"]);
    assert_eq!("acme.orders", schema["package"]["id"]);
    let lookup = schema["tools"]
        .as_array()
        .unwrap()
        .iter()
        .find(|t| t["name"] == "orders.lookup")
        .unwrap();
    assert_eq!("acme.orders/orders.lookup", lookup["toolId"]);
    assert_eq!(
        "The user asks about a specific order number.",
        lookup["useWhen"]
    );
    let order_id = &lookup["parameters"][0];
    assert_eq!("orderId", order_id["name"]);
    assert_eq!(true, order_id["required"]);
    assert_eq!("^[0-9]{6,12}$", order_id["constraints"]["pattern"]);
    assert_eq!(false, lookup["parameters"][1]["required"]);
    assert_eq!(
        "where is order {orderId}",
        lookup["annotations"]["phrasings"][0]
    );

    // The same schema from the package alone, with annotations from a file.
    let notes = dir.join("training/annotations.json").display().to_string();
    let again = dir.join("again.json");
    let (code, out) = toolgate(&[
        "schema",
        &output.display().to_string(),
        "--annotations",
        &notes,
        "-o",
        &again.display().to_string(),
    ]);
    assert_eq!(0, code, "{out}");
    assert_eq!(
        std::fs::read(&path).unwrap(),
        std::fs::read(&again).unwrap()
    );
}

#[test]
fn schema_refuses_annotations_for_unknown_tools() {
    let dir = stage("schema-unknown", &source_manifest(), ARTIFACT);
    std::fs::create_dir_all(dir.join("training")).unwrap();
    std::fs::write(
        dir.join("training/annotations.json"),
        r#"{"tools":{"nope":{}}}"#,
    )
    .unwrap();
    let (code, out) = toolgate(&["schema", &dir.display().to_string()]);
    assert_eq!(1, code);
    assert!(
        out.contains("\"nope\" is not a tool in this package"),
        "{out}"
    );
    assert_eq!(2, toolgate(&["schema"]).0);
}
