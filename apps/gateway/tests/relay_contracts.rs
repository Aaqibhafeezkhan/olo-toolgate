// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use olo_toolgate_gateway::validation::Contracts;
use serde_json::json;
#[test]
fn real_control_catalog_and_response_models_are_registered_and_closed() {
    let contracts = Contracts::new().unwrap();
    assert!(contracts.valid("LocalToolCatalog", &json!({"tools":[]})));
    assert!(!contracts.valid("LocalToolCatalog", &json!({"tools":[],"token":"private"})));
    let fixtures: serde_json::Value = serde_json::from_str(include_str!(
        "../../../tests/fixtures/contracts/v1/valid.json"
    ))
    .unwrap();
    for name in [
        "RemoteToolSubmission",
        "RemoteToolResponse",
        "CancellationAccepted",
    ] {
        assert!(contracts.valid(name, &fixtures[name]), "{name}");
        let mut malformed = fixtures[name].clone();
        malformed["untrusted"] = json!(true);
        assert!(!contracts.valid(name, &malformed));
    }
}
