// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Current signed revocation floor, shared by local and relayed native runs.
use crate::{Failure, Result};
use olo_toolgate_contracts::v2::{KillExecutionScope, KillPush, KillTrustDocument, KillTrustKey};
use olo_toolgate_tenant_identity::revocation::{Execution, Executions, State, Subject, Verifier};
use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use tokio::sync::watch;
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct Stored {
    version: u32,
    keys: Vec<KillTrustKey>,
    state: Option<serde_json::Value>,
}
impl Default for Stored {
    fn default() -> Self {
        Self {
            version: 1,
            keys: vec![],
            state: None,
        }
    }
}
pub(crate) struct Shared {
    state: Mutex<Option<State>>,
    updates: watch::Sender<u64>,
    fault: AtomicBool,
    executions: Mutex<Executions>,
}
pub(crate) struct Prepared {
    pub stored: Stored,
    state: State,
    pub epoch: u64,
}
pub(crate) fn validate(tenant: Option<&str>, stored: &Stored) -> Result<Option<State>> {
    if stored.version != 1 {
        return Err(Failure::Unsupported);
    }
    match &stored.state {
        None if stored.keys.is_empty() => Ok(None),
        Some(value) => {
            let verifier = Verifier::new(tenant.ok_or(Failure::TenantMismatch)?, &stored.keys)
                .map_err(|_| Failure::Unauthorized)?;
            State::restore(
                &verifier,
                &serde_json::to_vec(value).map_err(|_| Failure::Validation)?,
            )
            .map(Some)
            .map_err(|_| Failure::Unauthorized)
        }
        _ => Err(Failure::Validation),
    }
}
pub(crate) fn subject(scope: &KillExecutionScope) -> Subject {
    Subject {
        package_id: scope.package_id.clone(),
        package_digest: Some(scope.package_digest.clone()),
        publisher: scope.publisher.clone(),
        tool_id: Some(scope.tool_id.clone()),
        pool_id: scope.pool_id.clone(),
        image_digest: scope.image_digest.clone(),
    }
}
impl Shared {
    pub(crate) fn restore(tenant: Option<&str>, stored: &Stored) -> Result<Arc<Self>> {
        let state = validate(tenant, stored)?;
        let (updates, _) = watch::channel(0);
        Ok(Arc::new(Self {
            state: Mutex::new(state),
            updates,
            fault: AtomicBool::new(false),
            executions: Mutex::new(Executions::default()),
        }))
    }
    pub(crate) fn ready(&self) -> bool {
        !self.fault.load(Ordering::Acquire) && *self.updates.borrow() != 0
    }
    #[cfg(test)]
    pub(crate) fn require_epoch(&self, epoch: u64) -> Result<()> {
        if !self.ready()
            || self
                .state
                .lock()
                .map_err(|_| Failure::Unavailable)?
                .as_ref()
                .is_none_or(|state| epoch < state.epoch())
        {
            return Err(Failure::Unauthorized);
        }
        Ok(())
    }
    pub(crate) fn register(&self, subject: Subject) -> Result<Execution> {
        if !self.ready() {
            return Err(Failure::Unauthorized);
        }
        let state = self.state.lock().map_err(|_| Failure::Unavailable)?;
        Ok(self
            .executions
            .lock()
            .map_err(|_| Failure::Unavailable)?
            .register(state.as_ref().ok_or(Failure::Unauthorized)?, subject))
    }
    /// Called only for complete state and keys fetched by the fixed-origin device HTTPS transport.
    pub(crate) fn prepare(
        &self,
        tenant: &str,
        trust: KillTrustDocument,
        push: KillPush,
    ) -> Result<Prepared> {
        if self.fault.load(Ordering::Acquire) || trust.tenant_id != tenant {
            return Err(Failure::Unauthorized);
        }
        let verifier = Verifier::new(tenant, &trust.keys).map_err(|_| Failure::Unauthorized)?;
        let proof = verifier
            .verify_full_fetch(&serde_json::to_vec(&push).map_err(|_| Failure::Validation)?)
            .map_err(|_| Failure::Unauthorized)?;
        let mut state = self
            .state
            .lock()
            .map_err(|_| Failure::Unavailable)?
            .clone()
            .unwrap_or_else(|| State::empty(&verifier));
        state
            .replace_trusted_full(&proof)
            .map_err(|_| Failure::Unauthorized)?;
        if state.epoch() < trust.authorization_epoch {
            return Err(Failure::Unauthorized);
        }
        let epoch = state.epoch();
        let stored = Stored {
            version: 1,
            keys: trust.keys,
            state: Some(
                serde_json::from_slice(&state.encode().map_err(|_| Failure::Validation)?)
                    .map_err(|_| Failure::Validation)?,
            ),
        };
        Ok(Prepared {
            stored,
            state,
            epoch,
        })
    }
    /// The owner must persist prepared.stored before publishing or acknowledging.
    pub(crate) fn publish(&self, prepared: Prepared) -> Result<()> {
        if self.fault.load(Ordering::Acquire) {
            return Err(Failure::Unavailable);
        }
        let mut state = self.state.lock().map_err(|_| Failure::Unavailable)?;
        if state
            .as_ref()
            .is_some_and(|old| old.epoch() > prepared.state.epoch())
        {
            return Err(Failure::Unauthorized);
        }
        self.executions
            .lock()
            .map_err(|_| Failure::Unavailable)?
            .observe(&prepared.state);
        *state = Some(prepared.state);
        self.updates
            .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
        Ok(())
    }
    pub(crate) fn fail(&self) {
        self.fault.store(true, Ordering::Release);
        self.updates
            .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
    }
    pub(crate) async fn terminated(&self, execution: &Execution) {
        let mut updates = self.updates.subscribe();
        loop {
            if self.fault.load(Ordering::Acquire) || execution.terminated() {
                return;
            }
            if updates.changed().await.is_err() {
                return;
            }
        }
    }
}

#[cfg(test)]
pub(crate) mod testing {
    use super::*;
    use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
    use rsa::{
        pkcs1v15::SigningKey,
        signature::{SignatureEncoding, Signer},
        traits::PublicKeyParts,
        RsaPrivateKey,
    };
    use serde_json::{json, Value};
    use std::sync::OnceLock;
    fn key() -> &'static RsaPrivateKey {
        static KEY: OnceLock<RsaPrivateKey> = OnceLock::new();
        KEY.get_or_init(|| RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
    }
    pub(crate) fn trust(tenant: &str, epoch: u64) -> KillTrustDocument {
        KillTrustDocument {
            tenant_id: tenant.into(),
            authorization_epoch: epoch,
            keys: vec![KillTrustKey {
                kid: "organization".into(),
                n: URL_SAFE_NO_PAD.encode(key().n().to_bytes_be()),
                e: URL_SAFE_NO_PAD.encode(key().e().to_bytes_be()),
            }],
        }
    }
    fn canonical(value: &Value) -> Vec<u8> {
        if let Value::Object(fields) = value {
            let mut keys: Vec<_> = fields.keys().collect();
            keys.sort_by_key(|key| key.encode_utf16().collect::<Vec<_>>());
            let entries: Vec<_> = keys
                .into_iter()
                .map(|key| {
                    format!(
                        "{}:{}",
                        serde_json::to_string(key).unwrap(),
                        String::from_utf8(canonical(&fields[key])).unwrap()
                    )
                })
                .collect();
            format!("{{{}}}", entries.join(",")).into_bytes()
        } else if let Value::Array(items) = value {
            format!(
                "[{}]",
                items
                    .iter()
                    .map(|value| String::from_utf8(canonical(value)).unwrap())
                    .collect::<Vec<_>>()
                    .join(",")
            )
            .into_bytes()
        } else {
            serde_json::to_vec(value).unwrap()
        }
    }
    fn sign(mut value: Value, typ: &str) -> Value {
        let message = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(canonical(
                &json!({"alg":"RS256","typ":typ,"kid":"organization"})
            )),
            URL_SAFE_NO_PAD.encode(canonical(&value))
        );
        let signature =
            SigningKey::<rsa::sha2::Sha256>::new(key().clone()).sign(message.as_bytes());
        value["signature"] =
            format!("{message}.{}", URL_SAFE_NO_PAD.encode(signature.to_vec())).into();
        value
    }
    pub(crate) fn push(tenant: &str, epoch: u64, mode: Option<&str>) -> KillPush {
        let kills=mode.map(|mode|vec![sign(json!({"eventId":format!("control:{epoch}"),"issuer":"control","tenantId":tenant,
            "authorizationEpoch":epoch,"scope":{"kind":"TENANT"},"mode":mode,"reason":"incident","issuedAtUnixMs":1000}),"toolgate-kill-event+jws")]).unwrap_or_default();
        serde_json::from_value(sign(
            json!({"tenantId":tenant,"authorizationEpoch":epoch,"kills":kills,"recoveries":[]}),
            "toolgate-kill-push+jws",
        ))
        .unwrap()
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    const TENANT: &str = "10000000-0000-4000-8000-000000000001";
    fn scope() -> KillExecutionScope {
        KillExecutionScope {
            package_id: None,
            package_digest: format!("sha256:{}", "a".repeat(64)),
            tool_id: "acme/tool".into(),
            publisher: None,
            pool_id: Some("".into()),
            image_digest: None,
        }
    }
    #[tokio::test]
    async fn signed_floor_requires_publish_and_live_refresh_after_restart_and_never_rolls_back() {
        let shared = Shared::restore(Some(TENANT), &Stored::default()).unwrap();
        let prepared = shared
            .prepare(
                TENANT,
                testing::trust(TENANT, 1),
                testing::push(TENANT, 1, Some("STOP_NEW")),
            )
            .unwrap();
        let durable = prepared.stored.clone();
        assert!(!shared.ready());
        shared.publish(prepared).unwrap();
        assert!(shared.require_epoch(0).is_err());
        shared.require_epoch(1).unwrap();
        let restarted = Shared::restore(Some(TENANT), &durable).unwrap();
        assert!(!restarted.ready());
        assert!(restarted
            .prepare(
                TENANT,
                testing::trust(TENANT, 0),
                testing::push(TENANT, 0, None)
            )
            .is_err());
        let scope = scope();
        let execution = shared.register(subject(&scope)).unwrap();
        execution.accept(1, subject(&scope)).unwrap();
        let termination = shared.terminated(&execution);
        tokio::pin!(termination);
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(20), &mut termination)
                .await
                .is_err()
        );
        shared
            .publish(
                shared
                    .prepare(
                        TENANT,
                        testing::trust(TENANT, 2),
                        testing::push(TENANT, 2, Some("TERMINATE")),
                    )
                    .unwrap(),
            )
            .unwrap();
        tokio::time::timeout(std::time::Duration::from_millis(100), &mut termination)
            .await
            .unwrap();
        assert!(Shared::restore(Some("20000000-0000-4000-8000-000000000001"), &durable).is_err());
    }
    #[tokio::test]
    async fn persistence_fault_ends_authority_and_foreign_or_corrupted_signed_state_is_refused() {
        let shared = Shared::restore(Some(TENANT), &Stored::default()).unwrap();
        let mut corrupt = testing::push(TENANT, 1, Some("STOP_NEW"));
        corrupt.authorization_epoch = 2;
        assert!(shared
            .prepare(TENANT, testing::trust(TENANT, 1), corrupt)
            .is_err());
        assert!(!shared.ready());
        let other = "20000000-0000-4000-8000-000000000001";
        assert!(shared
            .prepare(
                TENANT,
                testing::trust(other, 1),
                testing::push(other, 1, None)
            )
            .is_err());
        shared
            .publish(
                shared
                    .prepare(
                        TENANT,
                        testing::trust(TENANT, 1),
                        testing::push(TENANT, 1, None),
                    )
                    .unwrap(),
            )
            .unwrap();
        let execution = shared.register(subject(&scope())).unwrap();
        shared.fail();
        assert!(!shared.ready());
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            shared.terminated(&execution),
        )
        .await
        .unwrap();
        assert!(shared
            .prepare(
                TENANT,
                testing::trust(TENANT, 2),
                testing::push(TENANT, 2, None)
            )
            .is_err());
    }
}
