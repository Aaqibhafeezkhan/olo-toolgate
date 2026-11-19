// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! The protected owner is the filesystem target. SDK result frames cannot create witnesses.
use super::AuthorizationPort;
use crate::files::content_sha256;
use crate::{files::Area, transport::Call, Failure, Result};
use olo_toolgate_contracts::v2::{NativeFileIntent, NativeFileOperation};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};

pub(super) struct BrokerSession {
    pub owner: Arc<dyn AuthorizationPort>,
    pub uncertain: AtomicBool,
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use olo_toolgate_contracts::{v2::NativeFileEffectGrant, AuthorizationRequest};
    use std::sync::Mutex;
    struct Owner {
        root: std::path::PathBuf,
        deny: AtomicBool,
        lose_receipt: bool,
        calls: Mutex<Vec<&'static str>>,
    }
    impl AuthorizationPort for Owner {
        fn revoked(&self) -> Call<'_, ()> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn authorize(&self, _request: AuthorizationRequest) -> Call<'_, ()> {
            Box::pin(async { Err(Failure::Unsupported) })
        }
        fn file_effect_begin(
            &self,
            intent: NativeFileIntent,
            _ordinal: u64,
        ) -> Call<'_, NativeFileEffectGrant> {
            Box::pin(async move {
                self.calls.lock().unwrap().push("intent");
                if self.deny.load(Ordering::SeqCst) {
                    return Err(Failure::Unauthorized);
                }
                if intent.operation == NativeFileOperation::Write {
                    assert!(!self.root.join(&intent.path).exists());
                }
                let canonical = serde_json::to_value(intent).unwrap();
                Ok(NativeFileEffectGrant {
                    effect_id: "nf-test".into(),
                    intent_digest: content_sha256(serde_json::to_vec(&canonical).unwrap()),
                })
            })
        }
        fn file_effect_receipt(
            &self,
            _grant: NativeFileEffectGrant,
            _before: Option<String>,
            _after: String,
        ) -> Call<'_, ()> {
            Box::pin(async move {
                self.calls.lock().unwrap().push("receipt");
                if self.lose_receipt {
                    Err(Failure::Unavailable)
                } else {
                    Ok(())
                }
            })
        }
    }
    fn setup(deny: bool, lose_receipt: bool) -> (Area, Arc<Owner>, BrokerSession) {
        let root = std::env::temp_dir().join(format!(
            "native-file-proof-{}",
            crate::identity::nonce().unwrap()
        ));
        let area = Area::open(crate::files::Settings {
            root: root.clone(),
            max_file_bytes: 65536,
            max_entries: 32,
            extensions: vec!["txt".into()],
        })
        .unwrap();
        let owner = Arc::new(Owner {
            root,
            deny: AtomicBool::new(deny),
            lose_receipt,
            calls: Mutex::new(Vec::new()),
        });
        let session = BrokerSession {
            owner: owner.clone(),
            uncertain: AtomicBool::new(false),
        };
        (area, owner, session)
    }
    fn arguments() -> BTreeMap<String, Value> {
        BTreeMap::from([
            ("path".into(), json!("output.txt")),
            ("text".into(), json!("summary")),
        ])
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn intent_precedes_durable_write_and_receipt_follows_it() {
        let (area, owner, session) = setup(false, false);
        session
            .apply(
                &area,
                "FILES_WRITE",
                &json!({"path":"output.txt","text":"summary"}),
                &arguments(),
                1,
            )
            .unwrap();
        assert_eq!(area.read("output.txt").unwrap(), b"summary");
        assert_eq!(*owner.calls.lock().unwrap(), vec!["intent", "receipt"]);
        drop(area);
        std::fs::remove_dir_all(&owner.root).unwrap();
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn refused_intent_has_no_file_effect_and_poison_prevents_a_retry() {
        let (area, owner, session) = setup(true, false);
        let request = json!({"path":"output.txt","text":"summary"});
        assert!(session
            .apply(&area, "FILES_WRITE", &request, &arguments(), 1)
            .is_err());
        owner.deny.store(false, Ordering::SeqCst);
        assert!(session
            .apply(&area, "FILES_WRITE", &request, &arguments(), 2)
            .is_err());
        assert!(area.content_digest("output.txt").unwrap().is_none());
        assert_eq!(*owner.calls.lock().unwrap(), vec!["intent"]);
        drop(area);
        std::fs::remove_dir_all(&owner.root).unwrap();
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn lost_receipt_preserves_the_file_and_never_repeats_the_effect() {
        let (area, owner, session) = setup(false, true);
        let request = json!({"path":"output.txt","text":"summary"});
        assert!(session
            .apply(&area, "FILES_WRITE", &request, &arguments(), 1)
            .is_err());
        assert!(session
            .apply(&area, "FILES_WRITE", &request, &arguments(), 2)
            .is_err());
        assert_eq!(area.read("output.txt").unwrap(), b"summary");
        assert_eq!(*owner.calls.lock().unwrap(), vec!["intent", "receipt"]);
        drop(area);
        std::fs::remove_dir_all(&owner.root).unwrap();
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn content_must_be_exactly_one_of_the_admitted_arguments() {
        let (area, owner, session) = setup(false, false);
        assert!(session
            .apply(
                &area,
                "FILES_WRITE",
                &json!({"path":"output.txt","text":"different"}),
                &arguments(),
                1
            )
            .is_err());
        assert!(owner.calls.lock().unwrap().is_empty());
        assert!(area.content_digest("output.txt").unwrap().is_none());
        drop(area);
        std::fs::remove_dir_all(&owner.root).unwrap();
    }
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn rename_preserves_content_and_does_not_overwrite_an_existing_destination() {
        let (area, owner, session) = setup(false, false);
        area.write("source.txt", b"original", false).unwrap();
        let args = BTreeMap::from([
            ("path".into(), json!("source.txt")),
            ("destination".into(), json!("archive.txt")),
        ]);
        session
            .apply(
                &area,
                "FILES_RENAME",
                &json!({"path":"source.txt","destination":"archive.txt"}),
                &args,
                1,
            )
            .unwrap();
        assert_eq!(area.read("archive.txt").unwrap(), b"original");
        assert!(area.content_digest("source.txt").unwrap().is_none());
        area.write("source.txt", b"second", false).unwrap();
        assert!(session
            .apply(
                &area,
                "FILES_RENAME",
                &json!({"path":"source.txt","destination":"archive.txt"}),
                &args,
                2
            )
            .is_err());
        assert_eq!(area.read("archive.txt").unwrap(), b"original");
        assert_eq!(area.read("source.txt").unwrap(), b"second");
        drop(area);
        std::fs::remove_dir_all(&owner.root).unwrap();
    }
}
impl BrokerSession {
    fn wait<T>(&self, work: Call<'_, T>) -> Result<T> {
        let runtime = tokio::runtime::Handle::try_current().map_err(|_| Failure::Unsupported)?;
        if runtime.runtime_flavor() != tokio::runtime::RuntimeFlavor::MultiThread {
            return Err(Failure::Unsupported);
        }
        tokio::task::block_in_place(|| runtime.block_on(work))
    }
    pub fn apply(
        &self,
        area: &Area,
        operation: &str,
        request: &Value,
        arguments: &BTreeMap<String, Value>,
        ordinal: usize,
    ) -> Result<Value> {
        // Only the qualified Linux owner supports these target receipts.
        if !cfg!(target_os = "linux") || self.uncertain.swap(true, Ordering::SeqCst) {
            return Err(Failure::Unsupported);
        }
        let path = request
            .get("path")
            .and_then(Value::as_str)
            .ok_or(Failure::Validation)?;
        if !arguments.values().any(|v| v.as_str() == Some(path)) {
            return Err(Failure::Unauthorized);
        }
        let before = area.content_digest(path)?;
        let (intent, text) = match operation {
            "FILES_WRITE" => {
                let text = request
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or(Failure::Validation)?;
                if !arguments.values().any(|v| v.as_str() == Some(text)) {
                    return Err(Failure::Unauthorized);
                }
                (
                    NativeFileIntent {
                        operation: NativeFileOperation::Write,
                        path: path.into(),
                        destination: None,
                        content_sha256: Some(content_sha256(text.as_bytes())),
                    },
                    Some(text),
                )
            }
            "FILES_RENAME" => {
                let destination = request
                    .get("destination")
                    .and_then(Value::as_str)
                    .ok_or(Failure::Validation)?;
                if !arguments.values().any(|v| v.as_str() == Some(destination))
                    || before.is_none()
                    || area.content_digest(destination)?.is_some()
                {
                    return Err(Failure::Unauthorized);
                }
                (
                    NativeFileIntent {
                        operation: NativeFileOperation::Rename,
                        path: path.into(),
                        destination: Some(destination.into()),
                        content_sha256: None,
                    },
                    None,
                )
            }
            _ => return Err(Failure::Unsupported),
        };
        let grant = self.wait(self.owner.file_effect_begin(intent.clone(), ordinal as u64))?;
        let canonical = serde_json::to_value(&intent).map_err(|_| Failure::Validation)?;
        let expected =
            content_sha256(serde_json::to_vec(&canonical).map_err(|_| Failure::Validation)?);
        if grant.intent_digest != expected {
            return Err(Failure::Unauthorized);
        }
        match intent.operation {
            NativeFileOperation::Write => {
                area.write(path, text.ok_or(Failure::Validation)?.as_bytes(), false)?
            }
            NativeFileOperation::Rename => area.rename(
                path,
                intent.destination.as_deref().ok_or(Failure::Validation)?,
            )?,
        }
        let after = area
            .content_digest(intent.destination.as_deref().unwrap_or(path))?
            .ok_or(Failure::Unavailable)?;
        if intent.operation == NativeFileOperation::Write
            && intent.content_sha256.as_deref() != Some(&after)
            || intent.operation == NativeFileOperation::Rename
                && (before.as_deref() != Some(&after) || area.content_digest(path)?.is_some())
        {
            return Err(Failure::Unavailable);
        }
        self.wait(self.owner.file_effect_receipt(grant, before, after))?;
        self.uncertain.store(false, Ordering::SeqCst);
        Ok(json!({}))
    }
}
