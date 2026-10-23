// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Workload authentication facts are administrator mounted; group grants live only in Control.
use crate::{digest, validation::Contracts};
use olo_toolgate_contracts::v2::{CredentialKind, RequestContext, RequestMode};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    sync::{Arc, PoisonError, RwLock},
};
use subtle::ConstantTimeEq;

/// Largest credential file accepted at startup or on reload.
pub const MAX_CREDENTIAL_FILE_BYTES: usize = 1_048_576;

/// Deliberately no Debug implementation. Static tokens cannot impersonate a HUMAN session.
/// The context names no device: Control places each call on a device of the binding's group.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Credential {
    pub token_sha256: String,
    pub context: RequestContext,
    pub expires_at_unix_ms: u64,
}

/// One fully validated credential set and the digest of the bytes it came from.
struct Loaded {
    credentials: Vec<Credential>,
    sha256: String,
}

/// The live set is swapped whole: a request sees either the previous or the new set, never a mix.
pub struct Authenticator {
    loaded: RwLock<Arc<Loaded>>,
    tenant: Option<String>,
    users: Option<crate::user_credential::Verifier>,
}
impl Authenticator {
    pub fn new(
        credentials: Vec<Credential>,
        contracts: &Contracts,
        now: u64,
    ) -> Result<Self, &'static str> {
        let sha256 = digest(&serde_json::to_vec(&credentials).map_err(|_| "invalid workload")?);
        let credentials = validate(credentials, contracts, now)?;
        Ok(Self {
            users: None,
            tenant: None,
            loaded: RwLock::new(Arc::new(Loaded {
                credentials,
                sha256,
            })),
        })
    }
    /// Parse a mounted credential file; its digest identifies exactly which file is loaded.
    pub fn from_bytes(bytes: &[u8], contracts: &Contracts, now: u64) -> Result<Self, &'static str> {
        Ok(Self {
            users: None,
            tenant: None,
            loaded: RwLock::new(Arc::new(parse(bytes, contracts, now)?)),
        })
    }
    /// Bind once at application construction; reloads must keep every credential in this tenant.
    pub fn for_tenant(mut self, tenant: String) -> Result<Self, &'static str> {
        if !crate::tenancy::canonical_uuid(&tenant)
            || self
                .current()
                .credentials
                .iter()
                .any(|credential| credential.context.tenant_id != tenant)
        {
            return Err("credential tenant mismatch");
        }
        self.tenant = Some(tenant);
        Ok(self)
    }
    /// Also accept Control-issued user credentials (M3.4). Workload credentials can still never be HUMAN.
    pub fn with_user_credentials(mut self, users: crate::user_credential::Verifier) -> Self {
        self.users = Some(users);
        self
    }
    /// Replace the set only after the whole new file validates; any error keeps the current set.
    pub fn reload(
        &self,
        bytes: &[u8],
        contracts: &Contracts,
        now: u64,
    ) -> Result<usize, &'static str> {
        let next = Arc::new(parse(bytes, contracts, now)?);
        if self.tenant.as_ref().is_some_and(|tenant| {
            next.credentials
                .iter()
                .any(|credential| credential.context.tenant_id != *tenant)
        }) {
            return Err("credential tenant mismatch");
        }
        let count = next.credentials.len();
        *self.loaded.write().unwrap_or_else(PoisonError::into_inner) = next;
        Ok(count)
    }
    /// Digest and size of the loaded set; never any credential material.
    pub fn loaded(&self) -> (String, usize) {
        let current = self.current();
        (current.sha256.clone(), current.credentials.len())
    }
    fn current(&self) -> Arc<Loaded> {
        self.loaded
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }
    pub fn authenticate(
        &self,
        bearer: &str,
        request_id: String,
        now: u64,
    ) -> Option<(RequestContext, u64)> {
        if let Some(users) = self
            .users
            .as_ref()
            .filter(|_| crate::user_credential::Verifier::applies(bearer))
        {
            return users.verify(bearer, request_id, now);
        }
        if !(32..=256).contains(&bearer.len())
            || !bearer
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-_.~".contains(&b))
        {
            return None;
        }
        let hash = digest(bearer.as_bytes());
        let current = self.current();
        let mut found = None;
        for c in &current.credentials {
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
        self.current()
            .credentials
            .iter()
            .any(|c| c.expires_at_unix_ms > now)
    }
}

fn parse(bytes: &[u8], contracts: &Contracts, now: u64) -> Result<Loaded, &'static str> {
    if bytes.len() > MAX_CREDENTIAL_FILE_BYTES {
        return Err("credential file too large");
    }
    let credentials: Vec<Credential> =
        serde_json::from_slice(bytes).map_err(|_| "invalid credential JSON")?;
    Ok(Loaded {
        credentials: validate(credentials, contracts, now)?,
        sha256: digest(bytes),
    })
}

fn validate(
    credentials: Vec<Credential>,
    contracts: &Contracts,
    now: u64,
) -> Result<Vec<Credential>, &'static str> {
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
    Ok(credentials)
}
