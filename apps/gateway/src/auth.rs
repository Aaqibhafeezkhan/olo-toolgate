// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Workload authentication facts are administrator mounted; group grants live only in Control.
use crate::{digest, validation::Contracts};
use olo_toolgate_contracts::v2::{CredentialKind, RequestContext, RequestMode};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use subtle::ConstantTimeEq;

/// Deliberately no Debug implementation. Static tokens cannot impersonate a HUMAN session.
/// The context names no device: Control places each call on a device of the binding's group.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Credential {
    pub token_sha256: String,
    pub context: RequestContext,
    pub expires_at_unix_ms: u64,
}
pub struct Authenticator {
    credentials: Vec<Credential>,
}
impl Authenticator {
    pub fn new(
        credentials: Vec<Credential>,
        contracts: &Contracts,
        now: u64,
    ) -> Result<Self, &'static str> {
        if credentials.is_empty() || credentials.len() > 256 {
            return Err("invalid credential count");
        }
        let mut hashes = BTreeSet::new();
        for c in &credentials {
            let context = &c.context;
            if c.token_sha256.len() != 64
                || !c
                    .token_sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                || !hashes.insert(&c.token_sha256)
                || c.expires_at_unix_ms <= now
                || context.mode == RequestMode::Human
                || context.credential_kind != CredentialKind::StaticToken
                || context
                    .expires_at_unix_ms
                    .is_some_and(|expires| expires != c.expires_at_unix_ms)
                || context.workload_binding_id.is_none()
                || context.agent_id.is_none()
                || context.credential_epoch.is_none()
                || context.credential_sha256.as_deref() != Some(c.token_sha256.as_str())
                || context.mode == RequestMode::Delegated
                    && (context.user_id.is_none() || context.session_epoch.is_none())
                || context.mode == RequestMode::Service
                    && (context.user_id.is_some()
                        || context.session_epoch.is_some()
                        || !context.chain.is_empty())
                || !contracts.valid(
                    "RequestContext",
                    &serde_json::to_value(context).map_err(|_| "invalid workload")?,
                )
            {
                return Err("invalid workload credential");
            }
        }
        Ok(Self { credentials })
    }
    pub fn authenticate(
        &self,
        bearer: &str,
        request_id: String,
        now: u64,
    ) -> Option<(RequestContext, u64)> {
        if !(32..=256).contains(&bearer.len())
            || !bearer
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-_.~".contains(&b))
        {
            return None;
        }
        let hash = digest(bearer.as_bytes());
        let mut found = None;
        for c in &self.credentials {
            if bool::from(hash.as_bytes().ct_eq(c.token_sha256.as_bytes()))
                && c.expires_at_unix_ms > now
            {
                found = Some(c);
            }
        }
        found.map(|c| {
            let mut context = c.context.clone();
            context.request_id = request_id;
            context.expires_at_unix_ms = Some(c.expires_at_unix_ms);
            (context, c.expires_at_unix_ms)
        })
    }
    pub fn ready(&self, now: u64) -> bool {
        self.credentials.iter().any(|c| c.expires_at_unix_ms > now)
    }
}
