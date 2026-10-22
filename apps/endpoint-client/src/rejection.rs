// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Routing refusals must never retire an installation's device identity or authorize a rebind.
use crate::{contracts::Contracts, Failure};
use olo_toolgate_contracts::{ErrorCode, ErrorEnvelope};

pub(crate) const MAX_ERROR: usize = 4096;

fn classify(status: u16, code: Option<ErrorCode>) -> Failure {
    match (status, code) {
        (403, Some(ErrorCode::TenantMismatch)) => Failure::TenantMismatch,
        (404, Some(ErrorCode::TenantUnavailable)) | (421, _) => Failure::Unavailable,
        (401, Some(ErrorCode::Unauthorized)) | (403, Some(ErrorCode::Forbidden)) => {
            Failure::Revoked
        }
        (409, _) => Failure::Conflict,
        (410, _) => Failure::EnrollmentRequired,
        _ => Failure::Unavailable,
    }
}

pub(crate) fn http(status: u16, bytes: &[u8], contracts: &Contracts) -> Failure {
    if bytes.len() > MAX_ERROR {
        return Failure::Unavailable;
    }
    let code = contracts
        .decode::<ErrorEnvelope>("ErrorEnvelope", bytes)
        .ok()
        .map(|value| value.code);
    classify(status, code)
}

pub(crate) fn socket(status: u16, bytes: &[u8]) -> Failure {
    if bytes.len() > MAX_ERROR {
        return Failure::Unavailable;
    }
    let code = crate::json::strict_json(bytes)
        .ok()
        .and_then(|value| value.get("code").cloned())
        .and_then(|value| serde_json::from_value::<ErrorCode>(value).ok());
    classify(status, code)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tenant_and_authority_refusals_preserve_identity_on_both_transports() {
        let contracts = Contracts::shared().unwrap();
        for (status, code, expected) in [
            (403, ErrorCode::TenantMismatch, Failure::TenantMismatch),
            (404, ErrorCode::TenantUnavailable, Failure::Unavailable),
            (421, ErrorCode::MisdirectedRequest, Failure::Unavailable),
            (403, ErrorCode::Forbidden, Failure::Revoked),
            (401, ErrorCode::Unauthorized, Failure::Revoked),
        ] {
            let envelope = serde_json::to_vec(&ErrorEnvelope {
                code: code.clone(),
                request_id: "request".into(),
                retryable: false,
            })
            .unwrap();
            assert_eq!(http(status, &envelope, contracts), expected);
            assert_eq!(
                socket(
                    status,
                    &serde_json::to_vec(&serde_json::json!({"code":code})).unwrap()
                ),
                expected
            );
        }
    }
    #[test]
    fn untyped_or_corrupt_denials_do_not_revoke_an_identity() {
        let contracts = Contracts::shared().unwrap();
        for bytes in [
            b"".as_slice(),
            b"forbidden",
            br#"{"code":"TENANT_MISMATCH"}"#,
            br#"{"code":"FORBIDDEN","code":"TENANT_MISMATCH","requestId":"r","retryable":false}"#,
        ] {
            assert_eq!(http(403, bytes, contracts), Failure::Unavailable);
        }
        assert_eq!(
            http(403, &vec![b'x'; MAX_ERROR + 1], contracts),
            Failure::Unavailable
        );
        assert_eq!(socket(403, b"{}"), Failure::Unavailable);
    }
}
