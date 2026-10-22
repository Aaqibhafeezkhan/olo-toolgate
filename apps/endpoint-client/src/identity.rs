// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Device key custody and cryptographic discovery/identity validation.
use crate::{storage::ProtectedStore, Failure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::DeviceIdentity;
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PublicKeyData};

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
    olo_toolgate_tenant_identity::certificate_der(pem).map_err(Into::into)
}
pub fn verify_tenant_identity(
    identity: &DeviceIdentity,
    manifest: &olo_toolgate_contracts::v2::TenantDiscovery,
    key: &DeviceKey,
    device: &str,
    now: u64,
) -> Result<()> {
    olo_toolgate_tenant_identity::verify_tenant_identity(
        identity,
        manifest,
        &key.fingerprint(),
        device,
        now,
    )
    .map_err(Into::into)
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
