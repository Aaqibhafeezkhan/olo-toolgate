// Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
//! Fixed-origin signed kill supervision. Poll failure never renews an execution lease.
use crate::control::Control;
use olo_toolgate_contracts::v2::{KillExecutionScope, KillTrustDocument, KillTrustKey};
use olo_toolgate_tenant_identity::revocation::{Execution, Executions, State, Subject, Verifier};
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use tokio::sync::watch;

const MAX_JOURNAL: u64 = 18 * 1024 * 1024;
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Journal {
    version: u32,
    tenant: String,
    origin: String,
    keys: Vec<KillTrustKey>,
    state: serde_json::Value,
}
struct Current {
    verifier: Verifier,
    keys: Vec<KillTrustKey>,
    state: State,
}
pub(crate) struct Supervisor {
    tenant: String,
    origin: String,
    path: PathBuf,
    current: Mutex<Option<Current>>,
    persistence_failed: AtomicBool,
    executions: Mutex<Executions>,
    updates: watch::Sender<u64>,
}
fn invalid() -> String {
    "Invalid current revocation journal or signed authority".into()
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
impl Supervisor {
    pub(crate) fn open(tenant: &str, origin: &str, directory: &Path) -> Result<Self, String> {
        olo_toolgate_tenant_identity::origin(origin).map_err(|_| invalid())?;
        let directory = directory.join("revocation");
        let mut builder = std::fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        if directory.is_symlink() {
            return Err(invalid());
        }
        builder.create(&directory).map_err(|_| invalid())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if std::fs::metadata(&directory)
                .map_err(|_| invalid())?
                .permissions()
                .mode()
                & 0o077
                != 0
            {
                return Err(invalid());
            }
        }
        let path = directory.join("revocation-v1.json");
        let current = match std::fs::symlink_metadata(&path) {
            Ok(metadata) => {
                if !metadata.is_file() || metadata.len() > MAX_JOURNAL {
                    return Err(invalid());
                }
                let bytes = std::fs::read(&path).map_err(|_| invalid())?;
                // Reject duplicate members before serde deserializes the closed current format.
                let value = toolgate_cli::strict_json(&bytes).map_err(|_| invalid())?;
                let journal: Journal = serde_json::from_value(value).map_err(|_| invalid())?;
                if journal.version != 1 || journal.tenant != tenant || journal.origin != origin {
                    return Err(invalid());
                }
                let verifier = Verifier::new(tenant, &journal.keys).map_err(|_| invalid())?;
                let state = State::restore(
                    &verifier,
                    &serde_json::to_vec(&journal.state).map_err(|_| invalid())?,
                )
                .map_err(|_| invalid())?;
                Some(Current {
                    verifier,
                    keys: journal.keys,
                    state,
                })
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
            Err(_) => return Err(invalid()),
        };
        let (updates, _) = watch::channel(0);
        Ok(Self {
            tenant: tenant.into(),
            origin: origin.into(),
            path,
            current: Mutex::new(current),
            persistence_failed: AtomicBool::new(false),
            executions: Mutex::new(Executions::default()),
            updates,
        })
    }
    // Restored disk state supplies the anti-rollback floor, never startup execution readiness.
    pub(crate) fn ready(&self) -> bool {
        !self.persistence_failed.load(Ordering::Acquire) && *self.updates.borrow() != 0
    }
    pub(crate) fn register(&self, subject: Subject) -> Result<Execution, String> {
        if !self.ready() {
            return Err(invalid());
        }
        let current = self.current.lock().map_err(|_| invalid())?;
        let state = &current.as_ref().ok_or_else(invalid)?.state;
        Ok(self
            .executions
            .lock()
            .map_err(|_| invalid())?
            .register(state, subject))
    }
    #[cfg(test)]
    fn permits_epoch(&self, epoch: u64) -> bool {
        self.ready()
            && self
                .current
                .lock()
                .unwrap()
                .as_ref()
                .is_some_and(|c| epoch >= c.state.epoch())
    }
    fn save(&self, current: &Current) -> Result<(), String> {
        let journal = Journal {
            version: 1,
            tenant: self.tenant.clone(),
            origin: self.origin.clone(),
            keys: current.keys.clone(),
            state: serde_json::from_slice(&current.state.encode().map_err(|_| invalid())?)
                .map_err(|_| invalid())?,
        };
        let bytes = serde_json::to_vec(&journal).map_err(|_| invalid())?;
        if bytes.len() as u64 > MAX_JOURNAL {
            return Err(invalid());
        }
        let temp = self
            .path
            .with_extension(format!("{}.tmp", crate::random_hex(16)));
        let mut options = std::fs::OpenOptions::new();
        options.create_new(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let result = (|| -> std::io::Result<()> {
            let mut file = options.open(&temp)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            std::fs::rename(&temp, &self.path)?;
            #[cfg(unix)]
            std::fs::File::open(self.path.parent().unwrap())?.sync_all()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(&temp);
        }
        result.map_err(|_| {
            self.persistence_failed.store(true, Ordering::Release);
            self.updates
                .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
            "Unable to persist revocation floor; restart required after repair".into()
        })
    }
    async fn refresh(&self, control: &Control) -> Result<u64, String> {
        let trust = control.kill_keys().await.map_err(|e| e.to_string())?;
        let bytes = control.kill_state(None).await.map_err(|e| e.to_string())?;
        self.install_full(trust, &bytes)
    }
    // Private: only refresh() may call this with network input, directly from authenticated Control.
    fn install_full(&self, trust: KillTrustDocument, bytes: &[u8]) -> Result<u64, String> {
        if self.persistence_failed.load(Ordering::Acquire) || trust.tenant_id != self.tenant {
            return Err(invalid());
        }
        let verifier = Verifier::new(&self.tenant, &trust.keys).map_err(|_| invalid())?;
        let fetch = verifier.verify_full_fetch(bytes).map_err(|_| invalid())?;
        let mut guard = self.current.lock().unwrap_or_else(|e| e.into_inner());
        let mut state = guard
            .as_ref()
            .map(|current| current.state.clone())
            .unwrap_or_else(|| State::empty(&verifier));
        state.replace_trusted_full(&fetch).map_err(|_| invalid())?;
        if state.epoch() < trust.authorization_epoch {
            return Err(invalid());
        }
        let epoch = state.epoch();
        let next = Current {
            verifier,
            keys: trust.keys,
            state,
        };
        self.save(&next)?;
        self.executions
            .lock()
            .map_err(|_| invalid())?
            .observe(&next.state);
        *guard = Some(next);
        self.updates
            .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
        Ok(epoch)
    }
    /// Relays convey signed facts, never trust roots or a complete-history
    /// replacement. Only the direct authenticated Control fetch may prune.
    pub(crate) fn relay(&self, bytes: &[u8]) -> Result<u64, String> {
        if !self.ready() {
            return Err(invalid());
        }
        let mut guard = self.current.lock().map_err(|_| invalid())?;
        let current = guard.as_ref().ok_or_else(invalid)?;
        let push = current.verifier.verify_push(bytes).map_err(|_| invalid())?;
        let mut next = Current {
            verifier: Verifier::new(&self.tenant, &current.keys).map_err(|_| invalid())?,
            keys: current.keys.clone(),
            state: current.state.clone(),
        };
        next.state.merge(&push).map_err(|_| invalid())?;
        if push.epoch() < current.state.epoch() {
            tracing::info!(tenant=%self.tenant, authorization_epoch=push.epoch(),
                retained_epoch=current.state.epoch(), "Stale signed kill relay retained existing negative authority");
        }
        self.save(&next)?;
        self.executions
            .lock()
            .map_err(|_| invalid())?
            .observe(&next.state);
        let epoch = next.state.epoch();
        *guard = Some(next);
        self.updates
            .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
        Ok(epoch)
    }
    async fn poll(&self, control: &Control) -> Result<u64, String> {
        let epoch = self
            .current
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .ok_or_else(invalid)?
            .state
            .epoch();
        let bytes = control
            .kill_state(Some(epoch))
            .await
            .map_err(|e| e.to_string())?;
        let mut guard = self.current.lock().unwrap_or_else(|e| e.into_inner());
        let current = guard.as_ref().ok_or_else(invalid)?;
        // This response is complete and comes directly from authenticated fixed-origin HTTPS.
        // Relayed pushes never use full replacement.
        let push = current
            .verifier
            .verify_full_fetch(&bytes)
            .map_err(|_| invalid())?;
        let mut next = Current {
            verifier: Verifier::new(&self.tenant, &current.keys).map_err(|_| invalid())?,
            keys: current.keys.clone(),
            state: current.state.clone(),
        };
        next.state.replace_full(&push).map_err(|_| invalid())?;
        let next_epoch = next.state.epoch();
        if next.state.encode().map_err(|_| invalid())?
            != current.state.encode().map_err(|_| invalid())?
        {
            self.save(&next)?;
            self.executions
                .lock()
                .map_err(|_| invalid())?
                .observe(&next.state);
            *guard = Some(next);
            self.updates
                .send_modify(|generation| *generation = generation.wrapping_add(1).max(1));
        }
        Ok(next_epoch)
    }
    pub(crate) async fn run(&self, control: &Control) {
        let mut delivery_failed = false;
        loop {
            let result = if self.ready() {
                self.poll(control).await
            } else {
                self.refresh(control).await
            };
            match result {
                Ok(epoch) => {
                    if delivery_failed {
                        tracing::info!(tenant = %self.tenant, "Revocation delivery recovered");
                    }
                    delivery_failed = false;
                    let _ = control.kill_ack(epoch).await;
                }
                Err(_) => {
                    if !delivery_failed {
                        tracing::warn!(tenant = %self.tenant, "Revocation delivery rejected or unavailable; retaining the persisted floor");
                    }
                    delivery_failed = true;
                    // A failed or rotated push cannot change trusted state. Refresh directly,
                    // preserving the disk floor; heartbeat/watchdog continue independently.
                    if let Ok(epoch) = self.refresh(control).await {
                        let _ = control.kill_ack(epoch).await;
                    }
                    tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                }
            }
        }
    }
    pub(crate) async fn terminated(&self, execution: &Execution) {
        let mut updates = self.updates.subscribe();
        loop {
            if self.persistence_failed.load(Ordering::Acquire) {
                return;
            }
            if execution.terminated() {
                return;
            }
            if updates.changed().await.is_err() {
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
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
    const TENANT: &str = "10000000-0000-4000-8000-000000000001";
    const ORIGIN: &str = "https://control.example.test";
    fn key() -> &'static RsaPrivateKey {
        static KEY: OnceLock<RsaPrivateKey> = OnceLock::new();
        KEY.get_or_init(|| RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap())
    }
    fn trust(epoch: u64) -> KillTrustDocument {
        KillTrustDocument {
            tenant_id: TENANT.into(),
            authorization_epoch: epoch,
            keys: vec![KillTrustKey {
                kid: "organization".into(),
                n: URL_SAFE_NO_PAD.encode(key().n().to_bytes_be()),
                e: URL_SAFE_NO_PAD.encode(key().e().to_bytes_be()),
            }],
        }
    }
    fn signed(mut value: Value, typ: &str) -> Value {
        let message = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(
                serde_json::to_vec(&json!({"alg":"RS256","typ":typ,"kid":"organization"})).unwrap()
            ),
            URL_SAFE_NO_PAD.encode(toolgate_cli::canonical::canonical_bytes(&value).unwrap())
        );
        let signature =
            SigningKey::<rsa::sha2::Sha256>::new(key().clone()).sign(message.as_bytes());
        value["signature"] =
            format!("{message}.{}", URL_SAFE_NO_PAD.encode(signature.to_vec())).into();
        value
    }
    fn push(epoch: u64, mode: &str, pool: &str) -> Vec<u8> {
        let kill = signed(
            json!({"eventId":format!("control:{epoch}"),"issuer":"control",
            "tenantId":TENANT,"authorizationEpoch":epoch,"scope":{"kind":"POOL","value":pool},
            "mode":mode,"reason":"incident","issuedAtUnixMs":1000}),
            "toolgate-kill-event+jws",
        );
        serde_json::to_vec(&signed(
            json!({"tenantId":TENANT,"authorizationEpoch":epoch,
            "kills":[kill],"recoveries":[]}),
            "toolgate-kill-push+jws",
        ))
        .unwrap()
    }
    fn root() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "toolgate-kills-{}-{}",
            std::process::id(),
            crate::random_hex(8)
        ));
        std::fs::create_dir_all(&path).unwrap();
        path
    }
    fn scope(pool: Option<&str>) -> KillExecutionScope {
        KillExecutionScope {
            package_id: Some("acme.orders".into()),
            package_digest: format!("sha256:{}", "a".repeat(64)),
            tool_id: "acme.orders/refund".into(),
            publisher: Some("acme".into()),
            pool_id: pool.map(Into::into),
            image_digest: Some("".into()),
        }
    }
    fn execution(supervisor: &Supervisor, scope: &KillExecutionScope) -> Execution {
        let current = supervisor.current.lock().unwrap();
        let current = current.as_ref().unwrap();
        let mut executions = supervisor.executions.lock().unwrap();
        let execution = executions.register(&State::empty(&current.verifier), subject(scope));
        execution.accept(0, subject(scope)).unwrap();
        executions.observe(&current.state);
        execution
    }
    #[tokio::test]
    async fn durable_floor_precedes_readiness_and_restart_needs_a_current_fetch() {
        let root = root();
        let supervisor = Supervisor::open(TENANT, ORIGIN, &root).unwrap();
        assert!(!supervisor.ready() && !supervisor.permits_epoch(1));
        supervisor
            .install_full(trust(1), &push(1, "TERMINATE", "frozen-pool"))
            .unwrap();
        assert!(supervisor.ready() && supervisor.permits_epoch(1) && !supervisor.permits_epoch(0));
        let bytes = std::fs::read(&supervisor.path).unwrap();
        let restored = Supervisor::open(TENANT, ORIGIN, &root).unwrap();
        assert!(!restored.ready());
        assert!(restored
            .install_full(trust(0), &push(0, "TERMINATE", "frozen-pool"))
            .is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), bytes);
        assert!(!restored.ready());
        assert!(Supervisor::open(TENANT, "https://other.example.test", &root).is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), bytes);
        restored
            .install_full(trust(1), &push(1, "TERMINATE", "frozen-pool"))
            .unwrap();
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            restored.terminated(&execution(&restored, &scope(Some("frozen-pool")))),
        )
        .await
        .unwrap();
        assert!(tokio::time::timeout(
            std::time::Duration::from_millis(20),
            restored.terminated(&execution(&restored, &scope(Some("moved-pool"))))
        )
        .await
        .is_err());
        // Unknown placement matches conservatively; an empty known pool does not.
        tokio::time::timeout(
            std::time::Duration::from_millis(100),
            restored.terminated(&execution(&restored, &scope(None))),
        )
        .await
        .unwrap();
        assert!(tokio::time::timeout(
            std::time::Duration::from_millis(20),
            restored.terminated(&execution(&restored, &scope(Some(""))))
        )
        .await
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn relayed_signed_state_merges_with_direct_keys_and_cannot_prune_or_reset_termination() {
        let root = root();
        let supervisor = Supervisor::open(TENANT, ORIGIN, &root).unwrap();
        assert!(supervisor
            .relay(&push(1, "TERMINATE", "frozen-pool"))
            .is_err());
        supervisor
            .install_full(trust(1), &push(1, "STOP_NEW", "frozen-pool"))
            .unwrap();
        let execution = supervisor
            .register(subject(&scope(Some("frozen-pool"))))
            .unwrap();
        execution
            .accept(1, subject(&scope(Some("frozen-pool"))))
            .unwrap();
        supervisor
            .relay(&push(2, "TERMINATE", "frozen-pool"))
            .unwrap();
        assert!(execution.terminated());
        let durable = std::fs::read(&supervisor.path).unwrap();
        let mut corrupt: serde_json::Value =
            serde_json::from_slice(&push(3, "TERMINATE", "frozen-pool")).unwrap();
        corrupt["authorizationEpoch"] = 4.into();
        assert!(supervisor
            .relay(&serde_json::to_vec(&corrupt).unwrap())
            .is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), durable);
        // A signed empty relay is still not an authenticated direct full fetch.
        let empty = serde_json::to_vec(&signed(
            json!({"tenantId":TENANT,"authorizationEpoch":3,"kills":[],"recoveries":[]}),
            "toolgate-kill-push+jws",
        ))
        .unwrap();
        supervisor.relay(&empty).unwrap();
        let before_stale = std::fs::read(&supervisor.path).unwrap();
        supervisor
            .relay(&push(1, "STOP_NEW", "frozen-pool"))
            .unwrap();
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), before_stale);
        assert_eq!(
            supervisor
                .current
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .state
                .mode(&subject(&scope(Some("frozen-pool")))),
            Some(olo_toolgate_contracts::v2::KillMode::Terminate)
        );
        assert!(execution.terminated());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn stop_new_never_terminates_running_work_and_matching_update_wakes_waiter() {
        let root = root();
        let supervisor = Supervisor::open(TENANT, ORIGIN, &root).unwrap();
        supervisor
            .install_full(trust(1), &push(1, "STOP_NEW", "frozen-pool"))
            .unwrap();
        let scope = scope(Some("frozen-pool"));
        let execution = execution(&supervisor, &scope);
        let watch = supervisor.terminated(&execution);
        tokio::pin!(watch);
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(20), &mut watch)
                .await
                .is_err()
        );
        supervisor
            .install_full(trust(2), &push(2, "TERMINATE", "frozen-pool"))
            .unwrap();
        tokio::time::timeout(std::time::Duration::from_millis(100), &mut watch)
            .await
            .unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn failed_persistence_or_foreign_and_obsolete_state_never_advances_or_converts() {
        let root = root();
        let supervisor = Supervisor::open(TENANT, ORIGIN, &root).unwrap();
        supervisor
            .install_full(trust(1), &push(1, "STOP_NEW", "frozen-pool"))
            .unwrap();
        let bytes = std::fs::read(&supervisor.path).unwrap();
        let mut foreign = trust(2);
        foreign.tenant_id = "20000000-0000-4000-8000-000000000001".into();
        assert!(supervisor
            .install_full(foreign, &push(2, "TERMINATE", "frozen-pool"))
            .is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), bytes);
        std::fs::remove_file(&supervisor.path).unwrap();
        std::fs::create_dir(&supervisor.path).unwrap(); // rename cannot replace a directory
        assert!(supervisor
            .install_full(trust(2), &push(2, "TERMINATE", "frozen-pool"))
            .is_err());
        assert!(!supervisor.ready() && !supervisor.permits_epoch(1));
        assert_eq!(
            supervisor
                .current
                .lock()
                .unwrap()
                .as_ref()
                .unwrap()
                .state
                .epoch(),
            1
        );
        std::fs::remove_dir(&supervisor.path).unwrap();
        let obsolete = br#"{"version":0}"#;
        std::fs::write(&supervisor.path, obsolete).unwrap();
        assert!(Supervisor::open(TENANT, ORIGIN, &root).is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), obsolete);
        std::fs::remove_dir_all(root).unwrap();
    }
    // Real TLS transport with independently pinned server and client certificates. This
    // exercises the host client, not Control's application-level enrollment verifier.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn fixed_origin_mtls_delivery_persists_before_ack_and_rejects_rollback_and_partition() {
        use std::io::Read;
        use std::sync::{
            atomic::{AtomicBool, Ordering},
            Arc,
        };
        use std::time::Duration;
        let _ = rustls::crypto::ring::default_provider().install_default();
        let root = root();
        let server = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let client = rcgen::generate_simple_self_signed(vec!["executor.test".into()]).unwrap();
        let mut roots = rustls::RootCertStore::empty();
        roots.add(client.cert.der().clone()).unwrap();
        let accepted = client.cert.der().to_vec();
        let verifier = rustls::server::WebPkiClientVerifier::builder(Arc::new(roots))
            .build()
            .unwrap();
        let tls = Arc::new(
            rustls::ServerConfig::builder()
                .with_client_cert_verifier(verifier)
                .with_single_cert(
                    vec![server.cert.der().clone()],
                    rustls::pki_types::PrivateKeyDer::Pkcs8(
                        rustls::pki_types::PrivatePkcs8KeyDer::from(
                            server.signing_key.serialize_der(),
                        ),
                    ),
                )
                .unwrap(),
        );
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let origin = format!(
            "https://localhost:{}",
            listener.local_addr().unwrap().port()
        );
        let state = Arc::new(Mutex::new((
            1u64,
            push(1, "STOP_NEW", "frozen-pool"),
            false,
            Vec::<Value>::new(),
            0usize,
        )));
        let script = state.clone();
        let done = Arc::new(AtomicBool::new(false));
        let finished = done.clone();
        let thread = std::thread::spawn(move || {
            let mut handlers = Vec::new();
            while !finished.load(Ordering::Acquire) {
                match listener.accept() {
                    Ok((socket, _)) => {
                        let tls = tls.clone();
                        let script = script.clone();
                        let accepted = accepted.clone();
                        handlers.push(std::thread::spawn(move||{
                            socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                            socket.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
                            let mut stream=rustls::StreamOwned::new(rustls::ServerConnection::new(tls).unwrap(),socket);
                            let mut head=Vec::new();let mut byte=[0u8;1];
                            while !head.ends_with(b"\r\n\r\n") {
                                if stream.read_exact(&mut byte).is_err() {return;}
                                head.push(byte[0]);if head.len()>16384 {return;}
                            }
                            assert_eq!(stream.conn.peer_certificates().unwrap()[0].as_ref(),accepted);
                            let head=String::from_utf8(head).unwrap();
                            let path=head.split_whitespace().nth(1).unwrap();
                            assert!(head.to_ascii_lowercase().contains(&format!("toolgate-tenant-id: {TENANT}")));
                            let length=head.lines().find_map(|line|{
                                let (name,value)=line.split_once(':')?;
                                name.eq_ignore_ascii_case("content-length").then(||value.trim().parse::<usize>().unwrap())
                            }).unwrap_or(0);
                            assert!(length<=4096);let mut body=vec![0u8;length];
                            stream.read_exact(&mut body).unwrap();
                            let (code,body)={
                                let mut script=script.lock().unwrap();script.4+=1;
                                if script.2 {(503,b"{}".to_vec())}
                                else if path.ends_with("/keys") {(200,serde_json::to_vec(&trust(script.0)).unwrap())}
                                else if path.ends_with("/acknowledgements") {
                                    script.3.push(serde_json::from_slice(&body).unwrap());(200,b"{}".to_vec())
                                } else {(200,script.1.clone())}
                            };
                            let response=format!("HTTP/1.1 {code} Result\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n",body.len());
                            let _=stream.write_all(response.as_bytes());let _=stream.write_all(&body);let _=stream.flush();
                        }));
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(2))
                    }
                    Err(_) => break,
                }
            }
            for handler in handlers {
                handler.join().unwrap();
            }
        });
        let ca = root.join("server.pem");
        std::fs::write(&ca, server.cert.pem()).unwrap();
        let config = crate::config::ControlConfig {
            identity_trust_directory: None,
            tenant_id: TENANT.into(),
            url: origin.clone(),
            client_certificate_path: None,
            client_key_path: None,
            ca_certificate_path: Some(ca),
            development_loopback_http: false,
        };
        let control = Control::new(&config).unwrap();
        assert!(control.kill_keys().await.is_err()); // no client certificate
        assert_eq!(state.lock().unwrap().4, 0);
        let wrong = rcgen::generate_simple_self_signed(vec!["other.test".into()]).unwrap();
        control
            .use_identity(
                format!("{}{}", wrong.cert.pem(), wrong.signing_key.serialize_pem()).as_bytes(),
            )
            .unwrap();
        assert!(control.kill_keys().await.is_err());
        assert_eq!(state.lock().unwrap().4, 0);
        control
            .use_identity(
                format!(
                    "{}{}",
                    client.cert.pem(),
                    client.signing_key.serialize_pem()
                )
                .as_bytes(),
            )
            .unwrap();
        let supervisor = Supervisor::open(TENANT, &origin, &root).unwrap();
        assert_eq!(supervisor.refresh(&control).await.unwrap(), 1);
        assert!(supervisor.path.is_file() && supervisor.ready());
        control.kill_ack(1).await.unwrap();
        assert_eq!(
            state.lock().unwrap().3[0],
            json!({"authorizationEpoch":1,"deliveryPath":"POLL"})
        );
        let scope = scope(Some("frozen-pool"));
        let execution = execution(&supervisor, &scope);
        let termination = supervisor.terminated(&execution);
        tokio::pin!(termination);
        assert!(
            tokio::time::timeout(Duration::from_millis(20), &mut termination)
                .await
                .is_err()
        );
        {
            let mut script = state.lock().unwrap();
            script.0 = 2;
            script.1 = push(2, "TERMINATE", "frozen-pool");
        }
        assert_eq!(supervisor.poll(&control).await.unwrap(), 2);
        tokio::time::timeout(Duration::from_millis(100), &mut termination)
            .await
            .unwrap();
        let floor = std::fs::read(&supervisor.path).unwrap();
        {
            let mut script = state.lock().unwrap();
            script.0 = 1;
            script.1 = push(1, "STOP_NEW", "frozen-pool");
        }
        assert!(supervisor.refresh(&control).await.is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), floor);
        state.lock().unwrap().2 = true;
        assert!(supervisor.poll(&control).await.is_err());
        assert_eq!(std::fs::read(&supervisor.path).unwrap(), floor);
        assert!(!supervisor.permits_epoch(1));
        // A recovery may be archived during a partition. Only a direct current
        // complete snapshot may remove the old kill, retaining the durable floor.
        {
            let mut script = state.lock().unwrap();
            script.0 = 3;
            script.1 = serde_json::to_vec(&signed(
                json!({"tenantId":TENANT,"authorizationEpoch":3,"kills":[],"recoveries":[]}),
                "toolgate-kill-push+jws",
            ))
            .unwrap();
            script.2 = false;
        }
        assert_eq!(supervisor.poll(&control).await.unwrap(), 3);
        assert!(supervisor.permits_epoch(3) && !supervisor.permits_epoch(2));
        assert!(tokio::time::timeout(
            Duration::from_millis(100),
            supervisor.terminated(&execution)
        )
        .await
        .is_ok());
        let restored = Supervisor::open(TENANT, &origin, &root).unwrap();
        assert!(!restored.ready());
        assert_eq!(restored.refresh(&control).await.unwrap(), 3);
        assert!(tokio::time::timeout(
            Duration::from_millis(20),
            restored.terminated(&restored.register(subject(&scope)).unwrap())
        )
        .await
        .is_err());
        {
            let mut script = state.lock().unwrap();
            script.0 = 2;
            script.1 = push(2, "TERMINATE", "frozen-pool");
        }
        let archived_floor = std::fs::read(&restored.path).unwrap();
        assert!(restored.poll(&control).await.is_err());
        assert_eq!(std::fs::read(&restored.path).unwrap(), archived_floor);
        assert!(restored.permits_epoch(3) && !restored.permits_epoch(2));
        done.store(true, Ordering::Release);
        tokio::task::spawn_blocking(move || thread.join().unwrap())
            .await
            .unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
}
