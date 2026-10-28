// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Protected continuity for an operator-provisioned workload certificate.
use crate::config::ControlConfig;
use olo_toolgate_contracts::v2::TenantDiscovery;
use olo_toolgate_tenant_identity::Trust;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Authority {
    format_version: u64,
    tenant_id: String,
    server_url: String,
    manifest: Option<TenantDiscovery>,
    trust: Option<Trust>,
}
pub(crate) struct Workload {
    pub certificate: String,
    pub fingerprint: String,
    pub host_id: String,
    path: PathBuf,
    state: tokio::sync::Mutex<Authority>,
}
impl Workload {
    pub fn open(config: &ControlConfig, certificate: String, key: &str) -> Result<Self, String> {
        use rcgen::PublicKeyData;
        let origin = olo_toolgate_tenant_identity::origin(&config.url)
            .map_err(|_| "HTTPS workload identity origin required")?;
        let root = config
            .identity_trust_directory
            .as_ref()
            .ok_or("identityTrustDirectory required for a preprovisioned certificate")?;
        if !root.is_absolute() || root.is_symlink() {
            return Err("Protected workload trust directory required".into());
        }
        let mut directory = std::fs::DirBuilder::new();
        directory.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            directory.mode(0o700);
        }
        directory
            .create(root)
            .map_err(|_| "Workload trust directory unavailable")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            let metadata =
                std::fs::metadata(root).map_err(|_| "Workload trust directory unavailable")?;
            if metadata.mode() & 0o077 != 0 {
                return Err("Private workload trust directory required".into());
            }
        }
        let path = root.join("authority.json");
        if path.is_symlink() {
            return Err("Workload trust state cannot be a symlink".into());
        }
        let state: Authority = if path.exists() {
            let bytes = read(&path, 131072)?;
            serde_json::from_slice(&bytes).map_err(|_| "Unsupported workload trust state")?
        } else {
            Authority {
                format_version: 1,
                tenant_id: config.tenant_id.clone(),
                server_url: origin.clone(),
                manifest: None,
                trust: None,
            }
        };
        if state.format_version != 1
            || state.tenant_id != config.tenant_id
            || state.server_url != origin
            || state.manifest.is_some() != state.trust.is_some()
            || state.manifest.as_ref().is_some_and(|manifest| {
                manifest.tenant_id != state.tenant_id || manifest.control_url != state.server_url
            })
            || state.trust.as_ref().is_some_and(|trust| {
                trust.tenant_id != state.tenant_id
                    || state.manifest.as_ref().is_none_or(|manifest| {
                        trust.version != manifest.key_set_version
                            || trust.issued_at != manifest.issued_at_unix_ms
                    })
            })
        {
            return Err("Workload tenant binding mismatch".into());
        }
        let der = olo_toolgate_tenant_identity::certificate_der(&certificate)
            .map_err(|_| "Workload certificate required")?;
        let (_, parsed) = x509_parser::parse_x509_certificate(&der)
            .map_err(|_| "Workload certificate required")?;
        let names: Vec<_> = parsed.subject().iter_common_name().collect();
        if names.len() != 1 {
            return Err("Workload host identity required".into());
        }
        let host_id = names[0]
            .as_str()
            .map_err(|_| "Workload host identity required")?
            .to_owned();
        let key = rcgen::KeyPair::from_pem(key).map_err(|_| "Workload private key required")?;
        let public = key.subject_public_key_info();
        if parsed.public_key().raw != public {
            return Err("Workload certificate/key mismatch".into());
        }
        write(
            &path,
            &serde_json::to_vec(&state).map_err(|_| "Workload trust serialization failed")?,
        )?;
        Ok(Self {
            certificate,
            fingerprint: olo_toolgate_tenant_identity::digest(&public),
            host_id,
            path,
            state: tokio::sync::Mutex::new(state),
        })
    }
    pub async fn qualify(&self, control: &crate::control::Control) -> Result<(), String> {
        let mut state = self.state.lock().await;
        let now = crate::now_ms();
        if state
            .manifest
            .as_ref()
            .is_none_or(|manifest| manifest.expires_at_unix_ms <= now.saturating_add(60000))
        {
            let bytes = control
                .discovery()
                .await
                .map_err(|_| "Workload tenant discovery unavailable")?;
            let verified = olo_toolgate_tenant_identity::verify_https_refresh(
                &bytes,
                &state.server_url,
                &state.tenant_id,
                state.trust.as_ref(),
                crate::now_ms(),
            )
            .map_err(|_| "Workload tenant discovery rejected")?;
            if verified.manifest.control_url != state.server_url {
                return Err("Workload Control origin mismatch".into());
            }
            state.manifest = Some(verified.manifest);
            state.trust = Some(verified.trust);
            write(
                &self.path,
                &serde_json::to_vec(&*state).map_err(|_| "Workload trust serialization failed")?,
            )?;
        }
        olo_toolgate_tenant_identity::verify_workload_certificate(
            &self.certificate,
            state
                .manifest
                .as_ref()
                .ok_or("Workload discovery required")?,
            &self.fingerprint,
            &self.host_id,
            crate::now_ms(),
        )
        .map_err(|_| "Workload tenant certificate rejected".into())
    }
}
fn read(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    use std::io::Read;
    let file = std::fs::File::open(path).map_err(|_| "Workload trust state unavailable")?;
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Workload trust state unavailable")?;
    if bytes.len() > limit {
        return Err("Workload trust state too large".into());
    }
    Ok(bytes)
}
fn write(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    let partial = path.with_file_name(format!(".authority-{}", crate::random_hex(8)));
    let mut options = std::fs::OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(&partial)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        std::fs::rename(&partial, path)
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&partial);
    }
    result.map_err(|_| "Workload trust state unavailable".into())
}
