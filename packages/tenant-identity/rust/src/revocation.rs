// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Signed revocation facts shared by healthy-host supervisors. No execution authority.
use crate::{contracts::Contracts, Failure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use olo_toolgate_contracts::v2::{
    KillEvent, KillIssuer, KillMode, KillPush, KillScope, KillScopeKind, KillTrustKey,
    RecoveryEvent,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

const EVENT: &str = "toolgate-kill-event+jws";
const PUSH: &str = "toolgate-kill-push+jws";
const MAX_EPOCH: u64 = 9007199254740991;
const MAX_WIRE: usize = 2 * 1024 * 1024;
const MAX_STATE: usize = 16 * 1024 * 1024;
const MAX_EVENTS: usize = 8192;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Header {
    alg: String,
    typ: String,
    kid: String,
}
fn decode(value: &str) -> Result<Vec<u8>> {
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| Failure::Validation)?;
    if URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(Failure::Validation);
    }
    Ok(bytes)
}
fn canonical(value: &Value) -> Result<Vec<u8>> {
    fn write(value: &Value, out: &mut Vec<u8>) -> Result<()> {
        match value {
            Value::Object(fields) => {
                out.push(b'{');
                let mut names: Vec<_> = fields.keys().collect();
                names.sort_by_key(|name| name.encode_utf16().collect::<Vec<_>>());
                for (i, name) in names.into_iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    out.extend(serde_json::to_vec(name).map_err(|_| Failure::Validation)?);
                    out.push(b':');
                    write(&fields[name], out)?;
                }
                out.push(b'}');
            }
            Value::Array(items) => {
                out.push(b'[');
                for (i, item) in items.iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    write(item, out)?;
                }
                out.push(b']');
            }
            // Kill contracts contain bounded integral counters/times, never floating point.
            Value::Number(n) if !n.is_u64() && !n.is_i64() => return Err(Failure::Validation),
            _ => out.extend(serde_json::to_vec(value).map_err(|_| Failure::Validation)?),
        }
        Ok(())
    }
    let mut out = Vec::new();
    write(value, &mut out)?;
    Ok(out)
}
fn unsigned<T: Serialize>(value: &T) -> Result<Value> {
    let mut value = serde_json::to_value(value).map_err(|_| Failure::Validation)?;
    value
        .as_object_mut()
        .ok_or(Failure::Validation)?
        .remove("signature");
    Ok(value)
}
fn counter(id: &str, issuer: &str) -> Result<()> {
    let number = id
        .strip_prefix(&format!("{issuer}:"))
        .ok_or(Failure::Validation)?;
    let n = number.parse::<u64>().map_err(|_| Failure::Validation)?;
    if n == 0 || n > 9999999999999999 || n.to_string() != number {
        return Err(Failure::Validation);
    }
    Ok(())
}

/// Decode the current bounded trust document; the caller authenticates its fixed HTTPS origin.
pub fn decode_trust_document(
    bytes: &[u8],
) -> Result<olo_toolgate_contracts::v2::KillTrustDocument> {
    Contracts::shared()?.decode("KillTrustDocument", bytes)
}

/// Explicit organization fleet keys, independent of admission and operator keys.
pub struct Verifier {
    tenant: String,
    keys: BTreeMap<String, (Vec<u8>, Vec<u8>)>,
    key_digest: Vec<u8>,
}
impl Verifier {
    pub fn new(tenant: &str, keys: &[KillTrustKey]) -> Result<Self> {
        Contracts::shared()?.encode("TenantUuid", &tenant)?;
        if keys.is_empty() || keys.len() > 4 {
            return Err(Failure::Validation);
        }
        let mut trusted = BTreeMap::new();
        let mut moduli = BTreeSet::new();
        for key in keys {
            if key.kid.is_empty()
                || key.kid.len() > 128
                || !key.kid.as_bytes()[0].is_ascii_alphanumeric()
                || !key
                    .kid
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"._:/-".contains(&c))
                || key.e != "AQAB"
            {
                return Err(Failure::Validation);
            }
            let n = decode(&key.n)?;
            let e = decode(&key.e)?;
            if !matches!(n.len(), 256 | 384 | 512)
                || n[0] & 0x80 == 0
                || !moduli.insert(n.clone())
                || trusted.insert(key.kid.clone(), (n, e)).is_some()
            {
                return Err(Failure::Validation);
            }
        }
        use sha2::Digest;
        let key_digest =
            sha2::Sha256::digest(serde_json::to_vec(&trusted).map_err(|_| Failure::Validation)?)
                .to_vec();
        Ok(Self {
            tenant: tenant.into(),
            keys: trusted,
            key_digest,
        })
    }
    fn signature(&self, signed: &str, typ: &str, value: &Value, limit: usize) -> Result<()> {
        if signed.len() > limit {
            return Err(Failure::Validation);
        }
        let parts: Vec<_> = signed.split('.').collect();
        if parts.len() != 3 || parts[0].len() > 1024 {
            return Err(Failure::Validation);
        }
        let header: Header = serde_json::from_value(
            crate::json::strict_json(&decode(parts[0])?).map_err(|_| Failure::Validation)?,
        )
        .map_err(|_| Failure::Validation)?;
        if header.alg != "RS256" || header.typ != typ {
            return Err(Failure::Unauthorized);
        }
        let (n, e) = self.keys.get(&header.kid).ok_or(Failure::Unauthorized)?;
        let bytes = decode(parts[2])?;
        if bytes.len() != n.len() {
            return Err(Failure::Unauthorized);
        }
        ring::signature::RsaPublicKeyComponents { n, e }
            .verify(
                &ring::signature::RSA_PKCS1_2048_8192_SHA256,
                format!("{}.{}", parts[0], parts[1]).as_bytes(),
                &bytes,
            )
            .map_err(|_| Failure::Unauthorized)?;
        if decode(parts[1])? != canonical(value)? {
            return Err(Failure::Unauthorized);
        }
        Ok(())
    }
    fn kill(&self, event: &KillEvent, epoch: u64) -> Result<()> {
        Contracts::shared()?.encode("KillEvent", event)?;
        if event.tenant_id != self.tenant {
            return Err(Failure::TenantMismatch);
        }
        if event.authorization_epoch == 0 || event.authorization_epoch > epoch {
            return Err(Failure::Validation);
        }
        counter(
            &event.event_id,
            match event.issuer {
                KillIssuer::Control => "control",
                KillIssuer::ControlBreakglass => "control-breakglass",
            },
        )?;
        validate_scope(&event.scope)?;
        self.signature(&event.signature, EVENT, &unsigned(event)?, 32768)
    }
    fn recovery(&self, event: &RecoveryEvent, epoch: u64) -> Result<()> {
        Contracts::shared()?.encode("RecoveryEvent", event)?;
        if event.tenant_id != self.tenant {
            return Err(Failure::TenantMismatch);
        }
        if event.issuer != "control"
            || event.authorization_epoch == 0
            || event.authorization_epoch > epoch
        {
            return Err(Failure::Validation);
        }
        counter(&event.event_id, "control")?;
        for id in &event.supersedes {
            counter(
                id,
                if id.starts_with("control-breakglass:") {
                    "control-breakglass"
                } else {
                    "control"
                },
            )?;
        }
        self.signature(&event.signature, EVENT, &unsigned(event)?, 32768)
    }
    /// A push may only merge. The private result cannot be constructed from worker JSON.
    pub fn verify_push(&self, bytes: &[u8]) -> Result<VerifiedPush> {
        if bytes.len() > MAX_WIRE {
            return Err(Failure::Validation);
        }
        let push: KillPush = Contracts::shared()?.decode("KillPush", bytes)?;
        if push.tenant_id != self.tenant {
            return Err(Failure::TenantMismatch);
        }
        let value = crate::json::strict_json(bytes).map_err(|_| Failure::Validation)?;
        let mut value = value;
        value
            .as_object_mut()
            .ok_or(Failure::Validation)?
            .remove("signature");
        self.signature(&push.signature, PUSH, &value, MAX_WIRE)?;
        let mut ids = BTreeSet::new();
        let mut epochs = BTreeSet::new();
        for kill in &push.kills {
            self.kill(kill, push.authorization_epoch)?;
            if !ids.insert(kill.event_id.clone()) || !epochs.insert(kill.authorization_epoch) {
                return Err(Failure::Validation);
            }
        }
        for recovery in &push.recoveries {
            self.recovery(recovery, push.authorization_epoch)?;
            if !ids.insert(recovery.event_id.clone())
                || !epochs.insert(recovery.authorization_epoch)
            {
                return Err(Failure::Validation);
            }
        }
        Ok(VerifiedPush {
            push,
            key_digest: self.key_digest.clone(),
        })
    }
    /// Only for a complete state fetched directly from the fixed authenticated HTTPS Control origin.
    /// Never call this for relayed frames; replacing state is a separate trusted transport action.
    pub fn verify_full_fetch(&self, bytes: &[u8]) -> Result<VerifiedFullFetch> {
        Ok(VerifiedFullFetch {
            value: self.verify_push(bytes)?,
        })
    }
}
pub struct VerifiedPush {
    push: KillPush,
    key_digest: Vec<u8>,
}
impl VerifiedPush {
    pub fn epoch(&self) -> u64 {
        self.push.authorization_epoch
    }
}
pub struct VerifiedFullFetch {
    value: VerifiedPush,
}

/// Values captured from a verified permit/package/placement, never an untrusted worker frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Subject {
    pub package_id: Option<String>,
    pub package_digest: Option<String>,
    pub publisher: Option<String>,
    pub tool_id: Option<String>,
    pub pool_id: Option<String>,
    pub image_digest: Option<String>,
}
fn validate_scope(scope: &KillScope) -> Result<()> {
    if scope.kind == KillScopeKind::Tenant {
        if scope.value.is_some() {
            return Err(Failure::Validation);
        }
    } else if scope.value.as_ref().is_none_or(|v| v.is_empty()) {
        return Err(Failure::Validation);
    }
    if matches!(
        scope.kind,
        KillScopeKind::PackageDigest | KillScopeKind::RuntimeImage
    ) {
        let value = scope.value.as_deref().ok_or(Failure::Validation)?;
        if !value.starts_with("sha256:")
            || value.len() != 71
            || !value.as_bytes()[7..]
                .iter()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(c))
        {
            return Err(Failure::Validation);
        }
    }
    Ok(())
}
fn matches_scope(scope: &KillScope, subject: &Subject) -> bool {
    let value = match scope.kind {
        KillScopeKind::Tenant => return true,
        KillScopeKind::Package => &subject.package_id,
        KillScopeKind::PackageDigest => &subject.package_digest,
        KillScopeKind::Publisher => &subject.publisher,
        KillScopeKind::Tool => &subject.tool_id,
        KillScopeKind::Pool => &subject.pool_id,
        KillScopeKind::RuntimeImage => &subject.image_digest,
    };
    value
        .as_ref()
        .is_none_or(|actual| scope.value.as_ref() == Some(actual))
}

/// A pending or active T5 admission. Termination is sticky even if recovery and
/// retention remove the signed event before the executor polls its notification.
#[derive(Clone)]
pub struct Execution {
    inner: std::sync::Arc<std::sync::Mutex<ExecutionBinding>>,
}
struct ExecutionBinding {
    subject: Subject,
    floor: u64,
    accepted: Option<u64>,
    terminated_at: Option<u64>,
}
impl Execution {
    /// Refine only once, from the exact validated T5 response. A response older
    /// than the pre-request floor is refused; later STOP_NEW snapshots do not
    /// invalidate authority already consumed before that event.
    pub fn accept(&self, epoch: u64, subject: Subject) -> Result<()> {
        let mut binding = self.inner.lock().map_err(|_| Failure::Unauthorized)?;
        if epoch < binding.floor || binding.accepted.is_some() {
            return Err(Failure::Unauthorized);
        }
        binding.subject = subject;
        binding.accepted = Some(epoch);
        if binding.terminated_at.is_some_and(|killed| killed > epoch) {
            return Err(Failure::Unauthorized);
        }
        Ok(())
    }
    pub fn terminated(&self) -> bool {
        let binding = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        binding
            .terminated_at
            .is_some_and(|killed| killed > binding.accepted.unwrap_or(binding.floor))
    }
}
/// Owned by a single fixed-tenant supervisor. Entries exist only while admission
/// or execution holds a handle, rather than retaining an unbounded kill history.
#[derive(Default)]
pub struct Executions {
    active: Vec<std::sync::Weak<std::sync::Mutex<ExecutionBinding>>>,
}
impl Executions {
    pub fn register(&mut self, state: &State, subject: Subject) -> Execution {
        self.active.retain(|entry| entry.strong_count() != 0);
        let execution = Execution {
            inner: std::sync::Arc::new(std::sync::Mutex::new(ExecutionBinding {
                subject,
                floor: state.epoch(),
                accepted: None,
                terminated_at: None,
            })),
        };
        self.active
            .push(std::sync::Arc::downgrade(&execution.inner));
        execution
    }
    /// Call under the same lock as state installation, before publication/ACK.
    pub fn observe(&mut self, state: &State) {
        self.active.retain(|entry| {
            let Some(entry) = entry.upgrade() else {
                return false;
            };
            let mut binding = entry.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(epoch) = state.terminating_epoch_after(
                &binding.subject,
                binding.accepted.unwrap_or(binding.floor),
            ) {
                binding.terminated_at = Some(binding.terminated_at.unwrap_or(0).max(epoch));
            }
            true
        });
    }
}

/// Persist the encoded state atomically before acknowledgement or admission.
/// The owner protects storage; a compromised root host remains outside this guarantee.
#[derive(Clone)]
pub struct State {
    tenant: String,
    highest: u64,
    full_epoch: u64,
    key_digest: Vec<u8>,
    kills: BTreeMap<String, KillEvent>,
    recoveries: BTreeMap<String, RecoveryEvent>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Stored {
    version: u32,
    tenant: String,
    highest: u64,
    full_epoch: u64,
    kills: BTreeMap<String, KillEvent>,
    recoveries: BTreeMap<String, RecoveryEvent>,
}
impl State {
    pub fn empty(verifier: &Verifier) -> Self {
        Self {
            tenant: verifier.tenant.clone(),
            highest: 0,
            full_epoch: 0,
            key_digest: verifier.key_digest.clone(),
            kills: BTreeMap::new(),
            recoveries: BTreeMap::new(),
        }
    }
    pub fn epoch(&self) -> u64 {
        self.highest
    }
    fn coherent(&self) -> Result<()> {
        if self.highest > MAX_EPOCH
            || self.full_epoch > self.highest
            || self.kills.len() + self.recoveries.len() > MAX_EVENTS
        {
            return Err(Failure::Validation);
        }
        let mut epochs = BTreeSet::new();
        for epoch in self
            .kills
            .values()
            .map(|v| v.authorization_epoch)
            .chain(self.recoveries.values().map(|v| v.authorization_epoch))
        {
            if !epochs.insert(epoch) {
                return Err(Failure::Unauthorized);
            }
        }
        for recovery in self.recoveries.values() {
            if self.kills.contains_key(&recovery.event_id) {
                return Err(Failure::Unauthorized);
            }
            for id in &recovery.supersedes {
                if self
                    .kills
                    .get(id)
                    .is_some_and(|kill| kill.authorization_epoch >= recovery.authorization_epoch)
                {
                    return Err(Failure::Unauthorized);
                }
            }
        }
        Ok(())
    }
    fn add(&mut self, push: &KillPush) -> Result<()> {
        if self.tenant != push.tenant_id {
            return Err(Failure::TenantMismatch);
        }
        for kill in &push.kills {
            if self.recoveries.contains_key(&kill.event_id)
                || self
                    .kills
                    .get(&kill.event_id)
                    .is_some_and(|old| old != kill)
            {
                return Err(Failure::Unauthorized);
            }
            if kill.authorization_epoch > self.full_epoch || self.kills.contains_key(&kill.event_id)
            {
                self.kills.insert(kill.event_id.clone(), kill.clone());
            }
        }
        for recovery in &push.recoveries {
            if self.kills.contains_key(&recovery.event_id)
                || self
                    .recoveries
                    .get(&recovery.event_id)
                    .is_some_and(|old| old != recovery)
            {
                return Err(Failure::Unauthorized);
            }
            if recovery.authorization_epoch > self.full_epoch
                || self.recoveries.contains_key(&recovery.event_id)
            {
                self.recoveries
                    .insert(recovery.event_id.clone(), recovery.clone());
            }
        }
        self.highest = self.highest.max(push.authorization_epoch);
        self.coherent()
    }
    pub fn merge(&mut self, push: &VerifiedPush) -> Result<()> {
        if self.key_digest != push.key_digest {
            return Err(Failure::Unauthorized);
        }
        let mut next = self.clone();
        next.add(&push.push)?;
        *self = next;
        Ok(())
    }
    pub fn replace_full(&mut self, fetch: &VerifiedFullFetch) -> Result<()> {
        if self.key_digest != fetch.value.key_digest {
            return Err(Failure::Unauthorized);
        }
        let push = &fetch.value.push;
        if push.authorization_epoch < self.highest {
            return Err(Failure::Unauthorized);
        }
        let mut checked = self.clone();
        checked.add(push)?;
        let next = Self {
            tenant: self.tenant.clone(),
            highest: push.authorization_epoch,
            full_epoch: push.authorization_epoch,
            key_digest: self.key_digest.clone(),
            kills: push
                .kills
                .iter()
                .cloned()
                .map(|v| (v.event_id.clone(), v))
                .collect(),
            recoveries: push
                .recoveries
                .iter()
                .cloned()
                .map(|v| (v.event_id.clone(), v))
                .collect(),
        };
        next.coherent()?;
        *self = next;
        Ok(())
    }
    /// Replace from a complete, authenticated fixed-origin fetch after a trusted key refresh.
    /// Old relayed pushes cannot use this operation. The persisted floor and known event
    /// identities survive key rotation; no local file or worker frame grants a new trust root.
    pub fn replace_trusted_full(&mut self, fetch: &VerifiedFullFetch) -> Result<()> {
        if fetch.value.push.authorization_epoch < self.highest {
            return Err(Failure::Unauthorized);
        }
        let mut next = self.clone();
        next.key_digest = fetch.value.key_digest.clone();
        next.replace_full(fetch)?;
        *self = next;
        Ok(())
    }
    /// Recovery permits fresh admissions; it never revives consumed work.
    pub fn terminating_epoch_after(&self, subject: &Subject, accepted_epoch: u64) -> Option<u64> {
        self.kills
            .values()
            .filter(|kill| {
                kill.mode == KillMode::Terminate
                    && kill.authorization_epoch > accepted_epoch
                    && matches_scope(&kill.scope, subject)
            })
            .map(|kill| kill.authorization_epoch)
            .max()
    }
    pub fn mode(&self, subject: &Subject) -> Option<KillMode> {
        let superseded: BTreeSet<_> = self
            .recoveries
            .values()
            .flat_map(|r| r.supersedes.iter())
            .collect();
        let mut mode = None;
        for kill in self
            .kills
            .values()
            .filter(|k| !superseded.contains(&k.event_id) && matches_scope(&k.scope, subject))
        {
            if kill.mode == KillMode::Terminate {
                return Some(KillMode::Terminate);
            }
            mode = Some(KillMode::StopNew);
        }
        mode
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.coherent()?;
        let bytes = serde_json::to_vec(&Stored {
            version: 1,
            tenant: self.tenant.clone(),
            highest: self.highest,
            full_epoch: self.full_epoch,
            kills: self.kills.clone(),
            recoveries: self.recoveries.clone(),
        })
        .map_err(|_| Failure::Validation)?;
        if bytes.len() > MAX_STATE {
            return Err(Failure::Validation);
        }
        Ok(bytes)
    }
    pub fn restore(verifier: &Verifier, bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_STATE {
            return Err(Failure::Validation);
        }
        let value = crate::json::strict_json(bytes).map_err(|_| Failure::Validation)?;
        let stored: Stored = serde_json::from_value(value).map_err(|_| Failure::Validation)?;
        if stored.version != 1 {
            return Err(Failure::Unsupported);
        }
        if stored.tenant != verifier.tenant {
            return Err(Failure::TenantMismatch);
        }
        let state = Self {
            tenant: stored.tenant,
            highest: stored.highest,
            full_epoch: stored.full_epoch,
            key_digest: verifier.key_digest.clone(),
            kills: stored.kills,
            recoveries: stored.recoveries,
        };
        state.coherent()?;
        for (id, kill) in &state.kills {
            if id != &kill.event_id {
                return Err(Failure::Validation);
            }
            verifier.kill(kill, state.highest)?;
        }
        for (id, recovery) in &state.recoveries {
            if id != &recovery.event_id {
                return Err(Failure::Validation);
            }
            verifier.recovery(recovery, state.highest)?;
        }
        Ok(state)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::{
        pkcs1v15::SigningKey,
        signature::{SignatureEncoding, Signer},
        traits::PublicKeyParts,
        RsaPrivateKey,
    };
    use std::sync::OnceLock;
    const TENANT: &str = "10000000-0000-4000-8000-000000000001";
    #[test]
    fn recovered_snapshot_and_later_retention_cannot_revive_consumed_or_pending_work() {
        let verifier = verifier();
        let mut state = State::empty(&verifier);
        let mut executions = Executions::default();
        let running = executions.register(&state, subject());
        running.accept(0, subject()).unwrap();
        let pending = executions.register(&state, subject());
        let combined = verifier
            .verify_full_fetch(&push(
                2,
                &[kill("control:1", 1, "TERMINATE")],
                &[recovery("control:2", 2, &["control:1"])],
            ))
            .unwrap();
        state.replace_full(&combined).unwrap();
        assert_eq!(state.mode(&subject()), None);
        executions.observe(&state);
        assert!(running.terminated());
        assert!(pending.accept(0, subject()).is_err());
        // Notification consumers may not poll until after both updates.
        state
            .replace_full(&verifier.verify_full_fetch(&push(3, &[], &[])).unwrap())
            .unwrap();
        executions.observe(&state);
        assert!(running.terminated());
        assert!(pending.terminated());
        let fresh = executions.register(&state, subject());
        fresh.accept(3, subject()).unwrap();
        assert!(!fresh.terminated());
        assert!(fresh.accept(4, subject()).is_err()); // binding cannot be renewed
    }
    #[test]
    fn delayed_t5_reply_uses_captured_floor_and_stop_new_does_not_cancel() {
        let verifier = verifier();
        let mut state = State::empty(&verifier);
        let mut executions = Executions::default();
        let pending = executions.register(&state, subject());
        state
            .replace_full(
                &verifier
                    .verify_full_fetch(&push(1, &[kill("control:1", 1, "STOP_NEW")], &[]))
                    .unwrap(),
            )
            .unwrap();
        executions.observe(&state);
        pending.accept(0, subject()).unwrap();
        assert!(!pending.terminated());
        let newer = executions.register(&state, subject());
        assert!(newer.accept(0, subject()).is_err());
        // Fresh consumption after the complete terminate+recovery is allowed.
        let after_request = executions.register(&state, subject());
        state
            .replace_full(
                &verifier
                    .verify_full_fetch(&push(
                        3,
                        &[kill("control:2", 2, "TERMINATE")],
                        &[recovery("control:3", 3, &["control:2"])],
                    ))
                    .unwrap(),
            )
            .unwrap();
        executions.observe(&state);
        after_request.accept(3, subject()).unwrap();
        assert!(!after_request.terminated());
    }
    #[test]
    fn unrelated_scope_never_cancels_and_dropped_handles_are_pruned() {
        let verifier = verifier();
        let mut state = State::empty(&verifier);
        let mut executions = Executions::default();
        let running = executions.register(&state, subject());
        running.accept(0, subject()).unwrap();
        let mut event = kill("control:1", 1, "TERMINATE");
        event.scope = KillScope {
            kind: KillScopeKind::Tool,
            value: Some("other-tool".into()),
        };
        // State installation has separate signature tests; this exercises matching.
        state.kills.insert(event.event_id.clone(), event);
        state.highest = 1;
        executions.observe(&state);
        assert!(!running.terminated());
        drop(running);
        executions.observe(&state);
        assert!(executions.active.is_empty());
    }
    fn key() -> &'static RsaPrivateKey {
        static KEY: OnceLock<RsaPrivateKey> = OnceLock::new();
        KEY.get_or_init(|| RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
    }
    fn trust(key: &RsaPrivateKey) -> KillTrustKey {
        KillTrustKey {
            kid: "organization".into(),
            n: URL_SAFE_NO_PAD.encode(key.n().to_bytes_be()),
            e: URL_SAFE_NO_PAD.encode(key.e().to_bytes_be()),
        }
    }
    fn verifier() -> Verifier {
        Verifier::new(TENANT, &[trust(key())]).unwrap()
    }
    fn sign_with(value: &Value, typ: &str, key: &RsaPrivateKey) -> String {
        let header = serde_json::json!({"alg":"RS256","typ":typ,"kid":"organization"});
        let message = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(canonical(&header).unwrap()),
            URL_SAFE_NO_PAD.encode(canonical(value).unwrap())
        );
        let signature = SigningKey::<rsa::sha2::Sha256>::new(key.clone()).sign(message.as_bytes());
        format!("{}.{}", message, URL_SAFE_NO_PAD.encode(signature.to_vec()))
    }
    fn kill_with(id: &str, epoch: u64, mode: &str, key: &RsaPrivateKey) -> KillEvent {
        let mut value = serde_json::json!({"eventId":id,"issuer":"control","tenantId":TENANT,"authorizationEpoch":epoch,
            "scope":{"kind":"TENANT"},"mode":mode,"reason":"Exact e\u{0301} reason","issuedAtUnixMs":1000});
        value["signature"] = sign_with(&value, EVENT, key).into();
        serde_json::from_value(value).unwrap()
    }
    fn kill(id: &str, epoch: u64, mode: &str) -> KillEvent {
        kill_with(id, epoch, mode, key())
    }
    fn recovery(id: &str, epoch: u64, supersedes: &[&str]) -> RecoveryEvent {
        let mut value = serde_json::json!({"eventId":id,"issuer":"control","tenantId":TENANT,"authorizationEpoch":epoch,
            "supersedes":supersedes,"reviewers":["checker-one","checker-two"],"issuedAtUnixMs":1001});
        value["signature"] = sign_with(&value, EVENT, key()).into();
        serde_json::from_value(value).unwrap()
    }
    fn push_with(
        epoch: u64,
        kills: &[KillEvent],
        recoveries: &[RecoveryEvent],
        key: &RsaPrivateKey,
    ) -> Vec<u8> {
        let mut value = serde_json::json!({"tenantId":TENANT,"authorizationEpoch":epoch,"kills":kills,"recoveries":recoveries});
        value["signature"] = sign_with(&value, PUSH, key).into();
        serde_json::to_vec(&value).unwrap()
    }
    fn push(epoch: u64, kills: &[KillEvent], recoveries: &[RecoveryEvent]) -> Vec<u8> {
        push_with(epoch, kills, recoveries, key())
    }
    fn subject() -> Subject {
        Subject {
            package_id: Some("acme.orders".into()),
            package_digest: Some(format!("sha256:{}", "a".repeat(64))),
            publisher: Some("acme".into()),
            tool_id: Some("acme.orders/pay".into()),
            pool_id: Some("payments".into()),
            image_digest: Some(format!("sha256:{}", "b".repeat(64))),
        }
    }
    #[test]
    fn reordered_replayed_events_never_remove_a_kill_without_its_recovery() {
        let v = verifier();
        let mut state = State::empty(&v);
        let first = kill("control:1", 1, "STOP_NEW");
        let second = kill("control:3", 3, "TERMINATE");
        let recovered = recovery("control:2", 2, &["control:1"]);
        for bytes in [
            push(3, std::slice::from_ref(&second), &[]),
            push(1, std::slice::from_ref(&first), &[]),
            push(2, &[], std::slice::from_ref(&recovered)),
            push(1, std::slice::from_ref(&first), &[]),
            push(3, std::slice::from_ref(&second), &[]),
        ] {
            state.merge(&v.verify_push(&bytes).unwrap()).unwrap();
        }
        assert_eq!(state.epoch(), 3);
        assert_eq!(state.mode(&subject()), Some(KillMode::Terminate));
        assert!(state.recoveries.contains_key("control:2"));
        let bytes = state.encode().unwrap();
        let reopened = State::restore(&v, &bytes).unwrap();
        assert_eq!(reopened.mode(&subject()), Some(KillMode::Terminate));
    }
    #[test]
    fn current_full_fetch_replaces_only_above_the_persisted_floor_and_gcs_old_replays() {
        let v = verifier();
        let mut state = State::empty(&v);
        let first = kill("control:1", 1, "TERMINATE");
        state
            .merge(
                &v.verify_push(&push(1, std::slice::from_ref(&first), &[]))
                    .unwrap(),
            )
            .unwrap();
        assert!(state
            .replace_full(&v.verify_full_fetch(&push(0, &[], &[])).unwrap())
            .is_err());
        let recovered = recovery("control:2", 2, &["control:1"]);
        state
            .merge(&v.verify_push(&push(2, &[], &[recovered])).unwrap())
            .unwrap();
        assert_eq!(state.mode(&subject()), None);
        state
            .replace_full(&v.verify_full_fetch(&push(2, &[], &[])).unwrap())
            .unwrap();
        state
            .merge(&v.verify_push(&push(1, &[first], &[])).unwrap())
            .unwrap();
        assert_eq!(state.mode(&subject()), None);
        assert_eq!(state.epoch(), 2);
        assert!(State::restore(&v, &state.encode().unwrap())
            .unwrap()
            .kills
            .is_empty());
    }
    #[test]
    fn same_id_or_epoch_forks_fail_atomically_and_cannot_partially_add_events() {
        let v = verifier();
        let mut state = State::empty(&v);
        let first = kill("control:1", 1, "STOP_NEW");
        state
            .merge(
                &v.verify_push(&push(1, std::slice::from_ref(&first), &[]))
                    .unwrap(),
            )
            .unwrap();
        let before = state.encode().unwrap();
        let fork = kill("control:1", 1, "TERMINATE");
        let extra = kill("control:2", 2, "TERMINATE");
        let verified = v.verify_push(&push(2, &[extra, fork], &[])).unwrap();
        assert!(state.merge(&verified).is_err());
        assert_eq!(state.encode().unwrap(), before);
        let same_epoch = kill("control:3", 1, "TERMINATE");
        assert!(state
            .merge(&v.verify_push(&push(1, &[same_epoch], &[])).unwrap())
            .is_err());
        assert_eq!(state.encode().unwrap(), before);
        assert!(v
            .verify_push(&push(1, &[first.clone(), first], &[]))
            .is_err());
    }
    #[test]
    fn signature_type_tenant_body_keyring_and_unknown_fields_are_not_interchangeable() {
        let v = verifier();
        let valid = push(1, &[kill("control:1", 1, "TERMINATE")], &[]);
        v.verify_push(&valid).unwrap();
        let mut value: Value = serde_json::from_slice(&valid).unwrap();
        value["authorizationEpoch"] = 2.into();
        assert!(v.verify_push(&serde_json::to_vec(&value).unwrap()).is_err());
        value["signature"] = sign_with(&unsigned(&value).unwrap(), EVENT, key()).into();
        assert!(v.verify_push(&serde_json::to_vec(&value).unwrap()).is_err());
        value["tenantId"] = "20000000-0000-4000-8000-000000000002".into();
        value["signature"] = sign_with(&unsigned(&value).unwrap(), PUSH, key()).into();
        assert!(matches!(
            v.verify_push(&serde_json::to_vec(&value).unwrap()),
            Err(Failure::TenantMismatch)
        ));
        let foreign = RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap();
        let other = Verifier::new(TENANT, &[trust(&foreign)]).unwrap();
        let forged = push_with(
            1,
            &[kill_with("control:1", 1, "TERMINATE", &foreign)],
            &[],
            &foreign,
        );
        assert!(v.verify_push(&forged).is_err());
        let mut state = State::empty(&v);
        assert!(state.merge(&other.verify_push(&forged).unwrap()).is_err());
        value = serde_json::from_slice(&valid).unwrap();
        value["workerManifest"] = serde_json::json!({});
        assert!(v.verify_push(&serde_json::to_vec(&value).unwrap()).is_err());
        assert!(v.verify_push(&vec![b'x'; MAX_WIRE + 1]).is_err());
        assert!(v.verify_push(b"{}{}").is_err());
    }
    #[test]
    fn malformed_header_scope_counter_epoch_and_old_formats_are_refused() {
        let v = verifier();
        let mut event = kill("control:1", 1, "TERMINATE");
        event.tenant_id = "20000000-0000-4000-8000-000000000002".into();
        event.signature = sign_with(&unsigned(&event).unwrap(), EVENT, key());
        assert!(v.verify_push(&push(1, &[event], &[])).is_err());
        let event = kill("control:01", 1, "TERMINATE");
        assert!(v.verify_push(&push(1, &[event], &[])).is_err());
        let event = kill("control:1", 2, "TERMINATE");
        assert!(v.verify_push(&push(1, &[event], &[])).is_err());
        let mut event = kill("control:1", 1, "TERMINATE");
        event.scope.value = Some("unexpected".into());
        event.signature = sign_with(&unsigned(&event).unwrap(), EVENT, key());
        assert!(v.verify_push(&push(1, &[event], &[])).is_err());
        let mut raw: Value =
            serde_json::from_slice(&push(1, &[kill("control:1", 1, "TERMINATE")], &[])).unwrap();
        raw["kills"][0].as_object_mut().unwrap().remove("tenantId");
        raw["signature"] = sign_with(&unsigned(&raw).unwrap(), PUSH, key()).into();
        assert!(v.verify_push(&serde_json::to_vec(&raw).unwrap()).is_err());
        let mut value: Value = serde_json::from_slice(&push(0, &[], &[])).unwrap();
        let header=URL_SAFE_NO_PAD.encode(br#"{"alg":"RS256","typ":"toolgate-kill-push+jws","kid":"organization","jku":"https://untrusted.test"}"#);
        let message = format!(
            "{}.{}",
            header,
            URL_SAFE_NO_PAD.encode(canonical(&unsigned(&value).unwrap()).unwrap())
        );
        let signature =
            SigningKey::<rsa::sha2::Sha256>::new(key().clone()).sign(message.as_bytes());
        value["signature"] =
            format!("{}.{}", message, URL_SAFE_NO_PAD.encode(signature.to_vec())).into();
        assert!(v.verify_push(&serde_json::to_vec(&value).unwrap()).is_err());
    }
    #[test]
    fn every_scope_and_terminate_precedence_use_only_pinned_subject_facts() {
        let s = subject();
        for (kind, value) in [
            (KillScopeKind::Package, s.package_id.clone()),
            (KillScopeKind::PackageDigest, s.package_digest.clone()),
            (KillScopeKind::Publisher, s.publisher.clone()),
            (KillScopeKind::Tool, s.tool_id.clone()),
            (KillScopeKind::Pool, s.pool_id.clone()),
            (KillScopeKind::RuntimeImage, s.image_digest.clone()),
            (KillScopeKind::Tenant, None),
        ] {
            let scope = KillScope {
                kind: kind.clone(),
                value,
            };
            validate_scope(&scope).unwrap();
            assert!(matches_scope(&scope, &s));
            let mut other = s.clone();
            other.package_id = Some("other".into());
            other.package_digest = Some(format!("sha256:{}", "c".repeat(64)));
            other.publisher = Some("other".into());
            other.tool_id = Some("other".into());
            other.pool_id = Some("other".into());
            other.image_digest = Some(format!("sha256:{}", "c".repeat(64)));
            assert_eq!(matches_scope(&scope, &other), kind == KillScopeKind::Tenant);
        }
        let v = verifier();
        let mut state = State::empty(&v);
        state
            .merge(
                &v.verify_push(&push(1, &[kill("control:1", 1, "STOP_NEW")], &[]))
                    .unwrap(),
            )
            .unwrap();
        assert_eq!(state.mode(&s), Some(KillMode::StopNew));
        state
            .merge(
                &v.verify_push(&push(2, &[kill("control:2", 2, "TERMINATE")], &[]))
                    .unwrap(),
            )
            .unwrap();
        assert_eq!(state.mode(&s), Some(KillMode::Terminate));
        let unknown = Subject {
            package_id: None,
            package_digest: None,
            publisher: None,
            tool_id: None,
            pool_id: None,
            image_digest: None,
        };
        assert!(matches_scope(
            &KillScope {
                kind: KillScopeKind::Pool,
                value: Some("pool".into())
            },
            &unknown
        ));
        let mut native = unknown;
        native.pool_id = Some(String::new());
        assert!(!matches_scope(
            &KillScope {
                kind: KillScopeKind::Pool,
                value: Some("pool".into())
            },
            &native
        ));
    }
    #[test]
    fn damaged_foreign_or_unsupported_journals_are_rejected_without_conversion() {
        let v = verifier();
        let mut state = State::empty(&v);
        state
            .merge(
                &v.verify_push(&push(1, &[kill("control:1", 1, "TERMINATE")], &[]))
                    .unwrap(),
            )
            .unwrap();
        let bytes = state.encode().unwrap();
        for (field, value) in [
            ("version", serde_json::json!(2)),
            (
                "tenant",
                serde_json::json!("20000000-0000-4000-8000-000000000002"),
            ),
            ("highest", serde_json::json!(0)),
            ("fullEpoch", serde_json::json!(2)),
        ] {
            let mut damaged: Value = serde_json::from_slice(&bytes).unwrap();
            damaged[field] = value;
            assert!(State::restore(&v, &serde_json::to_vec(&damaged).unwrap()).is_err());
        }
        let mut damaged: Value = serde_json::from_slice(&bytes).unwrap();
        damaged["kills"]["control:1"]["mode"] = "STOP_NEW".into();
        assert!(State::restore(&v, &serde_json::to_vec(&damaged).unwrap()).is_err());
        assert!(State::restore(&v, b"{}").is_err());
        assert_eq!(state.encode().unwrap(), bytes);
    }
    #[test]
    fn trusted_fixed_origin_key_refresh_preserves_floor_tenant_and_known_event_identity() {
        let old = verifier();
        let mut state = State::empty(&old);
        state
            .merge(
                &old.verify_push(&push(2, &[kill("control:2", 2, "TERMINATE")], &[]))
                    .unwrap(),
            )
            .unwrap();
        let original = state.encode().unwrap();
        let rotated = RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap();
        let current = Verifier::new(TENANT, &[trust(&rotated)]).unwrap();
        let fresh = |epoch, kills: &[KillEvent]| {
            current
                .verify_full_fetch(&push_with(epoch, kills, &[], &rotated))
                .unwrap()
        };
        assert!(state.replace_trusted_full(&fresh(1, &[])).is_err());
        assert_eq!(state.encode().unwrap(), original);
        // A trusted new signer cannot rewrite a retained event under the same ID.
        let fork = kill_with("control:2", 2, "STOP_NEW", &rotated);
        assert!(state.replace_trusted_full(&fresh(3, &[fork])).is_err());
        assert_eq!(state.encode().unwrap(), original);
        let newer = kill_with("control:3", 3, "TERMINATE", &rotated);
        let accepted = current
            .verify_push(&push_with(3, std::slice::from_ref(&newer), &[], &rotated))
            .unwrap();
        assert!(state.merge(&accepted).is_err()); // relay cannot rotate trust
        state.replace_trusted_full(&fresh(3, &[newer])).unwrap();
        assert_eq!(state.epoch(), 3);
        assert!(State::restore(&old, &state.encode().unwrap()).is_err());
        State::restore(&current, &state.encode().unwrap()).unwrap();
        assert!(state
            .merge(&old.verify_push(&push(4, &[], &[])).unwrap())
            .is_err());
    }
}
