// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
use crate::{Failure, Result};
use olo_toolgate_contracts::DeviceIdentity;
use x509_parser::prelude::*;
pub fn certificate_der(pem: &str) -> Result<Vec<u8>> {
    let (remaining, pem) =
        x509_parser::pem::parse_x509_pem(pem.as_bytes()).map_err(|_| Failure::Validation)?;
    if pem.label != "CERTIFICATE" || remaining.iter().any(|b| !b.is_ascii_whitespace()) {
        return Err(Failure::Validation);
    }
    Ok(pem.contents)
}
pub(crate) fn valid_ca(cert: &X509Certificate<'_>, now: u64) -> Result<()> {
    let time = ASN1Time::from_timestamp((now / 1000) as i64).map_err(|_| Failure::Validation)?;
    if !cert.validity().is_valid_at(time)
        || !cert.is_ca()
        || !cert
            .key_usage()
            .map_err(|_| Failure::Validation)?
            .is_some_and(|k| k.value.key_cert_sign())
    {
        return Err(Failure::Unauthorized);
    }
    Ok(())
}
/// Unverified subject used only to refuse a foreign configured credential before loading its key.
pub fn certificate_tenant(pem: &str) -> Result<String> {
    let der = certificate_der(pem)?;
    let (remaining, certificate) = parse_x509_certificate(&der).map_err(|_| Failure::Validation)?;
    if !remaining.is_empty() {
        return Err(Failure::Validation);
    }
    let units: Vec<_> = certificate.subject().iter_organizational_unit().collect();
    if units.len() != 1 {
        return Err(Failure::Validation);
    }
    let tenant = units[0]
        .as_str()
        .map_err(|_| Failure::Validation)?
        .to_owned();
    crate::contracts::Contracts::shared()?.encode("TenantUuid", &tenant)?;
    Ok(tenant)
}
/// Match the complete enrolled identity, including the user/device identity URI.
pub fn verify_tenant_identity(
    identity: &DeviceIdentity,
    manifest: &olo_toolgate_contracts::v2::TenantDiscovery,
    fingerprint: &str,
    device: &str,
    now: u64,
) -> Result<()> {
    if identity.device_id != device
        || identity.tenant_id != manifest.tenant_id
        || identity.server_id != manifest.server_id
        || identity.user_id.is_empty()
        || identity.expires_at_unix_ms <= now
        || !manifest
            .device_authorities
            .iter()
            .any(|authority| authority.certificate_pem == identity.issuer_certificate_pem)
    {
        return Err(Failure::Unauthorized);
    }
    verify_leaf(
        &identity.certificate_pem,
        &identity.issuer_certificate_pem,
        manifest,
        fingerprint,
        device,
        now,
        Some(&identity.user_id),
        Some(identity.expires_at_unix_ms),
    )
}
/// A preprovisioned workload has no client-supplied user claim. Control additionally
/// checks its signed URI against the current enrolled user/device record on every call.
pub fn verify_workload_certificate(
    pem: &str,
    manifest: &olo_toolgate_contracts::v2::TenantDiscovery,
    fingerprint: &str,
    device: &str,
    now: u64,
) -> Result<()> {
    for authority in &manifest.device_authorities {
        if verify_leaf(
            pem,
            &authority.certificate_pem,
            manifest,
            fingerprint,
            device,
            now,
            None,
            None,
        )
        .is_ok()
        {
            return Ok(());
        }
    }
    Err(Failure::Unauthorized)
}
#[allow(clippy::too_many_arguments)]
fn verify_leaf(
    pem: &str,
    authority: &str,
    manifest: &olo_toolgate_contracts::v2::TenantDiscovery,
    fingerprint: &str,
    device: &str,
    now: u64,
    user: Option<&str>,
    expires: Option<u64>,
) -> Result<()> {
    let ca_der = certificate_der(authority)?;
    let (remaining, ca) = parse_x509_certificate(&ca_der).map_err(|_| Failure::Validation)?;
    if !remaining.is_empty() {
        return Err(Failure::Validation);
    }
    valid_ca(&ca, now)?;
    let der = certificate_der(pem)?;
    let (remaining, certificate) = parse_x509_certificate(&der).map_err(|_| Failure::Validation)?;
    if !remaining.is_empty() {
        return Err(Failure::Validation);
    }
    let time = ASN1Time::from_timestamp((now / 1000) as i64).map_err(|_| Failure::Validation)?;
    let names = certificate.subject();
    let single = |oid: &str, expected: &str| -> bool {
        let values: Vec<_> = names
            .iter_attributes()
            .filter(|attribute| attribute.attr_type().to_id_string() == oid)
            .collect();
        values.len() == 1 && values[0].as_str().is_ok_and(|value| value == expected)
    };
    if names.iter_attributes().count() != 3
        || names.iter_rdn().count() != 3
        || !single("2.5.4.3", device)
        || !single("2.5.4.11", &manifest.tenant_id)
        || !single("2.5.4.10", &manifest.server_id)
    {
        return Err(Failure::Unauthorized);
    }
    let expected_uri = user.map(|user| {
        format!(
            "urn:toolgate:device:{}",
            crate::digest(
                format!(
                    "{}\n{}\n{}\n{}",
                    manifest.server_id, manifest.tenant_id, user, device
                )
                .as_bytes()
            )
        )
    });
    let sans = certificate
        .subject_alternative_name()
        .map_err(|_| Failure::Validation)?
        .ok_or(Failure::Unauthorized)?;
    if sans.value.general_names.len() != 1 {
        return Err(Failure::Unauthorized);
    }
    let GeneralName::URI(uri) = &sans.value.general_names[0] else {
        return Err(Failure::Unauthorized);
    };
    if let Some(expected) = expected_uri {
        if *uri != expected {
            return Err(Failure::Unauthorized);
        }
    } else if !uri
        .strip_prefix("urn:toolgate:device:")
        .is_some_and(|value| {
            value.len() == 64
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        })
    {
        return Err(Failure::Unauthorized);
    }
    let end = certificate.validity().not_after.timestamp();
    let start = certificate.validity().not_before.timestamp();
    if certificate.issuer() != ca.subject()
        || !certificate.validity().is_valid_at(time)
        || certificate.is_ca()
        || end - start > 86400
        || end < 0
        || expires.is_some_and(|expires| end as u64 * 1000 != expires / 1000 * 1000)
        || crate::digest(certificate.public_key().raw) != fingerprint
        || !certificate
            .extended_key_usage()
            .map_err(|_| Failure::Validation)?
            .is_some_and(|usage| {
                let value = &usage.value;
                value.client_auth
                    && !value.server_auth
                    && !value.any
                    && !value.code_signing
                    && !value.email_protection
                    && !value.time_stamping
                    && !value.ocsp_signing
                    && value.other.is_empty()
            })
        || !certificate
            .key_usage()
            .map_err(|_| Failure::Validation)?
            .is_some_and(|usage| usage.value.digital_signature() && !usage.value.key_cert_sign())
    {
        return Err(Failure::Unauthorized);
    }
    certificate
        .verify_signature(Some(ca.public_key()))
        .map_err(|_| Failure::Unauthorized)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::PublicKeyData;
    #[test]
    fn device_and_workload_validation_bind_the_signed_leaf_and_reject_cross_purpose_certificates() {
        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
            "../../../../tests/fixtures/contracts/v2/valid.json"
        ))
        .unwrap();
        let mut manifest: olo_toolgate_contracts::v2::TenantDiscovery =
            serde_json::from_value(fixtures["TenantDiscovery"].clone()).unwrap();
        let mut ca = rcgen::CertificateParams::new(Vec::<String>::new()).unwrap();
        ca.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Constrained(0));
        ca.key_usages = vec![rcgen::KeyUsagePurpose::KeyCertSign];
        let ca_key = rcgen::KeyPair::generate().unwrap();
        let certificate = ca.self_signed(&ca_key).unwrap();
        let issuer = rcgen::Issuer::new(ca, ca_key);
        manifest.device_authorities = vec![olo_toolgate_contracts::v2::DiscoveryDeviceAuthority {
            key_id: crate::digest(certificate.der()),
            state: olo_toolgate_contracts::v2::DiscoveryAuthorityState::Active,
            certificate_pem: certificate.pem(),
        }];
        let key = rcgen::KeyPair::generate().unwrap();
        let fingerprint = crate::digest(&key.subject_public_key_info());
        let uri = format!(
            "urn:toolgate:device:{}",
            crate::digest(
                format!(
                    "{}\n{}\nuser\ndevice",
                    manifest.server_id, manifest.tenant_id
                )
                .as_bytes()
            )
        );
        let leaf = |mixed: bool| {
            let mut params = rcgen::CertificateParams::new(Vec::<String>::new()).unwrap();
            let mut subject = rcgen::DistinguishedName::new();
            subject.push(rcgen::DnType::CommonName, "device");
            subject.push(
                rcgen::DnType::OrganizationalUnitName,
                manifest.tenant_id.as_str(),
            );
            subject.push(rcgen::DnType::OrganizationName, manifest.server_id.as_str());
            params.distinguished_name = subject;
            params.subject_alt_names = vec![rcgen::SanType::URI(uri.clone().try_into().unwrap())];
            params.key_usages = vec![rcgen::KeyUsagePurpose::DigitalSignature];
            params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ClientAuth];
            if mixed {
                params
                    .extended_key_usages
                    .push(rcgen::ExtendedKeyUsagePurpose::CodeSigning);
            }
            params.not_before = rcgen::date_time_ymd(2026, 1, 1);
            params.not_after = rcgen::date_time_ymd(2026, 1, 2);
            params.signed_by(&key, &issuer).unwrap().pem()
        };
        let now = rcgen::date_time_ymd(2026, 1, 1).unix_timestamp() as u64 * 1000 + 1000;
        let identity = DeviceIdentity {
            device_id: "device".into(),
            tenant_id: manifest.tenant_id.clone(),
            user_id: "user".into(),
            server_id: manifest.server_id.clone(),
            certificate_pem: leaf(false),
            issuer_certificate_pem: certificate.pem(),
            expires_at_unix_ms: rcgen::date_time_ymd(2026, 1, 2).unix_timestamp() as u64 * 1000,
        };
        verify_tenant_identity(&identity, &manifest, &fingerprint, "device", now).unwrap();
        verify_workload_certificate(
            &identity.certificate_pem,
            &manifest,
            &fingerprint,
            "device",
            now,
        )
        .unwrap();
        for field in ["tenant", "server", "user", "expiry"] {
            let mut foreign = identity.clone();
            match field {
                "tenant" => foreign.tenant_id = "10000000-0000-4000-8000-000000000002".into(),
                "server" => foreign.server_id = "foreign".into(),
                "user" => foreign.user_id = "foreign".into(),
                _ => foreign.expires_at_unix_ms += 1000,
            }
            assert!(
                verify_tenant_identity(&foreign, &manifest, &fingerprint, "device", now).is_err(),
                "{field}"
            );
        }
        assert!(verify_workload_certificate(
            &identity.certificate_pem,
            &manifest,
            &fingerprint,
            "foreign",
            now
        )
        .is_err());
        assert!(verify_workload_certificate(
            &identity.certificate_pem,
            &manifest,
            &"0".repeat(64),
            "device",
            now
        )
        .is_err());
        assert!(
            verify_workload_certificate(&leaf(true), &manifest, &fingerprint, "device", now)
                .is_err()
        );
        let mut other = manifest.clone();
        other.tenant_id = "10000000-0000-4000-8000-000000000002".into();
        assert!(verify_workload_certificate(
            &identity.certificate_pem,
            &other,
            &fingerprint,
            "device",
            now
        )
        .is_err());
        other = manifest.clone();
        other.device_authorities.clear();
        assert!(verify_workload_certificate(
            &identity.certificate_pem,
            &other,
            &fingerprint,
            "device",
            now
        )
        .is_err());
    }
}
