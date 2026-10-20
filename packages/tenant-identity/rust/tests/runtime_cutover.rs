// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Consume only public evidence captured from the isolated matching-runtime qualification.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::DeviceIdentity;
use olo_toolgate_tenant_identity::{
    certificate_der, digest, verify_bytes, verify_https_refresh, verify_tenant_identity, Trust,
};
use serde_json::Value;

fn timestamp(envelope: &Value) -> u64 {
    let encoded = envelope["jws"].as_str().unwrap().split('.').nth(1).unwrap();
    let payload: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(encoded).unwrap()).unwrap();
    // Replay each genuine short-lived packet at its captured issue time, without clock sleeps.
    payload["issuedAtUnixMs"].as_u64().unwrap() + 1000
}
fn identity(
    value: &Value,
    manifest: &olo_toolgate_contracts::v2::TenantDiscovery,
    now: u64,
) -> bool {
    let identity: DeviceIdentity = serde_json::from_value(value.clone()).unwrap();
    let der = certificate_der(&identity.certificate_pem).unwrap();
    let (_, leaf) = x509_parser::parse_x509_certificate(&der).unwrap();
    verify_tenant_identity(
        &identity,
        manifest,
        &digest(leaf.public_key().raw),
        &identity.device_id,
        now,
    )
    .is_ok()
}

#[test]
#[ignore = "requires tools/tenancy/check.py public evidence via TOOLGATE_TENANT_RUNTIME_EVIDENCE"]
fn genuine_runtime_packets_preserve_rotation_floors_and_tenant_certificate_isolation() {
    let path = std::env::var_os("TOOLGATE_TENANT_RUNTIME_EVIDENCE").expect("public evidence path");
    let bytes = std::fs::read(path).unwrap();
    assert!(bytes.len() <= 2097152);
    let evidence: Value = serde_json::from_slice(&bytes).unwrap();
    let mut manifests = Vec::new();
    for (index, name) in ["a", "b"].iter().enumerate() {
        let owner = evidence["owners"][index].as_str().unwrap();
        let origin = evidence["origins"][index].as_str().unwrap();
        let mut pin: Option<Trust> = None;
        let mut captured = Vec::new();
        for envelope in evidence[name].as_array().unwrap() {
            let bytes = serde_json::to_vec(envelope).unwrap();
            let now = timestamp(envelope);
            let verified = verify_bytes(&bytes, origin, Some(owner), pin.as_ref(), now).unwrap();
            assert!(verify_bytes(
                &bytes,
                origin,
                evidence["owners"][1 - index].as_str(),
                None,
                now
            )
            .is_err());
            pin = Some(verified.trust);
            captured.push(verified.manifest);
        }
        assert_eq!(captured.len(), 4);
        if index == 0 {
            assert_eq!(pin.as_ref().unwrap().version, 4);
            let first = &evidence[name][0];
            let first = verify_bytes(
                &serde_json::to_vec(first).unwrap(),
                origin,
                Some(owner),
                None,
                timestamp(first),
            )
            .unwrap();
            let rotated = &evidence[name][2];
            let bytes = serde_json::to_vec(rotated).unwrap();
            assert!(verify_bytes(
                &bytes,
                origin,
                Some(owner),
                Some(&first.trust),
                timestamp(rotated)
            )
            .is_err());
            let refreshed = verify_https_refresh(
                &bytes,
                origin,
                owner,
                Some(&first.trust),
                timestamp(rotated),
            )
            .unwrap();
            assert_eq!(refreshed.trust.version, 3);
            let saved: Trust =
                serde_json::from_slice(&serde_json::to_vec(pin.as_ref().unwrap()).unwrap())
                    .unwrap();
            assert!(
                verify_https_refresh(&bytes, origin, owner, Some(&saved), timestamp(rotated))
                    .is_err()
            );
        } else {
            assert_eq!(pin.as_ref().unwrap().version, 1);
        }
        manifests.push(captured);
    }
    let now = timestamp(&evidence["a"][3]);
    assert!(identity(&evidence["oldIdentityA"], &manifests[0][2], now));
    assert!(!identity(&evidence["oldIdentityA"], &manifests[0][3], now));
    assert!(identity(&evidence["newIdentityA"], &manifests[0][3], now));
    assert!(identity(&evidence["identityB"], &manifests[1][3], now));
    assert!(!identity(&evidence["identityB"], &manifests[0][3], now));
    assert!(!identity(&evidence["newIdentityA"], &manifests[1][3], now));
}
