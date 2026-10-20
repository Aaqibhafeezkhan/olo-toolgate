// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Native boundary for the shared tenant trust verifier; no alternate protocol reader.
pub use olo_toolgate_tenant_identity::{Trust, Verified};
pub fn verify(
    envelope: &olo_toolgate_contracts::v2::SignedTenantDiscovery,
    origin: &str,
    expected: Option<&str>,
    pin: Option<&Trust>,
    now: u64,
) -> crate::Result<Verified> {
    olo_toolgate_tenant_identity::verify(envelope, origin, expected, pin, now).map_err(Into::into)
}
impl From<olo_toolgate_tenant_identity::Failure> for crate::Failure {
    fn from(value: olo_toolgate_tenant_identity::Failure) -> Self {
        use olo_toolgate_tenant_identity::Failure as TrustFailure;
        match value {
            TrustFailure::Validation => Self::Validation,
            TrustFailure::Unauthorized => Self::Unauthorized,
            TrustFailure::TenantMismatch => Self::TenantMismatch,
            TrustFailure::Unsupported => Self::Unsupported,
        }
    }
}
