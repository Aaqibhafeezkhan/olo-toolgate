// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Packager runner bookkeeping (tool-sdk/conformance/README.md, runner contract): every case this
//! crate runs is named here with its test, must exist with the `packager` target, and every
//! packager-only case marked passing must be one of them.
mod common;
use common::{json, root};
use std::collections::BTreeMap;

/// Case id to the test in this crate that runs it for the packager target.
const RUNS: &[(&str, &str)] = &[
    (
        "descriptors/canonical-bytes",
        "descriptor_vectors::canonical_bytes_and_digests_match_the_vectors",
    ),
    (
        "descriptors/set-digest",
        "descriptor_vectors::set_digest_matches_the_vector",
    ),
    (
        "descriptors/equivalence",
        "descriptor_vectors::equivalence_matches_the_vectors",
    ),
    (
        "descriptors/includes-semantics",
        "descriptor_vectors::every_semantic_member_changes_the_digest",
    ),
    (
        "descriptors/excludes-deployment",
        "packages::packaging_members_do_not_change_the_descriptor_digest",
    ),
    (
        "descriptors/package-digest-exact-bytes",
        "packages::package_digest_is_over_checksums_and_ignores_signatures",
    ),
    (
        "descriptors/artifact-digest",
        "packages::an_artifact_byte_changes_package_digest_not_descriptor_digest",
    ),
    (
        "descriptors/reproducibility",
        "packages::packing_is_reproducible_across_directories",
    ),
    (
        "schemas/v2-valid",
        "schemas::every_v2_valid_fixture_validates",
    ),
    (
        "schemas/v2-invalid",
        "schemas::every_v2_invalid_fixture_is_rejected",
    ),
];

/// Every case of packages.json that targets the packager runs in one fixture-driven test.
const ARCHIVE_TEST: &str = "packages::archive_fixtures_are_accepted_or_rejected_as_expected";

fn cases() -> BTreeMap<String, serde_json::Value> {
    let mut all = BTreeMap::new();
    for entry in std::fs::read_dir(root().join("tool-sdk/conformance/contract-descriptor")).unwrap()
    {
        let path = entry.unwrap().path();
        let file = json(path.strip_prefix(root()).unwrap());
        for case in file["cases"].as_array().unwrap() {
            all.insert(case["id"].as_str().unwrap().to_owned(), case.clone());
        }
    }
    all
}

fn targets_packager(case: &serde_json::Value) -> bool {
    case["targets"]
        .as_array()
        .unwrap()
        .iter()
        .any(|t| t == "packager")
}

#[test]
fn every_named_case_exists_for_the_packager() {
    let cases = cases();
    for (id, test) in RUNS {
        let case = cases
            .get(*id)
            .unwrap_or_else(|| panic!("{id} is not a case"));
        assert!(
            targets_packager(case),
            "{id} does not target the packager ({test})"
        );
    }
    let archive: Vec<_> = cases
        .iter()
        .filter(|(id, case)| id.starts_with("packages/") && targets_packager(case))
        .collect();
    assert_eq!(
        20,
        archive.len(),
        "{ARCHIVE_TEST} covers one case per fixture"
    );
}

#[test]
fn passing_packager_only_cases_are_run_here() {
    for (id, case) in cases() {
        let only_packager = case["targets"].as_array().unwrap() == &[serde_json::json!("packager")];
        if only_packager && case["status"] == "passing" {
            assert!(
                RUNS.iter().any(|(run, _)| *run == id),
                "{id} is passing but not run by the packager"
            );
        }
    }
}
