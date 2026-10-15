// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! The Tool Host configuration file (`TOOLGATE_TOOL_HOST_CONFIG`), validated before anything starts.

use serde::Deserialize;
use std::net::SocketAddr;
use std::path::PathBuf;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    /// This host's identity: a permit's `aud` must equal it.
    pub host_id: String,
    /// Where the Gateway dispatches. Loopback only until the mTLS listener arrives with Gateway dispatch.
    pub listen: SocketAddr,
    /// Control's effect-signing keys that may sign permits.
    pub permit_keys: Vec<PermitKey>,
    /// The permit `iss` this host accepts.
    pub permit_issuer: String,
    /// Content-addressed packages, service profiles and globals (README.md, "Content store").
    pub store_dir: PathBuf,
    /// Extracted packages and per-invocation sandboxes.
    pub work_dir: PathBuf,
    pub control: ControlConfig,
    /// Enroll with Control as a device and fetch assignments (README.md, "Enrollment"). Without it
    /// the host presents the configured certificate and runs what is already in the store.
    #[serde(default)]
    pub enrollment: Option<Enrollment>,
    /// Language runtimes by `runtimeId`; `jvm` needs `javaPath`.
    #[serde(default)]
    pub runtimes: Runtimes,
    #[serde(default)]
    pub limits: Limits,
    /// Lets the broker reach `http://` destinations on loopback hosts, for local development and tests only.
    #[serde(default)]
    pub development_loopback_http: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PermitKey {
    pub kid: String,
    /// PEM: a `PUBLIC KEY` (SPKI) or a certificate holding the RSA key.
    pub public_key_path: PathBuf,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ControlConfig {
    /// Control's base URL, such as `https://control:8443`.
    pub url: String,
    /// This host's workload certificate and key (PEM), presented to Control.
    #[serde(default)]
    pub client_certificate_path: Option<PathBuf>,
    #[serde(default)]
    pub client_key_path: Option<PathBuf>,
    /// The CA that issued Control's certificate.
    #[serde(default)]
    pub ca_certificate_path: Option<PathBuf>,
    /// Allows `http://` to a loopback Control, for local development and tests only.
    #[serde(default)]
    pub development_loopback_http: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Enrollment {
    /// Holds this host's device key, its certificate and its check-in sequence.
    pub identity_dir: PathBuf,
    /// The pool this host registers in.
    #[serde(default = "default_pool")]
    pub pool_id: String,
}

fn default_pool() -> String {
    "default".into()
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Runtimes {
    pub java_path: PathBuf,
}

impl Default for Runtimes {
    fn default() -> Self {
        Runtimes {
            java_path: PathBuf::from("java"),
        }
    }
}

/// Tool processes start with an empty environment, so a bare runtime name such as `java` is
/// looked up on the host's own `PATH` once, here. A name that is not found is kept as given and
/// the launch fails with a clear error.
pub fn resolve(program: &std::path::Path) -> PathBuf {
    if program.components().count() != 1 {
        return program.to_path_buf();
    }
    std::env::var_os("PATH")
        .into_iter()
        .flat_map(|path| std::env::split_paths(&path).collect::<Vec<_>>())
        .map(|dir| dir.join(program))
        .find(|candidate| candidate.is_file())
        .unwrap_or_else(|| program.to_path_buf())
}

/// Host limits. Each is capped by the platform ceiling (platform-requirements.md §13.5); a configuration can only lower them.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Limits {
    pub max_concurrent: usize,
    pub max_input_bytes: usize,
    pub max_output_bytes: usize,
    pub sync_deadline_ms: u64,
    pub memory_bytes: u64,
    /// The longest admission window (`exp - iat`) a permit may have.
    pub max_permit_window_ms: u64,
}

pub const CEILING: Limits = Limits {
    max_concurrent: 64,
    max_input_bytes: 1024 * 1024,
    max_output_bytes: 4 * 1024 * 1024,
    sync_deadline_ms: 60_000,
    memory_bytes: 4 * 1024 * 1024 * 1024,
    max_permit_window_ms: 60_000,
};

impl Default for Limits {
    fn default() -> Self {
        Limits {
            max_concurrent: 8,
            memory_bytes: 512 * 1024 * 1024,
            max_permit_window_ms: 30_000,
            ..CEILING
        }
    }
}

impl Config {
    pub fn load(path: &std::path::Path) -> Result<Config, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let mut config: Config =
            serde_json::from_slice(&bytes).map_err(|e| format!("{}: {e}", path.display()))?;
        config.validate()?;
        config.runtimes.java_path = resolve(&config.runtimes.java_path);
        Ok(config)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.host_id.is_empty() || self.permit_issuer.is_empty() || self.permit_keys.is_empty() {
            return Err("hostId, permitIssuer and permitKeys are required".into());
        }
        if !self.listen.ip().is_loopback() {
            return Err(format!(
                "listen {} is not a loopback address; the mTLS listener for remote Gateways is not built yet",
                self.listen
            ));
        }
        let l = &self.limits;
        let c = &CEILING;
        if l.max_concurrent == 0
            || l.max_concurrent > c.max_concurrent
            || l.max_input_bytes > c.max_input_bytes
            || l.max_output_bytes > c.max_output_bytes
            || l.sync_deadline_ms > c.sync_deadline_ms
            || l.memory_bytes > c.memory_bytes
            || l.max_permit_window_ms > c.max_permit_window_ms
        {
            return Err("a limit is above the platform ceiling (platform-requirements.md §13.5)".into());
        }
        let url = &self.control.url;
        if !url.starts_with("https://") {
            let loopback = ["http://127.0.0.1", "http://localhost", "http://[::1]"]
                .iter()
                .any(|p| url.starts_with(p));
            if !(self.control.development_loopback_http && loopback) {
                return Err(format!("control.url {url} must be https, or a loopback http URL with developmentLoopbackHttp"));
            }
        }
        if self.enrollment.is_some() && self.control.client_certificate_path.is_some() {
            return Err(
                "enrollment issues this host's certificate; leave control.clientCertificatePath unset".into(),
            );
        }
        if self.control.client_certificate_path.is_some() != self.control.client_key_path.is_some()
        {
            return Err(
                "control.clientCertificatePath and control.clientKeyPath go together".into(),
            );
        }
        Ok(())
    }
}
