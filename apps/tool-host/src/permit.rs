// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Permit verification (platform-requirements.md §11.2, §11.3): a compact JWS over `InvocationPermitClaims`,
//! RS256 with one of Control's effect-signing keys, addressed to this host, inside its short
//! admission window, and never seen before.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use olo_toolgate_contracts::v2::{InvocationPermitClaims, McpScanClaims};
use std::collections::HashMap;
use std::sync::Mutex;

pub const TYP: &str = "toolgate-invocation-permit+jws";
pub const SCAN_TYP: &str = "toolgate-mcp-scan-permit+jws";
/// The longest admission window of a scan permit.
const SCAN_WINDOW_MS: u64 = 10_000;
const MAX_JWS: usize = 32 * 1024;
/// Clock skew allowed on `nbf` and `exp`.
const SKEW_MS: u64 = 2_000;

/// Why a permit is refused; each maps to a `RefusalReason`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PermitError {
    Invalid(String),
    Expired,
}

pub struct Verifier {
    keys: HashMap<String, Vec<u8>>,
    issuer: String,
    audience: String,
    tenant: String,
    max_window_ms: u64,
    seen: Mutex<HashMap<String, u64>>,
}

impl Verifier {
    /// `keys` maps `kid` to the DER `RSAPublicKey` (PKCS#1) ring verifies with.
    pub fn new(
        keys: HashMap<String, Vec<u8>>,
        issuer: &str,
        audience: &str,
        tenant: &str,
        max_window_ms: u64,
    ) -> Verifier {
        Verifier {
            keys,
            issuer: issuer.into(),
            audience: audience.into(),
            tenant: tenant.into(),
            max_window_ms,
            seen: Mutex::new(HashMap::new()),
        }
    }

    /// Verifies the signature and claims, then records `jti` so the same permit is never admitted twice.
    pub fn verify(&self, jws: &str, now_ms: u64) -> Result<InvocationPermitClaims, PermitError> {
        let invalid = |why: &str| PermitError::Invalid(why.into());
        let payload = self.payload(jws, TYP)?;
        let claims: InvocationPermitClaims = serde_json::from_slice(&payload)
            .map_err(|e| PermitError::Invalid(format!("the permit claims are invalid: {e}")))?;
        if claims.iss != self.issuer {
            return Err(invalid("the permit has another issuer"));
        }
        if claims.aud != self.audience {
            return Err(invalid("the permit is addressed to another Tool Host"));
        }
        if claims.identity.tenant_id != self.tenant {
            return Err(invalid("the permit belongs to another tenant"));
        }
        if claims.exp < claims.iat
            || claims.nbf > claims.exp
            || claims.exp - claims.iat > self.max_window_ms
        {
            return Err(invalid(
                "the permit's admission window is invalid or too long",
            ));
        }
        if claims.attempt == 0 {
            return Err(invalid("the permit has no attempt"));
        }
        if now_ms + SKEW_MS < claims.nbf
            || now_ms > claims.exp + SKEW_MS
            || claims.deadline_unix_ms <= now_ms
        {
            return Err(PermitError::Expired);
        }
        let mut seen = self.seen.lock().unwrap();
        seen.retain(|_, exp| *exp + SKEW_MS >= now_ms);
        if seen.contains_key(&claims.jti) {
            return Err(invalid("the permit was already presented"));
        }
        seen.insert(claims.jti.clone(), claims.exp);
        Ok(claims)
    }
}

impl Verifier {
    /// Verifies an MCP scan permit (typ toolgate-mcp-scan-permit+jws, docs/planning/mcp-proxy/plan.md §6): Control's
    /// signature, this host as audience, an admission window of at most 10 s, a signed deadline still ahead, and a jti
    /// never presented before.
    pub fn verify_scan(&self, jws: &str, now_ms: u64) -> Result<McpScanClaims, PermitError> {
        let invalid = |why: &str| PermitError::Invalid(why.into());
        let payload = self.payload(jws, SCAN_TYP)?;
        let claims: McpScanClaims = serde_json::from_slice(&payload).map_err(|e| {
            PermitError::Invalid(format!("the scan permit claims are invalid: {e}"))
        })?;
        if claims.aud != self.audience {
            return Err(invalid("the scan permit is addressed to another Tool Host"));
        }
        if claims.exp < claims.iat || claims.exp - claims.iat > SCAN_WINDOW_MS {
            return Err(invalid(
                "the scan permit's admission window is invalid or too long",
            ));
        }
        if now_ms + SKEW_MS < claims.iat
            || now_ms > claims.exp + SKEW_MS
            || claims.deadline_unix_ms <= now_ms
        {
            return Err(PermitError::Expired);
        }
        let mut seen = self.seen.lock().unwrap();
        seen.retain(|_, exp| *exp + SKEW_MS >= now_ms);
        if seen.contains_key(&claims.jti) {
            return Err(invalid("the scan permit was already presented"));
        }
        seen.insert(claims.jti.clone(), claims.exp);
        Ok(claims)
    }

    /// The payload of a compact JWS of this `typ`, signed RS256 with a known key.
    fn payload(&self, jws: &str, typ: &str) -> Result<Vec<u8>, PermitError> {
        let invalid = |why: &str| PermitError::Invalid(why.into());
        if jws.len() > MAX_JWS {
            return Err(invalid("the permit is too large"));
        }
        let parts: Vec<&str> = jws.split('.').collect();
        let [header, payload, signature] = parts.as_slice() else {
            return Err(invalid("the permit is not a compact JWS"));
        };
        let header_bytes =
            decode(header).ok_or_else(|| invalid("the permit header is not base64url"))?;
        let header: Header = serde_json::from_slice(&header_bytes)
            .map_err(|_| invalid("the permit header is not an RS256 permit header"))?;
        if header.alg != "RS256" || header.typ != typ {
            return Err(invalid(
                "the permit header is not an RS256 permit of this type",
            ));
        }
        let key = self
            .keys
            .get(&header.kid)
            .ok_or_else(|| invalid("the permit is signed with an unknown key"))?;
        let signature =
            decode(signature).ok_or_else(|| invalid("the permit signature is not base64url"))?;
        let signed = format!("{}.{}", parts[0], parts[1]);
        ring::signature::UnparsedPublicKey::new(&ring::signature::RSA_PKCS1_2048_8192_SHA256, key)
            .verify(signed.as_bytes(), &signature)
            .map_err(|_| invalid("the permit signature does not verify"))?;
        decode(payload).ok_or_else(|| invalid("the permit payload is not base64url"))
    }
}

/// A JWS protected header: exactly alg, typ and kid.
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Header {
    alg: String,
    typ: String,
    kid: String,
}

fn decode(part: &str) -> Option<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD.decode(part).ok()?;
    // Canonical base64url only, so one permit has one encoding.
    (URL_SAFE_NO_PAD.encode(&bytes) == part).then_some(bytes)
}

/// The DER `RSAPublicKey` inside a PEM `PUBLIC KEY` or certificate.
pub fn rsa_public_key(pem: &[u8]) -> Result<Vec<u8>, String> {
    use x509_parser::prelude::FromDer;
    let (_, pem) = x509_parser::pem::parse_x509_pem(pem).map_err(|e| format!("not PEM: {e}"))?;
    let spki = match pem.label.as_str() {
        "PUBLIC KEY" => {
            let (_, spki) = x509_parser::x509::SubjectPublicKeyInfo::from_der(&pem.contents)
                .map_err(|e| format!("not a public key: {e}"))?;
            spki.subject_public_key.data.to_vec()
        }
        "CERTIFICATE" => {
            let (_, cert) = x509_parser::parse_x509_certificate(&pem.contents)
                .map_err(|e| format!("not a certificate: {e}"))?;
            cert.public_key().subject_public_key.data.to_vec()
        }
        other => return Err(format!("expected a PUBLIC KEY or CERTIFICATE, not {other}")),
    };
    Ok(spki)
}
