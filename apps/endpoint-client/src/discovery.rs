// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Tenant discovery v2 verification. Initial bootstrap requires independently authenticated HTTPS.
use crate::{contracts::Contracts, Failure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::v2::{
    DiscoveryAuthorityState, DiscoveryHeader, DiscoveryKeySet, SignedTenantDiscovery,
    TenantDiscovery,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use x509_parser::{prelude::*, public_key::PublicKey};

/// Protected local continuity state, never accepted from a page or an IPC request.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Trust {
    pub tenant_id: String,
    pub version: u64,
    pub keys: DiscoveryKeySet,
    pub issued_at: u64,
}
pub struct Verified {
    pub manifest: TenantDiscovery,
    pub trust: Trust,
}
type Keys = BTreeMap<String, (Vec<u8>, Vec<u8>)>;
fn decode(value: &str) -> Result<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Failure::Validation)?;
    if URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(Failure::Validation);
    }
    Ok(bytes)
}
fn keys(contracts: &Contracts, value: &DiscoveryKeySet) -> Result<Keys> {
    contracts.encode("DiscoveryKeySet", value)?;
    let mut keys = BTreeMap::new();
    let mut moduli = BTreeSet::new();
    for key in &value.keys {
        let n = decode(&key.n)?;
        let e = decode(&key.e)?;
        if ![384, 512].contains(&n.len())
            || n[0] & 0x80 == 0
            || e != [1, 0, 1]
            || !moduli.insert(n.clone())
            || keys.contains_key(&key.kid)
        {
            return Err(Failure::Validation);
        }
        keys.insert(key.kid.clone(), (n, e));
    }
    Ok(keys)
}
/// Verify before exposing metadata, enrolling, changing local trust or using the device key.
pub fn verify(
    envelope: &SignedTenantDiscovery,
    origin: &str,
    expected_tenant: Option<&str>,
    pinned: Option<&Trust>,
    now: u64,
) -> Result<Verified> {
    let contracts = Contracts::shared()?;
    contracts.encode("SignedTenantDiscovery", envelope)?;
    let parts: Vec<_> = envelope.jws.split('.').collect();
    if parts.len() != 3 || parts[0].len() > 1024 || parts[1].len() > 87384 {
        return Err(Failure::Validation);
    }
    let header: DiscoveryHeader = contracts.decode("DiscoveryHeader", &decode(parts[0])?)?;
    let bytes = decode(parts[1])?;
    if bytes.len() > 65536 {
        return Err(Failure::Validation);
    }
    let value: TenantDiscovery = contracts.decode("TenantDiscovery", &bytes)?;
    if let Some(expected) = expected_tenant {
        contracts.encode("TenantUuid", &expected)?;
        if expected != value.tenant_id {
            return Err(Failure::TenantMismatch);
        }
    }
    if let Some(pin) = pinned {
        contracts.encode("TenantUuid", &pin.tenant_id)?;
        if pin.tenant_id != value.tenant_id {
            return Err(Failure::TenantMismatch);
        }
        if pin.version == 0 || pin.version > 9007199254740991 || pin.issued_at > 9007199254740991 {
            return Err(Failure::Validation);
        }
    }
    let control = crate::config::origin(&value.control_url)?;
    let gateway = crate::config::origin(&value.gateway_url)?;
    let mut audiences = BTreeSet::new();
    for audience in &value.audiences {
        if !audiences.insert(crate::config::origin(audience)?) {
            return Err(Failure::Validation);
        }
    }
    if value.issuer != format!("urn:toolgate:tenant:{}", value.tenant_id)
        || !audiences.contains(&control)
        || !audiences.contains(&gateway)
        || !audiences.contains(&crate::config::origin(origin)?)
    {
        return Err(Failure::Unauthorized);
    }
    let advertised = keys(contracts, &value.discovery_keys)?;
    let trusted = if let Some(pin) = pinned {
        keys(contracts, &pin.keys)?
    } else {
        advertised.clone()
    };
    let (n, e) = trusted.get(&header.kid).ok_or(Failure::Unauthorized)?;
    if advertised.get(&header.kid) != trusted.get(&header.kid) {
        return Err(Failure::Unauthorized);
    }
    if let Some(pin) = pinned {
        if value.key_set_version < pin.version
            || value.issued_at_unix_ms < pin.issued_at
            || value.key_set_version == pin.version && advertised != trusted
        {
            return Err(Failure::Unauthorized);
        }
    }
    let signature = decode(parts[2])?;
    if signature.len() != n.len() {
        return Err(Failure::Unauthorized);
    }
    ring::signature::RsaPublicKeyComponents { n, e }
        .verify(
            &ring::signature::RSA_PKCS1_2048_8192_SHA256,
            format!("{}.{}", parts[0], parts[1]).as_bytes(),
            &signature,
        )
        .map_err(|_| Failure::Unauthorized)?;
    let verification =
        reqwest::Url::parse(&value.verification_uri).map_err(|_| Failure::Validation)?;
    if verification.as_str() != format!("{control}/console/#enroll")
        || value.issued_at_unix_ms > now
        || value.expires_at_unix_ms <= now
        || value.expires_at_unix_ms - value.issued_at_unix_ms > 300000
    {
        return Err(Failure::Unauthorized);
    }
    if semver::Version::parse(&value.minimum_client_version).map_err(|_| Failure::Validation)?
        > semver::Version::parse(env!("CARGO_PKG_VERSION")).map_err(|_| Failure::Validation)?
    {
        return Err(Failure::Unsupported);
    }
    authorities(
        &value,
        advertised.values().map(|(n, _)| n.clone()).collect(),
        now,
    )?;
    let trust = Trust {
        tenant_id: value.tenant_id.clone(),
        version: value.key_set_version,
        keys: value.discovery_keys.clone(),
        issued_at: value.issued_at_unix_ms,
    };
    Ok(Verified {
        manifest: value,
        trust,
    })
}
fn authorities(value: &TenantDiscovery, mut moduli: BTreeSet<Vec<u8>>, now: u64) -> Result<()> {
    let mut ids = BTreeSet::new();
    let mut active = 0;
    for authority in &value.device_authorities {
        let der = crate::identity::certificate_der(&authority.certificate_pem)?;
        let (remaining, ca) = parse_x509_certificate(&der).map_err(|_| Failure::Validation)?;
        if !remaining.is_empty() {
            return Err(Failure::Validation);
        }
        crate::identity::valid_ca(&ca, now)?;
        let constraints = ca
            .basic_constraints()
            .map_err(|_| Failure::Validation)?
            .ok_or(Failure::Unauthorized)?;
        let units: Vec<_> = ca.subject().iter_organizational_unit().collect();
        let grouped_unit = ca.subject().iter_rdn().any(|rdn| {
            rdn.iter()
                .any(|attribute| attribute.attr_type().to_id_string() == "2.5.4.11")
                && rdn.iter().count() != 1
        });
        if ca.issuer() != ca.subject()
            || ca.signature_algorithm.algorithm.to_id_string() != "1.2.840.113549.1.1.11"
            || constraints.value.path_len_constraint != Some(0)
            || units.len() != 1
            || grouped_unit
            || units[0].as_str().ok() != Some(value.tenant_id.as_str())
            || authority.key_id != crate::digest(&der)
            || !ids.insert(&authority.key_id)
        {
            return Err(Failure::Unauthorized);
        }
        ca.verify_signature(None)
            .map_err(|_| Failure::Unauthorized)?;
        let public = ca.public_key().parsed().map_err(|_| Failure::Validation)?;
        let PublicKey::RSA(key) = public else {
            return Err(Failure::Unauthorized);
        };
        let n = key.modulus.strip_prefix(&[0]).unwrap_or(key.modulus);
        if ![384, 512].contains(&n.len())
            || n[0] & 0x80 == 0
            || key.exponent != [1, 0, 1]
            || !moduli.insert(n.to_vec())
        {
            return Err(Failure::Unauthorized);
        }
        if authority.state == DiscoveryAuthorityState::Active {
            active += 1;
            if authority.key_id != value.device_issuer_key_id {
                return Err(Failure::Unauthorized);
            }
        }
    }
    if active != 1 {
        return Err(Failure::Unauthorized);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> serde_json::Value {
        serde_json::from_str(include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../tests/fixtures/tenant-discovery/v2.json"
        )))
        .unwrap()
    }
    fn vector(f: &serde_json::Value, name: &str) -> SignedTenantDiscovery {
        serde_json::from_value(f["cases"][name].clone()).unwrap()
    }
    fn check(f: &serde_json::Value, name: &str, pin: Option<&Trust>) -> Result<Verified> {
        verify(
            &vector(f, name),
            f["origin"].as_str().unwrap(),
            f["tenantId"].as_str(),
            pin,
            f["now"].as_u64().unwrap(),
        )
    }
    #[test]
    fn public_vectors_require_preannounced_rotation_and_reject_key_rollback() {
        let f = fixture();
        let first = check(&f, "initial", None).unwrap();
        let announced = check(&f, "announced", Some(&first.trust)).unwrap();
        check(&f, "reordered", Some(&announced.trust)).unwrap();
        let rotated = check(&f, "rotated", Some(&announced.trust)).unwrap();
        let retired = check(&f, "retired", Some(&rotated.trust)).unwrap();
        for name in ["unannounced", "unversioned"] {
            assert!(check(&f, name, Some(&first.trust)).is_err(), "{name}");
        }
        assert!(check(&f, "initial", Some(&retired.trust)).is_err());
        assert!(check(&f, "revokedSigner", Some(&announced.trust)).is_err());
        assert!(check(&f, "rotated", Some(&retired.trust)).is_err());
        verify(
            &vector(&f, "initial"),
            "https://TENANT.example.test:443",
            f["tenantId"].as_str(),
            None,
            f["now"].as_u64().unwrap(),
        )
        .unwrap();
        assert_eq!(
            check(&f, "splitOrigins", None)
                .unwrap()
                .manifest
                .control_url,
            "https://control.tenant.example.test:8443"
        );
    }
    #[test]
    fn rejects_other_tenants_wrong_purposes_algorithms_unknown_keys_and_shared_ca_keys() {
        let f = fixture();
        let first = check(&f, "initial", None).unwrap();
        for name in [
            "otherTenant",
            "wrongPurpose",
            "wrongAlgorithm",
            "remoteKeyHeader",
            "unknownKey",
            "sharedCaKey",
            "wrongVersion",
            "aliasTenant",
        ] {
            assert!(check(&f, name, Some(&first.trust)).is_err(), "{name}");
        }
        assert!(matches!(
            verify(
                &vector(&f, "initial"),
                f["origin"].as_str().unwrap(),
                f["otherTenantId"].as_str(),
                None,
                f["now"].as_u64().unwrap()
            ),
            Err(Failure::TenantMismatch)
        ));
        assert!(verify(
            &vector(&f, "initial"),
            "https://wrong.example.test",
            None,
            None,
            f["now"].as_u64().unwrap()
        )
        .is_err());
    }
    #[test]
    fn rejects_stale_future_corrupt_and_noncanonical_documents() {
        let f = fixture();
        let now = f["now"].as_u64().unwrap();
        let envelope = vector(&f, "initial");
        let origin = f["origin"].as_str().unwrap();
        assert!(verify(&envelope, origin, None, None, now + 300000).is_err());
        assert!(verify(&envelope, origin, None, None, now - 11).is_err());
        let mut parts: Vec<_> = envelope.jws.split('.').map(str::to_owned).collect();
        let mut bytes = decode(&parts[2]).unwrap();
        bytes[0] ^= 1;
        parts[2] = URL_SAFE_NO_PAD.encode(bytes);
        assert!(verify(
            &SignedTenantDiscovery {
                jws: parts.join(".")
            },
            origin,
            None,
            None,
            now
        )
        .is_err());
        assert!(verify(
            &SignedTenantDiscovery {
                jws: envelope.jws + "="
            },
            origin,
            None,
            None,
            now
        )
        .is_err());
    }
}
