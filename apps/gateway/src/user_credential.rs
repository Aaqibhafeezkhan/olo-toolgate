// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! ToolGate user credentials (OLO integration, M3.4).
//!
//! Control issues a short-lived RS256 JWS (`typ: toolgate-user-credential+jws`) by RFC 8693 token exchange of a
//! platform session assertion. The Gateway verifies it against keys pinned by `kid` and builds a `HUMAN` context.
//! Nothing here grants access: Control still requires the user enabled, the identity binding at this session epoch,
//! and a team grant, so a revoked session stops at Control's next authorization even while the JWS is unexpired.
use crate::digest;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::v2::{CredentialKind, RequestContext, RequestMode};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Matches Control's `UserCredentials.CREDENTIAL_TYPE`.
pub const CREDENTIAL_TYPE: &str = "toolgate-user-credential+jws";
/// Control never issues a longer credential.
pub const MAX_LIFETIME_SECONDS: u64 = 300;
const CLOCK_SKEW_MS: u64 = 30_000;
const MAX_TOKEN_BYTES: usize = 4096;

/// Administrator configuration: Control's credential issuer, this Gateway's audience, and pinned verification keys.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Settings {
    pub issuer: String,
    pub audience: String,
    pub keys: Vec<KeyFile>,
}

/// A pinned `SubjectPublicKeyInfo` PEM, selected only by its exact `kid`.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct KeyFile {
    pub key_id: String,
    pub public_key_path: std::path::PathBuf,
}

impl Settings {
    pub fn validate(&self) -> Result<(), &'static str> {
        if !bounded(&self.issuer, 512) || !bounded(&self.audience, 512) {
            return Err("user credential issuer and audience required");
        }
        if self.keys.is_empty() || self.keys.len() > 8 {
            return Err("one to eight user credential keys");
        }
        let mut ids = std::collections::BTreeSet::new();
        if self
            .keys
            .iter()
            .any(|k| !identifier(&k.key_id) || !ids.insert(&k.key_id))
        {
            return Err("user credential key ids must be distinct identifiers");
        }
        Ok(())
    }
}

/// Deliberately no Debug implementation.
pub struct Verifier {
    issuer: String,
    audience: String,
    keys: BTreeMap<String, Vec<u8>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Header {
    alg: String,
    typ: String,
    kid: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Claims {
    iss: String,
    aud: String,
    sub: String,
    tid: String,
    sep: u64,
    bid: String,
    iat: u64,
    exp: u64,
    jti: String,
}

impl Verifier {
    /// Reads each pinned key file once at startup.
    pub fn load(settings: &Settings) -> Result<Self, &'static str> {
        settings.validate()?;
        let mut keys = Vec::new();
        for key in &settings.keys {
            keys.push((
                key.key_id.clone(),
                crate::config::read_bytes(&key.public_key_path)?,
            ));
        }
        Self::new(&settings.issuer, &settings.audience, keys)
    }

    pub fn new(
        issuer: &str,
        audience: &str,
        pems: Vec<(String, Vec<u8>)>,
    ) -> Result<Self, &'static str> {
        let mut keys = BTreeMap::new();
        for (kid, pem) in pems {
            if !identifier(&kid) || keys.insert(kid, rsa_public_key(&pem)?).is_some() {
                return Err("user credential key ids must be distinct identifiers");
            }
        }
        if !bounded(issuer, 512) || !bounded(audience, 512) || keys.is_empty() {
            return Err("invalid user credential settings");
        }
        Ok(Self {
            issuer: issuer.to_owned(),
            audience: audience.to_owned(),
            keys,
        })
    }

    /// Whether a bearer has JWS shape; static workload tokens never contain two dots.
    pub fn applies(bearer: &str) -> bool {
        bearer.bytes().filter(|b| *b == b'.').count() == 2
    }

    /// The `HUMAN` context and its expiry, or `None` for any defect. Never says which check failed.
    pub fn verify(
        &self,
        token: &str,
        request_id: String,
        now: u64,
    ) -> Option<(RequestContext, u64)> {
        if token.len() > MAX_TOKEN_BYTES {
            return None;
        }
        let mut parts = token.split('.');
        let (head, body, signature) = (parts.next()?, parts.next()?, parts.next()?);
        if parts.next().is_some() {
            return None;
        }
        let header: Header = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(head).ok()?).ok()?;
        if header.alg != "RS256" || header.typ != CREDENTIAL_TYPE {
            return None;
        }
        let key = self.keys.get(&header.kid)?;
        ring::signature::UnparsedPublicKey::new(&ring::signature::RSA_PKCS1_2048_8192_SHA256, key)
            .verify(
                &token.as_bytes()[..head.len() + 1 + body.len()],
                &URL_SAFE_NO_PAD.decode(signature).ok()?,
            )
            .ok()?;
        let claims: Claims = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(body).ok()?).ok()?;
        let expires = claims.exp.checked_mul(1000)?;
        if claims.iss != self.issuer
            || claims.aud != self.audience
            || !bounded(&claims.sub, 256)
            || !identifier(&claims.bid)
            || !bounded(&claims.jti, 128)
            || !crate::tenancy::canonical_uuid(&claims.tid)
            || claims.sep == 0
            || expires <= now
            || claims.iat.checked_mul(1000)? > now.saturating_add(CLOCK_SKEW_MS)
            || claims.exp <= claims.iat
            || claims.exp - claims.iat > MAX_LIFETIME_SECONDS
        {
            return None;
        }
        Some((
            RequestContext {
                request_id,
                tenant_id: claims.tid,
                mode: RequestMode::Human,
                credential_kind: CredentialKind::Jwt,
                issuer: Some(claims.iss),
                user_id: Some(claims.sub),
                agent_id: None,
                workload_binding_id: None,
                chain: Vec::new(),
                session_epoch: Some(claims.sep),
                credential_epoch: None,
                delegated_session_epoch: None,
                expires_at_unix_ms: Some(expires),
                binding_id: Some(claims.bid),
                credential_sha256: Some(digest(token.as_bytes())),
            },
            expires,
        ))
    }
}

fn bounded(value: &str, max: usize) -> bool {
    !value.is_empty() && value.len() <= max && !value.chars().any(char::is_control)
}

fn identifier(value: &str) -> bool {
    (1..=128).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.:".contains(&b))
}

/// The PKCS#1 key inside an RSA `SubjectPublicKeyInfo` PEM, as `ring` expects it.
fn rsa_public_key(pem: &[u8]) -> Result<Vec<u8>, &'static str> {
    use x509_parser::prelude::FromDer;
    let (_, pem) =
        x509_parser::pem::parse_x509_pem(pem).map_err(|_| "user credential key is not PEM")?;
    if pem.label != "PUBLIC KEY" {
        return Err("user credential key must be a PUBLIC KEY");
    }
    let (_, spki) = x509_parser::x509::SubjectPublicKeyInfo::from_der(&pem.contents)
        .map_err(|_| "user credential key is not a public key")?;
    if spki.algorithm.algorithm != x509_parser::oid_registry::OID_PKCS1_RSAENCRYPTION {
        return Err("user credential key must be RSA");
    }
    Ok(spki.subject_public_key.data.to_vec())
}
