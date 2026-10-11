// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Conformance: contract-descriptor/schemas.json (packager target).
mod common;
use common::json;
use toolgate_cli::schemas;

/// schemas/v2-valid
#[test]
fn every_v2_valid_fixture_validates() {
    let fixtures = json("tests/fixtures/contracts/v2/valid.json");
    let fixtures = fixtures.as_object().unwrap();
    assert!(fixtures.len() > 100);
    for (name, value) in fixtures {
        let errors = schemas::errors(name, value).unwrap();
        assert!(errors.is_empty(), "{name}: {errors:?}");
    }
}

/// schemas/v2-invalid
#[test]
fn every_v2_invalid_fixture_is_rejected() {
    let fixtures = json("tests/fixtures/contracts/v2/invalid.json");
    let mut count = 0;
    for (name, cases) in fixtures.as_object().unwrap() {
        for case in cases.as_array().unwrap() {
            let errors = schemas::errors(name, &case["value"]).unwrap();
            assert!(!errors.is_empty(), "{name}: {} was accepted", case["case"]);
            count += 1;
        }
    }
    assert!(count > 20);
}
