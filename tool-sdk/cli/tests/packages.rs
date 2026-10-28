// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Conformance: contract-descriptor/packages.json and the package cases of descriptors.json,
//! for the packager target.
mod common;
use common::{json, root, source_manifest, stage, ARTIFACT};
use std::collections::BTreeMap;
use toolgate_cli::{archive, package, Error};

const FIXTURES: &str = "tool-sdk/conformance/fixtures/packages";

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(root().join(FIXTURES).join(format!("{name}.tgpkg"))).unwrap()
}

/// packages/valid-accepted and every packages/<rule> rejection.
#[test]
fn archive_fixtures_are_accepted_or_rejected_as_expected() {
    let expected = json(format!("{FIXTURES}/expected.json"));
    let fixtures = expected["fixtures"].as_object().unwrap();
    assert_eq!(20, fixtures.len());
    for (name, outcome) in fixtures {
        let result = package::verify(&fixture(name));
        match outcome.as_str().unwrap() {
            "accept" => assert_eq!(
                result
                    .unwrap_or_else(|e| panic!("{name}: {e}"))
                    .package_digest,
                expected["validPackageDigest"].as_str().unwrap()
            ),
            _ => assert!(
                matches!(result, Err(Error::Rejected(_))),
                "{name} was not rejected: {result:?}"
            ),
        }
    }
}

/// descriptors/package-digest-exact-bytes: the digest is over CHECKSUMS.sha256, and signatures/
/// does not change it.
#[test]
fn package_digest_is_over_checksums_and_ignores_signatures() {
    let valid = fixture("valid");
    let verified = package::verify(&valid).unwrap();
    let zip_digest = toolgate_cli::canonical::sha256_tagged(&valid);
    assert_ne!(verified.package_digest, zip_digest);
    assert_eq!(
        verified.package_digest,
        toolgate_cli::canonical::sha256_tagged(&verified.files[package::CHECKSUMS])
    );
    let mut signed: BTreeMap<String, Vec<u8>> = verified.files.clone();
    signed.insert("signatures/publisher.dsse.json".into(), b"{}".to_vec());
    let resigned = package::verify(&archive::write(&signed).unwrap()).unwrap();
    assert_eq!(resigned.package_digest, verified.package_digest);
}

/// A package the CLI writes passes full validation, and its own writer output re-reads exactly.
#[test]
fn pack_writes_a_valid_package() {
    let dir = stage("pack", &source_manifest(), ARTIFACT);
    let (data, report) = package::pack(&dir).unwrap();
    let again = package::validate(&data).unwrap();
    assert_eq!(report.package_digest, again.package_digest);
    // The fixture descriptor's digest is the one recorded in the v2 fixtures.
    let fixture = json("tests/fixtures/contracts/v2/valid.json");
    assert_eq!(
        report.tools[0].1,
        fixture["PackageManifest"]["tools"][0]["toolDescriptorDigest"]
            .as_str()
            .unwrap()
    );
    let files = package::verify(&data).unwrap().files;
    assert!(files.contains_key("tools/orders.lookup.json"));
    assert_eq!(files["artifacts/app.jar"], ARTIFACT);
}

/// descriptors/reproducibility: the same input in two directories gives identical bytes.
#[test]
fn packing_is_reproducible_across_directories() {
    let manifest = source_manifest();
    let (a, _) = package::pack(&stage("repro-a", &manifest, ARTIFACT)).unwrap();
    let (b, _) = package::pack(&stage("repro-b", &manifest, ARTIFACT)).unwrap();
    assert_eq!(a, b);
}

/// descriptors/artifact-digest: one artifact byte changes the artifact and package digests only.
#[test]
fn an_artifact_byte_changes_package_digest_not_descriptor_digest() {
    let manifest = source_manifest();
    let (_, before) = package::pack(&stage("artifact-a", &manifest, ARTIFACT)).unwrap();
    let mut changed = ARTIFACT.to_vec();
    changed[0] ^= 1;
    let (data, after) = package::pack(&stage("artifact-b", &manifest, &changed)).unwrap();
    assert_ne!(before.package_digest, after.package_digest);
    assert_eq!(before.tools, after.tools);
    assert_eq!(before.descriptor_set_digest, after.descriptor_set_digest);
    let verified = package::verify(&data).unwrap();
    assert_eq!(
        verified.manifest["artifacts"][0]["artifactDigest"],
        toolgate_cli::canonical::sha256_tagged(&changed)
    );
}

/// descriptors/excludes-deployment: packaging members outside the descriptor do not change it.
#[test]
fn packaging_members_do_not_change_the_descriptor_digest() {
    let base = source_manifest();
    let (_, before) = package::pack(&stage("exclude-a", &base, ARTIFACT)).unwrap();
    let mut other = base.clone();
    other["sdk"]["version"] = "0.2.0".into();
    other["runtimes"][0]["version"] = "21".into();
    other["version"] = "1.4.1".into();
    other["tools"][0]["entryPoint"] = "com.example.Other".into();
    other["tools"][0]["limits"]["memoryBytes"] = 268435456.into();
    let (_, after) = package::pack(&stage("exclude-b", &other, ARTIFACT)).unwrap();
    assert_eq!(before.tools, after.tools);
    assert_eq!(before.descriptor_set_digest, after.descriptor_set_digest);
    assert_ne!(before.package_digest, after.package_digest);
}

#[test]
fn pack_refuses_a_stale_digest_and_validate_rejects_tampering() {
    let mut stale = source_manifest();
    stale["tools"][0]["toolDescriptorDigest"] = format!("sha256:{}", "0".repeat(64)).into();
    assert!(matches!(
        package::pack(&stage("stale", &stale, ARTIFACT)),
        Err(Error::Invalid(_))
    ));

    let (data, _) = package::pack(&stage("tamper", &source_manifest(), ARTIFACT)).unwrap();
    let mut files = package::verify(&data).unwrap().files;
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&files[package::MANIFEST]).unwrap();
    manifest["tools"][0]["descriptor"]["title"] = "Changed after packing".into();
    let manifest_bytes = serde_json::to_vec(&manifest).unwrap();
    files.insert(package::MANIFEST.into(), manifest_bytes);
    files.remove(package::CHECKSUMS);
    let checksums = package::render_checksums(&files);
    files.insert(package::CHECKSUMS.into(), checksums);
    let tampered = archive::write(&files).unwrap();
    assert!(
        package::verify(&tampered).is_ok(),
        "the archive rules alone still hold"
    );
    assert!(matches!(
        package::validate(&tampered),
        Err(Error::Rejected(_))
    ));
}

#[test]
fn validate_rejects_a_manifest_outside_the_schema() {
    let mut manifest = source_manifest();
    manifest["credentialReferences"] = serde_json::json!(["plaintext-token"]);
    assert!(matches!(
        package::pack(&stage("schema", &manifest, ARTIFACT)),
        Err(Error::Rejected(_))
    ));
}

#[test]
fn a_java_tool_must_run_from_one_self_contained_jar() {
    let mut split = source_manifest();
    split["artifacts"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({"artifactId":"libs","path":"artifacts/libs.zip","mediaType":"application/zip","runtimeId":"jvm"}));
    split["tools"][0]["artifactIds"] = serde_json::json!(["app", "libs"]);
    let staging = stage("split", &split, ARTIFACT);
    std::fs::write(staging.join("artifacts/libs.zip"), b"PK libs").unwrap();
    match package::pack(&staging) {
        Err(Error::Rejected(message)) => {
            assert!(message.contains("self-contained jar"), "{message}")
        }
        other => panic!("a split Java tool was not rejected: {other:?}"),
    }
}
