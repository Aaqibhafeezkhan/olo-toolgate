// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Shared tenant discovery and certificate verification for device and Gateway consumers.
mod config;
mod contracts;
mod discovery;
mod identity;
mod json;
pub use config::origin;
pub use discovery::{verify, Trust, Verified};
pub use identity::{
    certificate_der, certificate_tenant, verify_tenant_identity, verify_workload_certificate,
};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Failure {
    Validation,
    Unauthorized,
    TenantMismatch,
    Unsupported,
}
pub type Result<T> = std::result::Result<T, Failure>;
impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}
impl std::error::Error for Failure {}
pub fn verify_bytes(
    bytes: &[u8],
    origin: &str,
    expected: Option<&str>,
    pin: Option<&Trust>,
    now: u64,
) -> Result<Verified> {
    let envelope = contracts::Contracts::shared()?.decode("SignedTenantDiscovery", bytes)?;
    verify(&envelope, origin, expected, pin, now)
}
/// Only call for bytes fetched through independently authenticated HTTPS from a
/// protected, immutable Control origin. This is offline key-ring recovery, not
/// verification of a document supplied by a console or an untrusted relay.
pub fn verify_https_refresh(
    bytes: &[u8],
    control: &str,
    expected: &str,
    pin: Option<&Trust>,
    now: u64,
) -> Result<Verified> {
    let envelope: olo_toolgate_contracts::v2::SignedTenantDiscovery =
        contracts::Contracts::shared()?.decode("SignedTenantDiscovery", bytes)?;
    match verify(&envelope, control, Some(expected), pin, now) {
        Ok(value) => Ok(value),
        Err(Failure::Unauthorized) if pin.is_some() => {
            use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
            let previous = pin.ok_or(Failure::Unauthorized)?;
            let protected = envelope.jws.split('.').next().ok_or(Failure::Validation)?;
            if protected.len() > 1024 {
                return Err(Failure::Validation);
            }
            let header: olo_toolgate_contracts::v2::DiscoveryHeader =
                contracts::Contracts::shared()?.decode(
                    "DiscoveryHeader",
                    &URL_SAFE_NO_PAD
                        .decode(protected)
                        .map_err(|_| Failure::Validation)?,
                )?;
            if previous.keys.keys.iter().any(|key| key.kid == header.kid) {
                return Err(Failure::Unauthorized);
            }
            let verified = verify(&envelope, control, Some(expected), None, now)?;
            if verified.manifest.control_url != origin(control)?
                || verified.trust.version <= previous.version
                || verified.trust.issued_at < previous.issued_at
                || verified.trust.tenant_id != previous.tenant_id
            {
                return Err(Failure::Unauthorized);
            }
            Ok(verified)
        }
        Err(error) => Err(error),
    }
}
pub fn digest(bytes: &[u8]) -> String {
    use sha2::Digest;
    sha2::Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
