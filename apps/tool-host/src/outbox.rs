// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Durable, at-least-once result delivery. Replaying evidence never replays a tool.
use olo_toolgate_contracts::v2::InvocationResultReport;
use std::io::Write;
use std::path::{Path, PathBuf};

pub struct Outbox {
    directory: PathBuf,
}
impl Outbox {
    /// Startup only: a consumed admission left by a crashed supervisor is evidence
    /// of uncertainty. It never causes the worker to be launched again.
    pub fn recover_into(&self, results: &Outbox) -> Result<(), String> {
        for entry in std::fs::read_dir(&self.directory).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let report = read_report(&path)?;
            results.enqueue(&report)?;
            self.acknowledge(&path)?;
        }
        Ok(())
    }
    pub fn remove_report(&self, report: &InvocationResultReport) -> Result<(), String> {
        let bytes = serde_json::to_vec(report).map_err(|e| e.to_string())?;
        let digest = toolgate_cli::canonical::sha256_tagged(&bytes);
        self.acknowledge(&self.directory.join(format!("{}.json", &digest[7..])))
    }
    pub fn open(directory: PathBuf) -> Result<Self, String> {
        std::fs::create_dir_all(&directory).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))
                .map_err(|e| e.to_string())?;
        }
        sync(&directory).map_err(|e| e.to_string())?;
        if let Some(parent) = directory.parent() {
            sync(parent).map_err(|e| e.to_string())?;
        }
        Ok(Self { directory })
    }
    pub fn enqueue(&self, report: &InvocationResultReport) -> Result<PathBuf, String> {
        let bytes = serde_json::to_vec(report).map_err(|e| e.to_string())?;
        let digest = toolgate_cli::canonical::sha256_tagged(&bytes);
        let path = self.directory.join(format!("{}.json", &digest[7..]));
        let temp = self
            .directory
            .join(format!("{}.tmp", crate::random_hex(16)));
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let write = || -> std::io::Result<()> {
            let mut f = options.open(&temp)?;
            f.write_all(&bytes)?;
            f.sync_all()?;
            std::fs::rename(&temp, &path)?;
            sync(&self.directory)
        };
        if let Err(e) = write() {
            let _ = std::fs::remove_file(&temp);
            return Err(e.to_string());
        }
        Ok(path)
    }
    pub fn acknowledge(&self, path: &Path) -> Result<(), String> {
        match std::fs::remove_file(path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.to_string()),
        }
        sync(&self.directory).map_err(|e| e.to_string())
    }
    pub async fn drain(&self, control: &crate::control::Control) -> Result<(), String> {
        let entries = std::fs::read_dir(&self.directory).map_err(|e| e.to_string())?;
        for entry in entries {
            let path = entry.map_err(|e| e.to_string())?.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let report = match read_report(&path) {
                Ok(report) => report,
                Err(_) if !path.exists() => continue,
                Err(e) => return Err(e),
            };
            if control.report(&report).await.is_ok() {
                self.acknowledge(&path)?;
            }
        }
        Ok(())
    }
}
fn read_report(path: &Path) -> Result<InvocationResultReport, String> {
    let metadata = std::fs::symlink_metadata(path).map_err(|e| e.to_string())?;
    if !metadata.is_file() || metadata.len() > 5 * 1024 * 1024 {
        return Err("invalid result outbox file".into());
    }
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    let expected = format!(
        "{}.json",
        &toolgate_cli::canonical::sha256_tagged(&bytes)[7..]
    );
    if path.file_name().and_then(|n| n.to_str()) != Some(&expected) {
        return Err("result outbox checksum mismatch".into());
    }
    serde_json::from_slice(&bytes).map_err(|e| e.to_string())
}
fn sync(directory: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    std::fs::File::open(directory)?.sync_all()?;
    let _ = directory;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn corrupted_admission_is_retained_and_refuses_recovery() {
        let root =
            std::env::temp_dir().join(format!("toolgate-corrupt-spool-{}", crate::random_hex(16)));
        let spool = Outbox::open(root.join("admissions")).unwrap();
        let results = Outbox::open(root.join("results")).unwrap();
        let report: InvocationResultReport = serde_json::from_value(
            serde_json::json!({"invocationId":"i","fencingToken":"f","outcome":"OUTCOME_UNKNOWN","executionTier":"HOST_NATIVE"}),
        )
        .unwrap();
        let admission = spool.enqueue(&report).unwrap();
        std::fs::write(&admission, b"{}").unwrap();
        assert!(spool.recover_into(&results).is_err());
        assert!(admission.exists());
        assert_eq!(std::fs::read_dir(root.join("results")).unwrap().count(), 0);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn old_incomplete_reports_are_retained_without_conversion() {
        let root =
            std::env::temp_dir().join(format!("toolgate-old-spool-{}", crate::random_hex(16)));
        let spool = Outbox::open(root.join("admissions")).unwrap();
        let results = Outbox::open(root.join("results")).unwrap();
        let bytes = br#"{"invocationId":"i","fencingToken":"f","outcome":"OUTCOME_UNKNOWN"}"#;
        let digest = toolgate_cli::canonical::sha256_tagged(bytes);
        let path = root
            .join("admissions")
            .join(format!("{}.json", &digest[7..]));
        std::fs::write(&path, bytes).unwrap();
        assert!(spool.recover_into(&results).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), bytes);
        assert_eq!(std::fs::read_dir(root.join("results")).unwrap().count(), 0);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn lost_delivery_is_retried_with_identical_evidence_after_reopen() {
        use axum::{extract::State, http::StatusCode, routing::post, Json, Router};
        let seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::<serde_json::Value>::new()));
        async fn receive(
            State(seen): State<std::sync::Arc<std::sync::Mutex<Vec<serde_json::Value>>>>,
            Json(report): Json<serde_json::Value>,
        ) -> (StatusCode, Json<serde_json::Value>) {
            let mut seen = seen.lock().unwrap();
            seen.push(report);
            (
                if seen.len() == 1 {
                    StatusCode::SERVICE_UNAVAILABLE
                } else {
                    StatusCode::OK
                },
                Json(serde_json::json!({})),
            )
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let app = Router::new()
            .route("/api/control/v1/access/effects/report", post(receive))
            .with_state(seen.clone());
        let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        let control = crate::control::Control::new(&crate::config::ControlConfig {
            tenant_id: "10000000-0000-4000-8000-000000000001".into(),
            url: format!("http://127.0.0.1:{port}"),
            identity_trust_directory: None,
            client_certificate_path: None,
            client_key_path: None,
            ca_certificate_path: None,
            development_loopback_http: true,
        })
        .unwrap();
        let root =
            std::env::temp_dir().join(format!("toolgate-outbox-retry-{}", crate::random_hex(16)));
        let report: InvocationResultReport = serde_json::from_value(
            serde_json::json!({"invocationId":"i","fencingToken":"f","outcome":"OUTCOME_UNKNOWN","executionTier":"HOST_NATIVE"}),
        )
        .unwrap();
        let box1 = Outbox::open(root.clone()).unwrap();
        let path = box1.enqueue(&report).unwrap();
        box1.drain(&control).await.unwrap();
        assert!(path.exists());
        drop(box1);
        Outbox::open(root.clone())
            .unwrap()
            .drain(&control)
            .await
            .unwrap();
        assert!(!path.exists());
        let deliveries = seen.lock().unwrap();
        assert_eq!(deliveries.len(), 2);
        assert_eq!(deliveries[0], deliveries[1]);
        drop(deliveries);
        server.abort();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn interrupted_admission_becomes_uncertainty_evidence_without_reexecution() {
        let root = std::env::temp_dir().join(format!("toolgate-spool-{}", crate::random_hex(16)));
        let spool = Outbox::open(root.join("admissions")).unwrap();
        let results = Outbox::open(root.join("results")).unwrap();
        let report: InvocationResultReport = serde_json::from_value(
            serde_json::json!({"invocationId":"i","fencingToken":"f","outcome":"OUTCOME_UNKNOWN","executionTier":"HOST_NATIVE"}),
        )
        .unwrap();
        let admission = spool.enqueue(&report).unwrap();
        spool.recover_into(&results).unwrap();
        assert!(!admission.exists());
        let entries: Vec<_> = std::fs::read_dir(root.join("results")).unwrap().collect();
        assert_eq!(entries.len(), 1);
        let recovered: InvocationResultReport =
            serde_json::from_slice(&std::fs::read(entries[0].as_ref().unwrap().path()).unwrap())
                .unwrap();
        assert_eq!(report, recovered);
        spool.recover_into(&results).unwrap();
        assert_eq!(std::fs::read_dir(root.join("results")).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn report_survives_reopen_and_acknowledgement_is_idempotent() {
        let root = std::env::temp_dir().join(format!("toolgate-outbox-{}", crate::random_hex(16)));
        let report: InvocationResultReport = serde_json::from_value(
            serde_json::json!({"invocationId":"i","fencingToken":"f","outcome":"OUTCOME_UNKNOWN","executionTier":"HOST_NATIVE"}),
        )
        .unwrap();
        let path = Outbox::open(root.clone())
            .unwrap()
            .enqueue(&report)
            .unwrap();
        let reopened = Outbox::open(root.clone()).unwrap();
        assert_eq!(
            serde_json::from_slice::<InvocationResultReport>(&std::fs::read(&path).unwrap())
                .unwrap(),
            report
        );
        assert_eq!(reopened.enqueue(&report).unwrap(), path);
        reopened.acknowledge(&path).unwrap();
        reopened.acknowledge(&path).unwrap();
        assert!(!path.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
}
