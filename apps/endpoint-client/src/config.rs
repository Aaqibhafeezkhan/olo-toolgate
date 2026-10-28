// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
//! Administrator-owned immutable configuration; local callers cannot change destinations.
use crate::{Failure, Result};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Config {
    /// Administrator-pinned expected UUID; never supplied by an untrusted console as authority.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tenant_id: Option<String>,
    #[serde(default)]
    pub deployment: Option<crate::deployment::Settings>,
    #[serde(default)]
    pub execution: Option<crate::execution::Settings>,
    #[serde(default)]
    pub gateway: Option<crate::tool_gateway::Settings>,
    pub server_url: String,
    pub state_directory: PathBuf,
    pub ipc_endpoint: String,
    pub authorized_peers: Vec<String>,
    pub ca_certificate_path: Option<PathBuf>,
    pub request_timeout_seconds: u64,
    /// Loopback console that published a local gateway's CA, kept for operator-initiated repair.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_console_url: Option<String>,
}
impl Config {
    pub fn validate(&self) -> Result<()> {
        if let Some(tenant) = &self.tenant_id {
            crate::contracts::Contracts::shared()?.encode("TenantUuid", tenant)?;
        }
        if let Some(deployment) = &self.deployment {
            deployment.validate()?;
            if self.execution.is_none() {
                return Err(Failure::Validation);
            }
        }
        if let Some(execution) = &self.execution {
            execution.validate()?;
            if execution.state_directory != self.state_directory.join("runtimes") {
                return Err(Failure::Validation);
            }
        }
        if let Some(gateway) = &self.gateway {
            gateway.validate()?;
        }
        origin(&self.server_url)?;
        if let Some(console) = &self.local_console_url {
            if local_console(console)? != *console || !loopback(&self.server_url) {
                return Err(Failure::Validation);
            }
        }
        if !self.state_directory.is_absolute()
            || self.authorized_peers.is_empty()
            || self.authorized_peers.len() > 32
            || !(1..=15).contains(&self.request_timeout_seconds)
            || self
                .ca_certificate_path
                .as_ref()
                .is_some_and(|p| !p.is_absolute())
        {
            return Err(Failure::Validation);
        }
        #[cfg(unix)]
        {
            if !std::path::Path::new(&self.ipc_endpoint).is_absolute()
                || self
                    .authorized_peers
                    .iter()
                    .any(|p| p.parse::<u32>().is_err())
            {
                return Err(Failure::Validation);
            }
        }
        #[cfg(windows)]
        {
            if self.ipc_endpoint != r"\\.\pipe\olo-toolgate-client"
                || self.authorized_peers.iter().any(|p| {
                    !p.starts_with("S-1-")
                        || !p
                            .bytes()
                            .all(|b| b.is_ascii_digit() || b == b'S' || b == b'-')
                })
            {
                return Err(Failure::Validation);
            }
        }
        Ok(())
    }
    pub fn load(path: &std::path::Path) -> Result<Self> {
        let bytes = crate::storage::read_owned(path, 65536, false)?;
        let config: Self = serde_json::from_slice(&bytes).map_err(|_| Failure::Validation)?;
        config.validate()?;
        Ok(config)
    }
}
/// Preserve exact HTTPS authority. Do not follow URLs learned from local IPC.
pub fn origin(value: &str) -> Result<String> {
    let url = reqwest::Url::parse(value).map_err(|_| Failure::Validation)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err(Failure::Validation);
    }
    let host = url.host_str().ok_or(Failure::Validation)?;
    if host == "169.254.169.254"
        || host == "metadata.google.internal"
        || host == "0.0.0.0"
        || host == "::"
    {
        return Err(Failure::Validation);
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}
/// True for a gateway served from this machine, whose CA comes from its loopback console.
pub fn loopback(server: &str) -> bool {
    reqwest::Url::parse(server)
        .is_ok_and(|url| matches!(url.host_str(), Some("localhost" | "127.0.0.1")))
}
/// Exact loopback HTTP console origin, e.g. `http://127.0.0.1:18091`.
pub fn local_console(value: &str) -> Result<String> {
    let url = reqwest::Url::parse(value).map_err(|_| Failure::Validation)?;
    if value.len() > 2048
        || url.scheme() != "http"
        || !matches!(url.host_str(), Some("localhost" | "127.0.0.1"))
        || url.port().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(Failure::Validation);
    }
    Ok(url.as_str().trim_end_matches('/').to_owned())
}
