// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Device key custody and cryptographic discovery/identity validation.
use crate::{storage::ProtectedStore, Failure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::{ClientDiscovery, DeviceIdentity, SignedClientDiscovery};
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PublicKeyData};
use x509_parser::prelude::*;

/// No Debug/Serialize implementation: only CSR/public material crosses service boundaries.
pub struct DeviceKey {
    key: KeyPair,
}
impl DeviceKey {
    pub fn load_or_create(store: &ProtectedStore) -> Result<Self> {
        let key = match store.read("device-key")? {
            Some(bytes) => {
                #[cfg(windows)]
                let bytes = crate::platform::windows::unprotect(&bytes)?;
                KeyPair::from_pem(std::str::from_utf8(&bytes).map_err(|_| Failure::Validation)?)
                    .map_err(|_| Failure::Validation)?
            }
            None => {
                let key = KeyPair::generate().map_err(|_| Failure::Unavailable)?;
                let bytes = key.serialize_pem().into_bytes();
                #[cfg(windows)]
                let bytes = crate::platform::windows::protect(&bytes)?;
                store.write("device-key", &bytes)?;
                key
            }
        };
        Ok(Self { key })
    }
    pub fn fingerprint(&self) -> String {
        crate::digest(&self.key.subject_public_key_info())
    }
    pub fn console_proof(&self, nonce: &str) -> Result<String> {
        if nonce.len() != 64
            || !nonce
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(Failure::Validation);
        }
        let random = ring::rand::SystemRandom::new();
        let key = ring::signature::EcdsaKeyPair::from_pkcs8(
            &ring::signature::ECDSA_P256_SHA256_ASN1_SIGNING,
            &self.key.serialize_der(),
            &random,
        )
        .map_err(|_| Failure::Validation)?;
        let message = format!("OLO ToolGate console device proof v1\n{nonce}");
        let signature = key
            .sign(&random, message.as_bytes())
            .map_err(|_| Failure::Unavailable)?;
        Ok(URL_SAFE_NO_PAD.encode(signature.as_ref()))
    }
    pub fn csr(&self, device: &str) -> Result<String> {
        let mut params =
            CertificateParams::new(Vec::<String>::new()).map_err(|_| Failure::Validation)?;
        let mut name = DistinguishedName::new();
        name.push(DnType::CommonName, device);
        params.distinguished_name = name;
        params
            .serialize_request(&self.key)
            .and_then(|r| r.pem())
            .map_err(|_| Failure::Unavailable)
    }
    pub fn tls_identity(&self, identity: &DeviceIdentity) -> Result<reqwest::Identity> {
        let pem = format!("{}{}", identity.certificate_pem, self.key.serialize_pem());
        reqwest::Identity::from_pem(pem.as_bytes()).map_err(|_| Failure::Validation)
    }
}
pub fn nonce() -> Result<String> {
    use ring::rand::SecureRandom;
    let mut bytes = [0; 16];
    ring::rand::SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| Failure::Unavailable)?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
pub fn certificate_der(pem: &str) -> Result<Vec<u8>> {
    let (remaining, pem) =
        x509_parser::pem::parse_x509_pem(pem.as_bytes()).map_err(|_| Failure::Validation)?;
    if pem.label != "CERTIFICATE" || remaining.iter().any(|b| !b.is_ascii_whitespace()) {
        return Err(Failure::Validation);
    }
    Ok(pem.contents)
}
fn valid_ca(cert: &X509Certificate<'_>, now: u64) -> Result<()> {
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
/// HTTPS-authenticated discovery bootstraps a dedicated device CA; exact bytes are signed.
pub fn verify_discovery(
    envelope: &SignedClientDiscovery,
    origin: &str,
    now: u64,
    pinned_ca: Option<&str>,
) -> Result<ClientDiscovery> {
    let bytes = URL_SAFE_NO_PAD
        .decode(&envelope.payload)
        .map_err(|_| Failure::Validation)?;
    if bytes.len() > 32768 || URL_SAFE_NO_PAD.encode(&bytes) != envelope.payload {
        return Err(Failure::Validation);
    }
    let manifest: ClientDiscovery =
        crate::contracts::Contracts::new()?.decode("ClientDiscovery", &bytes)?;
    if manifest.protocol_version != 1
        || crate::config::origin(&manifest.control_url)? != origin
        || manifest.verification_uri != format!("{origin}/console/#enroll")
        || manifest.issued_at_unix_ms > now
        || manifest.expires_at_unix_ms <= now
        || manifest.expires_at_unix_ms - manifest.issued_at_unix_ms > 300000
        || semver::Version::parse(&manifest.minimum_client_version)
            .map_err(|_| Failure::Validation)?
            > semver::Version::parse(env!("CARGO_PKG_VERSION")).map_err(|_| Failure::Validation)?
    {
        return Err(Failure::Unauthorized);
    }
    crate::config::origin(&manifest.gateway_url)?;
    let der = certificate_der(&manifest.issuer_certificate_pem)?;
    let (_, ca) = parse_x509_certificate(&der).map_err(|_| Failure::Validation)?;
    valid_ca(&ca, now)?;
    if pinned_ca.is_some_and(|p| p != crate::digest(&der)) {
        return Err(Failure::Unauthorized);
    }
    let signature = URL_SAFE_NO_PAD
        .decode(&envelope.signature)
        .map_err(|_| Failure::Validation)?;
    if signature.len() > 1024 || URL_SAFE_NO_PAD.encode(&signature) != envelope.signature {
        return Err(Failure::Validation);
    }
    let key = ca.public_key().subject_public_key.data.as_ref();
    let mut message = b"toolgate-client-discovery-v1\0".to_vec();
    message.extend_from_slice(&bytes);
    ring::signature::UnparsedPublicKey::new(&ring::signature::RSA_PKCS1_2048_8192_SHA256, key)
        .verify(&message, &signature)
        .map_err(|_| Failure::Unauthorized)?;
    Ok(manifest)
}
/// Match issuer, exact enrollment identities, key possession and client-only X.509 privileges.
pub fn verify_identity(
    identity: &DeviceIdentity,
    manifest: &ClientDiscovery,
    key: &DeviceKey,
    device: &str,
    now: u64,
) -> Result<()> {
    if identity.device_id != device
        || identity.tenant_id != manifest.tenant_id
        || identity.server_id != manifest.server_id
        || identity.user_id.is_empty()
        || identity.issuer_certificate_pem != manifest.issuer_certificate_pem
        || identity.expires_at_unix_ms <= now
    {
        return Err(Failure::Unauthorized);
    }
    let ca_der = certificate_der(&identity.issuer_certificate_pem)?;
    let (_, ca) = parse_x509_certificate(&ca_der).map_err(|_| Failure::Validation)?;
    valid_ca(&ca, now)?;
    let der = certificate_der(&identity.certificate_pem)?;
    let (_, certificate) = parse_x509_certificate(&der).map_err(|_| Failure::Validation)?;
    let time = ASN1Time::from_timestamp((now / 1000) as i64).map_err(|_| Failure::Validation)?;
    let end = certificate.validity().not_after.timestamp();
    let start = certificate.validity().not_before.timestamp();
    if certificate.issuer() != ca.subject()
        || !certificate.validity().is_valid_at(time)
        || certificate.is_ca()
        || end - start > 86400
        || end < 0
        || end as u64 * 1000 != identity.expires_at_unix_ms / 1000 * 1000
        || crate::digest(certificate.public_key().raw) != key.fingerprint()
        || !certificate
            .extended_key_usage()
            .map_err(|_| Failure::Validation)?
            .is_some_and(|e| e.value.client_auth && !e.value.server_auth && !e.value.any)
        || !certificate
            .key_usage()
            .map_err(|_| Failure::Validation)?
            .is_some_and(|e| e.value.digital_signature() && !e.value.key_cert_sign())
    {
        return Err(Failure::Unauthorized);
    }
    certificate
        .verify_signature(Some(ca.public_key()))
        .map_err(|_| Failure::Unauthorized)?;
    Ok(())
}

#[cfg(test)]
mod console_proof_tests {
    use super::*;
    use ring::signature::KeyPair as _;

    #[test]
    fn proof_is_bound_to_the_device_key_domain_and_exact_nonce() {
        let device = DeviceKey {
            key: KeyPair::generate().unwrap(),
        };
        let nonce = "a".repeat(64);
        let signature = URL_SAFE_NO_PAD
            .decode(device.console_proof(&nonce).unwrap())
            .unwrap();
        let random = ring::rand::SystemRandom::new();
        let key = ring::signature::EcdsaKeyPair::from_pkcs8(
            &ring::signature::ECDSA_P256_SHA256_ASN1_SIGNING,
            &device.key.serialize_der(),
            &random,
        )
        .unwrap();
        let verifier = ring::signature::UnparsedPublicKey::new(
            &ring::signature::ECDSA_P256_SHA256_ASN1,
            key.public_key().as_ref(),
        );
        assert!(verifier
            .verify(
                format!("OLO ToolGate console device proof v1\n{nonce}").as_bytes(),
                &signature
            )
            .is_ok());
        assert!(verifier.verify(nonce.as_bytes(), &signature).is_err());
        assert!(verifier
            .verify(
                format!("OLO ToolGate console device proof v1\n{}", "b".repeat(64)).as_bytes(),
                &signature
            )
            .is_err());
        for invalid in ["", "abc", &"A".repeat(64), &"g".repeat(64), &"a".repeat(65)] {
            assert!(device.console_proof(invalid).is_err());
        }
    }
}
